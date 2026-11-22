package com.sunshine.mesh.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Android GATT server. All platform callbacks are translated into stable
 * [BleGattServerEvent]s; raw status codes and MAC addresses never reach logs.
 *
 * Indications are serialized per connection and confirmed by onNotificationSent,
 * not by the CCCD write callback.
 */
@SuppressLint("MissingPermission")
class AndroidBleGattServer(
    context: Context,
    private val prerequisites: BlePrerequisites,
) : BleGattServer {

    private val appContext = context.applicationContext

    private val eventChannel = Channel<BleGattServerEvent>(capacity = EVENT_QUEUE_CAPACITY)
    override val events: Flow<BleGattServerEvent> = eventChannel.receiveAsFlow()

    private val openMutex = Mutex()
    private val connections = ConcurrentHashMap<BluetoothDevice, BleServerConnection>()
    private val nextConnectionId = AtomicLong(0L)

    @Volatile
    private var server: BluetoothGattServer? = null

    @Volatile
    private var tx: BluetoothGattCharacteristic? = null

    @Volatile
    private var pendingServiceAdded: CompletableDeferred<Unit>? = null

    @Volatile
    private var closed = false

    private val callback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val pending = pendingServiceAdded ?: return
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == BleUuids.SERVICE) {
                pending.complete(Unit)
            } else {
                pending.completeExceptionally(
                    TransportException(TransportError.GATT_SERVER_FAILED),
                )
            }
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int,
        ) {
            if (closed) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cancelDevice(device)
                        return
                    }
                    val connection = BleServerConnection(
                        id = nextConnectionId.incrementAndGet(),
                        device = device,
                    )
                    if (connections.putIfAbsent(device, connection) == null) {
                        eventChannel.trySend(BleGattServerEvent.Connected(connection))
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val connection = connections.remove(device) ?: return
                    val pending = connection.pendingIndication
                    connection.pendingIndication = null
                    pending?.completeExceptionally(
                        TransportException(TransportError.LINK_LOST),
                    )
                    eventChannel.trySend(
                        BleGattServerEvent.Disconnected(
                            connection = connection,
                            error = if (status == BluetoothGatt.GATT_SUCCESS) {
                                null
                            } else {
                                TransportError.LINK_LOST
                            },
                        ),
                    )
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (closed) return
            val connection = connections[device] ?: return
            connection.mtu = mtu.coerceAtLeast(BleProtocol.MIN_MTU)
            eventChannel.trySend(
                BleGattServerEvent.MtuChanged(connection, connection.mtu),
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor.uuid != BleUuids.CCCD ||
                descriptor.characteristic?.uuid != BleUuids.TX
            ) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, responseNeeded)
                return
            }
            val connection = connections[device]
            if (connection == null) {
                respond(device, requestId, BluetoothGatt.GATT_FAILURE, responseNeeded)
                return
            }
            if (preparedWrite || offset != 0) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, responseNeeded)
                return
            }
            if (value == null || value.size != BleCccd.VALUE_BYTES) {
                respond(device, requestId, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, responseNeeded)
                return
            }
            val enabled = BleCccd.isIndicationEnabled(value)
            if (enabled == null) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, responseNeeded)
                return
            }

            // Update state before the response: the central may send HELLO as
            // soon as it receives onDescriptorWrite.
            connection.indicationsEnabled = enabled
            if (!enabled) {
                val pending = connection.pendingIndication
                connection.pendingIndication = null
                pending?.completeExceptionally(
                    TransportException(TransportError.PROTOCOL_ERROR),
                )
            }
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, responseNeeded)
            eventChannel.trySend(
                BleGattServerEvent.IndicationsChanged(connection, enabled),
            )
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            if (descriptor.uuid != BleUuids.CCCD ||
                descriptor.characteristic?.uuid != BleUuids.TX
            ) {
                respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED)
                return
            }
            if (offset != 0) {
                respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
                return
            }
            val connection = connections[device]
            if (connection == null) {
                respond(device, requestId, BluetoothGatt.GATT_FAILURE)
                return
            }
            respondWithValue(
                device,
                requestId,
                BleCccd.indicationValue(connection.indicationsEnabled),
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic.uuid != BleUuids.RX) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, responseNeeded)
                return
            }
            if (preparedWrite || offset != 0) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, responseNeeded)
                return
            }
            val connection = connections[device]
            if (connection == null) {
                respond(device, requestId, BluetoothGatt.GATT_FAILURE, responseNeeded)
                return
            }
            if (value == null ||
                value.isEmpty() ||
                value.size > BleProtocol.MAX_ATTRIBUTE_BYTES
            ) {
                respond(device, requestId, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, responseNeeded)
                return
            }

            val copy = value.copyOf()
            val event = BleGattServerEvent.FrameReceived(connection, copy)
            if (eventChannel.trySend(event).isFailure) {
                copy.fill(0)
                respond(
                    device,
                    requestId,
                    GATT_INSUFFICIENT_RESOURCES,
                    responseNeeded,
                )
                return
            }
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, responseNeeded)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED)
        }

        override fun onExecuteWrite(
            device: BluetoothDevice,
            requestId: Int,
            execute: Boolean,
        ) {
            respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val connection = connections[device] ?: return
            val pending = connection.pendingIndication
            connection.pendingIndication = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pending?.complete(Unit)
            } else {
                pending?.completeExceptionally(
                    TransportException(TransportError.LINK_LOST),
                )
            }
        }
    }

    override suspend fun open() {
        openMutex.withLock {
            if (closed) throw TransportException(TransportError.INTERNAL)
            if (server != null) return@withLock

            prerequisites.requireConnectReady()
            val manager = appContext.getSystemService(BluetoothManager::class.java)
                ?: throw TransportException(TransportError.BLUETOOTH_UNAVAILABLE)
            val service = buildService()
            val txCharacteristic = service.getCharacteristic(BleUuids.TX)
                ?: throw TransportException(TransportError.GATT_SERVER_FAILED)

            val gattServer = try {
                manager.openGattServer(appContext, callback)
            } catch (error: SecurityException) {
                throw TransportException(TransportError.PERMISSION_DENIED, error)
            } ?: throw TransportException(TransportError.GATT_SERVER_FAILED)

            val added = CompletableDeferred<Unit>()
            pendingServiceAdded = added
            server = gattServer
            tx = txCharacteristic

            try {
                if (!gattServer.addService(service)) {
                    throw TransportException(TransportError.GATT_SERVER_FAILED)
                }
                withTimeout(BleProtocol.GATT_OPEN_TIMEOUT_MS) { added.await() }
                if (closed) throw TransportException(TransportError.INTERNAL)
            } catch (error: TimeoutCancellationException) {
                cleanupAfterOpenFailure(gattServer)
                throw TransportException(TransportError.GATT_SERVER_FAILED, error)
            } catch (error: TransportException) {
                cleanupAfterOpenFailure(gattServer)
                throw error
            } catch (error: SecurityException) {
                cleanupAfterOpenFailure(gattServer)
                throw TransportException(TransportError.PERMISSION_DENIED, error)
            } finally {
                if (pendingServiceAdded === added) pendingServiceAdded = null
            }
        }
    }

    override suspend fun indicate(
        connection: BleServerConnection,
        frame: ByteArray,
    ) {
        if (frame.isEmpty() || frame.size > BleProtocol.MAX_ATTRIBUTE_BYTES) {
            throw TransportException(TransportError.PACKET_TOO_LARGE)
        }

        connection.indicationMutex.withLock {
            if (closed) throw TransportException(TransportError.INTERNAL)
            if (!connection.indicationsEnabled) {
                throw TransportException(TransportError.PROTOCOL_ERROR)
            }
            if (connections[connection.device] !== connection) {
                throw TransportException(TransportError.NOT_CONNECTED)
            }
            val gattServer = server
                ?: throw TransportException(TransportError.NOT_CONNECTED)
            val txCharacteristic = tx
                ?: throw TransportException(TransportError.GATT_SERVER_FAILED)

            val pending = CompletableDeferred<Unit>()
            connection.pendingIndication = pending
            val value = frame.copyOf()
            try {
                val sent = try {
                    notifyIndication(gattServer, connection.device, txCharacteristic, value)
                } catch (error: SecurityException) {
                    throw TransportException(TransportError.PERMISSION_DENIED, error)
                }
                if (!sent) throw TransportException(TransportError.LINK_LOST)

                withTimeout(BleProtocol.INDICATION_TIMEOUT_MS) { pending.await() }
            } catch (error: TimeoutCancellationException) {
                cancelConnection(connection)
                throw TransportException(TransportError.TRANSFER_TIMEOUT, error)
            } finally {
                if (connection.pendingIndication === pending) {
                    connection.pendingIndication = null
                }
                value.fill(0)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true

        pendingServiceAdded?.completeExceptionally(
            TransportException(TransportError.INTERNAL),
        )
        pendingServiceAdded = null

        val current = server
        server = null
        tx = null

        val snapshot = connections.values.toList()
        connections.clear()
        snapshot.forEach { connection ->
            val pending = connection.pendingIndication
            connection.pendingIndication = null
            pending?.completeExceptionally(
                TransportException(TransportError.LINK_LOST),
            )
            if (current != null) {
                try {
                    current.cancelConnection(connection.device)
                } catch (_: SecurityException) {
                } catch (_: IllegalStateException) {
                }
            }
        }

        try {
            current?.close()
        } catch (_: SecurityException) {
        }
        eventChannel.close()
    }

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(
            BleUuids.SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val rx = BluetoothGattCharacteristic(
            BleUuids.RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            BleUuids.TX,
            BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val cccd = BluetoothGattDescriptor(
            BleUuids.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or
                BluetoothGattDescriptor.PERMISSION_WRITE,
        )

        if (!tx.addDescriptor(cccd) ||
            !service.addCharacteristic(rx) ||
            !service.addCharacteristic(tx)
        ) {
            throw TransportException(TransportError.GATT_SERVER_FAILED)
        }
        return service
    }

    private fun notifyIndication(
        gattServer: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer.notifyCharacteristicChanged(
                device,
                characteristic,
                true,
                value,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gattServer.notifyCharacteristicChanged(device, characteristic, true)
        }

    private fun respond(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        responseNeeded: Boolean = true,
    ) {
        if (!responseNeeded) return
        sendResponse(device, requestId, status, 0, null)
    }

    private fun respondWithValue(
        device: BluetoothDevice,
        requestId: Int,
        value: ByteArray,
    ) {
        sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
    }

    /**
     * API 33 replaced the four-argument sendResponse with a device-aware
     * overload; API 36 stubs only expose the new one. Older devices still have
     * the old method at runtime, so it is reached reflectively.
     */
    private fun sendResponse(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        val current = server ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                current.sendResponse(device, requestId, status, offset, value)
            } else {
                LEGACY_SEND_RESPONSE.invoke(
                    current,
                    requestId,
                    status,
                    offset,
                    value,
                )
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        } catch (_: IllegalArgumentException) {
        } catch (_: ReflectiveOperationException) {
        }
    }

    private fun cancelDevice(device: BluetoothDevice) {
        try {
            server?.cancelConnection(device)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun cancelConnection(connection: BleServerConnection) {
        Timber.w("BLE indication timed out: connection=%d", connection.id)
        cancelDevice(connection.device)
    }

    private fun cleanupAfterOpenFailure(gattServer: BluetoothGattServer) {
        if (server === gattServer) {
            server = null
            tx = null
        }
        try {
            gattServer.close()
        } catch (_: SecurityException) {
        }
    }

    private companion object {
        const val EVENT_QUEUE_CAPACITY = 64
        const val GATT_INSUFFICIENT_RESOURCES = 0x11

        val LEGACY_SEND_RESPONSE: Method by lazy {
            BluetoothGattServer::class.java.getMethod(
                "sendResponse",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                ByteArray::class.java,
            )
        }
    }
}
