package app.still.gallery.similarity

import app.still.gallery.media.MediaItem
import app.still.gallery.catalogue.CatalogueNamespace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

const val SIMILARITY_INDEX_SCHEMA_VERSION = 1
const val SIMILARITY_INDEX_MAX_ROWS = 50_000
const val SIMILARITY_INDEX_APPLICATION_ID = 0x5354494c

/** In-memory only identity/revision input. The store receives only their digests. */
data class SimilarityIndexIdentity(
    val sourceKey: String,
    val sourceRevision: String,
    val namespaceKey: String,
    val namespaceIncarnation: String,
)

/** Hashed provider scope retained only long enough to reconcile this app-local index. */
data class SimilarityIndexNamespace(
    val namespaceKey: String,
    val providerVersionDigest: String,
    val providerGeneration: Long?,
)

data class SimilarityIndexNamespaceState(
    val providerVersionDigest: String,
    val providerGeneration: Long?,
    val incarnation: String,
)

data class SimilarityIndexNamespaceTransition(val state: SimilarityIndexNamespaceState, val rotated: Boolean)

fun similarityIndexNamespaceTransition(
    prior: SimilarityIndexNamespaceState?,
    current: SimilarityIndexNamespace,
    newIncarnation: () -> String,
): SimilarityIndexNamespaceTransition {
    val reset = prior != null && prior.providerGeneration != null && current.providerGeneration != null &&
        current.providerGeneration < prior.providerGeneration
    val rotate = prior == null || prior.providerVersionDigest != current.providerVersionDigest || reset
    val generation = when {
        reset -> current.providerGeneration
        current.providerGeneration != null -> maxOf(prior?.providerGeneration ?: 0L, current.providerGeneration)
        else -> prior?.providerGeneration
    }
    val incarnation = if (rotate) newIncarnation() else requireNotNull(prior).incarnation
    return SimilarityIndexNamespaceTransition(
        SimilarityIndexNamespaceState(current.providerVersionDigest, generation, incarnation), rotate,
    )
}

fun similarityIndexSchemaCanInitialize(databaseVersion: Int, userObjectCount: Int, applicationId: Int = 0): Boolean {
    require(databaseVersion >= 0 && userObjectCount >= 0 && applicationId >= 0)
    return databaseVersion != 0 || userObjectCount == 0 && applicationId == 0
}

fun similarityIndexNamespace(namespace: CatalogueNamespace): SimilarityIndexNamespace {
    require(namespace.providerAuthority.isNotBlank() && namespace.volumeName.isNotBlank())
    require(namespace.providerGeneration == null || namespace.providerGeneration >= 0)
    return SimilarityIndexNamespace(
        namespaceKey = similarityIndexNamespaceKey(namespace.providerAuthority, namespace.volumeName),
        providerVersionDigest = similarityDigest(listOf("still-similarity-provider-version-v1",
            if (namespace.providerVersion == null) "missing" else "present", namespace.providerVersion.orEmpty())),
        providerGeneration = namespace.providerGeneration,
    )
}

fun similarityIndexNamespaceKey(providerAuthority: String, volumeName: String): String {
    require(providerAuthority.isNotBlank() && volumeName.isNotBlank())
    return similarityDigest(listOf("still-similarity-namespace-v1", providerAuthority, volumeName))
}

fun similarityIndexIdentity(item: MediaItem, namespaceIncarnation: String): SimilarityIndexIdentity? {
    if (item.isVideo || item.id.isBlank() || item.providerAuthority.isBlank() || item.volumeName.isBlank() ||
        namespaceIncarnation.length != 64 || namespaceIncarnation.any { it !in '0'..'9' && it !in 'a'..'f' } ||
        item.providerVersion?.isBlank() == true || item.externalHandle.isBlank() || item.dateAddedSeconds < 0 ||
        item.generationAdded?.let { it < 0 } == true || item.generationModified?.let { it < 0 } == true ||
        item.generationAdded == null && item.dateAddedSeconds == 0L ||
        item.modifiedAt < 0 || item.bytes < 0 || item.width < 0 || item.height < 0 || item.orientationDegrees !in listOf(0, 90, 180, 270)) return null

    val birth = item.generationAdded?.let { "generation:$it" } ?: "date-added:${item.dateAddedSeconds}"
    val namespaceKey = similarityIndexNamespaceKey(item.providerAuthority, item.volumeName)
    val sourceKey = similarityDigest(listOf("still-similarity-source-v1", item.providerAuthority, item.volumeName,
        if (item.providerVersion == null) "provider-version-missing" else "provider-version-present", item.providerVersion.orEmpty(),
        namespaceIncarnation, "photo", item.externalHandle, birth))
    // MediaStore per-row generation is the strong revision signal. Older providers use
    // a deliberately best-effort metadata revision; every result remains manual review.
    val revision = similarityDigest(listOf("still-similarity-revision-v1", item.generationModified?.toString() ?: "legacy",
        item.modifiedAt.toString(), item.bytes.toString(), item.width.toString(), item.height.toString(),
        item.orientationDegrees.toString(), item.mimeType.lowercase(Locale.ROOT)))
    return SimilarityIndexIdentity(sourceKey, revision, namespaceKey, namespaceIncarnation)
}

private fun similarityDigest(fields: List<String>): String {
    val framed = fields.joinToString("") { "${it.length}:$it" }
    val bytes = MessageDigest.getInstance("SHA-256").digest(framed.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }
}

/** Fixed 136-byte representation: pHash, IEEE-754 aspect ratio, then 60 normalized UInt16 values. */
object SimilarityFeatureCodec {
    const val ENCODED_BYTES = 136

    fun encode(feature: SimilarityFeature): ByteArray {
        require(feature.algorithmVersion == SimilarityBounds.PIPELINE_VERSION)
        val spatial = feature.spatialValues()
        val histogram = feature.histogramValues()
        require(spatial.size == 48 && histogram.size == 12)
        return ByteBuffer.allocate(ENCODED_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(feature.perceptualHash)
            putLong(java.lang.Double.doubleToRawLongBits(feature.aspectRatio))
            (spatial.asList() + histogram.asList()).forEach { value ->
                require(value.isFinite() && value in 0.0..1.0)
                putShort((value * 65_535.0).roundToInt().toShort())
            }
        }.array()
    }

    fun decode(bytes: ByteArray): SimilarityFeature? {
        if (bytes.size != ENCODED_BYTES) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val hash = buffer.long
            val aspect = java.lang.Double.longBitsToDouble(buffer.long)
            val values = DoubleArray(60) { (buffer.short.toInt() and 0xffff) / 65_535.0 }
            if (!aspect.isFinite() || aspect <= 0.0 || buffer.hasRemaining()) return null
            SimilarityFeature(hash, aspect, values.copyOfRange(0, 48), values.copyOfRange(48, 60))
        }.getOrNull()
    }
}
