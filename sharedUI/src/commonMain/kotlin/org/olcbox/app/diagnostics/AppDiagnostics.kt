package org.olcbox.app.diagnostics

import kotlinx.coroutines.CancellationException

/** Only fixed operation names and exception structure; never messages, profiles or traffic. */
object AppDiagnostics {
    var receiver: ((String, Throwable?) -> Unit)? = null

    fun record(operation: String, error: Throwable? = null) {
        if (error is CancellationException) return
        try { receiver?.invoke(operation, error) } catch (_: Throwable) { }
    }
}
