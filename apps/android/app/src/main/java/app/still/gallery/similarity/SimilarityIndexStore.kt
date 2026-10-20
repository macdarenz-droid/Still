package app.still.gallery.similarity

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal val similarityIndexProcessLock = Any()

data class SimilarityIndexEntry(val identity: SimilarityIndexIdentity, val feature: SimilarityFeature)
data class SimilarityIndexCandidates(val sourceKeys: List<String>, val omitted: Boolean)

/** Local, rebuildable descriptors only. Every operation runs off the UI thread. */
class SimilarityIndexStore(context: Context) : SimilarityIndexStoreSession {
    private val helper = SimilarityIndexOpenHelper(context.applicationContext, checkFeatureIntegrityOnOpen = true)
    private val lock = similarityIndexProcessLock
    private var closed = false

    override fun revisions(): Map<String, String> = synchronized(lock) {
        checkOpen(); workerThread()
        helper.readableDatabase.rawQuery(
            "SELECT source_key, source_revision FROM similarity_features WHERE pipeline_version = ?",
            arrayOf(SimilarityBounds.PIPELINE_VERSION),
        ).use { cursor ->
            val result = LinkedHashMap<String, String>()
            while (cursor.moveToNext()) {
                val key = cursor.getString(0); val revision = cursor.getString(1)
                validateDigest(key); validateDigest(revision)
                check(result.put(key, revision) == null) { "Duplicate local similarity source" }
                check(result.size <= SIMILARITY_INDEX_MAX_ROWS) { "Local similarity index exceeds its row bound" }
            }
            result
        }
    }

