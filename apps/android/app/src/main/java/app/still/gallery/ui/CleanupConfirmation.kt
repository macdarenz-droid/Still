package app.still.gallery.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One visible confirmation, with reference identity and a single-use decision. */
class CleanupProtectionRequest internal constructor(val groupId: Int, val protect: Boolean)

class CleanupConfirmationState(private val interactionIdentity: Any?) {
    private var active = true
    var current: CleanupProtectionRequest? by mutableStateOf(null)
        private set

    fun open(groupId: Int, protect: Boolean) {
        if (active && interactionIdentity != null) current = CleanupProtectionRequest(groupId, protect)
    }

    fun dismiss(request: CleanupProtectionRequest) {
        if (current === request) current = null
    }

    fun confirm(request: CleanupProtectionRequest, liveInteractionIdentity: Any?, onConfirmed: () -> Unit): Boolean {
        if (!active || current !== request || interactionIdentity == null || interactionIdentity !== liveInteractionIdentity) return false
        current = null // Consume before the side effect, including reentrant callbacks.
        onConfirmed()
        return true
    }

    fun invalidate() { active = false; current = null }
}
