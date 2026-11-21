package com.sunshine.mesh.core.net

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTimeoutTest {

    @Test
    fun closesLinkWhenConnectExceedsDeadline() = runTest {
        var closed = false

        val failure = runCatching {
            withLinkDeadline(
                timeoutMs = MeshPolicy.BLE_CONNECT_TIMEOUT_MS,
                forceClose = { closed = true },
            ) {
                awaitCancellation()
            }
        }.exceptionOrNull()

        assertTrue(failure is LinkTimeoutException)
        assertTrue(closed)
    }

    @Test
    fun doesNotCloseLinkWhenConnectCompletes() = runTest {
        var closed = false

        val value = withLinkDeadline(
            timeoutMs = MeshPolicy.BLE_CONNECT_TIMEOUT_MS,
            forceClose = { closed = true },
        ) {
            42
        }

        assertEquals(42, value)
        assertFalse(closed)
    }

    @Test
    fun keepsTimeoutWhenForcedCloseFails() = runTest {
        val failure = runCatching {
            withLinkDeadline(
                timeoutMs = MeshPolicy.BLE_CONNECT_TIMEOUT_MS,
                forceClose = { throw IllegalStateException("close failed") },
            ) {
                awaitCancellation()
            }
        }.exceptionOrNull()

        assertTrue(failure is LinkTimeoutException)
        assertEquals(1, failure?.suppressed?.size)
    }
}
