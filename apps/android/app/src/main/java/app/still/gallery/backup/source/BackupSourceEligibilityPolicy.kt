package app.still.gallery.backup.source

import java.util.Collections
import java.util.EnumSet

private const val MAX_SYNTHETIC_COMPONENTS = 8
private const val MAX_SYNTHETIC_ID = 1_000_000
private fun <T> ownBounded(values: List<T>, limit: Int): List<T> = Collections.unmodifiableList(values.take(limit))

/** Hypothetical numeric fixture names only; no canonical identity or provider attestation. */
data class SyntheticSourceIdentity(val namespace: Int, val source: Int, val version: Int,
    val sourceRevision: Int, val componentSetRevision: Int)
enum class SyntheticMediaKind { PHOTO, VIDEO, UNKNOWN }
data class SyntheticSelectedSource(val identity: SyntheticSourceIdentity, val kind: SyntheticMediaKind)
enum class SyntheticFidelity { ORIGINAL_BYTES, READABLE_REPRESENTATION, UNKNOWN, UNSUPPORTED }
enum class SyntheticSourceContinuity { STABLE_IN_MODEL, OBSERVED_METADATA_ONLY, UNKNOWN, CHANGED }
enum class SyntheticInventoryStatus { COMPLETE, UNKNOWN, PARTIAL, UNSUPPORTED }
enum class SyntheticComponentRole { PRIMARY, MOTION, SIDECAR, UNSUPPORTED }
data class SyntheticSourceComponent(val source: SyntheticSourceIdentity, val component: Int,
    val revision: Int, val role: SyntheticComponentRole)

/** All facts are supplied hypothetically, including the claim that an inventory is complete. */
class SyntheticSourceInventory(status: SyntheticInventoryStatus,
    required: List<SyntheticSourceComponent>, available: List<SyntheticSourceComponent>) {
    val status: SyntheticInventoryStatus = status
    // The ninth entry preserves denial of excess input without retaining an unbounded list.
    val required: List<SyntheticSourceComponent> = ownBounded(required, MAX_SYNTHETIC_COMPONENTS + 1)
    val available: List<SyntheticSourceComponent> = ownBounded(available, MAX_SYNTHETIC_COMPONENTS + 1)
}
class SyntheticSourceEvidence(val identity: SyntheticSourceIdentity, val fidelity: SyntheticFidelity,
    val continuity: SyntheticSourceContinuity, val inventory: SyntheticSourceInventory)

/** No URI, path, content, hash, platform object or real provider evidence is accepted. */
class SyntheticSourceEligibilityScenario(requestedSelection: List<SyntheticSelectedSource>,
    currentSelection: List<SyntheticSelectedSource>, val evidence: SyntheticSourceEvidence?,
    val originalRequestMade: Boolean = false) {
    // Retaining two entries is sufficient to deny any selection with more than one member.
    val requestedSelection: List<SyntheticSelectedSource> = ownBounded(requestedSelection, 2)
    val currentSelection: List<SyntheticSelectedSource> = ownBounded(currentSelection, 2)
}

enum class SyntheticEligibilityBlocker {
    SELECTION_COUNT, NOT_A_PHOTO, INVALID_SOURCE_IDENTITY, SELECTION_SOURCE_MISMATCH,
    SOURCE_EVIDENCE_MISSING, SOURCE_EVIDENCE_MISMATCH,
    READABLE_REPRESENTATION_ONLY, FIDELITY_UNKNOWN, FIDELITY_UNSUPPORTED, ORIGINAL_REQUEST_IS_NOT_PROOF,
    SOURCE_STABILITY_UNPROVEN, SOURCE_CHANGED,
    INVENTORY_UNKNOWN, INVENTORY_PARTIAL, INVENTORY_UNSUPPORTED,
    COMPONENT_COUNT, PRIMARY_REQUIRED, UNSUPPORTED_COMPONENT, COMPONENT_SOURCE_MISMATCH,
    INVALID_COMPONENT_IDENTITY, DUPLICATE_COMPONENT, REQUIRED_COMPONENT_MISSING,
    UNDECLARED_COMPONENT, COMPONENT_REVISION_MISMATCH, COMPONENT_ROLE_MISMATCH,
}

/** A detached whole-scenario classification, never an eligibility grant or usable handle. */
class SyntheticSourceEligibilityDecision internal constructor(blockers: Collection<SyntheticEligibilityBlocker>) {
    val scope = "synthetic_source_eligibility_model_v1"
    val blockers: List<SyntheticEligibilityBlocker> = Collections.unmodifiableList(blockers.distinct().sortedBy { it.ordinal })
    val syntheticModelRequirementsSatisfied: Boolean get() = blockers.isEmpty()
    val productionCapability = false
    val backupEligible = false
    override fun toString() = "SyntheticSourceEligibilityDecision(syntheticModelRequirementsSatisfied=$syntheticModelRequirementsSatisfied, productionCapability=false, backupEligible=false, blockers=$blockers)"
}

