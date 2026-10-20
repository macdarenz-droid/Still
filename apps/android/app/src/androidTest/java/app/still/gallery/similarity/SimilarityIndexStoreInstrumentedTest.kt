package app.still.gallery.similarity

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.still.gallery.catalogue.CatalogueNamespace
import app.still.gallery.media.MediaItem
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Runs the real SQLiteOpenHelper against Android's SQLite when a device/emulator is available. */
class SimilarityIndexStoreInstrumentedTest : InstrumentationTestCase() {
    private lateinit var target: Context
    private val databaseName = "still-similarity-index.db"

    override fun setUp() {
        super.setUp()
        target = instrumentation.targetContext.applicationContext
        target.deleteDatabase(databaseName)
    }

    override fun tearDown() {
        target.deleteDatabase(databaseName)
        super.tearDown()
    }

    private fun <T> worker(block: () -> T): T {
        val result = AtomicReference<Result<T>?>()
        Thread { result.set(runCatching(block)) }.apply { start(); join() }
        return requireNotNull(result.get()).getOrThrow()
    }

    private fun namespace(version: String? = "provider-v1", generation: Long? = 10) =
        CatalogueNamespace("media", "external", version, generation)

    private fun item(handle: String = "7", generation: Long = 7) = MediaItem(
        id = "content://media/external/images/media/$handle", name = "private-name.jpg", mimeType = "image/jpeg", isVideo = false,
        capturedAt = 10, width = 128, height = 128, bytes = 4_096, durationMs = 0, albumId = "private-album",
        albumName = "Private album", modifiedAt = generation, providerAuthority = "media", volumeName = "external",
        providerVersion = "provider-v1", providerGeneration = 10, externalHandle = handle,
        generationAdded = generation, generationModified = generation, dateAddedSeconds = generation,
    )

    private fun feature(seed: Long = 1) = SimilarityFeature(seed, 1.0,
        DoubleArray(48) { 0.5 }, DoubleArray(12) { 0.25 })

    private fun countBuckets(): Int {
        val db = SQLiteDatabase.openDatabase(target.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY)
        return try { db.rawQuery("SELECT count(*) FROM similarity_hash_buckets", null).use { check(it.moveToFirst()); it.getInt(0) } }
        finally { db.close() }
    }

