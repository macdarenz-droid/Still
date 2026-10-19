package app.still.gallery.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryPolicyTest {
    @Test fun typeFiltersRetainOnlyTheRequestedLiveItems() {
        val photo = item("photo")
        val video = item("video", video = true)
        val live = listOf(video, photo)
        assertEquals(setOf(photo, video), libraryOrder(live, MediaFilter.ALL, MediaSort.NEWEST).toSet())
        assertEquals(listOf(photo), libraryOrder(live, MediaFilter.PHOTOS, MediaSort.NEWEST))
        assertEquals(listOf(video), libraryOrder(live, MediaFilter.VIDEOS, MediaSort.NEWEST))
    }

    @Test fun dateOrdersKeepUnknownDatesLastAndTiesDeterministic() {
        val old = item("old").copy(capturedAt = 10)
        val a = item("a").copy(capturedAt = 20, name = "Same.jpg")
        val b = item("b").copy(capturedAt = 20, name = "Same.jpg")
        val unknown = item("unknown").copy(capturedAt = 0)
        val rows = listOf(b, unknown, old, a)
        assertEquals(listOf(a, b, old, unknown), libraryOrder(rows, MediaFilter.ALL, MediaSort.NEWEST))
        assertEquals(listOf(old, a, b, unknown), libraryOrder(rows.reversed(), MediaFilter.ALL, MediaSort.OLDEST))
    }

    @Test fun filenameOrderIsGlobalAcrossDatesWithCaseAndStableUriTies() {
        val a = item("a").copy(name = "Alpha.jpg", capturedAt = 3_000)
        val b = item("b").copy(name = "alpha.jpg", capturedAt = 1_000)
        val c = item("c").copy(name = "beta.jpg", capturedAt = 2_000)
        val d = item("d").copy(name = "beta.jpg", capturedAt = 4_000)
        assertEquals(listOf(a, b, c, d), libraryOrder(listOf(d, b, c, a), MediaFilter.ALL, MediaSort.NAME))
        assertEquals(listOf(a, b, c, d), libraryOrder(listOf(c, a, d, b), MediaFilter.ALL, MediaSort.NAME))
    }

    @Test fun hiddenSelectionCannotJoinSharingOrBatchFavorites() {
        val photo = item("photo")
        val video = item("video", video = true)
        val selection = setOf(sourceUiToken(photo), sourceUiToken(video))
        val filtered = libraryOrder(listOf(photo, video), MediaFilter.VIDEOS, MediaSort.NAME)
        val actions = selectedMediaInView(selection, filtered)
        assertEquals(listOf(video), actions)
        assertEquals("video/*", shareMimeType(actions))
        assertTrue(selectedMediaInView(selection, emptyList()).isEmpty())
        assertEquals(listOf(photo), selectedMediaInView(selection, listOf(photo))) // Filename/album scope too.
        assertEquals(2, selection.size) // Policy does not mutate saved Favorites/selection state.
    }

    @Test fun unchangedStrongSourceSurvivesRefreshAndAdvancesItsWatermark() {
        val prior = strong()
        val current = prior.copy(providerGeneration = 150)
        assertEquals(setOf(sourceUiToken(current)), reconcileMediaTokens(setOf(sourceUiToken(prior)), listOf(current)))
        assertEquals(sourceUiIdentity(prior), sourceUiIdentity(current))
    }

    @Test fun reusedUriCannotRetainSelectionOrViewerAfterResetOrReplacement() {
        val prior = strong()
        val replaced = prior.copy(generationAdded = 91, dateAddedSeconds = 20)
        // The original URI-only policy collides for these distinct observations.
        assertEquals(prior.id, replaced.id)
        assertEquals(setOf(prior.id), visibleSelection(setOf(prior.id), listOf(replaced)))
        val selectedOrOpened = setOf(sourceUiToken(prior))
        assertTrue(reconcileMediaTokens(selectedOrOpened, listOf(replaced)).isEmpty())
        assertTrue(reconcileMediaTokens(selectedOrOpened, listOf(prior.copy(providerVersion = "reset"))).isEmpty())
        assertTrue(reconcileMediaTokens(selectedOrOpened, listOf(prior.copy(providerGeneration = 2))).isEmpty())
        assertNotEquals(previewFingerprint(prior, 360), previewFingerprint(replaced, 360))
    }

    @Test fun changedOrUncertainRowsDoNotRetainPendingUiActions() {
        val prior = strong()
        val token = setOf(sourceUiToken(prior))
        assertTrue(reconcileMediaTokens(token, listOf(prior.copy(generationModified = 95))).isEmpty())
        assertTrue(reconcileMediaTokens(token, listOf(prior.copy(orientationDegrees = 90))).isEmpty())
        assertTrue(reconcileMediaTokens(token, emptyList()).isEmpty())
        val legacy = prior.copy(providerVersion = null, providerGeneration = null, generationAdded = null, generationModified = null)
        assertTrue(reconcileMediaTokens(setOf(sourceUiToken(legacy)), listOf(legacy)).isEmpty())
    }

    @Test fun sameBucketAndRowNumbersInDifferentVolumesDoNotCollide() {
        val primary = strong()
        val removable = primary.copy(volumeName = "1234-ABCD", id = "content://media/1234-ABCD/images/media/7")
        assertNotEquals(sourceUiIdentity(primary), sourceUiIdentity(removable))
        assertNotEquals(sourceAlbumId("media", "external_primary", "7"), sourceAlbumId("media", "1234-ABCD", "7"))
        assertNotEquals(sourceAlbumId("other", "external_primary", "7"), sourceAlbumId("media", "external_primary", "7"))
        assertTrue(reconcileMediaTokens(setOf(sourceUiToken(primary)), listOf(removable)).isEmpty())
    }

    @Test fun resetDuringEnumerationRejectsVersionChangeOrGenerationRollback() {
        assertTrue(providerReadRemainedCurrent("a", 10, "a", 12))
        assertFalse(providerReadRemainedCurrent("a", 10, "b", 12))
        assertFalse(providerReadRemainedCurrent("a", 10, "a", 2))
        assertFalse(providerReadRemainedCurrent("a", 10, "a", null))
        assertTrue(providerReadRemainedCurrent(null, null, null, null)) // Explicitly best-effort old provider.
    }

    private fun strong() = item("content://media/external_primary/images/media/7").copy(
        providerAuthority = "media", volumeName = "external_primary", externalHandle = "7",
        providerVersion = "provider-a", providerGeneration = 100, generationAdded = 30,
        generationModified = 90, dateAddedSeconds = 10, modifiedAt = 20,
    )

    private fun item(id: String, video: Boolean = false) = MediaItem(
        id, id, if (video) "video/mp4" else "image/jpeg", video, 0, 0, 0, 0, 0, "", "",
    )
}
