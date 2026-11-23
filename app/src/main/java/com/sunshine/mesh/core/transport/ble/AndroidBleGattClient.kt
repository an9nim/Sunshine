package com.sunshine.mesh.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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

/**
 * Android GATT client for one connection. Every GATT operation is serialized
 * by [gattMutex]; callbacks never acquire it. They only complete the pending
 * [CompletableDeferred] and emit events, so an operation waiting for its
 * callback cannot deadlock against the callback itself.
 */
@SuppressLint("MissingPermission")
class AndroidBleGattClient(
    context: Context,
    private val address: String,
    private val prerequisites: BlePrerequisites,
) : BleGattClient {

    private val appContext = context.applicationContext

    private val eventChannel = Channel<BleGattClientEvent>(capacity = EVENT_QUEUE_CAPACITY)
    override val events: Flow<BleGattClientEvent> = eventChannel.receiveAsFlow()

    private val gattMutex = Mutex()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var rx: BluetoothGattCharacteristic? = null

    @Volatile
    private var tx: BluetoothGattCharacteristic? = null

    @Volatile
    private var cccd: BluetoothGattDescriptor? = null

    @Volatile
    private var currentMtu = BleProtocol.DEFAULT_MTU
    override val mtu: Int get() = currentMtu

    @Volatile
    private var connected = false

    @Volatile
    private var indicationsEnabled = false

    @Volatile
    private var closed = false

    @Volatile
    private var pendingConnect: CompletableDeferred<Unit>? = null

    @Volatile
    private var pendingDiscover: CompletableDeferred<Unit>? = null

    @Volatile
    private var pendingMtu: CompletableDeferred<Int>? = null

    @Volatile
    private var pendingDescriptorWrite: CompletableDeferred<Unit>? = null

    @Volatile
    private var pendingWrite: CompletableDeferred<Unit>? = null

    @Volatile
    private var pendingWriteValue: ByteArray? = null

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            if (gatt !== this@AndroidBleGattClient.gatt) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        connected = true
                        pendingConnect?.complete(Unit)
                        pendingConnect = null
                        eventChannel.trySend(BleGattClientEvent.Connected)
                    } else {
                        connected = false
                        pendingConnect?.completeExceptionally(
                            TransportException(TransportError.CONNECT_FAILED),
                        )
                        pendingConnect = null
                        eventChannel.trySend(
                            BleGattClientEvent.Disconnected(TransportError.CONNECT_FAILED),
                        )
                        closeGattInternal(disconnect = true)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = connected
                    connected = false
                    val error = if (status == BluetoothGatt.GATT_SUCCESS) {
                        null
                    } else {
                        TransportError.LINK_LOST
                    }
                    failPending(
                        error = error ?: TransportError.LINK_LOST,
                        connectError = TransportError.CONNECT_FAILED,
                    )
                    closeGattInternal(disconnect = false)
                    if (wasConnected) {
                        eventChannel.trySend(BleGattClientEvent.Disconnected(error))
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@AndroidBleGattClient.gatt) return
            val deferred = pendingDiscover ?: return
            pendingDiscover = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                deferred.completeExceptionally(
                    TransportException(TransportError.SERVICE_DISCOVERY_FAILED),
                )
                return
            }
            val service = gatt.getService(BleUuids.SERVICE)
            val rx = service?.getCharacteristic(BleUuids.RX)
            val tx = service?.getCharacteristic(BleUuids.TX)
            val cccd = tx?.getDescriptor(BleUuids.CCCD)
            if (rx == null ||
                tx == null ||
                cccd == null ||
                rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
                tx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0
            ) {
                deferred.completeExceptionally(
                    TransportException(TransportError.SERVICE_DISCOVERY_FAILED),
                )
                return
            }
            this@AndroidBleGattClient.rx = rx
            this@AndroidBleGattClient.tx = tx
            this@AndroidBleGattClient.cccd = cccd
            deferred.complete(Unit)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== this@AndroidBleGattClient.gatt) return
            val deferred = pendingMtu ?: return
            pendingMtu = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu.coerceAtLeast(BleProtocol.MIN_MTU)
                deferred.complete(currentMtu)
                eventChannel.trySend(BleGattClientEvent.MtuChanged(currentMtu))
            } else {
                currentMtu = BleProtocol.DEFAULT_MTU
                deferred.complete(currentMtu)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (gatt !== this@AndroidBleGattClient.gatt) return
            if (descriptor.uuid != BleUuids.CCCD ||
                descriptor.characteristic?.uuid != BleUuids.TX
            ) {
                return
            }
            val deferred = pendingDescriptorWrite ?: return
            pendingDescriptorWrite = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                indicationsEnabled = true
                deferred.complete(Unit)
            } else {
                indicationsEnabled = false
                deferred.completeExceptionally(
                    TransportException(TransportError.PROTOCOL_ERROR),
                )
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt !== this@AndroidBleGattClient.gatt) return
            if (characteristic.uuid != BleUuids.RX) return
            val deferred = pendingWrite ?: return
            pendingWrite = null
            val value = pendingWriteValue
            pendingWriteValue = null
            value?.fill(0)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                deferred.complete(Unit)
            } else {
                deferred.completeExceptionally(
                    TransportException(TransportError.LINK_LOST),
                )
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(gatt, characteristic, value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // On API 33+ the framework calls the value-aware overload above.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            handleCharacteristicChanged(gatt, characteristic, characteristic.value)
        }
    }

    override suspend fun connect() {
        gattMutex.withLock {
            checkOpen()
            if (connected) return@withLock
            if (gatt != null) closeGattInternal(disconnect = true)

            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                throw TransportException(TransportError.CONNECT_FAILED)
            }
            prerequisites.requireConnectReady()
            val adapter = prerequisites.requireAdapter()
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (error: IllegalArgumentException) {
                throw TransportException(TransportError.CONNECT_FAILED, error)
            } catch (error: SecurityException) {
                throw TransportException(TransportError.PERMISSION_DENIED, error)
            }

            val deferred = CompletableDeferred<Unit>()
            pendingConnect = deferred
            val gatt = try {
                device.connectGatt(
                    appContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                )
            } catch (error: SecurityException) {
                pendingConnect = null
                throw TransportException(TransportError.PERMISSION_DENIED, error)
            } catch (error: IllegalArgumentException) {
                pendingConnect = null
                throw TransportException(TransportError.CONNECT_FAILED, error)
            }
            if (gatt == null) {
                pendingConnect = null
                throw TransportException(TransportError.CONNECT_FAILED)
            }
            this.gatt = gatt

            try {
                withTimeout(BleProtocol.CONNECT_TIMEOUT_MS) { deferred.await() }
            } catch (error: TimeoutCancellationException) {
                closeGattInternal(disconnect = true)
                throw TransportException(TransportError.CONNECT_TIMEOUT, error)
            } catch (error: TransportException) {
                closeGattInternal(disconnect = true)
                throw error
            } finally {
                if (pendingConnect === deferred) pendingConnect = null
            }
        }
    }

    override suspend fun discoverServices() {
        gattMutex.withLock {
            checkConnected()
            if (rx != null && tx != null && cccd != null) return@withLock
            val gatt = gatt ?: throw TransportException(TransportError.NOT_CONNECTED)

            val deferred = CompletableDeferred<Unit>()
            pendingDiscover = deferred
            val started = try {
                gatt.discoverServices()
            } catch (error: SecurityException) {
                pendingDiscover = null
                throw TransportException(TransportError.PERMISSION_DENIED, error)
            }
            if (!started) {
                pendingDiscover = null
                throw TransportException(TransportError.SERVICE_DISCOVERY_FAILED)
            }

            try {
                withTimeout(BleProtocol.GATT_OPERATION_TIMEOUT_MS) { deferred.await() }
            } catch (error: TimeoutCancellationException) {
                throw TransportException(TransportError.SERVICE_DISCOVERY_FAILED, error)
            } finally {
                if (pendingDiscover === deferred) pendingDiscover = null
            }
        }
    }

    override suspend fun requestMtu(requested: Int): Int =
        gattMutex.withLock {
            checkConnected()
            val gatt = gatt ?: throw TransportException(TransportError.NOT_CONNECTED)
            val target = requested.coerceIn(BleProtocol.MIN_MTU, BleProtocol.REQUESTED_MTU)

            val deferred = CompletableDeferred<Int>()
            pendingMtu = deferred
            val started = try {
                gatt.requestMtu(target)
            } catch (error: SecurityException) {
                pendingMtu = null
                throw TransportException(TransportError.PERMISSION_DENIED, error)
            }
            if (!started) {
                pendingMtu = null
                currentMtu = BleProtocol.DEFAULT_MTU
                return@withLock BleProtocol.DEFAULT_MTU
            }

            try {
                withTimeout(BleProtocol.MTU_TIMEOUT_MS) { deferred.await() }
            } catch (error: TimeoutCancellationException) {
                currentMtu = BleProtocol.DEFAULT_MTU
                BleProtocol.DEFAULT_MTU
            } finally {
                if (pendingMtu === deferred) pendingMtu = null
            }
        }

    override suspend fun enableIndications() {
        gattMutex.withLock {
            checkConnected()
            if (indicationsEnabled) return@withLock
            val gatt = gatt ?: throw TransportException(TransportError.NOT_CONNECTED)
            val tx = tx ?: throw TransportException(TransportError.SERVICE_DISCOVERY_FAILED)
            val cccd = cccd ?: throw TransportException(TransportError.SERVICE_DISCOVERY_FAILED)

            val notificationEnabled = try {
                gatt.setCharacteristicNotification(tx, true)
            } catch (error: SecurityException) {
                throw TransportException(TransportError.PERMISSION_DENIED, error)
            }
            if (!notificationEnabled) {
                throw TransportException(TransportError.PROTOCOL_ERROR)
            }

            val deferred = CompletableDeferred<Unit>()
            pendingDescriptorWrite = deferred
            val started = try {
                writeDescriptorCompat(
                    gatt,
                    cccd,
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE.copyOf(),
                )
            } catch (error: SecurityException) {
                pendingDescriptorWrite = null
                throw TransportException(TransportError.PERMISSION_DENIED, error)
            }
            if (!started) {
                pendingDescriptorWrite = null
                throw TransportException(TransportError.PROTOCOL_ERROR)
            }

            try {
                withTimeout(BleProtocol.GATT_OPERATION_TIMEOUT_MS) { deferred.await() }
            } catch (error: TimeoutCancellationException) {
                throw TransportException(TransportError.PROTOCOL_ERROR, error)
            } finally {
                if (pendingDescriptorWrite === deferred) pendingDescriptorWrite = null
            }
        }
    }

    override suspend fun write(frame: ByteArray) {
        gattMutex.withLock {
            checkConnected()
            if (frame.isEmpty() || frame.size > BleProtocol.MAX_ATTRIBUTE_BYTES) {
                throw TransportException(TransportError.PACKET_TOO_LARGE)
            }
            val gatt = gatt ?: throw TransportException(TransportError.NOT_CONNECTED)
            val rx = rx ?: throw TransportException(TransportError.SERVICE_DISCOVERY_FAILED)

            val deferred = CompletableDeferred<Unit>()
            pendingWrite = deferred
            val value = frame.copyOf()
            pendingWriteValue = value
            val started = try {
                writeCharacteristicCompat(gatt, rx, value)
            } catch (error: SecurityException) {
                pendingWrite = null
                pendingWriteValue = null
                value.fill(0)
                throw TransportException(TransportError.PERMISSION_DENIED, error)
            }
            if (!started) {
                pendingWrite = null
                pendingWriteValue = null
                value.fill(0)
                throw TransportException(TransportError.LINK_LOST)
            }

            try {
                withTimeout(BleProtocol.GATT_OPERATION_TIMEOUT_MS) { deferred.await() }
            } catch (error: TimeoutCancellationException) {
                throw TransportException(TransportError.TRANSFER_TIMEOUT, error)
            } finally {
                if (pendingWrite === deferred) {
                    pendingWrite = null
                    pendingWriteValue = null
                    value.fill(0)
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        failPending(
            error = TransportError.LINK_LOST,
            connectError = TransportError.INTERNAL,
        )
        closeGattInternal(disconnect = true)
        eventChannel.close()
    }

    private fun handleCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
    ) {
        if (gatt !== this.gatt) return
        if (characteristic.uuid != BleUuids.TX) return
        if (value == null ||
            value.isEmpty() ||
            value.size > BleProtocol.MAX_ATTRIBUTE_BYTES
        ) {
            fail(TransportError.PROTOCOL_ERROR)
            return
        }
        val copy = value.copyOf()
        val event = BleGattClientEvent.FrameReceived(copy)
        if (eventChannel.trySend(event).isFailure) {
            copy.fill(0)
            fail(TransportError.BUFFER_OVERFLOW)
        }
    }

    private fun fail(error: TransportError) {
        if (closed) return
        connected = false
        failPending(error)
        closeGattInternal(disconnect = true)
        eventChannel.trySend(BleGattClientEvent.Failed(error))
    }

    private fun failPending(
        error: TransportError,
        connectError: TransportError = error,
    ) {
        pendingConnect?.completeExceptionally(TransportException(connectError))
        pendingConnect = null
        pendingDiscover?.completeExceptionally(TransportException(error))
        pendingDiscover = null
        pendingDescriptorWrite?.completeExceptionally(TransportException(error))
        pendingDescriptorWrite = null
        pendingWrite?.completeExceptionally(TransportException(error))
        pendingWrite = null
        pendingMtu?.complete(BleProtocol.DEFAULT_MTU)
        pendingMtu = null
        pendingWriteValue?.fill(0)
        pendingWriteValue = null
    }

    private fun closeGattInternal(disconnect: Boolean) {
        val current = gatt
        gatt = null
        rx = null
        tx = null
        cccd = null
        connected = false
        indicationsEnabled = false
        if (current == null) return
        if (disconnect) {
            try {
                current.disconnect()
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
            }
        }
        try {
            current.close()
        } catch (_: SecurityException) {
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }

    private fun checkOpen() {
        if (closed) throw TransportException(TransportError.INTERNAL)
    }

    private fun checkConnected() {
        checkOpen()
        if (!connected || gatt == null) {
            throw TransportException(TransportError.NOT_CONNECTED)
        }
    }

    private companion object {
        const val EVENT_QUEUE_CAPACITY = 64
    }
}

class AndroidBleGattClientFactory(
    context: Context,
    private val prerequisites: BlePrerequisites,
) : BleGattClientFactory {

    private val appContext = context.applicationContext

    override fun create(address: String): BleGattClient =
        AndroidBleGattClient(appContext, address, prerequisites)
}
