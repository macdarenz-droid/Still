package app.still.gallery.similarity

import app.still.gallery.media.MediaItem
import org.junit.Assert.*
import org.junit.Test

class SimilarityIndexPolicyTest {
    private val incarnation = "a".repeat(64)
    private fun source(
        id: String = "content://media/external/images/media/7",
        name: String = "family.jpg",
        volume: String = "external",
        providerVersion: String? = "store-v1",
        generationAdded: Long? = 10,
        generationModified: Long? = 12,
        dateAddedSeconds: Long = 10,
        modifiedAt: Long = 1_000,
        bytes: Long = 4_000,
    ) = MediaItem(id, name, "image/jpeg", false, 9_000, 400, 300, bytes, 0, "album-1", "Private album",
        orientationDegrees = 0, modifiedAt = modifiedAt, providerAuthority = "media", volumeName = volume,
        providerVersion = providerVersion, providerGeneration = 99, externalHandle = "7", generationAdded = generationAdded,
        generationModified = generationModified, dateAddedSeconds = dateAddedSeconds)

    @Test fun sourceIdentityIsFramedAndContainsNoReadableLocationOrName() {
        val identity = requireNotNull(similarityIndexIdentity(source(), incarnation))
        assertEquals(64, identity.sourceKey.length)
        assertEquals(64, identity.sourceRevision.length)
        assertFalse(identity.sourceKey.contains("family"))
        assertFalse(identity.sourceKey.contains("content"))
        assertEquals(identity, similarityIndexIdentity(source(name = "renamed.jpg", id = "content://media/external/images/media/7"), incarnation))
        assertNotEquals(identity.sourceKey, similarityIndexIdentity(source(volume = "123:external"), incarnation)!!.sourceKey)
        assertNotEquals(identity.sourceKey, similarityIndexIdentity(source(providerVersion = "store-v2"), incarnation)!!.sourceKey)
        assertNotEquals(similarityIndexIdentity(source(providerVersion = null), incarnation)!!.sourceKey,
            similarityIndexIdentity(source(providerVersion = "unversioned-provider"), incarnation)!!.sourceKey)
        assertNotEquals(identity.sourceKey, similarityIndexIdentity(source(generationAdded = 11), incarnation)!!.sourceKey)
        assertNotEquals(identity.sourceKey, similarityIndexIdentity(source(), "b".repeat(64))!!.sourceKey)
        assertNotEquals(identity.sourceRevision, similarityIndexIdentity(source(generationModified = 13), incarnation)!!.sourceRevision)
        assertNotEquals(identity.sourceRevision, similarityIndexIdentity(source(modifiedAt = 1_001), incarnation)!!.sourceRevision)
        assertNotEquals(identity.sourceRevision, similarityIndexIdentity(source(bytes = 4_001), incarnation)!!.sourceRevision)
    }

    @Test fun legacyIdentityUsesMetadataRevisionWithoutClaimingProviderGeneration() {
        val first = requireNotNull(similarityIndexIdentity(source(providerVersion = null, generationAdded = null,
            generationModified = null, modifiedAt = 50), incarnation))
        val renamed = requireNotNull(similarityIndexIdentity(source(name = "new-name.jpg", providerVersion = null,
            generationAdded = null, generationModified = null, modifiedAt = 50), incarnation))
        val changed = requireNotNull(similarityIndexIdentity(source(providerVersion = null, generationAdded = null,
            generationModified = null, modifiedAt = 51), incarnation))
        assertEquals(first, renamed)
        assertNotEquals(first.sourceRevision, changed.sourceRevision)
        assertNotNull(first)
        assertNull(similarityIndexIdentity(source(providerVersion = null, generationAdded = null, dateAddedSeconds = 0), incarnation))
    }

