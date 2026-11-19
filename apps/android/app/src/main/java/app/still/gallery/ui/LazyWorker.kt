package app.still.gallery.ui

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Creates an unstarted child job and reports cancellation if its body never enters. */
internal fun CoroutineScope.launchLazyWithEntryFallback(
    onUnstartedCompletion: (Throwable) -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val entered = AtomicBoolean(false)
    val launched = launch(start = CoroutineStart.LAZY) {
        entered.set(true)
        block()
    }
    launched.invokeOnCompletion { cause ->
        if (cause != null && !entered.get()) onUnstartedCompletion(cause)
    }
    return launched
}
