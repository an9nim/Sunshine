package com.sunshine.mesh.core.net

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class LinkTimeoutException(cause: Throwable) :
    Exception("Link connection timed out", cause)

/**
 * BLE and Wi-Fi Direct can leave a half-open handle when the remote device
 * vanishes. The caller supplies the only reliable close path so a timeout
 * releases the OS resource instead of waiting for the transport stack.
 */
suspend fun <T> withLinkDeadline(
    timeoutMs: Long = MeshPolicy.BLE_CONNECT_TIMEOUT_MS,
    forceClose: () -> Unit,
    block: suspend () -> T,
): T {
    require(timeoutMs > 0) { "timeoutMs must be positive" }
    try {
        return withTimeout(timeoutMs) { block() }
    } catch (timeout: TimeoutCancellationException) {
        val failure = LinkTimeoutException(timeout)
        try {
            forceClose()
        } catch (closeFailure: Exception) {
            // Attach to the exception the caller sees, not only its cause.
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}
