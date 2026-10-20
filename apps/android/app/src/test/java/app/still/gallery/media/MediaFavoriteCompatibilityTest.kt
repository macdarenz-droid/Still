package app.still.gallery.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFavoriteCompatibilityTest {
    @Test fun onlyExactAggregateImageAndVideoReferencesAreLegacyKeys() {
        val zero = "content://media/external/images/media/0"
        assertEquals(LegacyFavoriteReference(zero, false, "0"), legacyFavoriteReference(zero))
        assertEquals(LegacyFavoriteReference(OLD_VIDEO, true, "7"), legacyFavoriteReference(OLD_VIDEO))

        listOf(
            "http://media/external/images/media/7",
            "content://other/external/images/media/7",
            "content://media/external_primary/images/media/7",
            "content://media/external/videos/media/7",
            "content://media/external/images/media/-7",
            "content://media/external/images/media/+7",
            "content://media/external/images/media/seven",
            "content://media/external/images/media/7/",
            "content://media/external/images/media/7?original=1",
            "content://media/external/images/media/7#preview",
        ).forEach { key -> assertNull(key, legacyFavoriteReference(key)) }
    }

    @Test fun provenAggregateImageAndVideoFavoritesRemainVisibleAfterUriUpgrade() {
        // Image/video row IDs share a numeric value but are separate collections.
        val image = media()
        val video = media(isVideo = true)
        val live = listOf(image, video)
        val saved = mutableSetOf(OLD_IMAGE, OLD_VIDEO)
        val before = saved.toSet()
        val imageMatch = provenLegacyFavoriteMatch(reference(OLD_IMAGE), proof(image), live)
        val videoMatch = provenLegacyFavoriteMatch(reference(OLD_VIDEO), proof(video), live)

        assertEquals(image.id, imageMatch)
        assertEquals(video.id, videoMatch)
        val matches = mapOf(OLD_IMAGE to requireNotNull(imageMatch), OLD_VIDEO to requireNotNull(videoMatch))
        assertEquals(setOf(image.id, video.id), visibleFavoriteSourceIds(saved, matches, live))
        assertTrue(unresolvedFavoriteKeys(saved, matches, live).isEmpty())
        assertEquals(before, saved)
    }

    @Test fun mixedOldAndCurrentKeysDeduplicateVisibilityWithoutRewritingPreferences() {
        val image = media()
        val video = media(isVideo = true)
        val unavailable = "content://media/external/images/media/999"
        val saved = mutableSetOf(OLD_IMAGE, image.id, video.id, unavailable)
        val before = saved.toSet()
        val matches = mapOf(OLD_IMAGE to image.id)

        assertEquals(setOf(image.id, video.id), visibleFavoriteSourceIds(saved, matches, listOf(image, video)))
        assertEquals(setOf(unavailable), unresolvedFavoriteKeys(saved, matches, listOf(image, video)))
        assertEquals(before, saved)
    }

    @Test fun duplicateRowAcrossVolumesIsAmbiguousEvenWhenProofNamesOneVolume() {
        val primary = media()
        val card = media(volume = "1234-ABCD", dateAdded = 1_800_000_000, generationAdded = 80)
        val reference = reference(OLD_IMAGE)

        assertNull(provenLegacyFavoriteMatch(reference, proof(primary), listOf(primary, card)))
        assertNull(provenLegacyFavoriteMatch(reference, proof(primary), listOf(card, primary)))
    }

    @Test fun mismatchedProofNamespaceTypeAndBirthCannotRetargetLegacyFavorite() {
        val image = media()
        val valid = proof(image)
        listOf(
            valid.copy(providerAuthority = "other"),
            valid.copy(volumeName = "1234-ABCD"),
            valid.copy(externalHandle = "8"),
            valid.copy(isVideo = true),
            valid.copy(dateAddedSeconds = valid.dateAddedSeconds + 1),
            valid.copy(generationAdded = 11),
            valid.copy(generationAdded = null),
        ).forEach { wrong ->
            assertNull(wrong.toString(), provenLegacyFavoriteMatch(reference(OLD_IMAGE), wrong, listOf(image)))
        }
    }

    @Test fun changedLiveProviderVolumeRowTypeOrBirthCannotReuseOldProof() {
        val image = media()
        val oldProof = proof(image)
        listOf(
            media(authority = "other"),
            media(volume = "1234-ABCD"),
            media(handle = "8"),
            media(isVideo = true),
            image.copy(dateAddedSeconds = image.dateAddedSeconds + 1),
            image.copy(generationAdded = 11),
            image.copy(generationAdded = null),
        ).forEach { replacement ->
            assertNull(replacement.toString(), provenLegacyFavoriteMatch(reference(OLD_IMAGE), oldProof, listOf(replacement)))
        }
    }

    @Test fun modernProofCannotSubstituteDateAddedForMissingBirthGeneration() {
        val image = media(dateAdded = 123)
        val modernProof = proof(image).copy(birthGenerationRequired = true)
        assertEquals(image.id, provenLegacyFavoriteMatch(reference(OLD_IMAGE), modernProof, listOf(image)))

        val missingGeneration = image.copy(generationAdded = null)
        assertNull(provenLegacyFavoriteMatch(
            reference(OLD_IMAGE),
            modernProof.copy(generationAdded = null),
            listOf(missingGeneration),
        ))
    }

    @Test fun api29BestEffortProofRequiresPositiveDateAddedWhenBirthGenerationIsAbsent() {
        val image = media(dateAdded = 123, generationAdded = null)
        val fallbackProof = proof(image).copy(birthGenerationRequired = false)
        assertEquals(image.id, provenLegacyFavoriteMatch(reference(OLD_IMAGE), fallbackProof, listOf(image)))

        listOf(0L, -1L).forEach { unknownDate ->
            val unknownBirth = image.copy(dateAddedSeconds = unknownDate)
            assertNull("DATE_ADDED=$unknownDate", provenLegacyFavoriteMatch(
                reference(OLD_IMAGE),
                proof(unknownBirth).copy(birthGenerationRequired = false),
                listOf(unknownBirth),
            ))
        }
    }

    @Test fun noProofMeansNoRowNumberGuessAndTheSavedKeyRemainsUnresolved() {
        val image = media()
        val saved = mutableSetOf(OLD_IMAGE)

        assertTrue(visibleFavoriteSourceIds(saved, emptyMap(), listOf(image)).isEmpty())
        assertEquals(setOf(OLD_IMAGE), unresolvedFavoriteKeys(saved, emptyMap(), listOf(image)))
        assertEquals(setOf(OLD_IMAGE), saved)
    }

    @Test fun reducedLiveAccessHidesMappedAndCurrentFavoritesWithoutDroppingTheirKeys() {
        val image = media()
        val video = media(isVideo = true)
        val saved = mutableSetOf(OLD_IMAGE, OLD_VIDEO, video.id)
        val before = saved.toSet()
        val matches = mapOf(OLD_IMAGE to image.id, OLD_VIDEO to video.id)

        assertEquals(setOf(image.id, video.id), visibleFavoriteSourceIds(saved, matches, listOf(image, video)))
        assertEquals(setOf(image.id), visibleFavoriteSourceIds(saved, matches, listOf(image)))
        assertEquals(setOf(OLD_VIDEO, video.id), unresolvedFavoriteKeys(saved, matches, listOf(image)))
        assertTrue(visibleFavoriteSourceIds(saved, matches, emptyList()).isEmpty())
        assertEquals(before, unresolvedFavoriteKeys(saved, matches, emptyList()))
        assertEquals(before, saved)
    }

    @Test fun explicitUnfavoriteRemovesMatchedOldAndCurrentKeysOnly() {
        val image = media()
        val video = media(isVideo = true)
        val card = media(volume = "1234-ABCD")
        val unavailable = "content://media/external/images/media/999"
        val saved = mutableSetOf(OLD_IMAGE, image.id, OLD_VIDEO, card.id, unavailable)
        val before = saved.toSet()
        val matches = mapOf(OLD_IMAGE to image.id, OLD_VIDEO to video.id)
        val removed = favoriteKeysForSource(image.id, saved, matches)

        assertEquals(setOf(OLD_IMAGE, image.id), removed)
        val remaining = saved - removed
        assertEquals(setOf(OLD_VIDEO, card.id, unavailable), remaining)
        assertFalse(image.id in visibleFavoriteSourceIds(remaining, matches, listOf(image, video)))
        assertEquals(setOf(video.id), visibleFavoriteSourceIds(remaining, matches, listOf(image, video)))
        assertEquals(setOf(card.id, unavailable), unresolvedFavoriteKeys(remaining, matches, listOf(image, video)))
        assertEquals(before, saved)
    }

    @Test fun unfavoriteDoesNotRemoveAnUnprovedOldKeySharingTheSameRowNumber() {
        val image = media()
        val saved = mutableSetOf(OLD_IMAGE, image.id)

        assertEquals(setOf(image.id), favoriteKeysForSource(image.id, saved, emptyMap()))
        assertEquals(setOf(OLD_IMAGE), saved - favoriteKeysForSource(image.id, saved, emptyMap()))
        assertEquals(setOf(OLD_IMAGE, image.id), saved)
    }

    @Test fun aResolutionForAnUnsavedKeyCannotCreateOrRemoveAFavorite() {
        val image = media()
        val video = media(isVideo = true)
        val saved = setOf(video.id)
        val matches = mapOf(OLD_IMAGE to image.id)

        assertEquals(setOf(video.id), visibleFavoriteSourceIds(saved, matches, listOf(image, video)))
        assertTrue(favoriteKeysForSource(image.id, saved, matches).isEmpty())
        assertTrue(unresolvedFavoriteKeys(saved, matches, listOf(image, video)).isEmpty())
    }

    private fun reference(key: String) = requireNotNull(legacyFavoriteReference(key))

    private fun proof(item: MediaItem) = LegacyFavoriteProof(
        providerAuthority = item.providerAuthority,
        volumeName = item.volumeName,
        externalHandle = item.externalHandle,
        isVideo = item.isVideo,
        dateAddedSeconds = item.dateAddedSeconds,
        generationAdded = item.generationAdded,
    )

    private fun media(
        isVideo: Boolean = false,
        volume: String = "external_primary",
        handle: String = "7",
        authority: String = "media",
        dateAdded: Long = if (isVideo) 1_700_000_100 else 1_700_000_000,
        generationAdded: Long? = if (isVideo) 20 else 10,
    ) = MediaItem(
        id = "content://$authority/$volume/${if (isVideo) "video" else "images"}/media/$handle",
        name = if (isVideo) "clip.mp4" else "photo.jpg",
        mimeType = if (isVideo) "video/mp4" else "image/jpeg",
        isVideo = isVideo,
        capturedAt = 1_700_000_000_000,
        width = 1200,
        height = 800,
        bytes = 4096,
        durationMs = if (isVideo) 2000 else 0,
        albumId = "camera",
        albumName = "Camera",
        providerAuthority = authority,
        volumeName = volume,
        providerVersion = "provider-v1",
        providerGeneration = 100,
        externalHandle = handle,
        generationAdded = generationAdded,
        generationModified = 50,
        dateAddedSeconds = dateAdded,
    )

    companion object {
        private const val OLD_IMAGE = "content://media/external/images/media/7"
        private const val OLD_VIDEO = "content://media/external/video/media/7"
    }
}
