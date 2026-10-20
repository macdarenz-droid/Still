package app.still.gallery.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactReviewPolicyTest {
    private val content = ReadableContentKey("a".repeat(64), 100)
    private val ready = ProtectionSnapshot(ProtectionStatus.READY, 0, emptySet())

    private class Fixture(val content: ReadableContentKey) {
        val session = ExactReviewSession()
        val scan = session.beginScan()
        val members = (1..3).map { session.candidate(scan, "fresh-source-$it", ReadableMediaKind.IMAGE) }
        val snapshot = session.finish(scan, members.map { ExactReadEvidence(it, content) })
        val group = snapshot.groups.single()
        val favorites = members.associateWith { FavoriteState.NOT_FAVORITE }
    }

    @Test fun keysRequireVersionedCanonicalDigestAndPositiveActualLength() {
        assertThrows(IllegalArgumentException::class.java) { ReadableContentKey("A".repeat(64), 1) }
        assertThrows(IllegalArgumentException::class.java) { ReadableContentKey("a".repeat(63), 1) }
        assertThrows(IllegalArgumentException::class.java) { ReadableContentKey("g".repeat(64), 1) }
        assertThrows(IllegalArgumentException::class.java) { ReadableContentKey("a".repeat(64), 0) }
        assertThrows(IllegalArgumentException::class.java) { ReadableContentKey("a".repeat(64), -1) }
        assertThrows(IllegalArgumentException::class.java) { ReadableContentKey("a".repeat(64), 1, "unknown-v2") }
        assertNotEquals(content, content.copy(byteCount = 101))
    }

    @Test fun matchingRequiresEqualDigestActualLengthAndCompatibleKind() {
        val session = ExactReviewSession()
        val scan = session.beginScan()
        val imageA = session.candidate(scan, "media:primary:image:1", ReadableMediaKind.IMAGE)
        val imageB = session.candidate(scan, "media:removable:image:1", ReadableMediaKind.IMAGE)
        val video = session.candidate(scan, "media:primary:video:1", ReadableMediaKind.VIDEO)
        val otherLength = session.candidate(scan, "media:primary:image:2", ReadableMediaKind.IMAGE)
        val otherDigest = session.candidate(scan, "media:primary:image:3", ReadableMediaKind.IMAGE)
        val snapshot = session.finish(scan, listOf(
            ExactReadEvidence(imageA, content), ExactReadEvidence(imageB, content), ExactReadEvidence(video, content),
            ExactReadEvidence(otherLength, content.copy(byteCount = 101)), ExactReadEvidence(otherDigest, content.copy(sha256 = "b".repeat(64))),
        ))
        assertEquals(listOf(imageA, imageB), snapshot.groups.single().members)
    }

    @Test fun noExtrasAreOfferedUntilAKeeperExists() {
        val fixture = Fixture(content)
        val review = fixture.session.review(fixture.snapshot, fixture.group, emptySet(), fixture.favorites, ready)
        assertEquals(ExactReviewReason.NEEDS_KEEPER, review.reason)
        assertTrue(review.keepers.isEmpty())
        assertTrue(review.extras.isEmpty())
    }

    @Test fun multipleManualKeepersAreReversibleAndNeverBecomeExtras() {
        val fixture = Fixture(content)
        val first = fixture.session.review(fixture.snapshot, fixture.group, fixture.members.take(2).toSet(), fixture.favorites, ready)
        assertEquals(fixture.members.take(2).toSet(), first.keepers)
        assertEquals(setOf(fixture.members[2]), first.extras)
        val second = fixture.session.review(fixture.snapshot, fixture.group, setOf(fixture.members[1]), fixture.favorites, ready)
        assertEquals(setOf(fixture.members[0], fixture.members[2]), second.extras)
        val cleared = fixture.session.review(fixture.snapshot, fixture.group, emptySet(), fixture.favorites, ready)
        assertTrue(cleared.extras.isEmpty())
        assertEquals(ExactReviewReason.NEEDS_KEEPER, cleared.reason)
    }

    @Test fun currentFavoriteEvidenceRecomputesKeepersAfterHashing() {
        val fixture = Fixture(content)
        val before = fixture.session.review(fixture.snapshot, fixture.group, emptySet(), fixture.favorites, ready)
        assertTrue(before.extras.isEmpty())
        val favorite = fixture.favorites + (fixture.members[2] to FavoriteState.FAVORITE)
        val after = fixture.session.review(fixture.snapshot, fixture.group, emptySet(), favorite, ready)
        assertEquals(setOf(fixture.members[2]), after.keepers)
        assertFalse(fixture.members[2] in after.extras)
        assertEquals(2, after.extras.size)
        val removed = fixture.session.review(fixture.snapshot, fixture.group, emptySet(), fixture.favorites, ready)
        assertEquals(ExactReviewReason.NEEDS_KEEPER, removed.reason)
    }

    @Test fun contentProtectionKeepsEveryMatchingReadableCopyRegardlessOfSourceReference() {
        val fixture = Fixture(content)
        val protected = ProtectionSnapshot(ProtectionStatus.READY, 7, setOf(content))
        val review = fixture.session.review(fixture.snapshot, fixture.group, setOf(fixture.members[0]), fixture.favorites, protected)
        assertEquals(fixture.members.toSet(), review.keepers)
        assertTrue(review.extras.isEmpty())
        val unprotected = fixture.session.review(fixture.snapshot, fixture.group, setOf(fixture.members[0]), fixture.favorites, protected.copy(revision = 8, keys = emptySet()))
        assertEquals(2, unprotected.extras.size)
    }

    @Test fun unknownFavoriteOrProtectionStateCannotAuthorizeExtras() {
        val fixture = Fixture(content)
        for (favorites in listOf(fixture.favorites - fixture.members[2], fixture.favorites + (fixture.members[2] to FavoriteState.UNKNOWN))) {
            val review = fixture.session.review(fixture.snapshot, fixture.group, setOf(fixture.members[0]), favorites, ready)
            assertEquals(ExactReviewReason.FAVORITE_UNKNOWN, review.reason)
            assertTrue(review.extras.isEmpty())
        }
        val unknown = fixture.session.review(fixture.snapshot, fixture.group, setOf(fixture.members[0]), fixture.favorites, ProtectionSnapshot.unknown())
        assertEquals(ExactReviewReason.PROTECTION_UNKNOWN, unknown.reason)
        assertTrue(unknown.extras.isEmpty())
    }

    @Test fun differentBytesAtAReusedReferenceNeverInheritContentProtection() {
        val original = Fixture(content)
        val replacement = Fixture(content.copy(sha256 = "b".repeat(64)))
        assertEquals(original.members[0].sourceReference, replacement.members[0].sourceReference)
        val protections = ProtectionSnapshot(ProtectionStatus.READY, 1, setOf(content))
        val review = replacement.session.review(replacement.snapshot, replacement.group, emptySet(), replacement.favorites, protections)
        assertEquals(ExactReviewReason.NEEDS_KEEPER, review.reason)
        assertTrue(review.extras.isEmpty())
        // A new reference can inherit only the same freshly read key, never metadata.
        assertEquals(content, original.group.content)
    }

    @Test fun duplicateSourceAliasesEvidenceAndFinalizationAreRejected() {
        val session = ExactReviewSession()
        val scan = session.beginScan()
        val candidate = session.candidate(scan, "same-normalized-source", ReadableMediaKind.IMAGE)
        assertThrows(IllegalArgumentException::class.java) { session.candidate(scan, candidate.sourceReference, ReadableMediaKind.IMAGE) }
        assertThrows(IllegalArgumentException::class.java) { session.finish(scan, listOf(ExactReadEvidence(candidate, content), ExactReadEvidence(candidate, content))) }
        val snapshot = session.finish(scan, listOf(ExactReadEvidence(candidate, content)))
        assertTrue(snapshot.groups.isEmpty())
        assertThrows(IllegalStateException::class.java) { session.finish(scan, emptyList()) }
        assertThrows(IllegalStateException::class.java) { session.candidate(scan, "late", ReadableMediaKind.IMAGE) }
    }

    @Test fun invalidationNewScansAndRecreationRejectOldResultsAndActions() {
        val fixture = Fixture(content)
        fixture.session.invalidate()
        assertThrows(StaleExactReviewException::class.java) { fixture.session.review(fixture.snapshot, fixture.group, emptySet(), fixture.favorites, ready) }
        assertThrows(StaleExactReviewException::class.java) { fixture.session.requireCurrent(fixture.scan) }
        val current = fixture.session.beginScan()
        fixture.session.requireCurrent(current)
        assertThrows(StaleExactReviewException::class.java) { fixture.session.candidate(fixture.scan, "late-source", ReadableMediaKind.IMAGE) }
        val recreated = ExactReviewSession()
        recreated.beginScan()
        assertThrows(StaleExactReviewException::class.java) { recreated.requireCurrent(fixture.scan) }
    }

    @Test fun anotherSessionsTokenOrGroupsCannotBeMixedIntoCurrentReview() {
        val first = Fixture(content)
        val second = Fixture(content)
        assertThrows(IllegalArgumentException::class.java) { first.session.review(first.snapshot, second.group, emptySet(), first.favorites, ready) }
        assertThrows(IllegalArgumentException::class.java) { first.session.review(first.snapshot, first.group, setOf(second.members[0]), first.favorites, ready) }
        val third = ExactReviewSession()
        val scan = third.beginScan()
        assertThrows(IllegalArgumentException::class.java) { third.finish(scan, listOf(ExactReadEvidence(first.members[0], content))) }
    }

    @Test fun manyMatchingGroupsRetainTheirOwnKeepersAndRejectAnEqualLookingForeignGroup() {
        val session = ExactReviewSession()
        val scan = session.beginScan()
        val evidence = (1..2000).flatMap { index ->
            val key = ReadableContentKey(index.toString(16).padStart(64, '0'), 100)
            (1..2).map { copy ->
                ExactReadEvidence(session.candidate(scan, "source-$index-$copy", ReadableMediaKind.IMAGE), key)
            }
        }
        val snapshot = session.finish(scan, evidence)
        assertEquals(2000, snapshot.groups.size)
        for (group in snapshot.groups) {
            val keeper = group.members.first()
            val review = session.review(snapshot, group, setOf(keeper), group.members.associateWith { FavoriteState.NOT_FAVORITE }, ready)
            assertEquals(ExactReviewReason.READY, review.reason)
            assertEquals(setOf(keeper), review.keepers)
            assertEquals(setOf(group.members.last()), review.extras)
        }
        val original = snapshot.groups.last()
        val foreign = ExactMatchGroup(original.content, original.kind, original.members)
        assertThrows(IllegalArgumentException::class.java) {
            session.review(snapshot, foreign, setOf(original.members.first()), original.members.associateWith { FavoriteState.NOT_FAVORITE }, ready)
        }
    }
}
