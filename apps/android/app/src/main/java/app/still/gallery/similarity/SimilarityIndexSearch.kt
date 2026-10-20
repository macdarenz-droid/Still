package app.still.gallery.similarity

import app.still.gallery.media.MediaItem
import app.still.gallery.media.sourceUiToken

data class SimilarityIndexQueryCoverage(
    val currentAuthorizedPhotos: Int,
    val indexedCurrentPhotos: Int,
    val indexedPhotosSearched: Int,
    val pairChecks: Int,
    val omittedWork: Boolean,
    val additionalPairsOmitted: Int,
)

data class SimilarityIndexQueryResult(val additionalPairs: List<SimilarityPair>, val coverage: SimilarityIndexQueryCoverage)

interface SimilarityFeatureIndex {
    fun revisions(): Map<String, String>
    fun candidates(feature: SimilarityFeature, maximum: Int): SimilarityIndexCandidates
    fun features(sourceRevisions: Map<String, String>, sourceKeys: List<String>): Map<String, SimilarityFeature>
}

interface SimilarityIndexRepository : SimilarityFeatureIndex {
    fun reconcileNamespaces(namespaces: List<SimilarityIndexNamespace>, checkCurrent: () -> Unit = {}): Map<String, String>
    fun retainCurrent(identities: Map<String, String>, checkCurrent: () -> Unit = {})
    fun upsertPage(entries: List<SimilarityIndexEntry>, checkCurrent: () -> Unit = {})
}

/** Search selected photos only against current indexed sources, with bounded direct pairs. */
fun searchSimilarityIndex(
    selected: List<SimilarityAnalyzedSource>,
    selectedItems: List<MediaItem>,
    currentItems: List<MediaItem>,
    store: SimilarityFeatureIndex,
    namespaceIncarnations: Map<String, String>,
    maximumPairChecks: Int = SimilarityIndexBounds.MAX_CANDIDATE_CHECKS,
    maximumResults: Int = SimilarityBounds.MAX_PAIRS,
    checkCurrent: () -> Unit = {},
): SimilarityIndexQueryResult {
    require(selected.size in 0..SimilarityBounds.MAX_SELECTED)
    require(selectedItems.size in 2..SimilarityBounds.MAX_SELECTED && selectedItems.none { it.isVideo })
    require(maximumPairChecks in 1..SimilarityIndexBounds.MAX_CANDIDATE_CHECKS)
    require(maximumResults in 1..SimilarityBounds.MAX_PAIRS)
    fun identity(item: MediaItem): SimilarityIndexIdentity? = namespaceIncarnations[
        similarityIndexNamespaceKey(item.providerAuthority, item.volumeName)
    ]?.let { similarityIndexIdentity(item, it) }
    val current = currentItems.filterNot { it.isVideo }.mapNotNull { item -> identity(item)?.let { it.sourceKey to (item to it.sourceRevision) } }
    require(current.map { it.first }.toSet().size == current.size) { "Current library repeats a similarity source" }
    val currentByKey = current.associate { it.first to it.second.first }
    val currentRevisions = current.associate { it.first to it.second.second }
    checkCurrent()
    val indexed = store.revisions().filter { (key, revision) -> currentRevisions[key] == revision }
    checkCurrent()
    val selectedKeys = selectedItems.mapNotNull { identity(it)?.sourceKey }.toSet()
    val pairs = mutableListOf<SimilarityPair>()
    val searchedPhotos = mutableSetOf<String>()
    var checks = 0
    var omitted = false

    query@ for (query in selected) {
        checkCurrent()
        if (checks == maximumPairChecks) { omitted = true; break }
        val candidateLimit = (maximumPairChecks - checks + selectedKeys.size).coerceAtMost(100_000).coerceAtLeast(1)
        val candidateKeys = store.candidates(query.feature, candidateLimit)
        if (candidateKeys.omitted) omitted = true
        val usable = candidateKeys.sourceKeys.filter { key -> key in indexed && key !in selectedKeys }
        val queryKey = identity(query.item)?.sourceKey ?: continue
        for (chunk in usable.chunked(128)) {
            val features = store.features(currentRevisions, chunk)
            searchedPhotos += features.keys
            for ((key, feature) in features.toSortedMap()) {
                if (checks % 128 == 0) checkCurrent()
                val item = currentByKey[key] ?: continue
                if (key == queryKey) continue
                if (checks == maximumPairChecks) { omitted = true; break@query }
                checks++
                val distance = similarityDistance(query.feature, feature) ?: continue
                pairs += SimilarityPair(query.item, item, distance)
            }
        }
    }

    val order = compareBy<SimilarityPair> { it.evidence.pHashDistance }
        .thenBy { it.evidence.spatialDistance }.thenBy { it.evidence.colorDistance }.thenBy { it.evidence.aspectDistance }
        .thenBy { minOf(sourceUiToken(it.first), sourceUiToken(it.second)) }
        .thenBy { maxOf(sourceUiToken(it.first), sourceUiToken(it.second)) }
    val ordered = pairs.distinctBy { listOf(sourceUiToken(it.first), sourceUiToken(it.second)).sorted() }.sortedWith(order)
    checkCurrent()
    return SimilarityIndexQueryResult(ordered.take(maximumResults), SimilarityIndexQueryCoverage(
        currentAuthorizedPhotos = currentItems.count { !it.isVideo },
        indexedCurrentPhotos = indexed.size,
        indexedPhotosSearched = searchedPhotos.size,
        pairChecks = checks,
        omittedWork = omitted,
        additionalPairsOmitted = (ordered.size - maximumResults).coerceAtLeast(0),
    ))
}
