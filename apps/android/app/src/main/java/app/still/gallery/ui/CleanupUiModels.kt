package app.still.gallery.ui

/** Display-only cleanup state. Media handles, digests and scan tokens stay in the owner. */
enum class CleanupPhase { IDLE, SCANNING, STOPPING, COMPLETE, CANCELLED, INVALIDATED, BUSY, FAILED }
enum class CleanupProtectionState { LOADING, READY, UNKNOWN, SAVING }
enum class CleanupFavoriteState { FAVORITE, NOT_FAVORITE, UNKNOWN }
enum class CleanupReviewReason { READY, NEEDS_KEEPER, FAVORITE_UNKNOWN, PROTECTION_UNKNOWN }

data class CleanupCoverageUi(
    val scopeKnown: Boolean = false,
    val considered: Int = 0,
    val candidates: Int = 0,
    val checked: Int = 0,
    val compared: Int = 0,
    val empty: Int = 0,
    val unavailable: Int = 0,
    val changed: Int = 0,
    val omitted: Int = 0,
    val aliases: Int = 0,
)

data class CleanupMemberUi(
    val id: Int,
    val name: String,
    val isVideo: Boolean,
    val byteCount: Long,
    val manualKeeper: Boolean,
    val favorite: CleanupFavoriteState,
    val protected: Boolean,
)

data class CleanupGroupUi(
    val id: Int,
    val members: List<CleanupMemberUi>,
    val protected: Boolean,
    val reviewReason: CleanupReviewReason,
    val extras: Int,
)

data class CleanupUiState(
    val phase: CleanupPhase = CleanupPhase.IDLE,
    val coverage: CleanupCoverageUi = CleanupCoverageUi(),
    val limitedAccess: Boolean = false,
    val hasAccess: Boolean = true,
    val canStart: Boolean = false,
    val groups: List<CleanupGroupUi> = emptyList(),
    val protectionState: CleanupProtectionState = CleanupProtectionState.LOADING,
    val protectionWriteFailed: Boolean = false,
    val sourceLimitExceeded: Boolean = false,
    // Actual memory-only review object. Equal-looking rows cannot reuse an old decision.
    val interactionIdentity: Any? = null,
)
