package app.still.gallery.catalogue

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Device-local metadata index. Creation, reconcile and close belong on an IO
 * dispatcher; beginScan/invalidateScans/requireCurrent perform memory checks only.
 * No read API can turn retained metadata into a visible or currently accessible
 * photo. The fresh authorised provider snapshot remains the UI's sole authority.
 *
 * Begin the scan BEFORE the provider query. Invalidate it immediately on new
 * permission scope, reload, cancellation, lock disposal or external source change.
 * checkActive should propagate the calling coroutine's cancellation during work.
 * The caller must fence UI publication with its own scope/token as well.
 *
 * Invalidation never blocks the main thread on SQLite. An invalidation racing
 * the final disk commit can leave retained observations, but the post-commit
 * check rejects success/publication. Those rows imply neither access nor deletion.
 * These are metadata revisions, not pinned AssetVersions, checksums, backup or
 * deletion authority. Pre-generation row continuity is explicitly best effort.
 */
class LocalCatalogue(context: Context) : AutoCloseable {
    private val fence = CatalogueScanFence()
    private val closed = AtomicBoolean()
    private val databaseLock = Any()
    private val statements: Map<String, String>
    private val helper: CatalogueOpenHelper

    init {
        requireWorkerThread()
        val application = context.applicationContext
        statements = listOf("read_namespace", "insert_incarnation", "insert_namespace", "update_namespace", "read_source", "insert_source", "update_source")
            .associateWith { name -> application.assets.open("catalogue/$name.sql").bufferedReader().use { it.readText() } }
        helper = CatalogueOpenHelper(application)
    }

    fun beginScan(): CatalogueScan {
        check(!closed.get()) { "The local catalogue is closed" }
        return fence.beginScan()
    }

    fun invalidateScans() { fence.invalidate() }

    fun requireCurrent(scan: CatalogueScan) {
        if (closed.get()) throw StaleCatalogueScanException()
        fence.requireCurrent(scan)
    }

    fun reconcile(
        scan: CatalogueScan,
        observations: List<CatalogueObservation>,
        namespaces: List<CatalogueNamespace>,
        checkActive: () -> Unit = {},
    ): CatalogueReconcileResult {
        requireWorkerThread()
        return synchronized(databaseLock) {
            requireCurrent(scan)
            checkActive()
            // Copy caller collections and validate before opening any transaction.
            val snapshot = observations.toList()
            val namespaceSnapshot = namespaces.toList()
            snapshot.forEach(CataloguePolicy::validate)
            val namespaceByKey = namespaceSnapshot.associateBy { it.providerAuthority to it.volumeName }
            require(namespaceByKey.size == namespaceSnapshot.size) { "A snapshot repeats a provider volume" }
            namespaceSnapshot.forEach { namespace ->
                require(namespace.providerAuthority.isNotBlank() && namespace.providerAuthority.length <= 256)
                require(namespace.volumeName.isNotBlank() && namespace.volumeName.length <= 256)
                require(namespace.providerVersion == null || namespace.providerVersion.length <= 1024)
                require(namespace.providerGeneration == null || namespace.providerGeneration >= 0)
            }
            val identities = mutableSetOf<List<Any>>()
            snapshot.forEach { observation ->
                require(namespaceByKey[observation.providerAuthority to observation.volumeName] == CataloguePolicy.namespace(observation)) {
                    "Observation provenance does not match this provider snapshot"
                }
                require(identities.add(listOf(observation.providerAuthority, observation.volumeName, observation.isVideo, observation.externalHandle))) {
                    "A snapshot repeats a provider source"
                }
            }
            requireCurrent(scan)
            checkActive()
            val database = helper.writableDatabase
            database.beginTransaction()
            var created = 0
            var updated = 0
            var unchanged = 0
            try {
                val incarnations = namespaceSnapshot.associate { namespace ->
                    checkActive()
                    requireCurrent(scan)
                    val state = reconcileNamespace(database, namespace)
                    (namespace.providerAuthority to namespace.volumeName) to state.incarnationId
                }
                for (observation in snapshot) {
                    checkActive()
                    requireCurrent(scan)
                    val incarnationId = requireNotNull(incarnations[observation.providerAuthority to observation.volumeName])
                    val prior = readSource(database, incarnationId, observation)
                    val fingerprint = CataloguePolicy.metadataFingerprint(observation)
                    val change = CataloguePolicy.reconcileObservation(prior, fingerprint, ::newId)
                    when {
                        prior == null -> {
                            val identity = listOf(change.sourceId, incarnationId, if (observation.isVideo) 1 else 0, observation.externalHandle, CataloguePolicy.birthMarker(observation))
                            database.execSQL(sql("insert_source"), (identity + listOf(change.observationId, change.revision, fingerprint) + metadataValues(observation)).toTypedArray())
                            created++
                        }
                        change.changed -> {
                            val values = listOf(change.observationId, change.revision, fingerprint) + metadataValues(observation) + listOf(prior.sourceId, prior.revision)
                            executeUpdate(database, sql("update_source"), values)
                            updated++
                        }
                        else -> unchanged++
                    }
                }
                checkActive()
                requireCurrent(scan)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            // Commit-window races can retain metadata but cannot publish success.
            checkActive()
            requireCurrent(scan)
            CatalogueReconcileResult(created, updated, unchanged)
        }
    }

    override fun close() {
        requireWorkerThread()
        if (closed.compareAndSet(false, true)) {
            fence.invalidate()
            synchronized(databaseLock) { helper.close() }
        }
    }

    private fun reconcileNamespace(database: SQLiteDatabase, namespace: CatalogueNamespace): NamespaceState {
        val prior = database.rawQuery(sql("read_namespace"), arrayOf(namespace.providerAuthority, namespace.volumeName)).use { cursor ->
            if (!cursor.moveToFirst()) null
            else NamespaceState(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getString(1), if (cursor.isNull(2)) null else cursor.getLong(2))
        }
        val next = CataloguePolicy.reconcileNamespace(prior, namespace, ::newId)
        if (prior == null || next.incarnationId != prior.incarnationId) {
            database.execSQL(sql("insert_incarnation"), arrayOf<Any?>(next.incarnationId, namespace.providerAuthority, namespace.volumeName, next.version))
        }
        if (prior == null) {
            database.execSQL(sql("insert_namespace"), arrayOf<Any?>(namespace.providerAuthority, namespace.volumeName, next.incarnationId, next.version, next.generation))
        } else if (next != prior) {
            executeUpdate(database, sql("update_namespace"), listOf(next.incarnationId, next.version, next.generation, namespace.providerAuthority, namespace.volumeName))
        }
        return next
    }

    private fun readSource(database: SQLiteDatabase, incarnationId: String, observation: CatalogueObservation): ExistingObservation? =
        database.rawQuery(sql("read_source"), arrayOf(incarnationId, if (observation.isVideo) "1" else "0", observation.externalHandle, CataloguePolicy.birthMarker(observation))).use { cursor ->
            if (!cursor.moveToFirst()) null
            else ExistingObservation(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3))
        }

