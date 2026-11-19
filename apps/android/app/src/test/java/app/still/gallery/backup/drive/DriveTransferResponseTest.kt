package app.still.gallery.backup.drive

import org.junit.Assert.*
import org.junit.Test

class DriveTransferResponseTest {
    private val uri = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=fixture_secret_session"
    private val md5 = "a".repeat(32)
    private val metadata = "{\"id\":\"fixture_object\",\"size\":\"17\",\"md5Checksum\":\"$md5\"}"
    private fun response(body: String = metadata, type: String = "application/json", status: Int = 200) =
        DriveTransferResponse(status, listOf("Content-Type" to type), body.toByteArray())
    private fun denied(reason: DriveProtocolError = DriveProtocolError.INVALID_RESPONSE, run: () -> Unit) {
        try { run(); fail("expected $reason") } catch (failure: DriveProtocolException) { assertEquals(reason, failure.reason) }
    }

    @Test fun documentedGoogleSessionShapeAcceptsOnlyBoundedHttpsCapabilityAndRedactsRendering() {
        for (value in listOf(uri, uri.replace("googleapis.com/", "googleapis.com:443/"),
            "https://www.googleapis.com/upload/drive/v3/files?upload_id=fixture_secret_session&uploadType=resumable")) {
            val session = DriveSession.parse(value)
            assertFalse(session.toString().contains(value)); assertFalse(session.toString().contains("fixture_secret_session"))
        }
    }

    @Test fun unsafeHostSchemePathCredentialsFragmentsEncodingAndQueryCannotBecomeSession() {
        val values = listOf(uri.replace("https:", "http:"), uri.replace("www.googleapis.com", "www.googleapis.com.attacker.invalid"),
            uri.replace("www.googleapis.com", "attacker.invalid@www.googleapis.com"), uri.replace("www.googleapis.com", "127.0.0.1"),
            uri.replace("www.googleapis.com", "www.googleapis.com:444"), uri + "#secret", uri.replace("/upload/", "/drive/"),
            uri.replace("/upload/", "/%75pload/"), uri + "&other=value", uri.replace("upload_id=fixture_secret_session", "upload_id="),
            uri.replace("upload_id=fixture_secret_session", "uploadType=resumable"), uri.replace("fixture_secret_session", "%66ixture"),
            uri + "\r\nInjected: value", "//www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=x",
            uri + "x".repeat(DriveProtocolLimits.SESSION_CHARACTERS), uri.replace("fixture_secret_session", "x".repeat(1025)))
        for (value in values) denied(DriveProtocolError.INVALID_SESSION) { DriveSession.parse(value) }
    }

    @Test fun responseOwnsMutableInputsAndRendersNeitherHeaderCapabilitiesNorBody() {
        val headers = mutableListOf("Location" to uri); val bytes = "sensitive fixture body".toByteArray()
        val response = DriveTransferResponse(200, headers, bytes)
        headers[0] = "Location" to "changed"; bytes.fill(0)
        assertEquals(uri, response.header("location"))
        assertEquals("sensitive fixture body", response.body().toString(Charsets.UTF_8))
        response.body().fill(0); assertEquals("sensitive fixture body", response.body().toString(Charsets.UTF_8))
        assertFalse(response.toString().contains("fixture_secret_session")); assertFalse(response.toString().contains("sensitive"))
    }

    @Test fun malformedDuplicateOrOversizedHeaderFramesAreRejected() {
        for (headers in listOf(listOf("Range" to "bytes=0-1", "range" to "bytes=0-2"),
            listOf("Location" to "$uri\n"), listOf("bad header" to "value"), listOf("Location" to "x".repeat(4097)),
            (1..33).map { "Header-$it" to "value" }, (1..3).map { "Header-$it" to "x".repeat(4000) }))
            denied { DriveTransferResponse(200, headers) }
        for (status in listOf(0, 99, 600)) denied { DriveTransferResponse(status) }
        denied { DriveTransferResponse(200, body = ByteArray(DriveProtocolLimits.OBJECT_BYTES + 2)) }
    }

    @Test fun absentRangeConfirmsZeroAndInclusiveEndCountsOneMoreByteWithoutOverflow() {
        assertEquals(0L, driveAcknowledgedBytes(null)); assertEquals(1L, driveAcknowledgedBytes("bytes=0-0"))
        assertEquals(262144L, driveAcknowledgedBytes("bytes=0-262143"))
        assertEquals(Long.MAX_VALUE, driveAcknowledgedBytes("bytes=0-${Long.MAX_VALUE - 1}"))
    }

