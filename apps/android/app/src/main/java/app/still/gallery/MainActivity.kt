package app.still.gallery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import app.still.gallery.security.AppLockController
import app.still.gallery.ui.AppLockRoot
import app.still.gallery.ui.StillTheme

class MainActivity : ComponentActivity() {
    private lateinit var appLock: AppLockController
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> appLock.onScreenOff()
                Intent.ACTION_USER_PRESENT -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus()) appLock.onForeground()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Loads the strict setting and applies window protection before any gallery composition.
        appLock = AppLockController(this, ::protectWindow)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(0xFF111315.toInt()),
        )
        ContextCompat.registerReceiver(this, screenEvents, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { StillTheme { AppLockRoot(appLock) } }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 29) appLock.onForeground()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            super.onTopResumedActivityChanged(isTopResumedActivity)
            if (isTopResumedActivity) appLock.onForeground() else appLock.onBackground()
        }
    }

    override fun onPause() {
        appLock.onBackground()
        super.onPause()
    }

    override fun onStop() {
        appLock.onBackground()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appLock.onExternalEntry()
    }

    override fun onDestroy() {
        appLock.dispose()
        unregisterReceiver(screenEvents)
        super.onDestroy()
    }

    private fun protectWindow(protected: Boolean) {
        if (protected) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!protected)
    }
}