/** Pure host decision model only. Satisfied hypothetical facts do not become device/provider proof. */
object BackupSourceEligibilityPolicy {
    fun evaluateSynthetic(scenario: SyntheticSourceEligibilityScenario): SyntheticSourceEligibilityDecision {
        val blockers = EnumSet.noneOf(SyntheticEligibilityBlocker::class.java)
        val requested = scenario.requestedSelection.singleOrNull()
        val current = scenario.currentSelection.singleOrNull()
        if (requested == null || current == null) blockers += SyntheticEligibilityBlocker.SELECTION_COUNT
        for (selected in scenario.requestedSelection + scenario.currentSelection) {
            if (selected.kind != SyntheticMediaKind.PHOTO) blockers += SyntheticEligibilityBlocker.NOT_A_PHOTO
            if (!valid(selected.identity)) blockers += SyntheticEligibilityBlocker.INVALID_SOURCE_IDENTITY
        }
        val exactSelection = requested != null && requested == current
        if (requested != null && current != null && !exactSelection) blockers += SyntheticEligibilityBlocker.SELECTION_SOURCE_MISMATCH
        val evidence = scenario.evidence
        if (evidence == null) {
            blockers += SyntheticEligibilityBlocker.SOURCE_EVIDENCE_MISSING
            return SyntheticSourceEligibilityDecision(blockers)
        }
        if (!valid(evidence.identity)) blockers += SyntheticEligibilityBlocker.INVALID_SOURCE_IDENTITY
        if (!exactSelection || evidence.identity != requested.identity)
            blockers += SyntheticEligibilityBlocker.SOURCE_EVIDENCE_MISMATCH
        when (evidence.fidelity) {
            SyntheticFidelity.ORIGINAL_BYTES -> Unit
            SyntheticFidelity.READABLE_REPRESENTATION -> blockers += SyntheticEligibilityBlocker.READABLE_REPRESENTATION_ONLY
            SyntheticFidelity.UNKNOWN -> blockers += SyntheticEligibilityBlocker.FIDELITY_UNKNOWN
            SyntheticFidelity.UNSUPPORTED -> blockers += SyntheticEligibilityBlocker.FIDELITY_UNSUPPORTED
        }
        if (scenario.originalRequestMade && evidence.fidelity != SyntheticFidelity.ORIGINAL_BYTES)
            blockers += SyntheticEligibilityBlocker.ORIGINAL_REQUEST_IS_NOT_PROOF
        when (evidence.continuity) {
            SyntheticSourceContinuity.STABLE_IN_MODEL -> Unit
            SyntheticSourceContinuity.OBSERVED_METADATA_ONLY, SyntheticSourceContinuity.UNKNOWN -> blockers += SyntheticEligibilityBlocker.SOURCE_STABILITY_UNPROVEN
            SyntheticSourceContinuity.CHANGED -> blockers += SyntheticEligibilityBlocker.SOURCE_CHANGED
        }
        val inventory = evidence.inventory
        when (inventory.status) {
            SyntheticInventoryStatus.COMPLETE -> Unit
            SyntheticInventoryStatus.UNKNOWN -> blockers += SyntheticEligibilityBlocker.INVENTORY_UNKNOWN
            SyntheticInventoryStatus.PARTIAL -> blockers += SyntheticEligibilityBlocker.INVENTORY_PARTIAL
            SyntheticInventoryStatus.UNSUPPORTED -> blockers += SyntheticEligibilityBlocker.INVENTORY_UNSUPPORTED
        }
        for (components in listOf(inventory.required, inventory.available)) {
            if (components.size !in 1..MAX_SYNTHETIC_COMPONENTS) blockers += SyntheticEligibilityBlocker.COMPONENT_COUNT
            if (components.count { it.role == SyntheticComponentRole.PRIMARY } != 1) blockers += SyntheticEligibilityBlocker.PRIMARY_REQUIRED
            if (components.map { it.component }.toSet().size != components.size) blockers += SyntheticEligibilityBlocker.DUPLICATE_COMPONENT
            for (component in components) {
                if (component.source != evidence.identity) blockers += SyntheticEligibilityBlocker.COMPONENT_SOURCE_MISMATCH
                if (!valid(component.source)) blockers += SyntheticEligibilityBlocker.INVALID_SOURCE_IDENTITY
                if (component.component !in 1..MAX_SYNTHETIC_ID || component.revision !in 1..MAX_SYNTHETIC_ID)
                    blockers += SyntheticEligibilityBlocker.INVALID_COMPONENT_IDENTITY
                if (component.role == SyntheticComponentRole.UNSUPPORTED) blockers += SyntheticEligibilityBlocker.UNSUPPORTED_COMPONENT
            }
        }
        val requiredIds = inventory.required.map { it.component }.toSet()
        val availableIds = inventory.available.map { it.component }.toSet()
        if (!availableIds.containsAll(requiredIds)) blockers += SyntheticEligibilityBlocker.REQUIRED_COMPONENT_MISSING
        if (!requiredIds.containsAll(availableIds)) blockers += SyntheticEligibilityBlocker.UNDECLARED_COMPONENT
        // Never choose a member from an ambiguous duplicate component ID.
        val uniqueAvailable = inventory.available.groupBy { it.component }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        for (required in inventory.required) {
            val available = uniqueAvailable[required.component] ?: continue
            if (required.revision != available.revision) blockers += SyntheticEligibilityBlocker.COMPONENT_REVISION_MISMATCH
            if (required.role != available.role) blockers += SyntheticEligibilityBlocker.COMPONENT_ROLE_MISMATCH
        }
        return SyntheticSourceEligibilityDecision(blockers)
    }

    private fun valid(source: SyntheticSourceIdentity) = listOf(source.namespace, source.source, source.version,
        source.sourceRevision, source.componentSetRevision).all { it in 1..MAX_SYNTHETIC_ID }
}
