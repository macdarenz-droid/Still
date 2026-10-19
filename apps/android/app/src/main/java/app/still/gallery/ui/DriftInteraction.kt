package app.still.gallery.ui

import app.still.gallery.media.MediaItem
import app.still.gallery.media.reconcileMediaTokens
import app.still.gallery.media.DriftNavigationSession
import app.still.gallery.media.DriftSnapshot
import app.still.gallery.media.selectedMediaInView

/** UI intent only: it neither retains rows nor grants access while a read is pending. */
class DriftFocusMemory {
    var token: String? = null
        private set

    fun capture(candidate: String?) { if (candidate != null) token = candidate }
    fun reconcile(current: List<MediaItem>) {
        token = token?.let { reconcileMediaTokens(setOf(it), current).singleOrNull() }
    }
    fun settle(candidate: String?, loading: Boolean) { if (!loading) token = candidate }
}

class DriftFocusEntry internal constructor(val sourceToken: String)

/** Memory-only UI intent fence. A navigation projection never grants source access. */
class DriftInteraction(private val authorized: () -> Boolean) {
    private var projection: Any? = null
    var currentFocus: DriftFocusEntry? = null
        private set
    val focusedToken: String? get() = currentFocus?.sourceToken
    var railStart: Int? = null
        private set

    fun replace(current: Any, focus: String?) {
        projection = current
        currentFocus = focus?.let(::DriftFocusEntry)
        railStart = null
    }

    fun invalidate() { projection = null; currentFocus = null; railStart = null }

    fun isCurrent(expected: Any, focus: DriftFocusEntry?): Boolean =
        authorized() && projection === expected && focus != null && currentFocus === focus

    /** A queued gesture must not override a newer explicit navigation choice. */
    fun focus(expected: Any, previous: DriftFocusEntry?, nextToken: String): Boolean {
        if (!isCurrent(expected, previous)) return false
        currentFocus = DriftFocusEntry(nextToken)
        railStart = null
        return true
    }

    fun page(expected: Any, previous: DriftFocusEntry?, start: Int): Boolean {
        if (start < 0 || !isCurrent(expected, previous)) return false
        currentFocus = DriftFocusEntry(previous!!.sourceToken)
        railStart = start
        return true
    }
}

/** A real Grid/Ribbon open also becomes the current browsing-position intent. */
fun driftRememberOpenedSource(navigation: DriftNavigationSession, snapshot: DriftSnapshot,
    interaction: DriftInteraction, memory: DriftFocusMemory, token: String): Boolean {
    val target = navigation.target(snapshot, token) ?: return false
    if (!interaction.focus(snapshot, interaction.currentFocus, target.sourceUiToken)) return false
    memory.capture(target.sourceUiToken)
    return true
}

/** A retained broad-view batch button cannot consume a newer narrow-view selection. */
fun driftBatchItems(navigation: DriftNavigationSession, snapshot: DriftSnapshot,
    selected: Set<String>, visible: List<MediaItem>): List<MediaItem> =
    if (navigation.isCurrent(snapshot)) selectedMediaInView(selected, visible) else emptyList()

/** Only this bounded window is composed or requests previews, even in a large library. */
fun driftCardIndices(itemCount: Int, focusedIndex: Int): IntRange =
    if (focusedIndex !in 0 until itemCount) IntRange.EMPTY
    else maxOf(0, focusedIndex - 2)..minOf(itemCount - 1, focusedIndex + 2)

/** Every opening is a distinct interaction, even reopening the same observed source. */
class DriftViewerEntry internal constructor(val sourceToken: String)

class DriftViewerSession(private val authorized: () -> Boolean) {
    var current: DriftViewerEntry? = null
        private set

    fun open(sourceToken: String): DriftViewerEntry? {
        if (!authorized()) return null
        return DriftViewerEntry(sourceToken).also { current = it }
    }

    fun isCurrent(expected: DriftViewerEntry): Boolean = authorized() && current === expected

    fun close(expected: DriftViewerEntry): Boolean {
        if (!isCurrent(expected)) return false
        current = null
        return true
    }

    fun invalidate() { current = null }
}

class DriftDateRequest internal constructor(internal val focus: Any)

/** A cancelled or replaced picker may neither navigate nor dismiss another opening. */
class DriftDatePicker(private val currentFocus: () -> Any?) {
    var current: DriftDateRequest? = null
        private set

    fun open(focus: Any): DriftDateRequest? {
        if (currentFocus() !== focus) return null
        return DriftDateRequest(focus).also { current = it }
    }

    fun isCurrent(request: DriftDateRequest): Boolean = current === request && currentFocus() === request.focus

    fun dismiss(request: DriftDateRequest): Boolean {
        if (!isCurrent(request)) return false
        current = null
        return true
    }

    fun confirm(request: DriftDateRequest, jump: () -> Boolean): Boolean {
        if (!isCurrent(request)) return false
        val accepted = jump()
        if (accepted && current === request) current = null
        return accepted
    }

    fun invalidate() { current = null }
}

class DriftOverlayRequest internal constructor()

/** Options edits stay in one opening; cancellation and reopening fence all older callbacks. */
class DriftOverlaySession(private val authorized: () -> Boolean) {
    private var current: DriftOverlayRequest? = null

    fun open(): DriftOverlayRequest? = if (authorized()) DriftOverlayRequest().also { current = it } else null
    fun isCurrent(request: DriftOverlayRequest): Boolean = authorized() && current === request
    fun edit(request: DriftOverlayRequest, change: () -> Unit): Boolean {
        if (!isCurrent(request)) return false
        change()
        return true
    }
    fun close(request: DriftOverlayRequest): Boolean {
        if (!isCurrent(request)) return false
        current = null
        return true
    }
    fun invalidate() { current = null }
}
