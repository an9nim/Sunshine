package com.sunshine.mesh.core.transport.wifidirect

import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.io.Closeable

/**
 * Length-prefix framing: 4 bytes big-endian length, then exactly that many
 * payload bytes. Shared by the handshake and by data frames.
 */
internal object WifiDirectFraming {

    fun writeFrame(output: OutputStream, payload: ByteArray) {
        val length = payload.size
        output.write(
            byteArrayOf(
                (length ushr 24).toByte(),
                (length ushr 16).toByte(),
                (length ushr 8).toByte(),
                length.toByte(),
            ),
        )
        output.write(payload)
    }

    /** Returns null on clean EOF before a complete frame. */
    fun readFrame(input: InputStream, maxBytes: Int): ByteArray? {
        val prefix = ByteArray(WifiDirectProtocol.LENGTH_PREFIX_BYTES)
        if (!readFully(input, prefix)) return null
        val length = ((prefix[0].toInt() and 0xff) shl 24) or
            ((prefix[1].toInt() and 0xff) shl 16) or
            ((prefix[2].toInt() and 0xff) shl 8) or
            (prefix[3].toInt() and 0xff)
        if (length !in 1..maxBytes) {
            throw IOException("frame length out of range")
        }
        val frame = ByteArray(length)
        if (!readFully(input, frame)) {
            frame.fill(0)
            return null
        }
        return frame
    }

    fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }
}

/**
 * One TCP link. Reads whole frames into [incoming]; [send] is serialized by a
 * per-link Mutex so frames never interleave. EOF or IO failure closes the link.
 */
class WifiDirectSocketLink internal constructor(
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
    scope: CoroutineScope,
) : Closeable {

    private val linkJob = Job(scope.coroutineContext[Job])
    private val linkScope = CoroutineScope(scope.coroutineContext + linkJob)

    private val incomingChannel = Channel<ByteArray>(capacity = INCOMING_QUEUE_CAPACITY)
    val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()

    private val sendMutex = Mutex()

    @Volatile
    private var closed = false

    init {
        linkScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val frame = WifiDirectFraming.readFrame(
                        input,
                        WifiDirectProtocol.MAX_FRAME_BYTES,
                    ) ?: break
                    if (incomingChannel.trySend(frame).isFailure) {
                        frame.fill(0)
                        break
                    }
                }
            } catch (error: IOException) {
                // Remote vanished or sent a malformed frame; fall through to close.
            } finally {
                close()
            }
        }
    }

    suspend fun send(packet: ByteArray) {
        if (packet.isEmpty() || packet.size > WifiDirectProtocol.MAX_FRAME_BYTES) {
            throw TransportException(TransportError.PACKET_TOO_LARGE)
        }
        sendMutex.withLock {
            if (closed) throw TransportException(TransportError.NOT_CONNECTED)
            try {
                withContext(Dispatchers.IO) {
                    WifiDirectFraming.writeFrame(output, packet)
                    output.flush()
                }
            } catch (error: IOException) {
                close()
                throw TransportException(TransportError.LINK_LOST, error)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        incomingChannel.close()
        linkJob.cancel()
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val INCOMING_QUEUE_CAPACITY = 16
    }
}

class AcceptedWifiDirectLink(
    val peerId: PeerId,
    val link: WifiDirectSocketLink,
)

/**
 * GO side: accept one socket, read the 8-byte peerId handshake, then hand the
 * framed link over. The accept itself is bounded by the socket timeout.
 */
suspend fun acceptWifiDirectLink(
    serverSocket: ServerSocket,
    scope: CoroutineScope,
): AcceptedWifiDirectLink = withContext(Dispatchers.IO) {
    serverSocket.soTimeout = WifiDirectProtocol.SOCKET_TIMEOUT_MS.toInt()
    val socket = try {
        serverSocket.accept()
    } catch (error: SocketTimeoutException) {
        throw TransportException(TransportError.CONNECT_TIMEOUT, error)
    } catch (error: IOException) {
        throw TransportException(TransportError.CONNECT_FAILED, error)
    }
    try {
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val handshake = WifiDirectFraming.readFrame(
            input,
            WifiDirectProtocol.HANDSHAKE_BYTES,
        ) ?: throw TransportException(TransportError.LINK_LOST)
        if (handshake.size != WifiDirectProtocol.HANDSHAKE_BYTES) {
            throw TransportException(TransportError.PROTOCOL_ERROR)
        }
        val peerId = PeerId.fromBytes(handshake)
        val output = BufferedOutputStream(socket.getOutputStream())
        AcceptedWifiDirectLink(
            peerId,
            WifiDirectSocketLink(socket, input, output, scope),
        )
    } catch (error: TransportException) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
        throw error
    } catch (error: IOException) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
        throw TransportException(TransportError.CONNECT_FAILED, error)
    }
}

/**
 * Client side: connect with a 10-second deadline, send our peerId handshake,
 * then hand the framed link over.
 */
suspend fun connectWifiDirectLink(
    address: InetAddress,
    port: Int,
    localPeerId: PeerId,
    scope: CoroutineScope,
): WifiDirectSocketLink = withContext(Dispatchers.IO) {
    val socket = Socket()
    try {
        socket.connect(
            InetSocketAddress(address, port),
            WifiDirectProtocol.SOCKET_TIMEOUT_MS.toInt(),
        )
        socket.tcpNoDelay = true
        val output = BufferedOutputStream(socket.getOutputStream())
        WifiDirectFraming.writeFrame(output, localPeerId.toByteArray())
        output.flush()
        val input = BufferedInputStream(socket.getInputStream())
        WifiDirectSocketLink(socket, input, output, scope)
    } catch (error: IOException) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
        throw TransportException(TransportError.CONNECT_FAILED, error)
    }
}
