package app.still.gallery.media

import java.util.concurrent.CancellationException

/** Independent annotation requests cannot republish an older READY over a newer failure. */
class CleanupRequestFence(private val authorized: () -> Boolean) {
    class Request internal constructor()
    private var current: Request? = null
    @Synchronized fun begin(): Request {
        if (!authorized()) throw CancellationException("Gallery access ended")
        return Request().also { current = it }
    }
    @Synchronized fun invalidate() { current = null }
    @Synchronized fun requireCurrent(request: Request) {
        if (current !== request || !authorized()) throw CancellationException("Annotation request ended")
    }
    @Synchronized fun publish(request: Request, action: () -> Unit): Boolean {
        if (current !== request || !authorized()) return false
        action()
        return true
    }
}

/** A fresh scan cannot open members through an older/different gallery mapping. */
fun cleanupSnapshotMatchesGallery(fresh: List<MediaItem>, gallery: List<MediaItem>): Boolean = try {
    cleanupUniqueSources(fresh).first.associate { cleanupSourceReference(it) to sourceUiToken(it) } ==
        cleanupUniqueSources(gallery).first.associate { cleanupSourceReference(it) to sourceUiToken(it) }
} catch (_: CleanupSourceChangedException) { false }
