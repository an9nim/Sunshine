package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BleHandshakeTest {

    private val low = PeerId.parse("0000000000000001")
    private val high = PeerId.parse("ffffffffffffffff")

    @Test
    fun centralRoleAcceptsLowerLocal() {
        BleHandshake.checkCentralRole(localPeerId = low, remotePeerId = high)
    }

    @Test
    fun centralRoleRejectsHigherLocal() {
        assertProtocolError { BleHandshake.checkCentralRole(high, low) }
    }

    @Test
    fun centralRoleRejectsSelf() {
        assertProtocolError { BleHandshake.checkCentralRole(low, low) }
    }

    @Test
    fun helloAcceptsHigherLocal() {
        BleHandshake.validateHello(BleFrame.Hello(BleProtocol.VERSION, low), high)
    }

    @Test
    fun helloRejectsLowerLocal() {
        assertProtocolError {
            BleHandshake.validateHello(BleFrame.Hello(BleProtocol.VERSION, high), low)
        }
    }

    @Test
    fun helloRejectsUnsupportedVersion() {
        assertProtocolError {
            BleHandshake.validateHello(BleFrame.Hello(BleProtocol.VERSION + 1, low), high)
        }
    }

    @Test
    fun helloRejectsSelf() {
        assertProtocolError {
            BleHandshake.validateHello(BleFrame.Hello(BleProtocol.VERSION, high), high)
        }
    }

    @Test
    fun helloAckAcceptsMatchingPeer() {
        BleHandshake.validateHelloAck(BleFrame.HelloAck(BleProtocol.VERSION, high), low, high)
    }

    @Test
    fun helloAckRejectsPeerMismatch() {
        assertProtocolError {
            BleHandshake.validateHelloAck(BleFrame.HelloAck(BleProtocol.VERSION, low), low, high)
        }
    }

    @Test
    fun helloAckRejectsUnsupportedVersion() {
        assertProtocolError {
            BleHandshake.validateHelloAck(
                BleFrame.HelloAck(BleProtocol.VERSION + 1, high),
                low,
                high,
            )
        }
    }

    private fun assertProtocolError(block: () -> Unit) {
        try {
            block()
            fail("expected TransportException(PROTOCOL_ERROR)")
        } catch (error: TransportException) {
            assertEquals(TransportError.PROTOCOL_ERROR, error.code)
        }
    }
}
