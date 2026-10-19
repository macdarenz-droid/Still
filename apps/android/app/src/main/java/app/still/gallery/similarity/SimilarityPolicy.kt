package app.still.gallery.similarity

import app.still.gallery.media.MediaItem
import app.still.gallery.media.cleanupSourceReference
import app.still.gallery.media.sourceUiToken
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

object SimilarityBounds {
    const val MAX_SELECTED = 200
    const val MAX_INPUT_BYTES = 16 * 1024 * 1024
    const val MAX_RUN_BYTES = 256L * 1024 * 1024
    const val BUFFER_BYTES = 64 * 1024
    const val MAX_ENCODED_BUFFERS = 32 * 1024 * 1024
    const val MAX_EDGE = 256
    const val MIN_SOURCE_EDGE = 64
    const val MIN_DECODED_EDGE = 32
    const val MAX_PIXELS = 1_000_000L
    const val MAX_ALLOCATION_BYTES = 4L * 1024 * 1024
    const val MAX_PAIRS = 50
    const val PIPELINE_VERSION = "phash32-dct8-rgb4-hist4-v1"
    const val CALIBRATION_VERSION = "synthetic-scenes-v1"
    const val MAX_HASH_DISTANCE = 10
    const val MAX_COLOR_DISTANCE = 0.32
    const val MAX_SPATIAL_DISTANCE = 0.12
    const val MAX_ASPECT_DISTANCE = 0.12
}

enum class SimilarityFailure { INVALID_SELECTION, BUSY, STALE, SOURCE_CHANGED, SCOPE_LIMIT, CLOSE_FAILED, IO }
class SimilarityException(val reason: SimilarityFailure) : IOException("Photo appearance analysis: ${reason.name}")
enum class SimilaritySettleResult { CLEAN, BUSY, CLOSE_FAILED }
enum class SimilarityPixelSkip { INVALID, TINY, TRANSPARENT, LOW_INFORMATION, UNSUPPORTED }
sealed class SimilarityPixelResult {
    class Accepted(val feature: SimilarityFeature) : SimilarityPixelResult()
    class Rejected(val reason: SimilarityPixelSkip) : SimilarityPixelResult()
}

/** One source-derived sampled image, never an original/version or interchangeable-copy proof. */
class SimilarityFeature internal constructor(
    val perceptualHash: Long,
    val aspectRatio: Double,
    spatial: DoubleArray,
    histogram: DoubleArray,
) {
    val algorithmVersion = SimilarityBounds.PIPELINE_VERSION
    private val spatial = spatial.copyOf()
    private val histogram = histogram.copyOf()
    init {
        require(aspectRatio.isFinite() && aspectRatio > 0 && spatial.size == 48 && histogram.size == 12)
        require(spatial.all { it.isFinite() && it in 0.0..1.0 } && histogram.all { it.isFinite() && it in 0.0..1.0 })
    }
    internal fun distances(other: SimilarityFeature) = SimilarityDistance(
        java.lang.Long.bitCount(perceptualHash xor other.perceptualHash),
        histogram.indices.sumOf { abs(histogram[it] - other.histogram[it]) } / 3,
        spatial.indices.sumOf { abs(spatial[it] - other.spatial[it]) } / spatial.size,
        abs(ln(aspectRatio / other.aspectRatio)),
    )
}

data class SimilarityDistance(val pHashDistance: Int, val colorDistance: Double, val spatialDistance: Double, val aspectDistance: Double) {
    val algorithmVersion = SimilarityBounds.PIPELINE_VERSION
}

/** Distances are retrieval heuristics, never confidence, quality or importance scores. */
fun similarityDistance(first: SimilarityFeature, second: SimilarityFeature): SimilarityDistance? {
    val distance = first.distances(second)
    if (distance.pHashDistance > SimilarityBounds.MAX_HASH_DISTANCE || distance.colorDistance > SimilarityBounds.MAX_COLOR_DISTANCE ||
        distance.spatialDistance > SimilarityBounds.MAX_SPATIAL_DISTANCE || distance.aspectDistance > SimilarityBounds.MAX_ASPECT_DISTANCE ||
        !distance.colorDistance.isFinite() || !distance.spatialDistance.isFinite() || !distance.aspectDistance.isFinite()) return null
    return distance
}

