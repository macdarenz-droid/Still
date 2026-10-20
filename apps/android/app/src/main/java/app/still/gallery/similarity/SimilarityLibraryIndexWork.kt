package app.still.gallery.similarity

import app.still.gallery.media.CleanupSourceReader
import app.still.gallery.media.CleanupSourceSnapshot
import app.still.gallery.media.CleanupScopeLimitException
import app.still.gallery.media.CleanupSourceChangedException
import app.still.gallery.media.cleanupUniqueSources
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class SimilarityIndexProgress(
    val currentPhotos: Int = 0,
    val indexablePhotos: Int = 0,
    val alreadyIndexed: Int = 0,
    val indexed: Int = 0,
    val processed: Int = 0,
    val attempted: Int = 0,
    val analyzed: Int = 0,
    val empty: Int = 0,
    val unreadable: Int = 0,
    val oversized: Int = 0,
    val unsupported: Int = 0,
    val lowInformation: Int = 0,
    val bytesRead: Long = 0,
    val unattempted: Int = 0,
    val complete: Boolean = false,
)

/** One foreground user action. Each complete source page is committed independently. */
class SimilarityLibraryIndexWork(
    private val reader: CleanupSourceReader,
    private val store: SimilarityIndexRepository,
    private val favorites: Set<String>,
    private val checkCurrent: () -> Unit,
    decoder: SimilarityFeatureDecoder = AndroidSimilarityFeatures(),
) {
    private val scanner = SimilarityScanner(reader, decoder)
    private var activeTicket: SimilarityTicket? = null
    private var activeJob: Job? = null

    fun invalidate() { activeTicket?.let(scanner::invalidate) }

    private fun checkActionCurrent() {
        activeJob?.ensureActive()
        checkCurrent()
    }

    suspend fun run(
        onSnapshot: suspend (CleanupSourceSnapshot) -> Unit,
        onProgress: suspend (SimilarityIndexProgress) -> Unit,
    ): SimilarityIndexProgress = withContext(Dispatchers.IO) {
        activeJob = currentCoroutineContext()[Job]
        try {
        checkActionCurrent()
        reader.checkAccess()
        val snapshot = reader.snapshot(favorites.toSet())
        if (snapshot.items.size > SimilarityIndexBounds.MAX_INDEX_SOURCES) throw SimilarityException(SimilarityFailure.SCOPE_LIMIT)
        checkActionCurrent(); reader.validateScope(snapshot)
        val (unique, _) = cleanupUniqueSources(snapshot.items)
        val photos = unique.filterNot { it.isVideo }
        val incarnations = store.reconcileNamespaces(snapshot.namespaces.map(::similarityIndexNamespace), ::checkActionCurrent)
        val identityRows = photos.mapNotNull { item ->
            val incarnation = incarnations[similarityIndexNamespaceKey(item.providerAuthority, item.volumeName)] ?: return@mapNotNull null
            similarityIndexIdentity(item, incarnation)?.let { it.sourceKey to it.sourceRevision }
        }
        if (identityRows.map { it.first }.toSet().size != identityRows.size) throw SimilarityException(SimilarityFailure.SOURCE_CHANGED)
        val identities = identityRows.toMap()
        store.retainCurrent(identities, ::checkActionCurrent)
        checkActionCurrent()
        onSnapshot(snapshot)
        checkActionCurrent()

        val before = store.revisions()
        val pending = photos.filter { item ->
            val incarnation = incarnations[similarityIndexNamespaceKey(item.providerAuthority, item.volumeName)] ?: return@filter false
            val identity = similarityIndexIdentity(item, incarnation) ?: return@filter false
            before[identity.sourceKey] != identity.sourceRevision
        }
        val already = identities.count { (key, revision) -> before[key] == revision }
        var progress = SimilarityIndexProgress(
            currentPhotos = photos.size,
            indexablePhotos = identities.size,
            alreadyIndexed = already,
            indexed = already,
            unattempted = pending.size,
        )
        onProgress(progress)
        var cursor = 0
        while (cursor < pending.size && progress.bytesRead < SimilarityBounds.MAX_RUN_BYTES) {
            checkActionCurrent(); reader.checkAccess()
            val page = pending.subList(cursor, minOf(cursor + SimilarityBounds.MAX_SELECTED, pending.size)).toList()
            val ticket = try { scanner.beginIndexPage(page, ::checkActionCurrent) }
                catch (failure: SimilarityException) { throw failure }
            activeTicket = ticket
            var latest = SimilarityCoverage(page.size)
            val remaining = SimilarityBounds.MAX_RUN_BYTES - progress.bytesRead
            val result = scanner.scan(ticket, favorites, onSnapshot = { observed ->
                if (observed !== snapshot) throw SimilarityException(SimilarityFailure.SOURCE_CHANGED)
            }, onProgress = { coverage ->
                latest = coverage
                val classified = coverage.featured + coverage.empty + coverage.unreadable + coverage.oversized +
                    coverage.unsupported + coverage.lowInformation
                onProgress(progress.copy(
                    processed = progress.processed + classified,
                    attempted = progress.attempted + coverage.attempted,
                    analyzed = progress.analyzed + coverage.featured,
                    empty = progress.empty + coverage.empty,
                    unreadable = progress.unreadable + coverage.unreadable,
                    oversized = progress.oversized + coverage.oversized,
                    unsupported = progress.unsupported + coverage.unsupported,
                    lowInformation = progress.lowInformation + coverage.lowInformation,
                    bytesRead = progress.bytesRead + coverage.admittedBytes + coverage.quotaProbeBytes,
                    unattempted = (pending.size - cursor - classified).coerceAtLeast(0),
                ))
            }, maxRunBytes = remaining, providedSnapshot = snapshot)
            checkActionCurrent(); reader.validateScope(snapshot)
            val entries = result.analyzedSources.mapNotNull { analyzed ->
                val incarnation = incarnations[similarityIndexNamespaceKey(analyzed.item.providerAuthority, analyzed.item.volumeName)] ?: return@mapNotNull null
                val identity = similarityIndexIdentity(analyzed.item, incarnation) ?: return@mapNotNull null
                SimilarityIndexEntry(identity, analyzed.feature)
            }
            if (entries.isNotEmpty()) store.upsertPage(entries, ::checkActionCurrent)
            checkActionCurrent()
            val fullyClassified = page.size - latest.budgetOmitted
            cursor += fullyClassified
            activeTicket = null
            val nowIndexed = store.revisions().count { (key, revision) -> identities[key] == revision }
            progress = progress.copy(
                indexed = nowIndexed,
                processed = progress.processed + fullyClassified,
                attempted = progress.attempted + latest.attempted,
                analyzed = progress.analyzed + latest.featured,
                empty = progress.empty + latest.empty,
                unreadable = progress.unreadable + latest.unreadable,
                oversized = progress.oversized + latest.oversized,
                unsupported = progress.unsupported + latest.unsupported,
                lowInformation = progress.lowInformation + latest.lowInformation,
                bytesRead = progress.bytesRead + latest.admittedBytes + latest.quotaProbeBytes,
                unattempted = (pending.size - cursor).coerceAtLeast(0),
            )
            onProgress(progress)
            if (latest.budgetOmitted > 0 || progress.bytesRead >= SimilarityBounds.MAX_RUN_BYTES) break
        }
        checkActionCurrent(); reader.validateScope(snapshot)
        progress.copy(unattempted = (pending.size - cursor).coerceAtLeast(0), complete = cursor >= pending.size)
            .also { checkActionCurrent(); onProgress(it) }
        } catch (_: CleanupScopeLimitException) {
            throw SimilarityException(SimilarityFailure.SCOPE_LIMIT)
        } catch (_: CleanupSourceChangedException) {
            throw SimilarityException(SimilarityFailure.SOURCE_CHANGED)
        } finally {
            activeJob = null
        }
    }

    suspend fun settle(): SimilaritySettleResult = scanner.settle()
}

object SimilarityIndexBounds {
    const val MAX_INDEX_SOURCES = 50_000
    const val MAX_CANDIDATE_CHECKS = 19_900
    const val MAX_STORED_ROWS = SIMILARITY_INDEX_MAX_ROWS
}
