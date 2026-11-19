package app.still.gallery.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy

/** Local function references can compare equal while capturing different focus entries. */
class DriftRibbonGate(initial: () -> Boolean) {
    private val current = mutableStateOf(initial, referentialEqualityPolicy())

    fun update(next: () -> Boolean) { current.value = next }
    operator fun invoke(): Boolean = current.value()
}
