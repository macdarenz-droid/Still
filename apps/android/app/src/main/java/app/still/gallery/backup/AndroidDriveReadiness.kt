package app.still.gallery.backup

import java.io.InputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One fixed read-only Drive endpoint. No file, media, account or arbitrary URL input. */
class AndroidDriveReadiness internal constructor(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val nanoTime: () -> Long = System::nanoTime,
) : DriveReadinessTransport {
    companion object { internal const val ENDPOINT = "https://www.googleapis.com/drive/v3/about?fields=storageQuota(limit,usage)" }

    override suspend fun check(lease: DriveAccessLease, request: DriveReadinessRequest): DriveStorageQuota = withContext(Dispatchers.IO) {
        val job = coroutineContext
        val start = nanoTime()
        fun checkCurrent() {
            job.ensureActive(); request.check()
            if (!lease.isCurrent()) throw DriveReadinessFailure(DriveReadinessError.NEEDS_SIGN_IN)
            if (nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(DriveReadinessLimits.DEADLINE_MS))
                throw DriveReadinessFailure(DriveReadinessError.NETWORK)
        }
        var connection: HttpsURLConnection? = null
        var body: InputStream? = null
        try {
            checkCurrent()
            val target = URL(ENDPOINT)
            val held = open(target); connection = held
            // The injected test seam cannot redirect the bearer to another URL either.
            if (held.url.toExternalForm() != target.toExternalForm() || held.url.protocol != "https" || held.url.host != "www.googleapis.com" ||
                held.url.userInfo != null || held.url.ref != null || held.url.port !in listOf(-1, 443))
                throw DriveReadinessFailure(DriveReadinessError.INVALID_RESPONSE)
            held.instanceFollowRedirects = false; held.useCaches = false; held.allowUserInteraction = false
            held.connectTimeout = DriveReadinessLimits.CONNECT_TIMEOUT_MS; held.readTimeout = DriveReadinessLimits.READ_TIMEOUT_MS
            held.requestMethod = "GET"
            held.setRequestProperty("Accept", "application/json"); held.setRequestProperty("Accept-Encoding", "identity")
            checkCurrent(); held.setRequestProperty("Authorization", lease.authorizationHeader())
            request.attachAbort {
                // disconnect may block on an implementation lock: never run it on the UI thread.
                Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { try { held.disconnect() } catch (_: Exception) { /* finalizer also disconnects */ } })
            }
            checkCurrent()
            when (held.responseCode) {
                200 -> Unit
                401 -> throw DriveReadinessFailure(DriveReadinessError.NEEDS_SIGN_IN)
                403 -> throw DriveReadinessFailure(DriveReadinessError.PERMISSION_DENIED)
                429 -> throw DriveReadinessFailure(DriveReadinessError.RATE_LIMITED)
                else -> throw DriveReadinessFailure(DriveReadinessError.NETWORK)
            }
            checkCurrent()
            val contentType = held.contentType?.split(';')?.map { it.trim().lowercase() }
                ?: throw DriveReadinessFailure(DriveReadinessError.INVALID_RESPONSE)
            if (contentType.firstOrNull() != "application/json" || contentType.drop(1).any { it != "charset=utf-8" } ||
                held.contentEncoding?.lowercase()?.let { it != "identity" } == true || held.contentLengthLong > DriveReadinessLimits.RESPONSE_BYTES)
                throw DriveReadinessFailure(DriveReadinessError.INVALID_RESPONSE)
            body = held.inputStream
            val bytes = ByteArray(DriveReadinessLimits.RESPONSE_BYTES + 1); var used = 0
            while (true) {
                checkCurrent()
                val count = body.read(bytes, used, bytes.size - used)
                checkCurrent()
                if (count == -1) break
                if (count <= 0 || count > bytes.size - used) throw DriveReadinessFailure(DriveReadinessError.INVALID_RESPONSE)
                used += count
                if (used > DriveReadinessLimits.RESPONSE_BYTES) throw DriveReadinessFailure(DriveReadinessError.INVALID_RESPONSE)
            }
            body.close(); body = null
            checkCurrent()
            val quota = parseDriveStorageQuota(bytes.copyOf(used))
            checkCurrent(); quota
        } catch (failure: DriveReadinessFailure) { throw failure }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { request.check(); throw DriveReadinessFailure(DriveReadinessError.NETWORK) }
        finally {
            request.detachAbort()
            try { body?.close() } catch (_: Exception) { /* no successful result exists when this body is retained */ }
            try { connection?.errorStream?.close() } catch (_: Exception) { /* never parse provider error text */ }
            try { connection?.disconnect() } catch (_: Exception) { /* no token/body logging */ }
        }
    }
}