    @Test fun namespaceIncarnationRotatesForVersionChangesAndGenerationRollback() {
        val firstScope = SimilarityIndexNamespace("1".repeat(64), "2".repeat(64), 500)
        val first = similarityIndexNamespaceTransition(null, firstScope) { "a".repeat(64) }
        assertTrue(first.rotated)
        val advanced = similarityIndexNamespaceTransition(first.state, firstScope.copy(providerGeneration = 510)) { "b".repeat(64) }
        assertFalse(advanced.rotated)
        assertEquals(first.state.incarnation, advanced.state.incarnation)
        assertEquals(510L, advanced.state.providerGeneration)

        val reset = similarityIndexNamespaceTransition(advanced.state, firstScope.copy(providerGeneration = 4)) { "c".repeat(64) }
        assertTrue(reset.rotated)
        assertEquals("c".repeat(64), reset.state.incarnation)
        assertEquals(4L, reset.state.providerGeneration)

        val providerReset = similarityIndexNamespaceTransition(reset.state, firstScope.copy(providerVersionDigest = "3".repeat(64))) { "d".repeat(64) }
        assertTrue(providerReset.rotated)
        assertEquals("d".repeat(64), providerReset.state.incarnation)
    }

    @Test fun legacyNamespaceWithoutVersionOrGenerationKeepsBestEffortIncarnation() {
        val legacy = SimilarityIndexNamespace("1".repeat(64), "2".repeat(64), null)
        val first = similarityIndexNamespaceTransition(null, legacy) { "e".repeat(64) }
        val reopened = similarityIndexNamespaceTransition(first.state, legacy) { "f".repeat(64) }
        assertFalse(reopened.rotated)
        assertEquals(first.state.incarnation, reopened.state.incarnation)
    }

    @Test fun schemaInitializerRefusesToMutateAnUnversionedForeignDatabase() {
        assertTrue(similarityIndexSchemaCanInitialize(databaseVersion = 0, userObjectCount = 0))
        assertFalse(similarityIndexSchemaCanInitialize(databaseVersion = 0, userObjectCount = 1))
        assertFalse(similarityIndexSchemaCanInitialize(databaseVersion = 0, userObjectCount = 0, applicationId = 1234))
        assertTrue(similarityIndexSchemaCanInitialize(databaseVersion = 1, userObjectCount = 4))
    }

    @Test fun featureCodecRoundTripsProductionDescriptorAndRejectsCorruptRows() {
        val feature = SimilarityFeature(0x123456789abcdefL, 1.5,
            DoubleArray(48) { it / 48.0 }, DoubleArray(12) { it / 12.0 })
        val encoded = SimilarityFeatureCodec.encode(feature)
        assertEquals(136, encoded.size)
        val decoded = requireNotNull(SimilarityFeatureCodec.decode(encoded))
        assertEquals(feature.perceptualHash, decoded.perceptualHash)
        assertEquals(0, similarityDistance(feature, decoded)?.pHashDistance)
        assertEquals(0.0, similarityDistance(feature, decoded)?.spatialDistance ?: -1.0, 0.0001)
        assertEquals(0.0, similarityDistance(feature, decoded)?.colorDistance ?: -1.0, 0.0001)
        assertNull(SimilarityFeatureCodec.decode(encoded.copyOf(135)))
        val corruptAspect = encoded.copyOf().also { bytes ->
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(8, java.lang.Double.doubleToRawLongBits(Double.NaN))
        }
        assertNull(SimilarityFeatureCodec.decode(corruptAspect))
    }

    @Test fun codecRejectsUnboundedAndNonfiniteProductionValues() {
        assertTrue(runCatching {
            SimilarityFeatureCodec.encode(SimilarityFeature(1, Double.NaN, DoubleArray(48), DoubleArray(12)))
        }.isFailure)
        assertTrue(runCatching {
            SimilarityFeatureCodec.encode(SimilarityFeature(1, 1.0, DoubleArray(48) { 2.0 }, DoubleArray(12)))
        }.isFailure)
    }

    @Test fun elevenHashBucketsPreserveEveryPairWithinTheConfiguredHammingRadius() {
        val random = java.util.Random(0x10C0FFEE)
        repeat(1_000) {
            val first = random.nextLong()
            val count = random.nextInt(SimilarityBounds.MAX_HASH_DISTANCE + 1)
            val bits = mutableSetOf<Int>()
            while (bits.size < count) bits += random.nextInt(64)
            val flipped = bits.fold(first) { hash, bit -> hash xor (1L shl bit) }
            val firstBuckets = similarityHashBuckets(first)
            val secondBuckets = similarityHashBuckets(flipped)
            assertEquals(11, firstBuckets.size)
            assertEquals(11, secondBuckets.size)
            assertTrue("A Hamming-near pair must share a retrieval bucket", firstBuckets.indices.any { firstBuckets[it] == secondBuckets[it] })
        }
    }
}
