package app.still.gallery.media

/** Permission loss is a visibility change, never evidence that an original was deleted. */
enum class AccessLevel { NONE, PARTIAL, FULL }

data class MediaAccess(
    val sdk: Int,
    val legacy: Boolean = false,
    val images: Boolean = false,
    val videos: Boolean = false,
    val selected: Boolean = false,
) {
    val canReadImages: Boolean get() = if (sdk < 33) legacy else images || (sdk >= 34 && selected)
    val canReadVideos: Boolean get() = if (sdk < 33) legacy else videos || (sdk >= 34 && selected)
    val level: AccessLevel get() = when {
        sdk < 33 && legacy -> AccessLevel.FULL
        sdk >= 33 && images && videos -> AccessLevel.FULL
        canReadImages || canReadVideos -> AccessLevel.PARTIAL
        else -> AccessLevel.NONE
    }
}

data class MediaItem(
    // Device-local SourceObject handle only. Never use this URI as a canonical cloud Asset ID.
    val id: String,
    val name: String,
    val mimeType: String,
    val isVideo: Boolean,
    val capturedAt: Long,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val durationMs: Long,
    val albumId: String,
    val albumName: String,
    val orientationDegrees: Int = 0,
    val modifiedAt: Long = 0,
)

fun shareMimeType(items: List<MediaItem>): String = when {
    items.all { !it.isVideo } -> "image/*"
    items.all { it.isVideo } -> "video/*"
    else -> "*/*"
}

fun visibleSelection(selected: Set<String>, accessible: List<MediaItem>): Set<String> =
    selected.intersect(accessible.mapTo(mutableSetOf()) { it.id })

/** Local preview cache key only, never a content hash or canonical immutable AssetVersion ID. */
fun previewFingerprint(item: MediaItem, edge: Int): String =
    "${item.id}:${item.modifiedAt}:${item.orientationDegrees}:${item.width}x${item.height}:${item.bytes}:$edge"

/** Serializes invalidation and a late thumbnail completion across worker/UI threads. */
class AccessEpoch {
    private var token = 0L
    @Synchronized fun snapshot(): Long = token
    @Synchronized fun invalidate(clear: () -> Unit) { token++; clear() }
    @Synchronized fun commitIfCurrent(expected: Long, store: () -> Unit): Boolean {
        if (expected != token) return false
        store()
        return true
    }
}
