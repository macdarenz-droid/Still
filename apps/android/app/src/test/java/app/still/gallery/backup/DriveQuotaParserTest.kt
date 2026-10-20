package app.still.gallery.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DriveQuotaParserTest {
    @Test fun requestedQuotaFieldsReturnExactByteCountsRegardlessOfOrder() {
        assertEquals(DriveStorageQuota(15_000_000_000L, 4_294_967_297L), parse(
            """{"storageQuota":{"limit":"15000000000","usage":"4294967297"}}"""
        ))
        assertEquals(DriveStorageQuota(15_000_000_000L, 4_294_967_297L), parse(
            " \r\n\t{\"storageQuota\" : { \"usage\" : \"4294967297\", \"limit\" : \"15000000000\" }}\t\r\n "
        ))
    }

    @Test fun absentQuotaAndAbsentFieldsRemainUnknownRatherThanBecomingZero() {
        for (json in listOf("{}", """{"storageQuota":{}}""")) {
            val result = parse(json)
            assertNull("limit in $json", result.limitBytes)
            assertNull("usage in $json", result.usageBytes)
        }
        assertEquals(DriveStorageQuota(42L, null), parse("""{"storageQuota":{"limit":"42"}}"""))
        assertEquals(DriveStorageQuota(null, 42L), parse("""{"storageQuota":{"usage":"42"}}"""))
    }

    @Test fun zeroLongMaximumAndUsageBeyondLimitAreValidReports() {
        assertEquals(DriveStorageQuota(0L, 0L), parse("""{"storageQuota":{"limit":"0","usage":"0"}}"""))
        assertEquals(DriveStorageQuota(Long.MAX_VALUE, Long.MAX_VALUE), parse(
            """{"storageQuota":{"limit":"9223372036854775807","usage":"9223372036854775807"}}"""
        ))
        assertEquals(DriveStorageQuota(0L, Long.MAX_VALUE), parse(
            """{"storageQuota":{"limit":"0","usage":"9223372036854775807"}}"""
        ))
        assertEquals(DriveStorageQuota(10L, 11L), parse("""{"storageQuota":{"limit":"10","usage":"11"}}"""))
    }

    @Test fun validJsonEscapesAreDecodedBeforeMatchingFieldsAndDecimalValues() {
        assertEquals(DriveStorageQuota(10L, 0L), parse(
            """{"storage\u0051uota":{"l\u0069mit":"\u0031\u0030","usage":"\u0030"}}"""
        ))
    }

    @Test fun negativeOverflowAndNonDecimalStringsCannotBecomeQuotaValues() {
        val invalidValues = listOf(
            "", "-1", "+1", "00", "01", "1.0", "1e3", "1E3", "0x10", " 1", "1 ",
            "9223372036854775808", "18446744073709551615", "9999999999999999999999999999999999999999",
            "١", "１２", "NaN", "Infinity"
        )
        for (field in listOf("limit", "usage")) {
            for (value in invalidValues) {
                assertInvalid("""{"storageQuota":{"$field":"$value"}}""", "$field=$value")
            }
        }
    }

    @Test fun quotaValuesMustBeDecimalStringsRatherThanJsonNumbersOrOtherTypes() {
        for (field in listOf("limit", "usage")) {
            for (encoded in listOf("0", "42", "1.5", "null", "true", "false", "[]", "{}", "[\"1\"]")) {
                assertInvalid("""{"storageQuota":{"$field":$encoded}}""", "$field=$encoded")
            }
        }
    }

    @Test fun rootAndStorageQuotaMustBeObjects() {
        for (json in listOf("", " \r\n\t", "null", "true", "0", "[]", "[{}]", "\"storageQuota\"")) {
            assertInvalid(json, "root: $json")
        }
        for (encoded in listOf("null", "true", "0", "[]", "[{}]", "\"quota\"")) {
            assertInvalid("""{"storageQuota":$encoded}""", "storageQuota=$encoded")
        }
    }

    @Test fun fieldsOutsideTheRequestedProjectionAreRejectedAtEitherObjectLevel() {
        for (json in listOf(
            """{"kind":"drive#about"}""",
            """{"user":{}}""",
            """{"storageQuota":{},"kind":"drive#about"}""",
            """{"kind":"drive#about","storageQuota":{}}""",
            """{"storageQuota":{"usageInDrive":"1"}}""",
            """{"storageQuota":{"limit":"10","usage":"1","usageInDriveTrash":"0"}}""",
            """{"storageQuota":{"credentials":"unexpected"}}""",
            """{"StorageQuota":{}}""",
            """{"storageQuota":{"Limit":"10"}}"""
        )) assertInvalid(json)
    }

    @Test fun duplicateFieldsAreRejectedEvenWhenValuesAgreeOrNamesUseEscapes() {
        for (json in listOf(
            """{"storageQuota":{},"storageQuota":{}}""",
            """{"storageQuota":{"limit":"1"},"storageQuota":{"limit":"2"}}""",
            """{"storageQuota":{},"storage\u0051uota":{}}""",
            """{"storageQuota":{"limit":"1","limit":"1"}}""",
            """{"storageQuota":{"usage":"1","usage":"2"}}""",
            """{"storageQuota":{"limit":"1","l\u0069mit":"2"}}""",
            """{"storageQuota":{"usage":"1","usag\u0065":"1"}}"""
        )) assertInvalid(json)
    }

    @Test fun everyTruncatedPrefixOfACompleteQuotaResponseIsRejected() {
        val complete = """{"storageQuota":{"limit":"1024","usage":"512"}}"""
        for (length in 0 until complete.length) {
            assertInvalid(complete.substring(0, length), "response truncated at $length bytes")
        }
        assertEquals(DriveStorageQuota(1_024L, 512L), parse(complete))
    }

    @Test fun trailingDataAndMalformedSeparatorsCannotBeIgnored() {
        for (json in listOf(
            "{}{}", "{} true", "{}x", "{}\u0000", "{}\u00a0",
            """{"storageQuota":{},}""",
            """{"storageQuota":{"limit":"1",}}""",
            """{"storageQuota":{"limit":"1" "usage":"2"}}""",
            """{"storageQuota":{"limit" "1"}}""",
            """{"storageQuota":{"limit":"1",,"usage":"2"}}""",
            """{'storageQuota':{}}"""
        )) assertInvalid(json)
    }

    @Test fun invalidEscapesAndUnescapedControlCharactersAreRejected() {
        for (json in listOf(
            """{"storageQuota":{"limit":"\x31"}}""",
            """{"storageQuota":{"limit":"\v"}}""",
            """{"storageQuota":{"limit":"\u003"}}""",
            """{"storageQuota":{"limit":"\u00zz"}}""",
            """{"storageQuota":{"lim\it":"1"}}""",
            "{\"storageQuota\":{\"limit\":\"1\n2\"}}",
            "{\"storageQuota\":{\"limit\":\"1\u00002\"}}",
            "{\"storageQuota\":{\"li\tmit\":\"1\"}}"
        )) assertInvalid(json)
    }

    @Test fun malformedUtf8BytesAreInvalidResponsesRatherThanReplacementText() {
        val prefix = "{\"storageQuota\":{\"limit\":\"".toByteArray(Charsets.UTF_8)
        val suffix = "\"}}".toByteArray(Charsets.UTF_8)
        val invalidSequences = listOf(
            intArrayOf(0x80),
            intArrayOf(0xc0, 0xb1),
            intArrayOf(0xc2),
            intArrayOf(0xe2, 0x82),
            intArrayOf(0xe2, 0x28, 0xa1),
            intArrayOf(0xed, 0xa0, 0x80),
            intArrayOf(0xf4, 0x90, 0x80, 0x80),
            intArrayOf(0xff)
        )
        for ((index, sequence) in invalidSequences.withIndex()) {
            assertInvalid(prefix + sequence.map { it.toByte() }.toByteArray() + suffix, "invalid UTF-8 sequence $index")
        }
    }

    @Test fun eightKiBResponseBoundaryIsInclusiveAndLargerResponsesAreRejected() {
        val response = """{"storageQuota":{"limit":"10","usage":"4"}}""".toByteArray(Charsets.UTF_8)
        val atLimit = response + ByteArray(8_192 - response.size) { ' '.code.toByte() }
        assertEquals(8_192, atLimit.size)
        assertEquals(DriveStorageQuota(10L, 4L), parseDriveStorageQuota(atLimit))
        assertInvalid(atLimit + byteArrayOf(' '.code.toByte()), "8,193-byte response")
        assertInvalid(response + ByteArray(32_768) { ' '.code.toByte() }, "oversized response with valid prefix")
    }

    private fun parse(json: String): DriveStorageQuota = parseDriveStorageQuota(json.toByteArray(Charsets.UTF_8))

    private fun assertInvalid(json: String, label: String = json) = assertInvalid(json.toByteArray(Charsets.UTF_8), label)

    private fun assertInvalid(bytes: ByteArray, label: String) {
        val failure = assertThrows(label, DriveReadinessFailure::class.java) { parseDriveStorageQuota(bytes) }
        assertEquals(label, DriveReadinessError.INVALID_RESPONSE, failure.reason)
    }
}
