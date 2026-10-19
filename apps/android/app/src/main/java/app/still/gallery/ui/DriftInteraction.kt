package app.still.gallery.ui

/** Memory-only UI intent fence. A navigation projection never grants source access. */
class DriftInteraction(private val authorized: () -> Boolean) {
    private var projection: Any? = null
    var focusedToken: String? = null
        private set

    fun replace(current: Any, focus: String?) {
        projection = current
        focusedToken = focus
    }

    fun invalidate() { projection = null; focusedToken = null }

    fun isCurrent(expected: Any, sourceToken: String?): Boolean =
        authorized() && projection === expected && focusedToken == sourceToken

    /** A queued gesture must not override a newer explicit navigation choice. */
    fun focus(expected: Any, previousToken: String?, nextToken: String): Boolean {
        if (!isCurrent(expected, previousToken)) return false
        focusedToken = nextToken
        return true
    }
}

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
