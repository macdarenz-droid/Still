package app.still.gallery.backup.drive

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object DriveProtocolLimits {
    const val CHUNK_BYTES = 256 * 1024
    const val OBJECT_BYTES = 1024 * 1024
    const val METADATA_BYTES = 8 * 1024
    const val SESSION_CHARACTERS = 2048
}

enum class DriveProtocolError {
    INVALID_FIXTURE, INVALID_REQUEST, INVALID_SESSION, INVALID_RESPONSE, BUSY,
    RECONCILIATION_REQUIRED, INITIATION_UNKNOWN, SESSION_EXPIRED, READBACK_MISMATCH, CANCELLED, STALE_EPOCH,
}
class DriveProtocolException(val reason: DriveProtocolError) : Exception()
internal fun protocolRequire(value: Boolean, reason: DriveProtocolError = DriveProtocolError.INVALID_RESPONSE) {
    if (!value) throw DriveProtocolException(reason)
}

/** Fake wire response only. Header values and bytes never appear in rendering. */
class DriveTransferResponse(val status: Int, headers: List<Pair<String, String>> = emptyList(), body: ByteArray = byteArrayOf()) {
    private val fields: List<Pair<String, String>>
    private val bytes: ByteArray
    init {
        protocolRequire(status in 100..599 && headers.size <= 32 && body.size <= DriveProtocolLimits.OBJECT_BYTES + 1)
        protocolRequire(headers.all { (name, value) -> name.matches(Regex("[A-Za-z0-9-]{1,64}")) && value.length <= 4096 && value.none { it < ' ' || it > '~' } })
        protocolRequire(headers.sumOf { it.first.length + it.second.length } <= DriveProtocolLimits.METADATA_BYTES)
        protocolRequire(headers.map { it.first.lowercase() }.toSet().size == headers.size)
        fields = headers.map { it.first.lowercase() to it.second }; bytes = body.copyOf()
    }
    internal fun header(name: String): String? = fields.firstOrNull { it.first == name.lowercase() }?.second
    internal fun body(): ByteArray = bytes.copyOf()
    internal val bodyLength: Int get() = bytes.size
    override fun toString() = "DriveTransferResponse(status=$status, redacted)"
}

/** A secret capability, deliberately neither a data class nor a serializable record. */
internal class DriveSession private constructor(private val uri: URI) {
    companion object {
        fun parse(value: String): DriveSession {
            protocolRequire(value.length in 1..DriveProtocolLimits.SESSION_CHARACTERS && value.all { it in '!'..'~' }, DriveProtocolError.INVALID_SESSION)
            val uri = try { URI(value) } catch (_: Exception) { throw DriveProtocolException(DriveProtocolError.INVALID_SESSION) }
            protocolRequire(uri.scheme == "https" && uri.host == "www.googleapis.com" && uri.port in listOf(-1, 443) && uri.userInfo == null &&
                uri.fragment == null && uri.rawPath == "/upload/drive/v3/files", DriveProtocolError.INVALID_SESSION)
            val parts = uri.rawQuery?.split('&') ?: emptyList()
            protocolRequire(parts.size == 2 && parts.count { it == "uploadType=resumable" } == 1 &&
                parts.count { it.matches(Regex("upload_id=[A-Za-z0-9._~-]{1,1024}")) } == 1, DriveProtocolError.INVALID_SESSION)
            return DriveSession(uri)
        }
    }
    override fun toString() = "DriveSession(redacted)"
}

/** Missing Range confirms zero bytes; bounds/monotonicity belong to the request policy. */
internal fun driveAcknowledgedBytes(value: String?): Long {
    if (value == null) return 0
    val end = Regex("bytes=0-(0|[1-9][0-9]{0,18})").matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()
        ?: throw DriveProtocolException(DriveProtocolError.INVALID_RESPONSE)
    protocolRequire(end < Long.MAX_VALUE)
    return end + 1
}

internal data class DriveCompletion(val objectId: String, val byteLength: Int, val md5: String)

/** Exact projected binary-file metadata. It grants no original/media authority. */
internal fun driveCompletion(response: DriveTransferResponse, expectedBytes: Int, expectedMd5: String): DriveCompletion {
    protocolRequire(response.status == 200 || response.status == 201)
    protocolRequire(response.bodyLength in 1..DriveProtocolLimits.METADATA_BYTES)
    protocolRequire(response.header("content-type")?.lowercase()?.let { it == "application/json" || it == "application/json; charset=utf-8" } == true)
    val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(response.body())).toString() } catch (_: Exception) { throw DriveProtocolException(DriveProtocolError.INVALID_RESPONSE) }
    val fields = DriveMetadataJson(text).parse()
    protocolRequire(fields.keys == setOf("id", "size", "md5Checksum"))
    val id = fields.getValue("id")
    val size = fields.getValue("size")
    val md5 = fields.getValue("md5Checksum")
    protocolRequire(id.matches(Regex("[A-Za-z0-9_-]{1,200}")) && size.matches(Regex("[1-9][0-9]{0,18}")) && size.toLongOrNull() == expectedBytes.toLong())
    protocolRequire(md5.matches(Regex("[a-f0-9]{32}")) && md5 == expectedMd5)
    return DriveCompletion(id, expectedBytes, md5)
}

private class DriveMetadataJson(private val text: String) {
    private var at = 0
    private fun bad(): Nothing = throw DriveProtocolException(DriveProtocolError.INVALID_RESPONSE)
    private fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(char: Char): Boolean { space(); return if (at < text.length && text[at] == char) { at++; true } else false }
    private fun need(char: Char) { if (!take(char)) bad() }
    private fun string(): String {
        need('"'); val out = StringBuilder()
        while (at < text.length) {
            val char = text[at++]
            if (char == '"') return out.toString()
            if (char < ' ' || out.length >= 256) bad()
            if (char != '\\') out.append(char)
            else {
                if (at >= text.length) bad()
                when (val escaped = text[at++]) {
                    '"', '\\', '/' -> out.append(escaped)
                    'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                    'u' -> {
                        if (at + 4 > text.length) bad()
                        val hex = text.substring(at, at + 4)
                        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) bad()
                        out.append(hex.toInt(16).toChar()); at += 4
                    }
                    else -> bad()
                }
            }
        }
        bad()
    }
    fun parse(): Map<String, String> {
        need('{'); val result = mutableMapOf<String, String>()
        if (!take('}')) while (true) {
            val name = string(); if (result.containsKey(name)) bad(); need(':'); result[name] = string()
            if (take('}')) break
            need(',')
        }
        space(); if (at != text.length) bad()
        return result
    }
}
