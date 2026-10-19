package app.still.gallery.media

import java.util.Locale

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
    val providerAuthority: String = "media",
    val volumeName: String = "external",
    val providerVersion: String? = null,
    val providerGeneration: Long? = null,
    val externalHandle: String = "",
    val generationAdded: Long? = null,
    val generationModified: Long? = null,
    val dateAddedSeconds: Long = 0,
)

enum class MediaFilter { ALL, PHOTOS, VIDEOS }
enum class MediaSort { NEWEST, OLDEST, NAME }

/** Stable, global filename order; unknown dates follow known dates in either date order. */
fun libraryOrder(items: List<MediaItem>, filter: MediaFilter, sort: MediaSort): List<MediaItem> {
    val names = compareBy<MediaItem> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name }.thenBy { it.id }
    val dates = compareBy<MediaItem> { it.capturedAt <= 0 }
        .thenComparator { first, second ->
            if (sort == MediaSort.NEWEST) second.capturedAt.coerceAtLeast(0).compareTo(first.capturedAt.coerceAtLeast(0))
            else first.capturedAt.coerceAtLeast(0).compareTo(second.capturedAt.coerceAtLeast(0))
        }.then(names)
    return items.filter {
        when (filter) { MediaFilter.ALL -> true; MediaFilter.PHOTOS -> !it.isVideo; MediaFilter.VIDEOS -> it.isVideo }
    }.sortedWith(if (sort == MediaSort.NAME) names else dates)
}

/** Bucket IDs are only unique within their provider/volume namespace. */
fun sourceAlbumId(authority: String, volume: String, bucket: String): String =
    listOf(authority, volume, bucket).joinToString("|") { "${it.length}:$it" }

/** Versions changing or generation rollback during enumeration require a fresh read. */
fun providerReadRemainedCurrent(beforeVersion: String?, beforeGeneration: Long?, afterVersion: String?, afterGeneration: Long?): Boolean =
    beforeVersion == afterVersion && when {
        beforeGeneration == null -> afterGeneration == null
        afterGeneration == null -> false
        else -> afterGeneration >= beforeGeneration
    }

/** A local observed-metadata UI key, never a content hash or immutable asset version. */
fun sourceUiIdentity(item: MediaItem): String = listOf(
    item.id, item.providerAuthority, item.volumeName, item.providerVersion,
    item.externalHandle, item.generationAdded, item.generationModified, item.dateAddedSeconds,
    item.name, item.mimeType, item.isVideo, item.capturedAt, item.modifiedAt,
    item.orientationDegrees, item.width, item.height, item.bytes, item.durationMs,
).joinToString("|") { value -> value?.toString()?.let { "${it.length}:$it" } ?: "-:" }

/** Include the observed provider watermark so a rollback cannot retain a pending UI action. */
fun sourceUiToken(item: MediaItem): String = "${item.providerGeneration ?: "?"}#${sourceUiIdentity(item)}"

/**
 * Retain only unchanged, strongly evidenced rows, advancing their provider watermark.
 * Legacy/unknown continuity is intentionally cleared on refresh. Visibility still comes
 * only from current; these tokens never authorize provider access or identify cloud jobs.
 */
fun reconcileMediaTokens(tokens: Set<String>, current: List<MediaItem>): Set<String> {
    if (tokens.isEmpty()) return emptySet()
    val previous = tokens.mapNotNull { token ->
        val delimiter = token.indexOf('#')
        if (delimiter < 1) null else token.substring(0, delimiter).toLongOrNull()?.let { watermark ->
            token.substring(delimiter + 1) to watermark
        }
    }.toMap()
    return current.mapNotNullTo(mutableSetOf()) { item ->
        val generation = item.providerGeneration
        val oldGeneration = previous[sourceUiIdentity(item)]
        if (!item.providerVersion.isNullOrBlank() && item.generationAdded != null && item.generationModified != null &&
            generation != null && oldGeneration != null && generation >= oldGeneration
        ) sourceUiToken(item) else null
    }
}

/** Hidden items never join batch sharing or favorites when the view scope changes. */
fun selectedMediaInView(tokens: Set<String>, visible: List<MediaItem>): List<MediaItem> =
    if (tokens.isEmpty()) emptyList() else visible.filter { sourceUiToken(it) in tokens }

fun shareMimeType(items: List<MediaItem>): String = when {
    items.all { !it.isVideo } -> "image/*"
    items.all { it.isVideo } -> "video/*"
    else -> "*/*"
}

fun visibleSelection(selected: Set<String>, accessible: List<MediaItem>): Set<String> =
    selected.intersect(accessible.mapTo(mutableSetOf()) { it.id })

/** Local preview cache key only, never a content hash or canonical immutable AssetVersion ID. */
fun previewFingerprint(item: MediaItem, edge: Int): String =
    "${sourceUiIdentity(item)}:$edge"

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
