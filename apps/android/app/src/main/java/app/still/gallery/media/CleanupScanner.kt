package app.still.gallery.media

import android.content.Context
import androidx.core.net.toUri
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class CleanupCoverage(
    val considered: Int,
    val candidates: Int,
    val checked: Int = 0,
    val compared: Int = 0,
    val empty: Int = 0,
    val unavailable: Int = 0,
    val changed: Int = 0,
    val omitted: Int = 0,
    val aliases: Int = 0,
)

data class CleanupReadMember(val item: MediaItem, val digest: CleanupDigest)
data class CleanupReadResult(val snapshot: CleanupSourceSnapshot, val members: List<CleanupReadMember>, val coverage: CleanupCoverage)
class CleanupReaderBusyException : IOException("A previous provider read is still finishing")

/** IO abstraction lets synthetic streaming/provenance tests exercise the actual scanner. */
interface CleanupSourceReader {
    fun checkAccess()
    suspend fun snapshot(favorites: Set<String>): CleanupSourceSnapshot
    suspend fun probe(item: MediaItem): MediaItem?
    suspend fun validateScope(snapshot: CleanupSourceSnapshot)
    fun open(item: MediaItem): InputStream
}

class AndroidCleanupSourceReader(private val context: Context, private val device: DeviceMedia, private val access: MediaAccess) : CleanupSourceReader {
    override fun checkAccess() { if (context.mediaAccess() != access) throw CancellationException("Media access changed") }
    override suspend fun snapshot(favorites: Set<String>) = device.cleanupSnapshot(access, favorites)
    override suspend fun probe(item: MediaItem) = device.cleanupProbe(item)
    override suspend fun validateScope(snapshot: CleanupSourceSnapshot) = device.cleanupValidateScope(snapshot)
    override fun open(item: MediaItem): InputStream = context.contentResolver.openInputStream(item.id.toUri()) ?: throw IOException("Media is not readable")
}

class CleanupScanner(private val reader: CleanupSourceReader, private val admission: CleanupReadAdmission = cleanupProcessReads) {
    suspend fun scan(
        favorites: Set<String>,
        checkCurrent: () -> Unit,
        onSnapshot: suspend (CleanupSourceSnapshot) -> Unit,
        onCandidate: (MediaItem) -> Unit,
        onProgress: suspend (CleanupCoverage) -> Unit,
    ): CleanupReadResult {
        checkCurrent()
        val lease = admission.tryEnter() ?: throw CleanupReaderBusyException()
        try {
            return withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                val check = { job.ensureActive(); checkCurrent(); reader.checkAccess() }
                check()
                val snapshot = reader.snapshot(favorites)
                check()
                if (snapshot.items.size > CleanupBounds.MAX_SOURCES) throw CleanupScopeLimitException()
                onSnapshot(snapshot)
                check()
                val (unique, aliases) = cleanupUniqueSources(snapshot.items)
                val candidates = cleanupCandidates(unique)
                var coverage = CleanupCoverage(snapshot.items.size, candidates.size, omitted = unique.size - candidates.size, aliases = aliases)
                onProgress(coverage)
                val members = mutableListOf<CleanupReadMember>()
                var lastProgress = System.nanoTime()
                for (item in candidates) {
                    check()
                    onCandidate(item)
                    try {
                        if (!cleanupSameSource(item, reader.probe(item), snapshot.access.sdk >= 30)) throw CleanupSourceChangedException()
                        check()
                        val digest = readCleanupBytes({ reader.open(item) }, check)
                        if (!cleanupSameSource(item, reader.probe(item), snapshot.access.sdk >= 30)) throw CleanupSourceChangedException()
                        check()
                        members += CleanupReadMember(item, digest)
                        coverage = coverage.copy(compared = coverage.compared + 1)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: CleanupEmptyReadException) {
                        coverage = coverage.copy(empty = coverage.empty + 1)
                    } catch (_: CleanupSourceChangedException) {
                        coverage = coverage.copy(changed = coverage.changed + 1)
                    } catch (_: IOException) {
                        coverage = coverage.copy(unavailable = coverage.unavailable + 1)
                    } catch (_: RuntimeException) {
                        coverage = coverage.copy(unavailable = coverage.unavailable + 1)
                    }
                    check()
                    coverage = coverage.copy(checked = coverage.checked + 1)
                    val now = System.nanoTime()
                    if (coverage.checked == coverage.candidates || now - lastProgress >= 250_000_000L) {
                        onProgress(coverage)
                        lastProgress = now
                    }
                }
                check()
                reader.validateScope(snapshot)
                // Also recheck early members at the END of the entire scan. This is
                // essential best-effort evidence on old providers and for partial access.
                for (member in members) {
                    check()
                    if (!cleanupSameSource(member.item, reader.probe(member.item), snapshot.access.sdk >= 30)) throw CleanupSourceChangedException()
                }
                reader.validateScope(snapshot)
                check()
                CleanupReadResult(snapshot, members.toList(), coverage)
            }
        } finally {
            // withContext does not return while its blocking worker is still running.
            // Thus cancel/recreation cannot release the lane ahead of actual closure.
            lease.close()
        }
    }
}
