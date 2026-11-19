package app.still.gallery.backup.source

import org.junit.Assert.*
import org.junit.Test

/** Hypothetical metadata only: these tests never establish original/device/provider authority. */
class BackupSourceEligibilityPolicyTest {
    private val source = SyntheticSourceIdentity(1, 2, 3, 4, 5)
    private val photo = SyntheticSelectedSource(source, SyntheticMediaKind.PHOTO)
    private val primary = SyntheticSourceComponent(source, 11, 1, SyntheticComponentRole.PRIMARY)
    private val motion = SyntheticSourceComponent(source, 12, 2, SyntheticComponentRole.MOTION)
    private val sidecar = SyntheticSourceComponent(source, 13, 3, SyntheticComponentRole.SIDECAR)
    private val all = listOf(primary, motion, sidecar)
    private fun scenario(requested: List<SyntheticSelectedSource> = listOf(photo), current: List<SyntheticSelectedSource> = requested,
        identity: SyntheticSourceIdentity = source, fidelity: SyntheticFidelity = SyntheticFidelity.ORIGINAL_BYTES,
        continuity: SyntheticSourceContinuity = SyntheticSourceContinuity.STABLE_IN_MODEL,
        status: SyntheticInventoryStatus = SyntheticInventoryStatus.COMPLETE,
        required: List<SyntheticSourceComponent> = listOf(primary), available: List<SyntheticSourceComponent> = required,
        originalRequest: Boolean = false) = SyntheticSourceEligibilityScenario(requested, current,
        SyntheticSourceEvidence(identity, fidelity, continuity, SyntheticSourceInventory(status, required, available)), originalRequest)
    private fun evaluate(value: SyntheticSourceEligibilityScenario) = BackupSourceEligibilityPolicy.evaluateSynthetic(value)
    private fun noAuthority(result: SyntheticSourceEligibilityDecision) {
        assertEquals("synthetic_source_eligibility_model_v1", result.scope)
        assertFalse(result.productionCapability)
        assertFalse(result.backupEligible)
    }
    private fun blocked(value: SyntheticSourceEligibilityScenario, vararg reasons: SyntheticEligibilityBlocker): SyntheticSourceEligibilityDecision {
        val result = evaluate(value)
        assertFalse("Hypothetical requirements must fail: ${reasons.toList()}", result.syntheticModelRequirementsSatisfied)
        for (reason in reasons) assertTrue("Missing blocker $reason in ${result.blockers}", result.blockers.contains(reason))
        noAuthority(result); return result
    }
    private fun satisfied(value: SyntheticSourceEligibilityScenario): SyntheticSourceEligibilityDecision {
        val result = evaluate(value)
        assertTrue(result.syntheticModelRequirementsSatisfied)
        assertTrue(result.blockers.isEmpty()); noAuthority(result); return result
    }

