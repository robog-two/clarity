package net.robog.clarity.app.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One-line confirmations ("Sent to Dana.", "Rule saved. Constitution v2.") shown above the tab bar. */
class Toasts {
    private val flow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: Flow<String> = flow.asSharedFlow()

    fun show(message: String) {
        flow.tryEmit(message)
    }
}
