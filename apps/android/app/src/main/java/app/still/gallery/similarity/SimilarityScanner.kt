package app.still.gallery.similarity

import app.still.gallery.media.*
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Source-derived selected-batch features; no disk staging, saved bytes, automatic action or time cutoff. */
class SimilarityScanner(
    private val reader: CleanupSourceReader,
    private val decoder: SimilarityFeatureDecoder = AndroidSimilarityFeatures(),
    private val ownership: SimilarityReadOwnership = similarityProcessReads,
    private val admission: CleanupReadAdmission = cleanupProcessReads,
) {
    private val session = SimilaritySession()
    fun begin(selected: List<MediaItem>, checkCurrent: () -> Unit): SimilarityTicket = session.begin(selected, checkCurrent)
    fun invalidate(ticket: SimilarityTicket) { session.invalidate(ticket) }
    fun isCurrent(ticket: SimilarityTicket): Boolean = session.isCurrent(ticket)
    fun resolve(result: SimilarityResult, pair: SimilarityPair): List<MediaItem>? = session.resolve(result, pair)
    suspend fun settle(): SimilaritySettleResult = ownership.settle()

    suspend fun scan(
        ticket: SimilarityTicket,
        favorites: Set<String>,
        onSnapshot: suspend (CleanupSourceSnapshot) -> Unit = {},
        onProgress: suspend (SimilarityCoverage) -> Unit = {},
    ): SimilarityResult {
        if (!session.isCurrent(ticket)) throw SimilarityException(SimilarityFailure.STALE)
        val work = ownership.enter(ticket, admission)
        try {
            return withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                val check = { job.ensureActive(); ticket.requireCurrent(); reader.checkAccess() }
                check()
                val snapshot = reader.snapshot(favorites.toSet())
                check()
                if (snapshot.items.size > CleanupBounds.MAX_SOURCES) throw SimilarityException(SimilarityFailure.SCOPE_LIMIT)
                val (unique, _) = cleanupUniqueSources(snapshot.items)
                val tokens = unique.groupBy(::sourceUiToken)
                val selected = ticket.selected.map { expected -> tokens[sourceUiToken(expected)]?.singleOrNull()?.takeIf { !it.isVideo }
                    ?: throw SimilarityException(SimilarityFailure.SOURCE_CHANGED) }
                onSnapshot(snapshot)
                check()
                reader.validateScope(snapshot)
                var coverage = SimilarityCoverage(selected.size)
                val sources = mutableListOf<SimilarityAnalyzedSource>()
                val budget = Budget()
                // Single fixed buffer, no accumulator growth, clone or 64KiB scratch allocation.
                val encoded = ByteArray(SimilarityBounds.MAX_INPUT_BYTES)
                onProgress(coverage)
                for (source in selected) {
                    check()
                    if (!cleanupSameSource(source, reader.probe(source), snapshot.access.sdk >= 30)) throw CleanupSourceChangedException()
                    check()
                    if (source.mimeType.lowercase(java.util.Locale.ROOT) !in setOf("image/jpeg", "image/png")) {
                        coverage = coverage.copy(unsupported = coverage.unsupported + 1)
                    } else if (budget.total == SimilarityBounds.MAX_RUN_BYTES) {
                        coverage = coverage.copy(budgetOmitted = coverage.budgetOmitted + 1)
                    } else {
                        coverage = coverage.copy(attempted = coverage.attempted + 1)
                        val captured = try {
                            val input = reader.open(source)
                            val resource = ownership.track(work, input)
                            // Physical close uncertainty dominates a primary read error. use() would
                            // suppress this signal under IOException and admit another provider handle.
                            try { capture(input, encoded, budget, check) } finally { resource.close() }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: SimilarityException) { throw failure }
                        catch (_: java.io.IOException) { Capture.Unreadable }
                        catch (_: RuntimeException) { Capture.Unreadable }
                        check()
                        when (captured) {
                            Capture.Empty -> coverage = coverage.copy(empty = coverage.empty + 1)
                            Capture.Oversized -> coverage = coverage.copy(oversized = coverage.oversized + 1)
                            Capture.BudgetOmitted -> coverage = coverage.copy(budgetOmitted = coverage.budgetOmitted + 1)
                            Capture.Unreadable -> coverage = coverage.copy(unreadable = coverage.unreadable + 1)
                            is Capture.Complete -> {
                                val pixels = try { decoder.decode(encoded, captured.count, check) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: SimilarityException) { throw failure }
                                    catch (_: Exception) { SimilarityPixelResult.Rejected(SimilarityPixelSkip.INVALID) }
                                check()
                                when (pixels) {
                                    is SimilarityPixelResult.Accepted -> {
                                        sources += SimilarityAnalyzedSource(source, pixels.feature, captured.sha256, captured.count.toLong())
                                        coverage = coverage.copy(featured = coverage.featured + 1)
                                    }
                                    is SimilarityPixelResult.Rejected -> coverage = if (pixels.reason == SimilarityPixelSkip.LOW_INFORMATION)
                                        coverage.copy(lowInformation = coverage.lowInformation + 1) else coverage.copy(unsupported = coverage.unsupported + 1)
                                }
                            }
                        }
                    }
                    if (!cleanupSameSource(source, reader.probe(source), snapshot.access.sdk >= 30)) throw CleanupSourceChangedException()
                    check()
                    coverage = coverage.copy(admittedBytes = budget.admitted, quotaProbeBytes = budget.probes)
                    onProgress(coverage)
                    check()
                }
                reader.validateScope(snapshot)
                // Failed/unsupported/budget-omitted members remain part of the user's selected scope.
                for (source in selected) {
                    check()
                    if (!cleanupSameSource(source, reader.probe(source), snapshot.access.sdk >= 30)) throw CleanupSourceChangedException()
                }
                reader.validateScope(snapshot)
                check()
                session.finish(ticket, sources, coverage).also { check() }
            }
        } catch (cancelled: CancellationException) { session.invalidate(ticket); throw cancelled }
        catch (_: CleanupScopeLimitException) { session.invalidate(ticket); throw SimilarityException(SimilarityFailure.SCOPE_LIMIT) }
        catch (_: CleanupSourceChangedException) { session.invalidate(ticket); throw SimilarityException(SimilarityFailure.SOURCE_CHANGED) }
        catch (failure: SimilarityException) { session.invalidate(ticket); throw failure }
        catch (_: Exception) { session.invalidate(ticket); throw SimilarityException(SimilarityFailure.IO) }
        finally {
            if (!ownership.finish(work)) {
                session.invalidate(ticket)
                throw SimilarityException(SimilarityFailure.CLOSE_FAILED)
            }
        }
    }

    private class Budget(var admitted: Long = 0, var probes: Int = 0) { val total: Long get() = admitted + probes }
    private sealed class Capture {
        class Complete(val count: Int, val sha256: String) : Capture()
        data object Empty : Capture()
        data object Oversized : Capture()
        data object BudgetOmitted : Capture()
        data object Unreadable : Capture()
    }
    private fun capture(input: InputStream, encoded: ByteArray, budget: Budget, check: () -> Unit): Capture {
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0
        var stalls = 0
        while (true) {
            check()
            val remaining = SimilarityBounds.MAX_RUN_BYTES - budget.total
            // No byte remains to prove EOF: omit this partial capture, never read past the run bound.
            if (remaining == 0L) return Capture.BudgetOmitted
            if (count == encoded.size) {
                val probe = input.read()
                check()
                if (probe == -1) break
                if (probe !in 0..255) throw java.io.IOException("Invalid quota probe")
                budget.probes++
                return Capture.Oversized
            }
            val requested = minOf(SimilarityBounds.BUFFER_BYTES.toLong(), (encoded.size - count).toLong(), remaining).toInt()
            val length = input.read(encoded, count, requested)
            check()
            if (length == -1) break
            if (length !in 0..requested) throw java.io.IOException("Invalid readable length")
            if (length == 0) { if (++stalls >= 8) throw java.io.IOException("Readable stream made no progress"); continue }
            stalls = 0
            hash.update(encoded, count, length)
            count += length
            budget.admitted += length
        }
        if (count == 0) return Capture.Empty
        return Capture.Complete(count, hash.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) })
    }
}
