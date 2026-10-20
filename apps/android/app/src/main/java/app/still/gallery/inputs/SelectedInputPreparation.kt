package app.still.gallery.inputs

import app.still.gallery.media.CleanupReadAdmission
import app.still.gallery.media.CleanupSourceChangedException
import app.still.gallery.media.CleanupScopeLimitException
import app.still.gallery.media.CleanupSourceReader
import app.still.gallery.media.MediaItem
import app.still.gallery.media.cleanupProcessReads
import app.still.gallery.media.cleanupSameSource
import app.still.gallery.media.sourceUiToken
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Same production source and heavy-reader lane as Cleanup; no source bytes are original proof. */
class SelectedInputPreparation(
    private val reader: CleanupSourceReader,
    private val store: SelectedInputStore,
    private val admission: CleanupReadAdmission = cleanupProcessReads,
    /** Only a trusted declaration about the readable representation; never MediaStore.SIZE. */
    private val declaredReadableLength: (MediaItem) -> Long? = { null },
) {
    suspend fun prepare(
        ticket: SelectedInputTicket,
        favorites: Set<String>,
        onProgress: (SelectedInputProgress) -> Unit = {},
    ): PreparedInputPair {
        store.beginWork(ticket)
        var success = false
        try {
            val lane = admission.tryEnter() ?: throw SelectedInputException(SelectedInputFailure.BUSY)
            store.attachLane(ticket, lane)
            val pair = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                val check = { job.ensureActive(); ticket.requireCurrent(); reader.checkAccess() }
                check()
                store.beginFiles(ticket)
                val snapshot = reader.snapshot(favorites.toSet())
                check()
                val byToken = snapshot.items.groupBy(::sourceUiToken)
                val selected = ticket.selected.map { expected ->
                    byToken[sourceUiToken(expected)]?.singleOrNull()?.takeIf { !it.isVideo }
                        ?: throw SelectedInputException(SelectedInputFailure.SOURCE_CHANGED)
                }
                selectedPhotos(selected)
                reader.validateScope(snapshot)
                check()
                val members = mutableListOf<PreparedSelectedInput>()
                var retained = 0L
                var lastProgress = System.nanoTime()
                onProgress(SelectedInputProgress(0, bytes = 0))
                for (source in selected) {
                    check()
                    if (!cleanupSameSource(source, reader.probe(source), snapshot.access.sdk >= 30)) throw CleanupSourceChangedException()
                    check()
                    val declared = declaredReadableLength(source)
                    if (declared != null && (declared < 0 || declared > SelectedInputBounds.MAX_INPUT_BYTES)) throw SelectedInputException(SelectedInputFailure.SIZE_LIMIT)
                    val output = store.create(ticket)
                    var count = 0L
                    val digest = MessageDigest.getInstance("SHA-256")
                    store.track(ticket, output.sink::close).use {
                        val sink = output.sink
                        val input = reader.open(source)
                        store.track(ticket, input::close).use {
                            val buffer = ByteArray(SelectedInputBounds.BUFFER_BYTES)
                            var stalled = 0
                            while (true) {
                                check()
                                val length = input.read(buffer)
                                check()
                                if (length == -1) break
                                if (length < -1 || length > buffer.size) throw SelectedInputException(SelectedInputFailure.IO)
                                if (length == 0) { if (++stalled >= 8) throw SelectedInputException(SelectedInputFailure.IO); continue }
                                stalled = 0
                                count = selectedInputCount(count, length, SelectedInputBounds.MAX_INPUT_BYTES)
                                retained = selectedInputCount(retained, length, SelectedInputBounds.MAX_RETAINED_BYTES)
                                sink.stream.write(buffer, 0, length)
                                check()
                                digest.update(buffer, 0, length)
                                if (System.nanoTime() - lastProgress >= 250_000_000L) {
                                    onProgress(SelectedInputProgress(members.size, bytes = retained))
                                    check(); lastProgress = System.nanoTime()
                                }
                            }
                            sink.sync()
                        }
                    }
                    check()
                    if (count == 0L) throw SelectedInputException(SelectedInputFailure.EMPTY)
                    if (declared != null && declared != count) throw SelectedInputException(SelectedInputFailure.IO)
                    val member = store.verify(ticket, output, source, count, selectedInputHex(digest.digest()), check)
                    if (!cleanupSameSource(source, reader.probe(source), snapshot.access.sdk >= 30)) throw CleanupSourceChangedException()
                    check()
                    members += member
                    onProgress(SelectedInputProgress(members.size, bytes = retained))
                    check()
                }
                // An early source changing while the second streams invalidates BOTH.
                reader.validateScope(snapshot)
                for (source in selected) {
                    check()
                    if (!cleanupSameSource(source, reader.probe(source), snapshot.access.sdk >= 30)) throw CleanupSourceChangedException()
                }
                reader.validateScope(snapshot)
                check()
                PreparedInputPair(ticket, members).also { store.publish(ticket, it) }
            }
            success = true
            return pair
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SelectedInputException) { throw failure }
        catch (_: CleanupScopeLimitException) { throw SelectedInputException(SelectedInputFailure.SCOPE_LIMIT) }
        catch (_: CleanupSourceChangedException) { throw SelectedInputException(SelectedInputFailure.SOURCE_CHANGED) }
        catch (_: Exception) { throw SelectedInputException(SelectedInputFailure.IO) }
        finally {
            store.finishWork(ticket)
            if (!success) {
                store.invalidate(ticket)
                if (store.closeAndCleanup(ticket) == SelectedInputCleanupResult.FAILED) throw SelectedInputException(SelectedInputFailure.CLEANUP_FAILED)
            }
        }
    }
}
