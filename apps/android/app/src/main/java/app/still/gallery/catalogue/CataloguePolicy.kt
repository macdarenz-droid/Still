package app.still.gallery.catalogue

import java.util.concurrent.atomic.AtomicLong
import java.net.URI

/** Provider observations only: neither immutable content versions nor proof of bytes/access. */
data class CatalogueObservation(
    val providerAuthority: String,
    val volumeName: String,
    val providerVersion: String?,
    val providerGeneration: Long?,
    val externalHandle: String,
    val generationAdded: Long?,
    val generationModified: Long?,
    val dateAddedSeconds: Long,
    val sourceUri: String,
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
    val orientationDegrees: Int,
    val modifiedAt: Long,
)

data class CatalogueReconcileResult(val created: Int, val updated: Int, val unchanged: Int) {
    /** Count of this fresh snapshot, never a claim about retained rows or complete library coverage. */
    val observedCount: Int get() = created + updated + unchanged
}

/** Include successfully queried volumes even when their current snapshot contains no media. */
data class CatalogueNamespace(
    val providerAuthority: String,
    val volumeName: String,
    val providerVersion: String?,
    val providerGeneration: Long?,
)

/** Instance-bound, memory-only token. Create BEFORE reading the provider. Never restore or serialize. */
class CatalogueScan internal constructor(internal val owner: Any, internal val sequence: Long)
class StaleCatalogueScanException : IllegalStateException("This catalogue scan is no longer current")

/** Invalidation never waits for database IO. The caller must also fence its UI publication. */
class CatalogueScanFence {
    private val owner = Any()
    private val sequence = AtomicLong()
    fun beginScan(): CatalogueScan = CatalogueScan(owner, advance())
    fun invalidate() { advance() }
    fun requireCurrent(scan: CatalogueScan) {
        if (scan.owner !== owner || scan.sequence != sequence.get()) throw StaleCatalogueScanException()
    }
    private fun advance(): Long = sequence.updateAndGet { current ->
        check(current != Long.MAX_VALUE) { "Catalogue scan generations are exhausted" }
        current + 1
    }
}

internal data class NamespaceState(val incarnationId: String, val version: String?, val generation: Long?)
internal data class ExistingObservation(val sourceId: String, val observationId: String, val revision: Long, val fingerprint: String)
internal data class ObservationChange(val sourceId: String, val observationId: String, val revision: Long, val changed: Boolean)

internal object CataloguePolicy {
    fun validate(observation: CatalogueObservation) {
        require(observation.providerAuthority.isNotBlank() && observation.providerAuthority.length <= 256)
        require(observation.volumeName.isNotBlank() && observation.volumeName.length <= 256)
        require(observation.externalHandle.isNotBlank() && observation.externalHandle.length <= 1024)
        require(observation.sourceUri.startsWith("content://") && observation.sourceUri.length <= 4096)
        val source = URI(observation.sourceUri)
        require(source.authority == observation.providerAuthority && !source.path.isNullOrBlank())
        require(observation.providerVersion == null || observation.providerVersion.length <= 1024)
        require(observation.providerGeneration == null || observation.providerGeneration >= 0)
        require(observation.generationAdded == null || observation.generationAdded >= 0)
        require(observation.generationModified == null || observation.generationModified >= 0)
        require(observation.dateAddedSeconds >= 0 && observation.capturedAt >= 0 && observation.modifiedAt >= 0)
        require(observation.width >= 0 && observation.height >= 0 && observation.bytes >= 0 && observation.durationMs >= 0)
        require(observation.orientationDegrees in listOf(0, 90, 180, 270))
        require(listOf(observation.name, observation.mimeType, observation.albumId, observation.albumName).all { it.length <= 16_384 })
    }

    fun namespace(observation: CatalogueObservation) = CatalogueNamespace(
        observation.providerAuthority, observation.volumeName, observation.providerVersion, observation.providerGeneration,
    )

    fun reconcileNamespace(prior: NamespaceState?, next: CatalogueNamespace, newId: () -> String): NamespaceState {
        val reset = prior == null || prior.version != next.providerVersion ||
            (prior.generation != null && next.providerGeneration != null && next.providerGeneration < prior.generation)
        return if (reset) NamespaceState(newId(), next.providerVersion, next.providerGeneration)
        else requireNotNull(prior).copy(generation = next.providerGeneration ?: prior.generation)
    }

    /** Provider row birth is best effort without generationAdded (e.g. same-second reuse on old Android). */
    fun birthMarker(observation: CatalogueObservation): String = observation.generationAdded?.let { "generation:$it" }
        ?: "date-added:${observation.dateAddedSeconds}"

    /** Length framing avoids separator collisions. This describes metadata, never media bytes. */
    fun metadataFingerprint(observation: CatalogueObservation): String = listOf(
        observation.sourceUri, observation.name, observation.mimeType, observation.isVideo,
        observation.capturedAt, observation.width, observation.height, observation.bytes,
        observation.durationMs, observation.albumId, observation.albumName, observation.orientationDegrees,
        observation.modifiedAt, observation.dateAddedSeconds, observation.generationAdded, observation.generationModified,
    ).joinToString("") { field ->
        if (field == null) "-1:" else field.toString().let { "${it.length}:$it" }
    }

    fun reconcileObservation(prior: ExistingObservation?, fingerprint: String, newId: () -> String): ObservationChange {
        if (prior == null) return ObservationChange(newId(), newId(), 1, true)
        if (prior.fingerprint == fingerprint) return ObservationChange(prior.sourceId, prior.observationId, prior.revision, false)
        check(prior.revision < Long.MAX_VALUE) { "Catalogue observation revision is exhausted" }
        return ObservationChange(prior.sourceId, newId(), prior.revision + 1, true)
    }
}
