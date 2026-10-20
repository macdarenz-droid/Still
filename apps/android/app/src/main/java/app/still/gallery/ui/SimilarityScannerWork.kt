package app.still.gallery.ui

import app.still.gallery.media.CleanupSourceReader
import app.still.gallery.media.CleanupSourceSnapshot
import app.still.gallery.media.MediaItem
import app.still.gallery.media.cleanupUniqueSources
import app.still.gallery.similarity.SimilarityIndexStore
import app.still.gallery.similarity.searchSimilarityIndex
import app.still.gallery.similarity.similarityIndexIdentity
import app.still.gallery.similarity.SimilarityCoverage
import app.still.gallery.similarity.SimilarityException
import app.still.gallery.similarity.SimilarityFailure as AnalysisFailure
import app.still.gallery.similarity.SimilarityPair
import app.still.gallery.similarity.SimilarityResult
import app.still.gallery.similarity.SimilarityScanner
import app.still.gallery.similarity.SimilaritySettleResult
import app.still.gallery.similarity.similarityIndexNamespace
import app.still.gallery.similarity.similarityIndexNamespaceKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** No analysis bitmaps, source streams or prepared Compare files escape this boundary. */
class SimilarityScannerWork(
    private val reader: CleanupSourceReader,
    selected: List<MediaItem>,
    private val favorites: Set<String>,
    checkCurrent: () -> Unit,
    private val acceptSnapshot: suspend (CleanupSourceSnapshot) -> Unit,
    private val indexStore: SimilarityIndexStore? = null,
) : SimilarityWork<SimilarityResult> {
    private val scanner = SimilarityScanner(reader)
    private val selectedItems = selected.toList()
    private val indexClosed = AtomicBoolean()
    // Admission/token creation precedes coroutine dispatch, including never-started cancellation.
    private val ticket = try { scanner.begin(selected, checkCurrent) } catch (failure: SimilarityException) { throw failure.toWorkException() }

    override suspend fun scan(progress: suspend (SimilarityCoverageUi) -> Unit): SimilarityResult = try {
        var currentSnapshot: CleanupSourceSnapshot? = null
        val result = scanner.scan(ticket, favorites, onSnapshot = { fresh ->
            currentSnapshot = fresh
            withContext(Dispatchers.Main.immediate) { acceptSnapshot(fresh) }
        }, onProgress = { progress(it.toUi()) })
        val index = indexStore
        if (index == null) result.also { progress(it.coverage.toUi()) }
        else withContext(Dispatchers.IO) {
            val snapshot = requireNotNull(currentSnapshot)
            val current = cleanupUniqueSources(snapshot.items).first
            val incarnations = index.reconcileNamespaces(snapshot.namespaces.map(::similarityIndexNamespace), ticket::requireCurrent)
            val identities = current.filterNot { it.isVideo }.mapNotNull { item ->
                val incarnation = incarnations[similarityIndexNamespaceKey(item.providerAuthority, item.volumeName)] ?: return@mapNotNull null
                similarityIndexIdentity(item, incarnation)?.let { it.sourceKey to it.sourceRevision }
            }.toMap()
            index.retainCurrent(identities, ticket::requireCurrent)
            reader.validateScope(snapshot)
            val query = searchSimilarityIndex(result.analyzedSources, selectedItems, current, index, incarnations,
                checkCurrent = ticket::requireCurrent)
            reader.validateScope(snapshot)
            val resultWithIndex = scanner.finishWithIndex(result, query, current)
            ticket.requireCurrent()
            resultWithIndex
        }.also { progress(it.coverage.toUi()) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SimilarityException) { throw failure.toWorkException() }
    finally { closeIndexStore() }

    override fun isCurrent(result: SimilarityResult): Boolean = scanner.isCurrent(ticket)
    override fun resolve(result: SimilarityResult, pair: Any): List<MediaItem>? = (pair as? SimilarityPair)?.let { scanner.resolve(result, it) }
    override fun invalidate() { scanner.invalidate(ticket) }
    override suspend fun settle(): SimilaritySettlement {
        val settled = scanner.settle().toUi()
        closeIndexStore()
        return settled
    }

    private suspend fun closeIndexStore() = withContext(NonCancellable + Dispatchers.IO) {
        if (indexClosed.compareAndSet(false, true)) indexStore?.close()
    }
}

suspend fun recoverSimilarityReads(reader: CleanupSourceReader): SimilaritySettlement = SimilarityScanner(reader).settle().toUi()

private fun SimilarityException.toWorkException() = SimilarityWorkException(when (reason) {
    AnalysisFailure.INVALID_SELECTION -> SimilarityFailure.INVALID_SELECTION
    AnalysisFailure.BUSY -> SimilarityFailure.BUSY
    AnalysisFailure.STALE, AnalysisFailure.SOURCE_CHANGED -> SimilarityFailure.SOURCE_CHANGED
    AnalysisFailure.SCOPE_LIMIT -> SimilarityFailure.SCOPE_LIMIT
    AnalysisFailure.CLOSE_FAILED -> SimilarityFailure.CLOSE_FAILED
    AnalysisFailure.IO -> SimilarityFailure.UNAVAILABLE
})

private fun SimilaritySettleResult.toUi() = when (this) {
    SimilaritySettleResult.CLEAN -> SimilaritySettlement.CLEAN
    SimilaritySettleResult.BUSY -> SimilaritySettlement.BUSY
    SimilaritySettleResult.CLOSE_FAILED -> SimilaritySettlement.CLOSE_FAILED
}

private fun SimilarityCoverage.toUi() = SimilarityCoverageUi(selected, attempted, featured, empty, unreadable, oversized,
    unsupported, lowInformation, budgetOmitted, admittedBytes, quotaProbeBytes.toLong(), exactPairsOmitted, resultPairsOmitted, pairChecks)
