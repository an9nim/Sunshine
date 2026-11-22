package com.sunshine.mesh.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.sunshine.mesh.core.permissions.MeshPermissions
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException

/**
 * Runtime gates shared by the BLE adapters. Permission requests are made by the
 * UI; this class only verifies that the transport may touch the radio.
 */
class BlePrerequisites(context: Context) {

    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    fun requireAdapter(): BluetoothAdapter {
        val manager = appContext.getSystemService(BluetoothManager::class.java)
            ?: throw TransportException(TransportError.BLUETOOTH_UNAVAILABLE)
        val adapter = manager.adapter
            ?: throw TransportException(TransportError.BLUETOOTH_UNAVAILABLE)

        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            throw TransportException(TransportError.BLUETOOTH_UNAVAILABLE)
        }

        val enabled = try {
            adapter.isEnabled
        } catch (error: SecurityException) {
            throw TransportException(TransportError.PERMISSION_DENIED, error)
        }
        if (!enabled) {
            throw TransportException(TransportError.BLUETOOTH_DISABLED)
        }
        return adapter
    }

    fun requireScanReady() {
        requireAdapter()
        requireGranted(MeshPermissions.bleScanPermissions(Build.VERSION.SDK_INT))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !isLocationEnabled()) {
            throw TransportException(TransportError.LOCATION_DISABLED)
        }
    }

    fun requireAdvertiseReady() {
        requireAdapter()
        requireGranted(MeshPermissions.bleAdvertisePermissions(Build.VERSION.SDK_INT))
    }

    fun requireConnectReady() {
        requireAdapter()
        requireGranted(MeshPermissions.bleConnectPermissions(Build.VERSION.SDK_INT))
    }

    private fun requireGranted(permissions: List<String>) {
        val missing = permissions.any { permission ->
            ContextCompat.checkSelfPermission(appContext, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            throw TransportException(TransportError.PERMISSION_DENIED)
        }
    }

    private fun isLocationEnabled(): Boolean {
        val manager = appContext.getSystemService(LocationManager::class.java)
            ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: SecurityException) {
            false
        }
    }
}
