package com.sunshine.mesh.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

/**
 * Android BLE advertiser. The payload is checked against the legacy 31-byte
 * limit before Android sees it, so a format regression fails with a stable
 * TransportError instead of ADVERTISE_FAILED_DATA_TOO_LARGE.
 */
class AndroidBleAdvertiser(
    context: Context,
    private val prerequisites: BlePrerequisites,
) : BleAdvertiser {

    private val eventChannel = Channel<BleAdvertiseEvent>(
        capacity = ADVERTISE_QUEUE_CAPACITY,
    )

    override val events: Flow<BleAdvertiseEvent> = eventChannel.receiveAsFlow()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var currentPeerId: PeerId? = null

    @Volatile
    private var advertising = false

    private var closed = false

    private val callback = object : AdvertiseCallback() {

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            val active = synchronized(this@AndroidBleAdvertiser) {
                advertising && !closed
            }
            if (active) {
                eventChannel.trySend(BleAdvertiseEvent.Started)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            val wasAdvertising = synchronized(this@AndroidBleAdvertiser) {
                val was = advertising
                advertising = false
                advertiser = null
                currentPeerId = null
                was
            }
            if (!wasAdvertising) return

            Timber.w("BLE advertising failed: errorCode=%d", errorCode)
            eventChannel.trySend(
                BleAdvertiseEvent.Failed(TransportError.ADVERTISE_FAILED),
            )
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start(peerId: PeerId) {
        if (closed) throw TransportException(TransportError.INTERNAL)
        if (advertising && currentPeerId == peerId) return

        stopInternal()
        prerequisites.requireAdvertiseReady()
        if (BleAdvertisementCodec.legacyBytes() > BleProtocol.LEGACY_ADV_BYTES) {
            throw TransportException(TransportError.ADVERTISE_FAILED)
        }

        val adapter: BluetoothAdapter = prerequisites.requireAdapter()
        val leAdvertiser = adapter.bluetoothLeAdvertiser
            ?: throw TransportException(TransportError.ADVERTISE_FAILED)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data: AdvertiseData = BleAdvertisingPayload.advertiseData(peerId)

        advertiser = leAdvertiser
        currentPeerId = peerId
        advertising = true
        try {
            leAdvertiser.startAdvertising(settings, data, callback)
        } catch (error: SecurityException) {
            resetAfterStartFailure()
            throw TransportException(TransportError.PERMISSION_DENIED, error)
        } catch (error: IllegalStateException) {
            resetAfterStartFailure()
            throw TransportException(TransportError.ADVERTISE_FAILED, error)
        } catch (error: IllegalArgumentException) {
            resetAfterStartFailure()
            throw TransportException(TransportError.ADVERTISE_FAILED, error)
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
        val current = advertiser
        advertiser = null
        currentPeerId = null
        advertising = false
        if (current == null) return

        try {
            current.stopAdvertising(callback)
        } catch (_: SecurityException) {
            // Permission may have been revoked; advertising is already stopped.
        } catch (_: IllegalStateException) {
            // Adapter is off; there is nothing to stop.
        }
    }

    private fun resetAfterStartFailure() {
        advertiser = null
        currentPeerId = null
        advertising = false
    }

    private companion object {
        const val ADVERTISE_QUEUE_CAPACITY = 8
    }
}
