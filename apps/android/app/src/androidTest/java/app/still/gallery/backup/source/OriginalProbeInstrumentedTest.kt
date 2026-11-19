package app.still.gallery.backup.source

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.test.InstrumentationTestCase

/** Generated pending fixture only. Run separately with location permission granted/denied.
 * This proves neither third-party original fidelity, complete inventory nor source stability.
 * No device invocation is implied by compiling this test APK.
 */
@Suppress("DEPRECATION")
class OriginalProbeInstrumentedTest : InstrumentationTestCase() {
    fun testGeneratedOriginalRequestAndExactFixtureCleanup() {
        val context = instrumentation.targetContext
        val probe = AndroidOriginalProbe(context)
        assertEquals(OriginalProbeCleanup.CLEAN, probe.settle())
        val granted = Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        try {
            val result = probe.startGenerated().await()
            if (Build.VERSION.SDK_INT < 29) assertEquals(OriginalProbeFailure.UNSUPPORTED_API, result.failure)
            else if (!granted) {
                // Proves denial before fixture creation/read, not the platform's redaction behavior.
                assertEquals(OriginalProbeFailure.LOCATION_PERMISSION_REQUIRED, result.failure)
                assertEquals(0L, result.actualBytes)
            } else {
                assertNull("Generated original request must match its privately known bytes", result.failure)
                assertTrue(result.actualBytes > 0)
                assertEquals(64, result.sha256?.length)
                assertTrue(result.locationPermissionGranted)
            }
            assertFalse(result.productionCapability)
            assertFalse(result.backupEligible)
            assertFalse(result.sourceStabilityEstablished)
            assertFalse(result.componentCompletenessEstablished)
            // Successful completion includes verified exact-row deletion; no row/URI escapes.
        } finally { assertEquals(OriginalProbeCleanup.CLEAN, probe.settle()) }
    }
}