    private fun metadataValues(observation: CatalogueObservation): List<Any?> = listOf(
        observation.sourceUri, observation.name, observation.mimeType, observation.capturedAt, observation.width, observation.height,
        observation.bytes, observation.durationMs, observation.albumId, observation.albumName, observation.orientationDegrees,
        observation.modifiedAt, observation.dateAddedSeconds, observation.generationAdded, observation.generationModified,
    )

    private fun executeUpdate(database: SQLiteDatabase, statement: String, values: List<Any?>) {
        database.compileStatement(statement).use { compiled ->
            values.forEachIndexed { index, value ->
                when (value) {
                    null -> compiled.bindNull(index + 1)
                    is Number -> compiled.bindLong(index + 1, value.toLong())
                    is String -> compiled.bindString(index + 1, value)
                    else -> throw IllegalArgumentException("Unsupported catalogue binding")
                }
            }
            check(compiled.executeUpdateDelete() == 1) { "The catalogue row changed during reconciliation" }
        }
    }

    private fun sql(name: String): String = requireNotNull(statements[name])
    private fun newId(): String = UUID.randomUUID().toString()
    private fun requireWorkerThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Open, reconcile and close the catalogue on an IO dispatcher" }
    }
}

private class CatalogueOpenHelper(private val application: Context) : SQLiteOpenHelper(
    application,
    "still-local-catalogue.db",
    null,
    1,
    DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("The local catalogue is corrupt; automatic deletion is disabled") },
) {
    override fun onConfigure(database: SQLiteDatabase) { database.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(database: SQLiteDatabase) {
        val schema = application.assets.open("catalogue/001_schema.sql").bufferedReader().use { it.readText() }
        schema.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(';').map(String::trim).filter(String::isNotEmpty).forEach(database::execSQL)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported catalogue upgrade $oldVersion to $newVersion; existing data was retained")
    }

    override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported catalogue downgrade $oldVersion to $newVersion; existing data was retained")
    }
}
