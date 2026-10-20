package app.still.gallery.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Query construction only; no device, MediaStore cursor or fixture visibility is simulated. */
class DeviceMediaQueryPolicyTest {
    @Test fun android26Through28EnumerationPreservesUnfilteredProviderQuery() {
        for (sdk in 26..28) {
            val query = deviceMediaQueryPolicy(sdk)
            assertNull(query.selection)
            assertNull(query.arguments)
        }
    }

    @Test fun android29AndNewerEnumerationExcludesOwnedPendingRows() {
        for (sdk in 29..37) {
            val query = deviceMediaQueryPolicy(sdk)
            assertEquals("is_pending = 0", query.selection)
            assertNull(query.arguments)
        }
    }

    @Test fun modernExactRowProbeKeepsPendingExclusionAndBindsTheRowId() {
        for (sdk in 29..37) {
            val query = deviceMediaQueryPolicy(sdk, 713L)
            assertEquals("is_pending = 0 AND _id = ?", query.selection)
            assertEquals(listOf("713"), query.arguments)
            assertFalse(query.selection!!.contains("713"))
        }
    }

    @Test fun legacyExactRowProbePreservesItsOriginalBoundPredicate() {
        for (sdk in 26..28) {
            val query = deviceMediaQueryPolicy(sdk, 713L)
            assertEquals("_id = ?", query.selection)
            assertEquals(listOf("713"), query.arguments)
        }
    }

    @Test fun rowIdLimitsStayBoundAndCannotReplaceTheVisibilityPredicate() {
        for (id in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
            val modern = deviceMediaQueryPolicy(37, id)
            assertEquals("is_pending = 0 AND _id = ?", modern.selection)
            assertEquals(listOf(id.toString()), modern.arguments)
            val legacy = deviceMediaQueryPolicy(28, id)
            assertEquals("_id = ?", legacy.selection)
            assertEquals(listOf(id.toString()), legacy.arguments)
        }
    }
}