private val similarityCosines = Array(8) { frequency -> DoubleArray(32) { x -> cos((2 * x + 1) * frequency * Math.PI / 64) } }

/** Production pixel path. Orientation maps samples without allocating a second bitmap. */
fun similarityFeature(width: Int, height: Int, argb: IntArray, exifOrientation: Int = 1): SimilarityPixelResult {
    fun reject(reason: SimilarityPixelSkip) = SimilarityPixelResult.Rejected(reason)
    if (width !in 1..SimilarityBounds.MAX_EDGE || height !in 1..SimilarityBounds.MAX_EDGE ||
        width.toLong() * height != argb.size.toLong() || exifOrientation !in 1..8) return reject(SimilarityPixelSkip.INVALID)
    if (width < 32 || height < 32) return reject(SimilarityPixelSkip.TINY)
    if (argb.any { (it ushr 24) != 255 }) return reject(SimilarityPixelSkip.TRANSPARENT)
    val swaps = exifOrientation >= 5
    val orientedWidth = if (swaps) height else width
    val orientedHeight = if (swaps) width else height
    fun pixel(x: Int, y: Int): Int {
        val (sx, sy) = when (exifOrientation) {
            1 -> x to y
            2 -> width - 1 - x to y
            3 -> width - 1 - x to height - 1 - y
            4 -> x to height - 1 - y
            5 -> y to x
            6 -> y to height - 1 - x
            7 -> width - 1 - y to height - 1 - x
            else -> width - 1 - y to x
        }
        return argb[sy * width + sx]
    }
    val luminance = DoubleArray(1024)
    val spatial = DoubleArray(48)
    val histogram = DoubleArray(12)
    var mean = 0.0
    for (gy in 0 until 32) for (gx in 0 until 32) {
        // Area averages avoid point-sampling aliases on high-frequency screenshots/patterns.
        val x0 = gx * orientedWidth / 32
        val x1 = maxOf(x0 + 1, (gx + 1) * orientedWidth / 32).coerceAtMost(orientedWidth)
        val y0 = gy * orientedHeight / 32
        val y1 = maxOf(y0 + 1, (gy + 1) * orientedHeight / 32).coerceAtMost(orientedHeight)
        var red = 0.0; var green = 0.0; var blue = 0.0; var count = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val value = pixel(x, y)
            red += (value ushr 16) and 255; green += (value ushr 8) and 255; blue += value and 255; count++
        }
        red /= count; green /= count; blue /= count
        val value = 0.299 * red + 0.587 * green + 0.114 * blue
        luminance[gy * 32 + gx] = value; mean += value
        val cell = ((gy / 8) * 4 + gx / 8) * 3
        spatial[cell] += red / (255 * 64); spatial[cell + 1] += green / (255 * 64); spatial[cell + 2] += blue / (255 * 64)
        histogram[(red.toInt() / 64).coerceAtMost(3)] += 1.0 / 1024
        histogram[4 + (green.toInt() / 64).coerceAtMost(3)] += 1.0 / 1024
        histogram[8 + (blue.toInt() / 64).coerceAtMost(3)] += 1.0 / 1024
    }
    mean /= 1024
    val deviation = sqrt(luminance.sumOf { (it - mean) * (it - mean) } / 1024)
    if (deviation < 8.0) return reject(SimilarityPixelSkip.LOW_INFORMATION)
    val dct = DoubleArray(64)
    for (v in 0 until 8) for (u in 0 until 8) {
        var coefficient = 0.0
        for (y in 0 until 32) for (x in 0 until 32) coefficient += (luminance[y * 32 + x] - mean) * similarityCosines[u][x] * similarityCosines[v][y]
        dct[v * 8 + u] = coefficient
    }
    if (dct.drop(1).sumOf(::abs) < 1.0) return reject(SimilarityPixelSkip.LOW_INFORMATION)
    val median = dct.drop(1).sorted()[31]
    var hash = 0L
    // The DC slot is fixed; 63 AC comparisons are stored in the 64-bit descriptor.
    for (index in 1 until 64) if (dct[index] > median) hash = hash or (1L shl index)
    return SimilarityPixelResult.Accepted(SimilarityFeature(hash, orientedWidth.toDouble() / orientedHeight, spatial, histogram))
}