    /**
     * Reconcile provider namespaces before deriving any source key. Version changes and
     * provider-generation rollbacks create a new incarnation and purge that namespace's
     * old features. APIs without either signal retain a best-effort local incarnation.
     */
    override fun reconcileNamespaces(namespaces: List<SimilarityIndexNamespace>, checkCurrent: () -> Unit): Map<String, String> = synchronized(lock) {
        checkOpen(); workerThread(); require(namespaces.size <= 32)
        require(namespaces.map { it.namespaceKey }.toSet().size == namespaces.size)
        namespaces.forEach {
            validateDigest(it.namespaceKey); validateDigest(it.providerVersionDigest)
            require(it.providerGeneration == null || it.providerGeneration >= 0)
        }
        checkCurrent()
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.execSQL("CREATE TEMP TABLE active_similarity_namespaces(namespace_key TEXT PRIMARY KEY)")
            database.compileStatement("INSERT INTO active_similarity_namespaces(namespace_key) VALUES(?)").use { insert ->
                namespaces.forEach { namespace ->
                    checkCurrent()
                    insert.bindString(1, namespace.namespaceKey); insert.executeInsert(); insert.clearBindings()
                }
            }
            val incarnations = LinkedHashMap<String, String>()
            namespaces.forEach { namespace ->
                checkCurrent()
                val prior = database.rawQuery(
                    "SELECT provider_version_digest, provider_generation, incarnation FROM similarity_index_namespaces WHERE namespace_key = ?",
                    arrayOf(namespace.namespaceKey),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else SimilarityIndexNamespaceState(cursor.getString(0),
                        if (cursor.isNull(1)) null else cursor.getLong(1), cursor.getString(2))
                }
                val transition = similarityIndexNamespaceTransition(prior, namespace, ::newNamespaceIncarnation)
                if (transition.rotated && prior != null) {
                    database.delete("similarity_features", "namespace_key = ?", arrayOf(namespace.namespaceKey))
                }
                val values = ContentValues().apply {
                    put("namespace_key", namespace.namespaceKey)
                    put("provider_version_digest", namespace.providerVersionDigest)
                    if (transition.state.providerGeneration == null) putNull("provider_generation") else put("provider_generation", transition.state.providerGeneration)
                    put("incarnation", transition.state.incarnation)
                }
                if (prior == null) database.insertOrThrow("similarity_index_namespaces", null, values)
                else check(database.update("similarity_index_namespaces", values, "namespace_key = ?", arrayOf(namespace.namespaceKey)) == 1)
                incarnations[namespace.namespaceKey] = transition.state.incarnation
            }
            database.execSQL("DELETE FROM similarity_index_namespaces WHERE NOT EXISTS (SELECT 1 FROM active_similarity_namespaces a WHERE a.namespace_key = similarity_index_namespaces.namespace_key)")
            database.execSQL("DROP TABLE active_similarity_namespaces")
            checkCurrent()
            database.setTransactionSuccessful()
            incarnations
        } finally { database.endTransaction() }
    }

    /** Keep only current namespace/revision/pipeline rows and their retrieval buckets. */
    override fun retainCurrent(identities: Map<String, String>, checkCurrent: () -> Unit) = synchronized(lock) {
        checkOpen(); workerThread(); require(identities.size <= SIMILARITY_INDEX_MAX_ROWS)
        identities.forEach { (key, revision) -> validateDigest(key); validateDigest(revision) }
        checkCurrent()
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.execSQL("CREATE TEMP TABLE active_similarity_sources(source_key TEXT PRIMARY KEY, source_revision TEXT NOT NULL)")
            val statement = database.compileStatement("INSERT INTO active_similarity_sources(source_key, source_revision) VALUES(?, ?)")
            statement.use { insert ->
                identities.forEach { (key, revision) ->
                    checkCurrent()
                    insert.bindString(1, key); insert.bindString(2, revision); insert.executeInsert(); insert.clearBindings()
                }
            }
            checkCurrent()
            database.execSQL("DELETE FROM similarity_features WHERE pipeline_version != ? OR NOT EXISTS (SELECT 1 FROM active_similarity_sources a WHERE a.source_key = similarity_features.source_key AND a.source_revision = similarity_features.source_revision)", arrayOf(SimilarityBounds.PIPELINE_VERSION))
            database.execSQL("DROP TABLE active_similarity_sources")
            checkCurrent()
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    /** Atomic complete-page upsert. A new page cannot grow beyond the shared metadata cap. */
    override fun upsertPage(entries: List<SimilarityIndexEntry>, checkCurrent: () -> Unit) = synchronized(lock) {
        checkOpen(); workerThread(); require(entries.size in 1..SimilarityBounds.MAX_SELECTED)
        require(entries.map { it.identity.sourceKey }.toSet().size == entries.size)
        entries.forEach {
            validateDigest(it.identity.sourceKey); validateDigest(it.identity.sourceRevision)
            validateDigest(it.identity.namespaceKey); validateDigest(it.identity.namespaceIncarnation)
            require(it.feature.algorithmVersion == SimilarityBounds.PIPELINE_VERSION)
        }
        checkCurrent()
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            val currentCount = database.rawQuery("SELECT count(*) FROM similarity_features WHERE pipeline_version = ?",
                arrayOf(SimilarityBounds.PIPELINE_VERSION)).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
            val entryKeys = entries.map { it.identity.sourceKey }.distinct()
            val placeholders = entryKeys.joinToString(",") { "?" }
            val existing = database.rawQuery(
                "SELECT count(*) FROM similarity_features WHERE pipeline_version = ? AND source_key IN ($placeholders)",
                arrayOf(SimilarityBounds.PIPELINE_VERSION, *entryKeys.toTypedArray()),
            ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
            check(currentCount - existing + entries.size <= SIMILARITY_INDEX_MAX_ROWS) { "Local similarity index reached its row bound" }
            val activeNamespaces = entries.map { it.identity.namespaceKey to it.identity.namespaceIncarnation }.toSet()
            activeNamespaces.forEach { (namespaceKey, namespaceIncarnation) ->
                checkCurrent()
                val currentNamespace = database.rawQuery(
                    "SELECT 1 FROM similarity_index_namespaces WHERE namespace_key = ? AND incarnation = ?",
                    arrayOf(namespaceKey, namespaceIncarnation),
                ).use { it.moveToFirst() }
                check(currentNamespace) { "The source provider namespace changed before index commit" }
            }
            entries.forEach { entry ->
                checkCurrent()
                val descriptor = SimilarityFeatureCodec.encode(entry.feature)
                val values = ContentValues().apply {
                    put("namespace_key", entry.identity.namespaceKey)
                    put("namespace_incarnation", entry.identity.namespaceIncarnation)
                    put("source_key", entry.identity.sourceKey); put("source_revision", entry.identity.sourceRevision)
                    put("pipeline_version", SimilarityBounds.PIPELINE_VERSION); put("perceptual_hash", entry.feature.perceptualHash)
                    put("descriptor", descriptor)
                }
                check(database.insertWithOnConflict("similarity_features", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                similarityHashBuckets(entry.feature.perceptualHash).forEachIndexed { number, value ->
                    database.insertOrThrow("similarity_hash_buckets", null, ContentValues().apply {
                        put("source_key", entry.identity.sourceKey); put("pipeline_version", SimilarityBounds.PIPELINE_VERSION)
                        put("block_number", number); put("block_value", value)
                    })
                }
            }
            checkCurrent()
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    /** Candidate keys are a recall-preserving Hamming-radius superset, bounded before descriptor loads. */
    override fun candidates(feature: SimilarityFeature, maximum: Int): SimilarityIndexCandidates = synchronized(lock) {
        checkOpen(); workerThread(); require(maximum in 1..100_000)
        val buckets = similarityHashBuckets(feature.perceptualHash)
        val predicate = buckets.indices.joinToString(" OR ") { "(block_number = ? AND block_value = ?)" }
        val args = ArrayList<String>(1 + buckets.size * 2).apply {
            add(SimilarityBounds.PIPELINE_VERSION)
            buckets.forEachIndexed { number, value -> add(number.toString()); add(value.toString()) }
        }
        helper.readableDatabase.rawQuery(
            "SELECT DISTINCT source_key FROM similarity_hash_buckets WHERE pipeline_version = ? AND ($predicate) ORDER BY source_key LIMIT ${maximum + 1}", args.toTypedArray(),
        ).use { cursor ->
            val keys = ArrayList<String>(maximum)
            var omitted = false
            while (cursor.moveToNext()) {
                val key = cursor.getString(0); validateDigest(key)
                if (keys.size == maximum) { omitted = true; break }
                keys += key
            }
            SimilarityIndexCandidates(keys, omitted)
        }
    }

    /** Decode only already-shortlisted keys that still match the exact current source revisions. */
    override fun features(sourceRevisions: Map<String, String>, sourceKeys: List<String>): Map<String, SimilarityFeature> = synchronized(lock) {
        checkOpen(); workerThread(); require(sourceKeys.size <= SIMILARITY_INDEX_MAX_ROWS)
        val keys = sourceKeys.distinct()
        val output = LinkedHashMap<String, SimilarityFeature>()
        keys.chunked(400).forEach { group ->
            if (group.isNotEmpty()) {
                val placeholders = group.joinToString(",") { "?" }
                val args = arrayOf(SimilarityBounds.PIPELINE_VERSION, *group.toTypedArray())
                helper.readableDatabase.rawQuery(
                    "SELECT source_key, source_revision, descriptor FROM similarity_features WHERE pipeline_version = ? AND source_key IN ($placeholders)", args,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(0); val revision = cursor.getString(1)
                        validateDigest(key); validateDigest(revision)
                        if (sourceRevisions[key] != revision) continue
                        val feature = requireNotNull(SimilarityFeatureCodec.decode(cursor.getBlob(2))) { "Invalid stored similarity descriptor" }
                        output[key] = feature
                    }
                }
            }
        }
        output
    }

    override fun clear() = synchronized(lock) {
        checkOpen(); workerThread()
        clearDerivedIndexRows(helper.writableDatabase)
    }

    override fun count(): Int = synchronized(lock) {
        checkOpen(); workerThread()
        helper.readableDatabase.rawQuery("SELECT count(*) FROM similarity_features WHERE pipeline_version = ?", arrayOf(SimilarityBounds.PIPELINE_VERSION))
            .use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0).also { check(it in 0..SIMILARITY_INDEX_MAX_ROWS) } }
    }

    override fun close() = synchronized(lock) {
        workerThread()
        if (!closed) { closed = true; helper.close() }
    }

    private fun checkOpen() = check(!closed) { "The local similarity index is closed" }
    private fun validateDigest(value: String) = require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' })
    private fun newNamespaceIncarnation(): String = MessageDigest.getInstance("SHA-256")
        .digest(UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
    private fun workerThread() { check(Looper.myLooper() != Looper.getMainLooper()) { "Similarity index access must run on an IO dispatcher" } }
}

/**
 * A deliberately narrow recovery path for the user's explicit Clear action. The
 * helper still checks ownership, exact DDL and metadata, while this session exposes
 * no reads or indexing operations that could use damaged descriptor rows.
 */
class SimilarityIndexClearStore(context: Context) : SimilarityIndexClearSession {
    private val helper = SimilarityIndexOpenHelper(context.applicationContext, checkFeatureIntegrityOnOpen = false)
    private var closed = false

    override fun clear() = synchronized(similarityIndexProcessLock) {
        check(!closed) { "The local similarity index clear session is closed" }
        check(Looper.myLooper() != Looper.getMainLooper()) { "Similarity index access must run on an IO dispatcher" }
        clearDerivedIndexRows(helper.writableDatabase)
    }

    override fun close() = synchronized(similarityIndexProcessLock) {
        if (!closed) { closed = true; helper.close() }
    }
}

private fun clearDerivedIndexRows(database: SQLiteDatabase) {
    database.beginTransaction()
    try {
        database.delete("similarity_hash_buckets", null, null)
        database.delete("similarity_features", null, null)
        database.delete("similarity_index_namespaces", null, null)
        database.setTransactionSuccessful()
    } finally { database.endTransaction() }
}

/** Eleven disjoint blocks guarantee a shared block for every hash at Hamming distance≤10. */
fun similarityHashBuckets(hash: Long): List<Int> {
    var shift = 0
    return List(11) { index ->
        val width = if (index < 9) 6 else 5
        val value = ((hash ushr shift) and ((1L shl width) - 1)).toInt()
        shift += width
        value
    }
}

private class SimilarityIndexOpenHelper(
    private val application: Context,
    private val checkFeatureIntegrityOnOpen: Boolean,
) : SQLiteOpenHelper(
    application, "still-similarity-index.db", null, SIMILARITY_INDEX_SCHEMA_VERSION,
    DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("The similarity index is corrupt; existing data was retained") },
) {
    override fun onConfigure(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(true)
        if (database.version == 0) requireNoForeignSchema(database)
    }

    override fun onCreate(database: SQLiteDatabase) {
        requireNoForeignSchema(database)
        val schema = application.assets.open("similarity/001_schema.sql").bufferedReader().use { it.readText() }
        schema.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(';').map(String::trim).filter(String::isNotEmpty).forEach(database::execSQL)
        database.execSQL("PRAGMA application_id = $SIMILARITY_INDEX_APPLICATION_ID")
    }

    override fun onOpen(database: SQLiteDatabase) {
        check(databasePragma(database, "application_id") == SIMILARITY_INDEX_APPLICATION_ID) {
            "The similarity index database belongs to another application"
        }
        val actual = database.rawQuery("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0) to cursor.getString(1), normalizeSql(cursor.getString(2).orEmpty())) }
        }
        val expected = application.assets.open("similarity/001_schema.sql").bufferedReader().use { it.readText() }
            .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").split(';')
            .map(String::trim).filter { it.startsWith("CREATE TABLE", true) || it.startsWith("CREATE INDEX", true) }
            .associate { statement ->
                val match = Regex("(?i)^CREATE\\s+(TABLE|INDEX)\\s+([a-zA-Z0-9_]+)").find(statement)
                    ?: error("Invalid local similarity schema statement")
                val type = match.groupValues[1].lowercase(Locale.ROOT)
                (type to match.groupValues[2]) to normalizeSql(statement)
            }
        check(actual == expected) { "The similarity index schema contains unsupported objects or definitions" }
        val metadata = database.rawQuery("SELECT key, value FROM similarity_index_meta", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
        check(metadata == mapOf("application" to "still-local-similarity-index-v1", "schema-version" to "1")) {
            "The similarity index belongs to another application or schema"
        }
        if (checkFeatureIntegrityOnOpen) validateFeatureIntegrity(database)
    }

    /** A missing or altered retrieval bucket can hide a source while falsely claiming full coverage. */
    private fun validateFeatureIntegrity(database: SQLiteDatabase) {
        val count = database.rawQuery("SELECT count(*) FROM similarity_features", null).use {
            check(it.moveToFirst()); it.getInt(0)
        }
        check(count in 0..SIMILARITY_INDEX_MAX_ROWS) { "The local similarity index exceeds its row bound" }
        val foreignKeyViolation = database.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }
        check(!foreignKeyViolation) { "The local similarity index contains broken references" }

        var currentKey: String? = null
        var expectedBuckets: List<Int> = emptyList()
        var nextBucket = 0
        fun finishSource() {
            if (currentKey != null) check(nextBucket == expectedBuckets.size) {
                "The local similarity index has incomplete retrieval buckets"
            }
        }
        database.rawQuery(
            "SELECT f.source_key, f.namespace_key, f.namespace_incarnation, f.source_revision, f.perceptual_hash, f.descriptor, n.incarnation, b.block_number, b.block_value " +
                "FROM similarity_features f " +
                "LEFT JOIN similarity_index_namespaces n ON n.namespace_key = f.namespace_key " +
                "LEFT JOIN similarity_hash_buckets b ON b.source_key = f.source_key AND b.pipeline_version = f.pipeline_version " +
                "WHERE f.pipeline_version = ? ORDER BY f.source_key, b.block_number",
            arrayOf(SimilarityBounds.PIPELINE_VERSION),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                if (key != currentKey) {
                    finishSource()
                    validateDigest(key)
                    validateDigest(cursor.getString(1)); validateDigest(cursor.getString(2)); validateDigest(cursor.getString(3))
                    check(!cursor.isNull(6) && cursor.getString(6) == cursor.getString(2)) {
                        "The local similarity index has a stale provider namespace"
                    }
                    val feature = requireNotNull(SimilarityFeatureCodec.decode(cursor.getBlob(5))) {
                        "The local similarity index contains an invalid descriptor"
                    }
                    check(feature.perceptualHash == cursor.getLong(4)) {
                        "The local similarity index descriptor and hash disagree"
                    }
                    currentKey = key
                    expectedBuckets = similarityHashBuckets(feature.perceptualHash)
                    nextBucket = 0
                }
                if (!cursor.isNull(7)) {
                    check(nextBucket < expectedBuckets.size && cursor.getInt(7) == nextBucket &&
                        cursor.getInt(8) == expectedBuckets[nextBucket]) {
                        "The local similarity index has inconsistent retrieval buckets"
                    }
                    nextBucket++
                }
            }
            finishSource()
        }
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported similarity index upgrade $oldVersion to $newVersion; existing data was retained")
    }

    override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported similarity index downgrade $oldVersion to $newVersion; existing data was retained")
    }

    private fun requireNoForeignSchema(database: SQLiteDatabase) {
        val objectCount = database.rawQuery("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'", null).use {
            check(it.moveToFirst()); it.getInt(0)
        }
        val applicationId = databasePragma(database, "application_id")
        check(similarityIndexSchemaCanInitialize(database.version, objectCount, applicationId)) {
            "An unversioned foreign or non-empty database is not a Still similarity index; existing data was retained"
        }
    }

    private fun databasePragma(database: SQLiteDatabase, pragma: String): Int =
        database.rawQuery("PRAGMA $pragma", null).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private fun validateDigest(value: String) = check(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' })

    private fun normalizeSql(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ").replace(Regex("\\s*([(),])\\s*"), "$1").trim().removeSuffix(";")
}