    @Test fun completePrimaryOnlySatisfiesSyntheticModelWithoutRealBackupEligibility() { satisfied(scenario()) }
    @Test fun completePrimaryMotionAndSidecarSatisfyOnlyTheSyntheticModel() { satisfied(scenario(required = all)) }
    @Test fun originalRequestFlagIsNeitherRequiredProofNorASubstituteForEvidence() {
        satisfied(scenario(originalRequest = true))
        blocked(scenario(fidelity = SyntheticFidelity.READABLE_REPRESENTATION, originalRequest = true),
            SyntheticEligibilityBlocker.READABLE_REPRESENTATION_ONLY, SyntheticEligibilityBlocker.ORIGINAL_REQUEST_IS_NOT_PROOF)
        blocked(scenario(fidelity = SyntheticFidelity.UNKNOWN, continuity = SyntheticSourceContinuity.UNKNOWN,
            status = SyntheticInventoryStatus.UNKNOWN, originalRequest = true), SyntheticEligibilityBlocker.FIDELITY_UNKNOWN,
            SyntheticEligibilityBlocker.SOURCE_STABILITY_UNPROVEN, SyntheticEligibilityBlocker.INVENTORY_UNKNOWN, SyntheticEligibilityBlocker.ORIGINAL_REQUEST_IS_NOT_PROOF)
    }
    @Test fun readableOnlyUnknownAndUnsupportedFidelityStayBlockedEvenWithCompleteInventory() {
        val cases = mapOf(SyntheticFidelity.READABLE_REPRESENTATION to SyntheticEligibilityBlocker.READABLE_REPRESENTATION_ONLY,
            SyntheticFidelity.UNKNOWN to SyntheticEligibilityBlocker.FIDELITY_UNKNOWN,
            SyntheticFidelity.UNSUPPORTED to SyntheticEligibilityBlocker.FIDELITY_UNSUPPORTED)
        for ((fidelity, reason) in cases) blocked(scenario(fidelity = fidelity), reason)
    }
    @Test fun noEvidenceDoesNotAcquireAuthorityFromAnOriginalRequestOrSelection() {
        blocked(SyntheticSourceEligibilityScenario(listOf(photo), listOf(photo), null, true), SyntheticEligibilityBlocker.SOURCE_EVIDENCE_MISSING)
    }
    @Test fun metadataOnlyOrUnknownStabilityCannotSupportOriginalRequirements() {
        for (continuity in listOf(SyntheticSourceContinuity.OBSERVED_METADATA_ONLY, SyntheticSourceContinuity.UNKNOWN))
            blocked(scenario(continuity = continuity, originalRequest = true), SyntheticEligibilityBlocker.SOURCE_STABILITY_UNPROVEN)
        blocked(scenario(continuity = SyntheticSourceContinuity.CHANGED), SyntheticEligibilityBlocker.SOURCE_CHANGED)
    }
    @Test fun unknownPartialAndUnsupportedInventoryBlockEvenWhenAllDeclaredComponentsExist() {
        val cases = mapOf(SyntheticInventoryStatus.UNKNOWN to SyntheticEligibilityBlocker.INVENTORY_UNKNOWN,
            SyntheticInventoryStatus.PARTIAL to SyntheticEligibilityBlocker.INVENTORY_PARTIAL,
            SyntheticInventoryStatus.UNSUPPORTED to SyntheticEligibilityBlocker.INVENTORY_UNSUPPORTED)
        for ((status, reason) in cases) blocked(scenario(status = status, required = all), reason)
    }
    @Test fun missingMotionOrSidecarBlocksTheWholePhotoInsteadOfAcceptingPrimaryOnly() {
        for (missing in listOf(motion, sidecar)) blocked(scenario(required = all, available = all - missing), SyntheticEligibilityBlocker.REQUIRED_COMPONENT_MISSING)
    }
    @Test fun anExtraUnspecifiedCompanionCannotBeSilentlyIgnored() {
        blocked(scenario(required = listOf(primary), available = all), SyntheticEligibilityBlocker.UNDECLARED_COMPONENT)
    }
    @Test fun exactlyOnePrimaryIsRequiredInBothInventories() {
        blocked(scenario(required = listOf(motion, sidecar)), SyntheticEligibilityBlocker.PRIMARY_REQUIRED)
        blocked(scenario(required = all, available = listOf(motion, sidecar)), SyntheticEligibilityBlocker.PRIMARY_REQUIRED, SyntheticEligibilityBlocker.REQUIRED_COMPONENT_MISSING)
        blocked(scenario(required = listOf(primary, motion.copy(role = SyntheticComponentRole.PRIMARY))), SyntheticEligibilityBlocker.PRIMARY_REQUIRED)
    }
    @Test fun unsupportedComponentsCannotBecomeSupportedFromAnInventoryCompleteFlag() {
        blocked(scenario(required = listOf(primary, sidecar.copy(role = SyntheticComponentRole.UNSUPPORTED))), SyntheticEligibilityBlocker.UNSUPPORTED_COMPONENT)
    }
    @Test fun duplicateComponentNamesIncludingDifferentRevisionsOrRolesAreAmbiguous() {
        for (duplicate in listOf(motion, motion.copy(revision = 9), motion.copy(role = SyntheticComponentRole.SIDECAR))) {
            blocked(scenario(required = all + duplicate), SyntheticEligibilityBlocker.DUPLICATE_COMPONENT)
            blocked(scenario(required = all, available = all + duplicate), SyntheticEligibilityBlocker.DUPLICATE_COMPONENT)
        }
    }
    @Test fun componentRevisionAndRoleMustMatchTheRequiredDescription() {
        blocked(scenario(required = all, available = listOf(primary, motion.copy(revision = 7), sidecar)), SyntheticEligibilityBlocker.COMPONENT_REVISION_MISMATCH)
        blocked(scenario(required = all, available = listOf(primary, motion.copy(role = SyntheticComponentRole.SIDECAR), sidecar)), SyntheticEligibilityBlocker.COMPONENT_ROLE_MISMATCH)
    }
    @Test fun emptyAndMoreThanEightComponentsAreDeniedWhileEightAreModeled() {
        blocked(scenario(required = emptyList()), SyntheticEligibilityBlocker.COMPONENT_COUNT, SyntheticEligibilityBlocker.PRIMARY_REQUIRED)
        val eight = listOf(primary) + (20..26).map { sidecar.copy(component = it) }
        satisfied(scenario(required = eight))
        blocked(scenario(required = eight + sidecar.copy(component = 99)), SyntheticEligibilityBlocker.COMPONENT_COUNT)
    }
    @Test fun excessiveInputIsCopiedOnlyToABoundedDenialWitness() {
        val many = List(1000) { sidecar.copy(component = 100 + it) }
        val inventory = SyntheticSourceInventory(SyntheticInventoryStatus.COMPLETE, listOf(primary) + many, listOf(primary) + many)
        assertEquals(9, inventory.required.size); assertEquals(9, inventory.available.size)
        blocked(SyntheticSourceEligibilityScenario(listOf(photo), listOf(photo),
            SyntheticSourceEvidence(source, SyntheticFidelity.ORIGINAL_BYTES, SyntheticSourceContinuity.STABLE_IN_MODEL, inventory)), SyntheticEligibilityBlocker.COMPONENT_COUNT)
        val selection = SyntheticSourceEligibilityScenario(List(1000) { photo }, List(1000) { photo }, null)
        assertEquals(2, selection.requestedSelection.size); assertEquals(2, selection.currentSelection.size)
        blocked(selection, SyntheticEligibilityBlocker.SELECTION_COUNT)
    }
    @Test fun zeroMultipleAndDuplicateSelectionsCannotReturnPerPhotoSuccess() {
        for (requested in listOf(emptyList(), listOf(photo, photo), listOf(photo, photo.copy(identity = source.copy(source = 9)))))
            blocked(scenario(requested = requested), SyntheticEligibilityBlocker.SELECTION_COUNT)
        blocked(scenario(current = emptyList()), SyntheticEligibilityBlocker.SELECTION_COUNT)
        blocked(scenario(current = listOf(photo, photo)), SyntheticEligibilityBlocker.SELECTION_COUNT)
    }
    @Test fun videoAndUnknownMediaKindAreNotEligiblePhotoModels() {
        for (kind in listOf(SyntheticMediaKind.VIDEO, SyntheticMediaKind.UNKNOWN))
            blocked(scenario(requested = listOf(photo.copy(kind = kind))), SyntheticEligibilityBlocker.NOT_A_PHOTO)
    }
    @Test fun replacedCurrentSourceCannotReuseCapturedSelectionOrEvidence() {
        val replacements = listOf(source.copy(namespace = 8), source.copy(source = 8), source.copy(version = 8),
            source.copy(sourceRevision = 8), source.copy(componentSetRevision = 8))
        for (changed in replacements) blocked(scenario(current = listOf(photo.copy(identity = changed))),
            SyntheticEligibilityBlocker.SELECTION_SOURCE_MISMATCH, SyntheticEligibilityBlocker.SOURCE_EVIDENCE_MISMATCH)
    }
    @Test fun evidenceForADifferentSourceOrRevisionDoesNotMatchCurrentSelection() {
        for (changed in listOf(source.copy(namespace = 9), source.copy(source = 9), source.copy(version = 9), source.copy(sourceRevision = 9), source.copy(componentSetRevision = 9)))
            blocked(scenario(identity = changed), SyntheticEligibilityBlocker.SOURCE_EVIDENCE_MISMATCH)
    }
    @Test fun componentsFromAnotherSourceNamespaceVersionOrInventoryCannotBeMixedIn() {
        for (changed in listOf(source.copy(namespace = 9), source.copy(source = 9), source.copy(version = 9), source.copy(sourceRevision = 9), source.copy(componentSetRevision = 9)))
            blocked(scenario(required = all, available = listOf(primary, motion.copy(source = changed), sidecar)), SyntheticEligibilityBlocker.COMPONENT_SOURCE_MISMATCH)
    }
    @Test fun invalidSyntheticSourceAndComponentNumbersReturnStructuredBlockers() {
        for (number in listOf(Int.MIN_VALUE, -1, 0, 1_000_001, Int.MAX_VALUE)) {
            val invalid = source.copy(namespace = number)
            blocked(scenario(requested = listOf(photo.copy(identity = invalid)), identity = invalid,
                required = listOf(primary.copy(source = invalid))), SyntheticEligibilityBlocker.INVALID_SOURCE_IDENTITY)
            blocked(scenario(required = listOf(primary.copy(component = number))), SyntheticEligibilityBlocker.INVALID_COMPONENT_IDENTITY)
            blocked(scenario(required = listOf(primary.copy(revision = number))), SyntheticEligibilityBlocker.INVALID_COMPONENT_IDENTITY)
        }
    }
    @Test fun inputCollectionsAreOwnedBeforeAndAfterEvaluation() {
        val selected = mutableListOf(photo); val required = all.toMutableList(); val available = all.toMutableList()
        val value = scenario(requested = selected, current = selected, required = required, available = available)
        selected.clear(); required.clear(); available.clear()
        val first = satisfied(value)
        selected += photo.copy(kind = SyntheticMediaKind.VIDEO); required += primary.copy(revision = 8)
        available += motion.copy(source = source.copy(source = 99))
        assertTrue(first.syntheticModelRequirementsSatisfied); assertEquals(first.blockers, satisfied(value).blockers)
    }
    @Test fun exposedInputAndOutputCollectionsCannotBeMutatedByACaller() {
        val value = scenario(required = all)
        immutable { (value.requestedSelection as MutableList).clear() }
        immutable { (value.currentSelection as MutableList).clear() }
        immutable { (value.evidence!!.inventory.required as MutableList).clear() }
        immutable { (value.evidence!!.inventory.available as MutableList).clear() }
        val result = blocked(scenario(fidelity = SyntheticFidelity.UNKNOWN), SyntheticEligibilityBlocker.FIDELITY_UNKNOWN)
        immutable { (result.blockers as MutableList).clear() }
        assertFalse(result.syntheticModelRequirementsSatisfied); noAuthority(result)
    }
    @Test fun blockerOrderIsDeterministicUniqueAndDoesNotDependOnComponentOrder() {
        val required = all + motion
        val available = listOf(primary, sidecar.copy(revision = 9))
        val first = blocked(scenario(fidelity = SyntheticFidelity.UNKNOWN, status = SyntheticInventoryStatus.PARTIAL,
            required = required, available = available), SyntheticEligibilityBlocker.FIDELITY_UNKNOWN, SyntheticEligibilityBlocker.INVENTORY_PARTIAL,
            SyntheticEligibilityBlocker.DUPLICATE_COMPONENT, SyntheticEligibilityBlocker.REQUIRED_COMPONENT_MISSING)
        val second = evaluate(scenario(fidelity = SyntheticFidelity.UNKNOWN, status = SyntheticInventoryStatus.PARTIAL,
            required = required.reversed(), available = available.reversed()))
        assertEquals(first.blockers, second.blockers); assertEquals(first.blockers.distinct().sortedBy { it.ordinal }, first.blockers)
    }
    @Test fun copiedHypotheticalDescriptionsNeverBecomeProviderProofOrBackupCapability() {
        val copied = all.map { it.copy(source = it.source.copy()) }
        val positive = satisfied(scenario(identity = source.copy(), required = copied, available = copied.toList()))
        assertTrue(positive.toString().contains("syntheticModelRequirementsSatisfied=true"))
        assertTrue(positive.toString().contains("productionCapability=false"))
        noAuthority(evaluate(scenario(fidelity = SyntheticFidelity.READABLE_REPRESENTATION)))
        // Even a caller-made empty model classification has no capability or usable source.
        noAuthority(SyntheticSourceEligibilityDecision(emptyList()))
    }
    private fun immutable(action: () -> Unit) {
        try { action(); fail("Collection must be immutable") } catch (_: UnsupportedOperationException) { }
    }
}