/** Conservative recognition of supported static containers; no frame-zero animation analysis. */
fun similarityContainerSupported(encoded: ByteArray, length: Int): Boolean {
    if (length !in 1..encoded.size) return false
    if (length >= 2 && encoded[0] == 0xff.toByte() && encoded[1] == 0xd8.toByte()) {
        for (marker in listOf("MotionPhoto", "MicroVideo")) {
            for (start in 0..length - marker.length) {
                if (encoded[start] != marker[0].code.toByte()) continue
                if (marker.indices.all { encoded[start + it] == marker[it].code.toByte() }) return false
            }
        }
        return true
    }
    val signature = intArrayOf(137, 80, 78, 71, 13, 10, 26, 10)
    if (length < 8 || signature.indices.any { (encoded[it].toInt() and 255) != signature[it] }) return false
    var offset = 8
    while (offset.toLong() + 12 <= length) {
        val size = (0..3).fold(0L) { value, i -> (value shl 8) or (encoded[offset + i].toLong() and 255) }
        if (size > length.toLong() - offset - 12) return false
        if (encoded[offset + 4] == 'a'.code.toByte() && encoded[offset + 5] == 'c'.code.toByte() &&
            encoded[offset + 6] == 'T'.code.toByte() && encoded[offset + 7] == 'L'.code.toByte()) return false
        offset += (size + 12).toInt()
    }
    return offset == length
}

fun similaritySampleSize(width: Int, height: Int): Int {
    require(width >= SimilarityBounds.MIN_SOURCE_EDGE && height >= SimilarityBounds.MIN_SOURCE_EDGE)
    var sample = 1
    while ((width.toLong() + sample - 1) / sample > SimilarityBounds.MAX_EDGE ||
        (height.toLong() + sample - 1) / sample > SimilarityBounds.MAX_EDGE) {
        require(sample <= Int.MAX_VALUE / 2)
        sample *= 2
    }
    require((width.toLong() + sample - 1) / sample >= SimilarityBounds.MIN_DECODED_EDGE &&
        (height.toLong() + sample - 1) / sample >= SimilarityBounds.MIN_DECODED_EDGE)
    return sample
}
fun similarityBitmapFits(width: Int, height: Int, allocationBytes: Long): Boolean =
    width in SimilarityBounds.MIN_DECODED_EDGE..SimilarityBounds.MAX_EDGE && height in SimilarityBounds.MIN_DECODED_EDGE..SimilarityBounds.MAX_EDGE &&
        width.toLong() * height <= SimilarityBounds.MAX_PIXELS && allocationBytes in 1..SimilarityBounds.MAX_ALLOCATION_BYTES

data class SimilarityCoverage(
    val selected: Int,
    val attempted: Int = 0,
    val featured: Int = 0,
    val empty: Int = 0,
    val unreadable: Int = 0,
    val oversized: Int = 0,
    val unsupported: Int = 0,
    val lowInformation: Int = 0,
    val budgetOmitted: Int = 0,
    /** Actual bytes admitted into the bounded capture, including later rejected reads. */
    val admittedBytes: Long = 0,
    /** Consumed over-limit bytes, at most one per opened representation, never hidden in admittedBytes. */
    val quotaProbeBytes: Int = 0,
    val exactPairsOmitted: Int = 0,
    val resultPairsOmitted: Int = 0,
    val pairChecks: Int = 0,
)

class SimilarityTicket internal constructor(internal val owner: Any, selected: List<MediaItem>, private val check: () -> Unit) {
    internal val selected = Collections.unmodifiableList(selected.toList())
    private val active = AtomicBoolean(true)
    internal fun invalidate() { active.set(false) }
    internal fun requireCurrent() {
        if (!active.get()) throw SimilarityException(SimilarityFailure.STALE)
        try { check() } catch (_: Exception) { active.set(false); throw SimilarityException(SimilarityFailure.STALE) }
        if (!active.get()) throw SimilarityException(SimilarityFailure.STALE)
    }
}
class SimilarityAnalyzedSource(val item: MediaItem, val feature: SimilarityFeature, val sha256: String, val byteCount: Long)
class SimilarityPair internal constructor(val first: MediaItem, val second: MediaItem, val evidence: SimilarityDistance) { val reviewOnly = true }
class SimilarityResult internal constructor(internal val ticket: SimilarityTicket, pairs: List<SimilarityPair>, val coverage: SimilarityCoverage) {
    val pairs = Collections.unmodifiableList(pairs.toList())
    val reviewOnly = true
}