    fun testReopenReconcilesProviderVersionAndClearRemovesOnlyDerivedRows() = worker {
        val first = SimilarityIndexStore(target)
        val firstNamespace = first.reconcileNamespaces(listOf(similarityIndexNamespace(namespace())))
        val identity = requireNotNull(similarityIndexIdentity(item(), firstNamespace.values.single()))
        first.upsertPage(listOf(SimilarityIndexEntry(identity, feature())))
        assertEquals(1, first.count())
        first.close()

        val reopened = SimilarityIndexStore(target)
        val same = reopened.reconcileNamespaces(listOf(similarityIndexNamespace(namespace(generation = 12))))
        assertEquals(firstNamespace.values.single(), same.values.single())
        assertEquals(identity.sourceRevision, reopened.revisions()[identity.sourceKey])
        assertTrue(reopened.candidates(feature(), 10).sourceKeys.contains(identity.sourceKey))
        assertTrue(reopened.features(reopened.revisions(), listOf(identity.sourceKey)).containsKey(identity.sourceKey))

        val changed = reopened.reconcileNamespaces(listOf(similarityIndexNamespace(namespace(version = "provider-v2", generation = 1))))
        assertTrue(same.values.single() != changed.values.single())
        assertEquals(0, reopened.count())
        reopened.upsertPage(listOf(SimilarityIndexEntry(
            requireNotNull(similarityIndexIdentity(item().copy(providerVersion = "provider-v2"), changed.values.single())), feature(2),
        )))
        reopened.clear()
        assertEquals(0, reopened.count())
        reopened.close()

        val inspect = SQLiteDatabase.openDatabase(target.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertEquals(0, inspect.rawQuery("SELECT count(*) FROM similarity_index_namespaces", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(0, inspect.rawQuery("SELECT count(*) FROM similarity_features", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(2, inspect.rawQuery("SELECT count(*) FROM similarity_index_meta", null).use { it.moveToFirst(); it.getInt(0) })
        } finally { inspect.close() }
    }

    fun testPersistedGenerationRollbackRotatesIncarnationAndPurgesRows() = worker {
        val first = SimilarityIndexStore(target)
        val original = first.reconcileNamespaces(listOf(similarityIndexNamespace(namespace(generation = 12)))).values.single()
        val identity = requireNotNull(similarityIndexIdentity(item("21", 21), original))
        first.upsertPage(listOf(SimilarityIndexEntry(identity, feature())))
        first.close()

        val reopened = SimilarityIndexStore(target)
        val afterRollback = reopened.reconcileNamespaces(listOf(similarityIndexNamespace(namespace(generation = 3)))).values.single()
        assertTrue(original != afterRollback)
        assertEquals(0, reopened.count())
        assertTrue(reopened.revisions().isEmpty())
        reopened.close()
    }

    fun testCurrentRevisionPruningAndNamespaceContractionCascadeFeatures() = worker {
        val store = SimilarityIndexStore(target)
        val firstNamespace = namespace()
        val secondNamespace = CatalogueNamespace("media", "secondary", "provider-v1", 10)
        val incarnations = store.reconcileNamespaces(listOf(firstNamespace, secondNamespace).map(::similarityIndexNamespace))
        val firstSource = item("31", 31)
        val secondSource = item("32", 32).copy(
            id = "content://media/secondary/images/media/32", volumeName = "secondary",
        )
        val firstIdentity = requireNotNull(similarityIndexIdentity(firstSource, incarnations.values.first()))
        val secondIdentity = requireNotNull(similarityIndexIdentity(secondSource, incarnations.values.last()))
        store.upsertPage(listOf(
            SimilarityIndexEntry(firstIdentity, feature(31)),
            SimilarityIndexEntry(secondIdentity, feature(32)),
        ))

        val revised = requireNotNull(similarityIndexIdentity(
            secondSource.copy(generationModified = requireNotNull(secondSource.generationModified) + 1),
            secondIdentity.namespaceIncarnation,
        ))
        store.retainCurrent(mapOf(firstIdentity.sourceKey to firstIdentity.sourceRevision,
            secondIdentity.sourceKey to revised.sourceRevision))
        assertEquals(mapOf(firstIdentity.sourceKey to firstIdentity.sourceRevision), store.revisions())

        store.upsertPage(listOf(SimilarityIndexEntry(secondIdentity, feature(33))))
        assertEquals(2, store.count())
        store.reconcileNamespaces(listOf(similarityIndexNamespace(firstNamespace)))
        assertEquals(1, store.count())
        assertEquals(setOf(firstIdentity.sourceKey), store.revisions().keys)
        store.close()
    }

    fun testScopeWithdrawalInsidePageTransactionRollsBackFeatureAndBucketWrites() = worker {
        val store = SimilarityIndexStore(target)
        val incarnation = store.reconcileNamespaces(listOf(similarityIndexNamespace(namespace()))).values.single()
        val entries = listOf(item("8", 8), item("9", 9)).mapIndexed { index, source ->
            SimilarityIndexEntry(requireNotNull(similarityIndexIdentity(source, incarnation)), feature(index.toLong() + 1))
        }
        var checks = 0
        val failure = runCatching {
            store.upsertPage(entries) {
                checks++
                if (checks == 4) throw CancellationException("Scope was withdrawn during the page")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, store.count())
        store.close()
        assertEquals(0, countBuckets())
    }

    fun testForeignUnversionedApplicationIdAndUnknownObjectsFailWithoutMutation() = worker {
        val path = target.getDatabasePath(databaseName)
        val foreign = SQLiteDatabase.openOrCreateDatabase(path.path, null)
        foreign.execSQL("PRAGMA application_id = 1234")
        foreign.close()
        val foreignStore = SimilarityIndexStore(target)
        assertTrue(runCatching { foreignStore.count() }.isFailure)
        runCatching { foreignStore.close() }
        val foreignClear = SimilarityIndexClearStore(target)
        assertTrue("Clear must refuse a foreign application ID", runCatching { foreignClear.clear() }.isFailure)
        runCatching { foreignClear.close() }
        var inspect = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertEquals(1234, inspect.rawQuery("PRAGMA application_id", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(0, inspect.rawQuery("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'", null).use { it.moveToFirst(); it.getInt(0) })
        } finally { inspect.close() }
        target.deleteDatabase(databaseName)

        val valid = SimilarityIndexStore(target)
        valid.reconcileNamespaces(listOf(similarityIndexNamespace(namespace())))
        valid.close()
        val database = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE)
        database.execSQL("CREATE TABLE unrelated(value TEXT NOT NULL)")
        database.execSQL("INSERT INTO unrelated VALUES('preserve')")
        database.close()
        val damaged = SimilarityIndexStore(target)
        assertTrue(runCatching { damaged.count() }.isFailure)
        runCatching { damaged.close() }
        val unknownClear = SimilarityIndexClearStore(target)
        assertTrue("Clear must refuse unknown schema objects", runCatching { unknownClear.clear() }.isFailure)
        runCatching { unknownClear.close() }
        inspect = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
        try { assertEquals("preserve", inspect.rawQuery("SELECT value FROM unrelated", null).use { it.moveToFirst(); it.getString(0) }) }
        finally { inspect.close() }
    }

    fun testMalformedStoredDescriptorFailsClosedWithoutDeletingItsRow() = worker {
        val store = SimilarityIndexStore(target)
        val incarnation = store.reconcileNamespaces(listOf(similarityIndexNamespace(namespace()))).values.single()
        val identity = requireNotNull(similarityIndexIdentity(item(), incarnation))
        store.upsertPage(listOf(SimilarityIndexEntry(identity, feature())))
        store.close()
        val db = SQLiteDatabase.openDatabase(target.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE)
        db.execSQL("PRAGMA ignore_check_constraints = ON")
        db.update("similarity_features", ContentValues().apply { put("descriptor", ByteArray(135)) },
            "source_key = ?", arrayOf(identity.sourceKey))
        db.close()

        val reopened = SimilarityIndexStore(target)
        assertTrue(runCatching { reopened.count() }.isFailure)
        runCatching { reopened.close() }
        val inspect = SQLiteDatabase.openDatabase(target.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY)
        try { assertEquals(1, inspect.rawQuery("SELECT count(*) FROM similarity_features", null).use { it.moveToFirst(); it.getInt(0) }) }
        finally { inspect.close() }
    }

    fun testMissingRequiredRetrievalBucketFailsClosedAndPreservesTheDescriptor() = worker {
        val queryHash = 0L
        val changedBits = listOf(0, 6, 12, 18, 24, 30, 36, 42, 48, 54)
        val targetHash = changedBits.fold(queryHash) { hash, bit -> hash or (1L shl bit) }
        val query = feature(queryHash)
        val targetFeature = feature(targetHash)
        val sharedBlocks = similarityHashBuckets(queryHash).indices.filter {
            similarityHashBuckets(queryHash)[it] == similarityHashBuckets(targetHash)[it]
        }
        assertEquals(listOf(10), sharedBlocks)

        val store = SimilarityIndexStore(target)
        val incarnation = store.reconcileNamespaces(listOf(similarityIndexNamespace(namespace()))).values.single()
        val identity = requireNotNull(similarityIndexIdentity(item(), incarnation))
        store.upsertPage(listOf(SimilarityIndexEntry(identity, targetFeature)))
        assertTrue(store.candidates(query, 10).sourceKeys.contains(identity.sourceKey))
        store.close()

        val database = SQLiteDatabase.openDatabase(target.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE)
        try { database.delete("similarity_hash_buckets", "source_key = ? AND block_number = ?", arrayOf(identity.sourceKey, "10")) }
        finally { database.close() }

        val damaged = SimilarityIndexStore(target)
        assertTrue("A missing required bucket must disable the index", runCatching { damaged.candidates(query, 10) }.isFailure)
        runCatching { damaged.close() }
        val inspect = SQLiteDatabase.openDatabase(target.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertEquals(1, inspect.rawQuery("SELECT count(*) FROM similarity_features WHERE source_key = ?", arrayOf(identity.sourceKey)).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(10, inspect.rawQuery("SELECT count(*) FROM similarity_hash_buckets WHERE source_key = ?", arrayOf(identity.sourceKey)).use { it.moveToFirst(); it.getInt(0) })
        } finally { inspect.close() }
    }

    fun testExplicitClearRecoversKnownSchemaIndexWithMalformedFeatureAndMissingBucket() = worker {
        val store = SimilarityIndexStore(target)
        val incarnation = store.reconcileNamespaces(listOf(similarityIndexNamespace(namespace()))).values.single()
        val identity = requireNotNull(similarityIndexIdentity(item(), incarnation))
        store.upsertPage(listOf(SimilarityIndexEntry(identity, feature())))
        store.close()

        val database = SQLiteDatabase.openDatabase(target.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            database.execSQL("PRAGMA ignore_check_constraints = ON")
            database.update("similarity_features", ContentValues().apply { put("descriptor", ByteArray(135)) },
                "source_key = ?", arrayOf(identity.sourceKey))
            database.delete("similarity_hash_buckets", "source_key = ? AND block_number = ?",
                arrayOf(identity.sourceKey, "10"))
        } finally { database.close() }

        val damaged = SimilarityIndexStore(target)
        assertTrue("Normal use must fail closed on damaged feature data", runCatching { damaged.count() }.isFailure)
        runCatching { damaged.close() }

        SimilarityIndexClearStore(target).use { it.clear() }
        val reopened = SimilarityIndexStore(target)
        assertEquals(0, reopened.count())
        assertTrue(reopened.revisions().isEmpty())
        reopened.close()

        val inspect = SQLiteDatabase.openDatabase(target.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertEquals(0, inspect.rawQuery("SELECT count(*) FROM similarity_index_namespaces", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(0, inspect.rawQuery("SELECT count(*) FROM similarity_features", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(0, inspect.rawQuery("SELECT count(*) FROM similarity_hash_buckets", null).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(2, inspect.rawQuery("SELECT count(*) FROM similarity_index_meta", null).use { it.moveToFirst(); it.getInt(0) })
        } finally { inspect.close() }
    }

    fun testStoredRowCapRejectsAnAdditionalSourceWithoutEvictingExistingDescriptors() = worker {
        val store = SimilarityIndexStore(target)
        val incarnation = store.reconcileNamespaces(listOf(similarityIndexNamespace(namespace()))).values.single()
        store.close()
        val validFeature = feature(0)
        val descriptor = SimilarityFeatureCodec.encode(validFeature)
        val buckets = similarityHashBuckets(validFeature.perceptualHash)
        val database = SQLiteDatabase.openDatabase(target.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE)
        database.beginTransaction()
        try {
            val featureInsert = database.compileStatement(
                "INSERT INTO similarity_features(namespace_key, namespace_incarnation, source_key, source_revision, pipeline_version, perceptual_hash, descriptor) VALUES(?, ?, ?, ?, ?, ?, ?)",
            )
            val bucketInsert = database.compileStatement(
                "INSERT INTO similarity_hash_buckets(source_key, pipeline_version, block_number, block_value) VALUES(?, ?, ?, ?)",
            )
            featureInsert.use { insert -> bucketInsert.use { bucket ->
                repeat(SIMILARITY_INDEX_MAX_ROWS) { index ->
                    val key = index.toString(16).padStart(64, '0')
                    insert.bindString(1, similarityIndexNamespaceKey("media", "external"))
                    insert.bindString(2, incarnation)
                    insert.bindString(3, key)
                    insert.bindString(4, "b".repeat(64))
                    insert.bindString(5, SimilarityBounds.PIPELINE_VERSION)
                    insert.bindLong(6, validFeature.perceptualHash)
                    insert.bindBlob(7, descriptor)
                    insert.executeInsert(); insert.clearBindings()
                    buckets.forEachIndexed { blockNumber, blockValue ->
                        bucket.bindString(1, key)
                        bucket.bindString(2, SimilarityBounds.PIPELINE_VERSION)
                        bucket.bindLong(3, blockNumber.toLong())
                        bucket.bindLong(4, blockValue.toLong())
                        bucket.executeInsert(); bucket.clearBindings()
                    }
                }
            } }
            database.setTransactionSuccessful()
        } finally { database.endTransaction(); database.close() }

        val capped = SimilarityIndexStore(target)
        val next = requireNotNull(similarityIndexIdentity(item("50001", 50_001), incarnation))
        val failure = runCatching { capped.upsertPage(listOf(SimilarityIndexEntry(next, feature()))) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(SIMILARITY_INDEX_MAX_ROWS, capped.count())
        capped.close()
    }
}