    @Test fun malformedContradictoryRangeSyntaxCannotBecomeProgress() {
        for (value in listOf("", "bytes=1-2", "bytes=0--1", "bytes=0-01", "bytes=00-1", "bytes=0-1,2-3",
            "bytes=0-1/20", "Bytes=0-1", " bytes=0-1", "bytes=0-1 ", "bytes=0-9223372036854775807", "bytes=0-9223372036854775808"))
            denied { driveAcknowledgedBytes(value) }
    }

    @Test fun completionRequiresExactRequestedBinaryFieldsLengthAndChecksum() {
        for (status in listOf(200, 201)) {
            val value = driveCompletion(response(status = status), 17, md5)
            assertEquals("fixture_object", value.objectId); assertEquals(17, value.byteLength); assertEquals(md5, value.md5)
        }
        assertEquals("fixture_object", driveCompletion(response(metadata.replace("\"id\"", "\"\\u0069d\"")), 17, md5).objectId)
        for (status in listOf(202, 308, 404, 500)) denied { driveCompletion(response(status = status), 17, md5) }
        denied { driveCompletion(response(), 18, md5) }; denied { driveCompletion(response(), 17, "b".repeat(32)) }
    }

    @Test fun missingExtraDuplicateWrongTypeAndInvalidIdentityMetadataAreRejected() {
        for (body in listOf("{}", "[]", metadata.replace(",\"size\":\"17\"", ""), metadata.dropLast(1) + ",\"name\":\"extra\"}",
            metadata.dropLast(1) + ",\"id\":\"another\"}", metadata.dropLast(1) + ",\"\\u0069d\":\"another\"}",
            metadata.replace("\"17\"", "17"), metadata.replace("\"17\"", "null"), metadata.replace("fixture_object", ""),
            metadata.replace("fixture_object", uri), metadata.replace("fixture_object", "x".repeat(201)), metadata.replace(md5, md5.uppercase()),
            metadata.replace(md5, "a".repeat(31)), metadata + " trailing")) denied { driveCompletion(response(body), 17, md5) }
        for (size in listOf("0", "-1", "01", "+17", "17.0", "1e1", " 17", "9223372036854775808"))
            denied { driveCompletion(response(metadata.replace("\"17\"", "\"$size\"")), 17, md5) }
    }

    @Test fun everyTruncatedMetadataPrefixAndMalformedUtf8FailsClosed() {
        for (end in metadata.indices) denied { driveCompletion(response(metadata.take(end)), 17, md5) }
        for (bytes in listOf(byteArrayOf(0xc3.toByte()), byteArrayOf(0xff.toByte()), byteArrayOf(0xc0.toByte(), 0x80.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())))
            denied { driveCompletion(DriveTransferResponse(200, listOf("Content-Type" to "application/json"), bytes), 17, md5) }
    }

    @Test fun malformedJsonEscapesSeparatorsNestedValuesAndControlCharactersAreRejected() {
        for (body in listOf(metadata.replace("fixture_object", "bad\\q"), metadata.replace("fixture_object", "bad\\u00xz"),
            metadata.replace("fixture_object", "bad\n"), metadata.replace("\"17\"", "{\"value\":\"17\"}"),
            metadata.replace(",\"size\"", ",,\"size\""), metadata.dropLast(1) + ",}", metadata.replace(":\"17\"", " \"17\"")))
            denied { driveCompletion(response(body), 17, md5) }
    }

    @Test fun metadataBoundIsInclusiveAndMimeTypeIsExplicit() {
        val bounded = metadata + " ".repeat(DriveProtocolLimits.METADATA_BYTES - metadata.toByteArray().size)
        assertEquals(17, driveCompletion(response(bounded), 17, md5).byteLength)
        denied { driveCompletion(response(bounded + " "), 17, md5) }
        for (type in listOf("text/html", "application/json; charset=iso-8859-1")) denied { driveCompletion(response(type = type), 17, md5) }
        denied { driveCompletion(DriveTransferResponse(200, body = metadata.toByteArray()), 17, md5) }
        assertEquals(17, driveCompletion(response(type = "application/json; charset=UTF-8"), 17, md5).byteLength)
    }

    @Test fun errorsDoNotRenderRejectedSecretInput() {
        try { DriveSession.parse(uri + "#private-session-fragment"); fail("unsafe session") }
        catch (failure: DriveProtocolException) {
            assertFalse(failure.toString().contains(uri)); assertFalse(failure.toString().contains("private-session-fragment")); assertNull(failure.cause)
        }
    }
}
