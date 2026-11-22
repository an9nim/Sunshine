package com.sunshine.mesh.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

/**
 * Android BLE scanner. Permission and location checks happen before
 * [startScan]; otherwise Android silently delivers no callbacks on API 24-30.
 */
class AndroidBleScanner(
    context: Context,
    private val prerequisites: BlePrerequisites,
) : BleScanner {

    private val eventChannel = Channel<BleScanEvent>(
        capacity = SCAN_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<BleScanEvent> = eventChannel.receiveAsFlow()

    private var scanner: BluetoothLeScanner? = null

    @Volatile
    private var scanning = false

    private var closed = false

    private val callback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val peerId = BleAdvertisingPayload.peerIdFrom(result.scanRecord) ?: return
            val address = try {
                result.device.address
            } catch (_: SecurityException) {
                return
            }
            eventChannel.trySend(
                BleScanEvent.Found(
                    BleAdvertisement(
                        peerId = peerId,
                        address = address,
                        rssi = result.rssi,
                    ),
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            if (!scanning) return
            scanning = false
            Timber.w("BLE scan failed: errorCode=%d", errorCode)
            eventChannel.trySend(BleScanEvent.Failed(TransportError.SCAN_FAILED))
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start() {
        if (closed) throw TransportException(TransportError.INTERNAL)
        if (scanning) return

        prerequisites.requireScanReady()
        val adapter: BluetoothAdapter = prerequisites.requireAdapter()
        val leScanner = adapter.bluetoothLeScanner
            ?: throw TransportException(TransportError.BLUETOOTH_UNAVAILABLE)

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleUuids.SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0L)
            .build()

        scanner = leScanner
        scanning = true
        try {
            leScanner.startScan(listOf(filter), settings, callback)
        } catch (error: SecurityException) {
            scanner = null
            scanning = false
            throw TransportException(TransportError.PERMISSION_DENIED, error)
        } catch (error: IllegalStateException) {
            scanner = null
            scanning = false
            throw TransportException(TransportError.BLUETOOTH_DISABLED, error)
        } catch (error: IllegalArgumentException) {
            scanner = null
            scanning = false
            throw TransportException(TransportError.SCAN_FAILED, error)
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun stop() {
        stopInternal()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        stopInternal()
        eventChannel.close()
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        val current = scanner ?: return
        scanner = null
        scanning = false
        try {
            current.stopScan(callback)
        } catch (_: SecurityException) {
            // Permission may have been revoked; the scan is already stopped.
        } catch (_: IllegalStateException) {
            // Adapter is off; there is nothing to stop.
        }
    }

    private companion object {
        const val SCAN_QUEUE_CAPACITY = 64
    }
}