/** Memory-only review identities; valid navigation never grants source access or another capability. */
class SimilaritySession {
    private val identity = Any()
    private var ticket: SimilarityTicket? = null
    private var result: SimilarityResult? = null
    @Synchronized fun begin(selected: List<MediaItem>, checkCurrent: () -> Unit): SimilarityTicket {
        if (selected.size !in 2..SimilarityBounds.MAX_SELECTED || selected.any { it.isVideo } ||
            selected.map { it.id }.toSet().size != selected.size || selected.map(::sourceUiToken).toSet().size != selected.size ||
            selected.map(::cleanupSourceReference).toSet().size != selected.size) throw SimilarityException(SimilarityFailure.INVALID_SELECTION)
        invalidate()
        return SimilarityTicket(identity, selected, checkCurrent).also { it.requireCurrent(); ticket = it }
    }
    @Synchronized fun invalidate() { ticket?.invalidate(); ticket = null; result = null }
    @Synchronized fun invalidate(expected: SimilarityTicket) { if (ticket === expected) invalidate() }
    @Synchronized fun isCurrent(expected: SimilarityTicket): Boolean = try {
        if (ticket !== expected || expected.owner !== identity) false else { expected.requireCurrent(); true }
    } catch (_: SimilarityException) { false }
    @Synchronized fun finish(expected: SimilarityTicket, sources: List<SimilarityAnalyzedSource>, coverage: SimilarityCoverage): SimilarityResult {
        if (!isCurrent(expected) || result != null) throw SimilarityException(SimilarityFailure.STALE)
        val selected = expected.selected.associateBy(::sourceUiToken)
        require(sources.size <= selected.size && sources.map { sourceUiToken(it.item) }.toSet().size == sources.size)
        require(sources.all { selected[sourceUiToken(it.item)] == it.item && it.byteCount in 1..SimilarityBounds.MAX_INPUT_BYTES && it.sha256.matches(Regex("[0-9a-f]{64}")) })
        require(coverage.selected == selected.size && coverage.featured == sources.size && coverage.attempted in 0..selected.size)
        require(listOf(coverage.empty, coverage.unreadable, coverage.oversized, coverage.unsupported, coverage.lowInformation, coverage.budgetOmitted).all { it in 0..selected.size })
        require(coverage.featured + coverage.empty + coverage.unreadable + coverage.oversized + coverage.unsupported + coverage.lowInformation + coverage.budgetOmitted == selected.size)
        require(coverage.admittedBytes in 0..SimilarityBounds.MAX_RUN_BYTES && coverage.quotaProbeBytes in 0..coverage.attempted && coverage.admittedBytes + coverage.quotaProbeBytes <= SimilarityBounds.MAX_RUN_BYTES)
        val candidates = mutableListOf<SimilarityPair>()
        var exact = 0; var checks = 0
        for (i in sources.indices) for (j in i + 1 until sources.size) {
            checks++
            val first = sources[i]; val second = sources[j]
            if (first.sha256 == second.sha256 && first.byteCount == second.byteCount) { exact++; continue }
            val distance = similarityDistance(first.feature, second.feature) ?: continue
            candidates += SimilarityPair(first.item, second.item, distance)
        }
        val ordered = candidates.sortedWith(compareBy<SimilarityPair> { it.evidence.pHashDistance }
            .thenBy { it.evidence.spatialDistance }.thenBy { it.evidence.colorDistance }.thenBy { it.evidence.aspectDistance }
            .thenBy { sourceUiToken(it.first) }.thenBy { sourceUiToken(it.second) })
        expected.requireCurrent()
        return SimilarityResult(expected, ordered.take(SimilarityBounds.MAX_PAIRS), coverage.copy(exactPairsOmitted = exact,
            resultPairsOmitted = (ordered.size - SimilarityBounds.MAX_PAIRS).coerceAtLeast(0), pairChecks = checks)).also { result = it }
    }
    @Synchronized fun resolve(expected: SimilarityResult, pair: SimilarityPair): List<MediaItem>? =
        if (result !== expected || !isCurrent(expected.ticket) || expected.pairs.none { it === pair }) null else listOf(pair.first, pair.second)
}
