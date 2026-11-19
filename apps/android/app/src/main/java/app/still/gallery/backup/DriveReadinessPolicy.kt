package app.still.gallery.backup

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object DriveReadinessLimits {
    const val RESPONSE_BYTES = 8_192
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 5_000
    const val DEADLINE_MS = 15_000L
}

enum class DriveReadinessError { CANCELLED, NEEDS_SIGN_IN, PERMISSION_DENIED, RATE_LIMITED, NETWORK, INVALID_RESPONSE }
class DriveReadinessFailure(val reason: DriveReadinessError) : Exception()
data class DriveStorageQuota(val limitBytes: Long?, val usageBytes: Long?)
enum class DriveReadinessPhase { IDLE, CHECKING, CHECKED, FAILED }
class DriveReadinessAction internal constructor()
data class DriveReadinessState(val phase: DriveReadinessPhase, val action: DriveReadinessAction,
    val quota: DriveStorageQuota? = null, val error: DriveReadinessError? = null)

/** Memory-only bearer capability. Its string form never contains the token. */
class DriveAccessLease internal constructor(private val token: String, internal val epoch: String,
    internal val revision: Long, private val current: () -> Boolean) {
    fun isCurrent(): Boolean = current()
    internal fun authorizationHeader(): String {
        if (!isCurrent()) throw DriveReadinessFailure(DriveReadinessError.NEEDS_SIGN_IN)
        return "Bearer $token"
    }
    override fun toString() = "DriveAccessLease(redacted)"
}

/** Invalidation is immediate; the transport installs a nonblocking abort hook. */
class DriveReadinessRequest internal constructor(private val current: () -> Boolean) {
    private val cancelled = AtomicBoolean(false)
    private val abort = AtomicReference<(() -> Unit)?>(null)
    fun cancel() { cancelled.set(true); abort.getAndSet(null)?.invoke() }
    fun check() {
        if (cancelled.get() || !current()) throw DriveReadinessFailure(DriveReadinessError.CANCELLED)
    }
    internal fun attachAbort(value: () -> Unit) {
        check(); abort.set(value)
        if (cancelled.get()) abort.getAndSet(null)?.invoke()
        check()
    }
    internal fun detachAbort() { abort.set(null) }
}

interface DriveReadinessTransport {
    suspend fun check(lease: DriveAccessLease, request: DriveReadinessRequest): DriveStorageQuota
}

/** Only the two explicitly requested Drive int64 fields are accepted. */
fun parseDriveStorageQuota(bytes: ByteArray): DriveStorageQuota {
    fun invalid(): Nothing = throw DriveReadinessFailure(DriveReadinessError.INVALID_RESPONSE)
    if (bytes.isEmpty() || bytes.size > DriveReadinessLimits.RESPONSE_BYTES) invalid()
    val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
    catch (_: Exception) { invalid() }
    return DriveQuotaJson(text).parse()
}

/** Small bounded parser for the fixed response projection, not a general JSON parser. */
private class DriveQuotaJson(private val text: String) {
    private var at = 0
    private fun invalid(): Nothing = throw DriveReadinessFailure(DriveReadinessError.INVALID_RESPONSE)
    private fun space() { while (at < text.length && text[at] in " \r\n\t") at++ }
    private fun take(char: Char): Boolean { space(); return if (at < text.length && text[at] == char) { at++; true } else false }
    private fun require(char: Char) { if (!take(char)) invalid() }
    private fun string(): String {
        require('"'); val result = StringBuilder()
        while (at < text.length) {
            val char = text[at++]
            if (char == '"') return result.toString()
            if (char < ' ' || result.length >= 64) invalid()
            if (char != '\\') result.append(char)
            else {
                if (at >= text.length) invalid()
                when (val escaped = text[at++]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        if (at + 4 > text.length) invalid()
                        val code = text.substring(at, at + 4).takeIf { it.all { digit -> digit in '0'..'9' || digit in 'a'..'f' || digit in 'A'..'F' } }?.toInt(16) ?: invalid()
                        result.append(code.toChar()); at += 4
                    }
                    else -> invalid()
                }
            }
        }
        invalid()
    }
    private fun integer(): Long {
        val value = string()
        if (!Regex("0|[1-9][0-9]{0,18}").matches(value)) invalid()
        return value.toLongOrNull() ?: invalid()
    }
    private fun quota(): DriveStorageQuota {
        require('{'); val seen = mutableSetOf<String>(); var limit: Long? = null; var usage: Long? = null
        if (!take('}')) while (true) {
            val name = string(); if (!seen.add(name)) invalid(); require(':')
            when (name) { "limit" -> limit = integer(); "usage" -> usage = integer(); else -> invalid() }
            if (take('}')) break
            require(',')
        }
        return DriveStorageQuota(limit, usage)
    }
    fun parse(): DriveStorageQuota {
        require('{'); var result = DriveStorageQuota(null, null)
        if (!take('}')) { if (string() != "storageQuota") invalid(); require(':'); result = quota(); require('}') }
        space(); if (at != text.length) invalid()
        return result
    }
}
