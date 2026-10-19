package app.still.gallery.inputs

import app.still.gallery.media.MediaItem
import app.still.gallery.media.sourceUiToken
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SelectedInputContractTest {
    private fun photo(number: Int) = MediaItem(
        id = "content://media/external_primary/images/media/$number",
        name = "$number.jpg", mimeType = "image/jpeg", isVideo = false,
        capturedAt = 1000, width = 100, height = 100, bytes = 1234, durationMs = 0,
        albumId = "album", albumName = "Album", providerVersion = "v1",
        providerGeneration = 10, externalHandle = number.toString(),
        generationAdded = number.toLong(), generationModified = 8,
    )
    private fun photos() = listOf(photo(1), photo(2))
    private fun member(ticket: SelectedInputTicket, id: String) = PreparedSelectedInput(ticket, id, "$id.jpg", 1234, "a".repeat(64))
    private fun expect(reason: SelectedInputFailure, block: () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (failure: SelectedInputException) { assertEquals(reason, failure.reason) }
    }
    private fun expectImmutable(block: () -> Unit) {
        try { block(); fail("Published snapshots must be unmodifiable") }
        catch (_: UnsupportedOperationException) { }
    }
    private fun initialize(owner: SelectedInputOwnership, root: String = "cache") {
        val (ticket, result) = owner.maintenance(root, null)
        assertEquals(SelectedInputCleanupResult.BUSY, result)
        assertNotNull(ticket)
        owner.finishMaintenance(ticket!!, true)
    }
    private fun acquire(owner: SelectedInputOwnership, check: () -> Unit = {}) = owner.acquire("cache", photos(), check)
    private fun publish(owner: SelectedInputOwnership, ticket: SelectedInputTicket): PreparedInputPair {
        owner.beginWork(ticket)
        val pair = PreparedInputPair(ticket, listOf(member(ticket, "first"), member(ticket, "second")))
        owner.publish(ticket, pair)
        owner.finishWork(ticket)
        return pair
    }

    @Test fun selectionRequiresExactlyTwoPhotosAndPreservesRequestedOrder() {
        val requested = mutableListOf(photo(2), photo(1))
        val selected = selectedPhotos(requested)
        requested.clear()
        assertEquals(listOf(photo(2), photo(1)), selected)
        for (invalid in listOf(emptyList(), listOf(photo(1)), listOf(photo(1), photo(2), photo(3)))) {
            expect(SelectedInputFailure.INVALID_SELECTION) { selectedPhotos(invalid) }
        }
    }

    @Test fun duplicateHandlesCannotBecomeTwoInputsByChangingVisibleMetadata() {
        val first = photo(1)
        val changed = first.copy(name = "renamed.jpg", providerGeneration = 11, bytes = 99)
        assertNotEquals(sourceUiToken(first), sourceUiToken(changed))
        expect(SelectedInputFailure.INVALID_SELECTION) { selectedPhotos(listOf(first, first)) }
        expect(SelectedInputFailure.INVALID_SELECTION) { selectedPhotos(listOf(first, changed)) }
    }

    @Test fun aliasUrisForOneProviderVolumeAndHandleRemainOneInput() {
        val first = photo(1)
        val alias = first.copy(id = "content://media/external/images/media/1", name = "alias.jpg")
        assertNotEquals(first.id, alias.id)
        assertNotEquals(sourceUiToken(first), sourceUiToken(alias))
        expect(SelectedInputFailure.INVALID_SELECTION) { selectedPhotos(listOf(first, alias)) }
        // A handle in another concrete volume is a different source, not an alias.
        assertEquals(2, selectedPhotos(listOf(first, alias.copy(volumeName = "removable"))).size)
    }

    @Test fun videoInEitherPositionIsRejectedWithoutInferringBytesFromMetadata() {
        val video = photo(2).copy(isVideo = true, mimeType = "video/mp4")
        expect(SelectedInputFailure.INVALID_SELECTION) { selectedPhotos(listOf(photo(1), video)) }
        expect(SelectedInputFailure.INVALID_SELECTION) { selectedPhotos(listOf(video, photo(1))) }
        val unknownSize = photo(1).copy(bytes = -1)
        val hugeReportedOriginal = photo(2).copy(bytes = Long.MAX_VALUE)
        assertEquals(listOf(unknownSize, hugeReportedOriginal), selectedPhotos(listOf(unknownSize, hugeReportedOriginal)))
    }

    @Test fun actualByteAccountingAcceptsExactPerInputAndPairLimits() {
        assertEquals(32L * 1024 * 1024, SelectedInputBounds.MAX_INPUT_BYTES)
        assertEquals(64L * 1024 * 1024, SelectedInputBounds.MAX_RETAINED_BYTES)
        assertEquals(64 * 1024, SelectedInputBounds.BUFFER_BYTES)
        var actual = 0L
        repeat(512) { actual = selectedInputCount(actual, 64 * 1024, SelectedInputBounds.MAX_INPUT_BYTES) }
        assertEquals(SelectedInputBounds.MAX_INPUT_BYTES, actual)
        assertEquals(SelectedInputBounds.MAX_RETAINED_BYTES,
            selectedInputCount(actual, actual.toInt(), SelectedInputBounds.MAX_RETAINED_BYTES))
        expect(SelectedInputFailure.SIZE_LIMIT) { selectedInputCount(actual, 1, SelectedInputBounds.MAX_INPUT_BYTES) }
        expect(SelectedInputFailure.SIZE_LIMIT) {
            selectedInputCount(SelectedInputBounds.MAX_RETAINED_BYTES, 1, SelectedInputBounds.MAX_RETAINED_BYTES)
        }
    }

    @Test fun byteAccountingRejectsMalformedCountersAndOverflow() {
        for ((current, count, limit) in listOf(
            Triple(-1L, 1, 10L), Triple(0L, -1, 10L), Triple(11L, 0, 10L),
            Triple(0L, 0, -1L), Triple(Long.MAX_VALUE, 1, Long.MAX_VALUE),
            Triple(Long.MAX_VALUE - 2, 3, Long.MAX_VALUE),
        )) expect(SelectedInputFailure.SIZE_LIMIT) { selectedInputCount(current, count, limit) }
        assertEquals(Long.MAX_VALUE, selectedInputCount(Long.MAX_VALUE - 1, 1, Long.MAX_VALUE))
        assertEquals(9L, selectedInputCount(9, 0, 10))
    }

    @Test fun ticketsHoldDefensiveUnmodifiableSelectedSnapshots() {
        val selected = photos().toMutableList()
        val ticket = SelectedInputTicket("cache", selected) {}
        selected[0] = photo(3)
        selected.clear()
        assertEquals(photos(), ticket.selected)
        expectImmutable { (ticket.selected as MutableList<MediaItem>).clear() }
        assertEquals(photos(), ticket.selected)
    }

    @Test fun preparedPairSnapshotsHaveTaskByteFidelityAndReferenceIdentity() {
        val ticket = SelectedInputTicket("cache", photos()) {}
        val first = member(ticket, "opaque-first")
        val sameShape = member(ticket, "opaque-first")
        assertNotSame(first, sameShape)
        assertNotEquals(first, sameShape)
        val members = mutableListOf(first, member(ticket, "opaque-second"))
        val pair = PreparedInputPair(ticket, members)
        members.clear()
        assertSame(ticket, pair.ticket)
        assertEquals(2, pair.members.size)
        assertSame(first, pair.members[0])
        assertEquals(SelectedInputFidelity.READABLE_REPRESENTATION, first.fidelity)
        assertEquals("opaque-first", first.representationId)
        assertEquals(1234L, first.byteCount)
        expectImmutable { (pair.members as MutableList<PreparedSelectedInput>).removeAt(0) }
        assertEquals(2, pair.members.size)
    }

    @Test fun acquisitionRequiresSuccessfulCleanupOfTheExactRoot() {
        val owner = SelectedInputOwnership()
        expect(SelectedInputFailure.NEEDS_CLEANUP) { acquire(owner) }
        initialize(owner, "other-cache")
        expect(SelectedInputFailure.NEEDS_CLEANUP) { acquire(owner) }
        initialize(owner)
        assertTrue(owner.current(acquire(owner)))
    }

    @Test fun acquisitionChecksLiveAuthorityAndFailureDoesNotReserveAnOwner() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        expect(SelectedInputFailure.STALE) { acquire(owner) { error("Permission withdrawn") } }
        val current = acquire(owner)
        assertTrue(owner.current(current))
    }

    @Test fun authorityWithdrawalIsPermanentForTheTicketEvenIfAccessReturns() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        var authorized = true
        var checks = 0
        val ticket = acquire(owner) { checks++; check(authorized) }
        assertTrue(owner.current(ticket))
        authorized = false
        assertFalse(owner.current(ticket))
        val afterWithdrawal = checks
        authorized = true
        assertFalse(owner.current(ticket))
        assertEquals(afterWithdrawal, checks)
        expect(SelectedInputFailure.STALE) { owner.beginWork(ticket) }
    }

    @Test fun explicitInvalidationCannotBeUndoneByAnotherCurrentCheck() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        val ticket = acquire(owner)
        ticket.invalidate()
        assertTrue(owner.owns(ticket)) // Invalidating authority does not free retained storage.
        assertFalse(owner.current(ticket))
        expect(SelectedInputFailure.STALE) { ticket.requireCurrent() }
        expect(SelectedInputFailure.BUSY) { acquire(owner) }
    }

    @Test fun invalidationDuringTheAuthorityCallbackCannotPublishCurrentness() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        var withdrawDuringCheck = false
        lateinit var ticket: SelectedInputTicket
        ticket = acquire(owner) { if (withdrawDuringCheck) ticket.invalidate() }
        assertTrue(owner.current(ticket))
        withdrawDuringCheck = true
        assertFalse(owner.current(ticket))
        expect(SelectedInputFailure.STALE) { ticket.requireCurrent() }
    }

    @Test fun blockedPreparationOwnsQuotaAfterCancellationUntilActualWorkFinishes() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        val ticket = acquire(owner)
        owner.beginWork(ticket)
        ticket.invalidate()
        assertEquals(null to SelectedInputCleanupResult.BUSY, owner.maintenance("cache", ticket))
        expect(SelectedInputFailure.BUSY) { acquire(owner) }
        owner.finishWork(ticket)
        val (cleanup, result) = owner.maintenance("cache", ticket)
        assertSame(ticket, cleanup)
        assertEquals(SelectedInputCleanupResult.BUSY, result)
        owner.finishMaintenance(ticket, true)
        assertTrue(owner.current(acquire(owner)))
    }

    @Test fun aReadySessionPreventsAnotherControllerOrRootFromSweepingIt() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        val ticket = acquire(owner)
        val pair = publish(owner, ticket)
        assertEquals(null to SelectedInputCleanupResult.BUSY, owner.maintenance("cache", null))
        assertEquals(null to SelectedInputCleanupResult.BUSY, owner.maintenance("other-cache", null))
        expect(SelectedInputFailure.BUSY) { owner.acquire("other-cache", photos()) {} }
        owner.borrow(ticket, pair.members[0])
        owner.releaseRead(ticket)
        assertTrue(owner.current(ticket))
    }

    @Test fun onlyThePublishedMemberReferenceAndCurrentTicketCanBorrow() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        val ticket = acquire(owner)
        val pair = publish(owner, ticket)
        val sameShapedTicket = SelectedInputTicket("cache", photos()) { fail("A foreign ticket cannot run its authority callback") }
        val sameShapedMember = member(ticket, "first")
        assertFalse(owner.current(sameShapedTicket))
        expect(SelectedInputFailure.STALE) { owner.borrow(sameShapedTicket, pair.members[0]) }
        expect(SelectedInputFailure.STALE) { owner.borrow(ticket, sameShapedMember) }
        owner.borrow(ticket, pair.members[0])
        owner.borrow(ticket, pair.members[1])
        expect(SelectedInputFailure.BUSY) { owner.borrow(ticket, pair.members[0]) }
        owner.releaseRead(ticket)
        owner.releaseRead(ticket)
    }

    @Test fun separateOwnershipInstancesCannotUseEachOthersTickets() {
        val first = SelectedInputOwnership()
        val second = SelectedInputOwnership()
        initialize(first); initialize(second)
        val firstTicket = acquire(first)
        val secondTicket = acquire(second)
        val firstPair = publish(first, firstTicket)
        assertFalse(second.current(firstTicket))
        assertFalse(first.current(secondTicket))
        expect(SelectedInputFailure.STALE) { second.beginWork(firstTicket) }
        expect(SelectedInputFailure.STALE) { second.borrow(firstTicket, firstPair.members[0]) }
        assertTrue(second.current(secondTicket))
    }

    @Test fun publicationRejectsAPairBoundToAnotherTaskTicket() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        val ticket = acquire(owner)
        owner.beginWork(ticket)
        val foreign = SelectedInputTicket("cache", photos()) {}
        val pair = PreparedInputPair(foreign, listOf(member(ticket, "first"), member(ticket, "second")))
        expect(SelectedInputFailure.STALE) { owner.publish(ticket, pair) }
        // Rejecting the foreign publication must leave the real task usable.
        val valid = PreparedInputPair(ticket, listOf(member(ticket, "real-first"), member(ticket, "real-second")))
        owner.publish(ticket, valid)
        owner.finishWork(ticket)
        owner.borrow(ticket, valid.members[0])
        owner.releaseRead(ticket)
    }

    @Test fun publicationCannotAttachAForeignMemberToTheCurrentPair() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        val ticket = acquire(owner)
        owner.beginWork(ticket)
        val foreign = SelectedInputTicket("cache", photos()) {}
        val mixedPair = PreparedInputPair(ticket, listOf(member(ticket, "first"), member(foreign, "second")))
        expect(SelectedInputFailure.STALE) { owner.publish(ticket, mixedPair) }
        val currentPair = PreparedInputPair(ticket, listOf(member(ticket, "first"), member(ticket, "second")))
        owner.publish(ticket, currentPair)
        owner.finishWork(ticket)
        expect(SelectedInputFailure.STALE) { owner.borrow(ticket, mixedPair.members[1]) }
        owner.borrow(ticket, currentPair.members[1])
        owner.releaseRead(ticket)
    }

    @Test fun publicationRequiresTwoDistinctRepresentationMembers() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        val ticket = acquire(owner)
        owner.beginWork(ticket)
        val first = member(ticket, "first")
        expect(SelectedInputFailure.STALE) { owner.publish(ticket, PreparedInputPair(ticket, listOf(first, first))) }
        val sameRepresentationId = member(ticket, "first")
        assertNotSame(first, sameRepresentationId)
        expect(SelectedInputFailure.STALE) {
            owner.publish(ticket, PreparedInputPair(ticket, listOf(first, sameRepresentationId)))
        }
        val pair = PreparedInputPair(ticket, listOf(first, member(ticket, "second")))
        owner.publish(ticket, pair)
        owner.finishWork(ticket)
        owner.borrow(ticket, pair.members[0])
        owner.releaseRead(ticket)
    }

    @Test fun aReaderKeepsOwnershipUntilCloseAndBlocksBorrowAfterInvalidation() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        val ticket = acquire(owner)
        val pair = publish(owner, ticket)
        owner.borrow(ticket, pair.members[0])
        ticket.invalidate()
        expect(SelectedInputFailure.STALE) { owner.borrow(ticket, pair.members[1]) }
        assertEquals(null to SelectedInputCleanupResult.BUSY, owner.maintenance("cache", ticket))
        owner.failedReadClose(ticket)
        assertEquals(null to SelectedInputCleanupResult.FAILED, owner.maintenance("cache", ticket))
        expect(SelectedInputFailure.BUSY) { acquire(owner) }
        owner.releaseRead(ticket)
        val (cleanup, _) = owner.maintenance("cache", ticket)
        assertSame(ticket, cleanup)
        owner.finishMaintenance(ticket, true)
        assertTrue(owner.current(acquire(owner)))
    }

    @Test fun unsuccessfulCleanupRetainsQuotaUntilAConfirmedSuccessfulRetry() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        val ticket = acquire(owner)
        ticket.invalidate()
        assertSame(ticket, owner.maintenance("cache", ticket).first)
        owner.finishMaintenance(ticket, false)
        expect(SelectedInputFailure.BUSY) { acquire(owner) }
        assertSame(ticket, owner.maintenance("cache", ticket).first)
        owner.finishMaintenance(ticket, true)
        val replacement = acquire(owner)
        assertNotSame(ticket, replacement)
        assertFalse(owner.current(ticket))
        assertTrue(owner.current(replacement))
    }

    @Test fun staleCleanupAndWorkerCallbacksCannotReleaseAReplacementTask() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        val old = acquire(owner)
        old.invalidate()
        assertSame(old, owner.maintenance("cache", old).first)
        owner.finishMaintenance(old, true)
        val replacement = acquire(owner)
        assertEquals(old.selected, replacement.selected)
        val pair = publish(owner, replacement)
        owner.borrow(replacement, pair.members[0])
        assertEquals(null to SelectedInputCleanupResult.CLEAN, owner.maintenance("cache", old))
        owner.finishWork(old)
        owner.releaseRead(old)
        owner.failedReadClose(old)
        owner.finishMaintenance(old, true)
        assertTrue(owner.current(replacement))
        replacement.invalidate()
        assertEquals(null to SelectedInputCleanupResult.BUSY, owner.maintenance("cache", replacement))
        owner.releaseRead(replacement)
        assertSame(replacement, owner.maintenance("cache", replacement).first)
        owner.finishMaintenance(replacement, true)
    }

    @Test fun simultaneousAcquisitionsAdmitOneTaskWithoutQueuing() {
        val owner = SelectedInputOwnership()
        initialize(owner)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map {
                workers.submit<Any> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    try { acquire(owner) } catch (failure: SelectedInputException) { failure.reason }
                }
            }
            start.countDown()
            val results = attempts.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is SelectedInputTicket })
            assertEquals(1, results.count { it == SelectedInputFailure.BUSY })
            assertTrue(owner.current(results.filterIsInstance<SelectedInputTicket>().single()))
        } finally { workers.shutdownNow() }
    }
}
