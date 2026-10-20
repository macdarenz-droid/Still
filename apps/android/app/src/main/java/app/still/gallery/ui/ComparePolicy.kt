package app.still.gallery.ui

import kotlin.math.abs
import app.still.gallery.media.MediaItem
import app.still.gallery.media.selectedMediaInView
import app.still.gallery.media.sourceUiToken

/** A retained Compare action cannot consume a different/newly selected pair in the same view. */
fun compareSelectedPhotos(captured: List<MediaItem>, selected: Set<String>, view: List<MediaItem>): List<MediaItem> {
    if (captured.size != 2 || captured.any { it.isVideo } || captured.map { it.id }.toSet().size != 2) return emptyList()
    val expected = captured.map(::sourceUiToken)
    if (expected.toSet().size != 2 || selectedMediaInView(selected, view).map(::sourceUiToken).toSet() != expected.toSet()) return emptyList()
    return expected.map { token -> view.singleOrNull { sourceUiToken(it) == token } ?: return emptyList() }
}

/** Bounded decoded previews only. These limits say nothing about source fidelity. */
object ComparePreviewBounds {
    const val MAX_EDGE = 1536
    const val MAX_PIXELS = 4_000_000L
    const val MAX_ALLOCATION_BYTES = MAX_PIXELS * 4
}

fun compareSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0) { "Unsupported preview dimensions" }
    var sample = 1
    fun sampled(dimension: Int) = (dimension.toLong() + sample - 1) / sample
    while (sampled(width) > ComparePreviewBounds.MAX_EDGE || sampled(height) > ComparePreviewBounds.MAX_EDGE ||
        sampled(width) * sampled(height) > ComparePreviewBounds.MAX_PIXELS
    ) {
        check(sample <= Int.MAX_VALUE / 2) { "Unsupported preview dimensions" }
        sample *= 2
    }
    return sample
}

fun comparePreviewFits(width: Int, height: Int, allocationBytes: Long): Boolean =
    width in 1..ComparePreviewBounds.MAX_EDGE && height in 1..ComparePreviewBounds.MAX_EDGE &&
        width.toLong() * height <= ComparePreviewBounds.MAX_PIXELS &&
        allocationBytes in 1..ComparePreviewBounds.MAX_ALLOCATION_BYTES

/** Linear transform about the image center, from the STAGED file's EXIF only. */
data class CompareOrientation(val xx: Int, val xy: Int, val yx: Int, val yy: Int) {
    val swapsAxes: Boolean get() = abs(xy) == 1
}

fun compareOrientation(exif: Int): CompareOrientation = when (exif) {
    1 -> CompareOrientation(1, 0, 0, 1)
    2 -> CompareOrientation(-1, 0, 0, 1)
    3 -> CompareOrientation(-1, 0, 0, -1)
    4 -> CompareOrientation(1, 0, 0, -1)
    5 -> CompareOrientation(0, 1, 1, 0)
    6 -> CompareOrientation(0, -1, 1, 0)
    7 -> CompareOrientation(0, -1, -1, 0)
    8 -> CompareOrientation(0, 1, -1, 0)
    else -> throw IllegalArgumentException("Unsupported preview orientation")
}

/** Pan is a fraction of each pane, so both panes follow the same bounded view. */
data class CompareTransform(val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun change(zoomFactor: Float = 1f, dx: Float = 0f, dy: Float = 0f): CompareTransform {
        if (!zoomFactor.isFinite() || zoomFactor <= 0f || !dx.isFinite() || !dy.isFinite()) return this
        val nextZoom = (zoom * zoomFactor).coerceIn(1f, 5f)
        if (nextZoom == 1f) return CompareTransform()
        val bound = (nextZoom - 1f) / 2f
        return CompareTransform(nextZoom, (x + dx).coerceIn(-bound, bound), (y + dy).coerceIn(-bound, bound))
    }
}

/** Per-opening identity: equal-looking pairs must never revive an older callback. */
class CompareRequest internal constructor()

class CompareRequestFence(private val allowed: () -> Boolean) {
    private var active: CompareRequest? = null
    fun open(): CompareRequest? {
        if (!allowed()) { invalidate(); return null }
        return CompareRequest().also { active = it }
    }
    fun isCurrent(request: CompareRequest?): Boolean {
        if (!allowed()) { invalidate(); return false }
        return request != null && active === request
    }
    fun invalidate() { active = null }
}
