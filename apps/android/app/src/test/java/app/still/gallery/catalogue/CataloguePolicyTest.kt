package app.still.gallery.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CataloguePolicyTest {
    private fun observation() = CatalogueObservation(
        providerAuthority = "media", volumeName = "external_primary", providerVersion = "provider-A", providerGeneration = 20,
        externalHandle = "42", generationAdded = 10, generationModified = 15, dateAddedSeconds = 100,
        sourceUri = "content://media/external_primary/images/media/42", name = "Photo.jpg", mimeType = "image/jpeg", isVideo = false,
        capturedAt = 100_000, width = 800, height = 600, bytes = 4096, durationMs = 0,
        albumId = "camera", albumName = "Camera", orientationDegrees = 0, modifiedAt = 110_000,
    )

    @Test fun unchangedMetadataPreservesBothOpaqueIdentitiesAndRevision() {
        val metadata = CataloguePolicy.metadataFingerprint(observation())
        val prior = ExistingObservation("opaque-source", "opaque-observation", 7, metadata)
        val unchanged = CataloguePolicy.reconcileObservation(prior, metadata) { error("Unchanged metadata allocated a new ID") }
        assertEquals(prior.sourceId, unchanged.sourceId)
        assertEquals(prior.observationId, unchanged.observationId)
        assertEquals(7L, unchanged.revision)
        assertFalse(unchanged.changed)
    }

    @Test fun changedMetadataCreatesNewObservationWhilePreservingSourceLineage() {
        val prior = ExistingObservation("opaque-source", "previous-observation", 1, CataloguePolicy.metadataFingerprint(observation()))
        for (changed in listOf(
            observation().copy(name = "Renamed.jpg"), observation().copy(bytes = 5000), observation().copy(modifiedAt = 120_000),
            observation().copy(generationModified = 16), observation().copy(albumName = "Travel"), observation().copy(orientationDegrees = 90),
        )) {
            val change = CataloguePolicy.reconcileObservation(prior, CataloguePolicy.metadataFingerprint(changed)) { "next-observation" }
            assertEquals(prior.sourceId, change.sourceId)
            assertNotEquals(prior.observationId, change.observationId)
            assertEquals(2L, change.revision)
            assertTrue(change.changed)
        }
    }

    @Test fun initialObservationUsesOpaqueSourceAndObservationIdsRatherThanProviderHandles() {
        val ids = listOf("opaque-source-id", "opaque-observation-id").iterator()
        val initial = CataloguePolicy.reconcileObservation(null, CataloguePolicy.metadataFingerprint(observation())) { ids.next() }
        assertEquals("opaque-source-id", initial.sourceId)
        assertEquals("opaque-observation-id", initial.observationId)
        assertNotEquals(observation().externalHandle, initial.sourceId)
        assertNotEquals(observation().sourceUri, initial.sourceId)
        assertEquals(1L, initial.revision)
    }

    @Test fun metadataFingerprintUsesUnambiguousFramingAndDoesNotClaimByteEquality() {
        val first = observation().copy(name = "a:b", mimeType = "c")
        val second = observation().copy(name = "a", mimeType = "b:c")
        assertNotEquals(CataloguePolicy.metadataFingerprint(first), CataloguePolicy.metadataFingerprint(second))
        // Equal observed fields do not pin bytes. This policy never reads/hashes media.
        assertEquals(CataloguePolicy.metadataFingerprint(first), CataloguePolicy.metadataFingerprint(first.copy()))
    }

    @Test fun providerVersionChangesAndGenerationRollbackCreateFreshIncarnations() {
        val base = CataloguePolicy.namespace(observation())
        val prior = NamespaceState("old-incarnation", "provider-A", 20)
        val unchanged = CataloguePolicy.reconcileNamespace(prior, base) { error("Unchanged namespace rotated identity") }
        assertEquals(prior, unchanged)
        val progressed = CataloguePolicy.reconcileNamespace(prior, base.copy(providerGeneration = 21)) { error("Progress rotated identity") }
        assertEquals(prior.incarnationId, progressed.incarnationId)
        assertEquals(21L, progressed.generation)
        assertEquals("reset-version", CataloguePolicy.reconcileNamespace(prior, base.copy(providerVersion = "provider-B")) { "reset-version" }.incarnationId)
        assertEquals("reset-generation", CataloguePolicy.reconcileNamespace(prior, base.copy(providerGeneration = 1)) { "reset-generation" }.incarnationId)
    }

    @Test fun repeatedVersionAfterResetCannotResurrectTheFirstIncarnationEvenForEmptyScans() {
        val providerA = CataloguePolicy.namespace(observation())
        val firstA = CataloguePolicy.reconcileNamespace(null, providerA) { "first-A" }
        val providerB = CataloguePolicy.reconcileNamespace(firstA, providerA.copy(providerVersion = "provider-B")) { "B" }
        // Namespace processing is independent of source observations, including zero-row snapshots.
        val secondA = CataloguePolicy.reconcileNamespace(providerB, providerA) { "second-A" }
        assertNotEquals(firstA.incarnationId, secondA.incarnationId)
        assertEquals("second-A", secondA.incarnationId)
    }

    @Test fun unknownProviderContinuityIsExplicitlyBestEffortAndBirthChangesSeparateReuse() {
        val original = observation()
        assertNotEquals(CataloguePolicy.birthMarker(original), CataloguePolicy.birthMarker(original.copy(generationAdded = 11)))
        val fallback = original.copy(providerVersion = null, providerGeneration = null, generationAdded = null)
        assertEquals("date-added:100", CataloguePolicy.birthMarker(fallback))
        assertNotEquals(CataloguePolicy.birthMarker(fallback), CataloguePolicy.birthMarker(fallback.copy(dateAddedSeconds = 101)))
        // Same-second reuse cannot be proven distinct on providers without generation data.
        assertEquals(CataloguePolicy.birthMarker(fallback), CataloguePolicy.birthMarker(fallback.copy(name = "Different.jpg")))
    }

    @Test fun newerScanInvalidationAndAnotherInstanceRejectOldTokens() {
        val fence = CatalogueScanFence()
        val older = fence.beginScan()
        fence.requireCurrent(older)
        val newer = fence.beginScan()
        assertThrows(StaleCatalogueScanException::class.java) { fence.requireCurrent(older) }
        fence.requireCurrent(newer)
        fence.invalidate()
        assertThrows(StaleCatalogueScanException::class.java) { fence.requireCurrent(newer) }
        val another = CatalogueScanFence()
        val anotherToken = another.beginScan()
        assertThrows(StaleCatalogueScanException::class.java) { fence.requireCurrent(anotherToken) }
        another.requireCurrent(anotherToken)
    }

    @Test fun equalSequenceTokensCannotCrossRecreatedCatalogueInstances() {
        val previous = CatalogueScanFence()
        val stale = previous.beginScan()
        val current = CatalogueScanFence()
        val fresh = current.beginScan()
        assertEquals(stale.sequence, fresh.sequence)
        assertThrows(StaleCatalogueScanException::class.java) { current.requireCurrent(stale) }
        current.requireCurrent(fresh)
    }

    @Test fun malformedProvenanceAndNegativeMetadataAreRejectedBeforeDatabaseMutation() {
        CataloguePolicy.validate(observation())
        for (invalid in listOf(
            observation().copy(providerAuthority = ""), observation().copy(volumeName = ""), observation().copy(externalHandle = ""),
            observation().copy(sourceUri = "/storage/phone/photo.jpg"), observation().copy(sourceUri = "content://another/42"),
            observation().copy(width = -1), observation().copy(bytes = -1), observation().copy(providerGeneration = -1),
            observation().copy(generationAdded = -1), observation().copy(generationModified = -1), observation().copy(orientationDegrees = 45),
        )) assertThrows(IllegalArgumentException::class.java) { CataloguePolicy.validate(invalid) }
    }

    @Test fun revisionExhaustionDoesNotWrapOrSilentlyReplaceIdentity() {
        val prior = ExistingObservation("source", "observation", Long.MAX_VALUE, "old-metadata")
        assertThrows(IllegalStateException::class.java) { CataloguePolicy.reconcileObservation(prior, "new-metadata") { "new" } }
    }

    @Test fun reconcileSummaryDescribesOnlyTheCurrentAuthorizedSnapshot() {
        assertEquals(0, CatalogueReconcileResult(0, 0, 0).observedCount)
        assertEquals(9, CatalogueReconcileResult(2, 3, 4).observedCount)
        // Historical retained rows are intentionally absent from this public result.
    }
}
