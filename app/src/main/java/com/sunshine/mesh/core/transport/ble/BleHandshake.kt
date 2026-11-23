package com.sunshine.mesh.core.transport.ble

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException

/**
 * Pure handshake validation. Peer ids are fixed-length lowercase hex, so
 * lexicographic order is the same as numeric byte order: the lower peer id
 * must be the central, the higher one the peripheral.
 */
object BleHandshake {

    fun checkCentralRole(localPeerId: PeerId, remotePeerId: PeerId) {
        checkNotSelf(localPeerId, remotePeerId)
        if (localPeerId.value >= remotePeerId.value) {
            throw protocolError("central peerId must be lower than remote peerId")
        }
    }

    fun validateHello(hello: BleFrame.Hello, localPeerId: PeerId) {
        checkVersion(hello.version)
        checkNotSelf(localPeerId, hello.peerId)
        if (localPeerId.value <= hello.peerId.value) {
            throw protocolError("peripheral peerId must be higher than remote peerId")
        }
    }

    fun validateHelloAck(
        ack: BleFrame.HelloAck,
        localPeerId: PeerId,
        remotePeerId: PeerId,
    ) {
        checkVersion(ack.version)
        checkNotSelf(localPeerId, ack.peerId)
        if (ack.peerId != remotePeerId) {
            throw protocolError("HELLO_ACK peerId does not match the advertised peer")
        }
        if (localPeerId.value >= remotePeerId.value) {
            throw protocolError("central peerId must be lower than remote peerId")
        }
    }

    private fun checkVersion(version: Int) {
        if (version != BleProtocol.VERSION) {
            throw protocolError("unsupported protocol version")
        }
    }

    private fun checkNotSelf(localPeerId: PeerId, remotePeerId: PeerId) {
        if (localPeerId == remotePeerId) {
            throw protocolError("cannot link to self")
        }
    }

    private fun protocolError(message: String): TransportException =
        TransportException(TransportError.PROTOCOL_ERROR, IllegalArgumentException(message))
}
