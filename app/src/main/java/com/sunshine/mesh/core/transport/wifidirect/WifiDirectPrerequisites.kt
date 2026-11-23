package com.sunshine.mesh.core.transport.wifidirect

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.sunshine.mesh.core.permissions.MeshPermissions
import com.sunshine.mesh.core.transport.TransportError
import com.sunshine.mesh.core.transport.TransportException

/**
 * Runtime gates for Wi-Fi Direct. Discovery needs fine location with location
 * services on API 24-32; from API 33 NEARBY_WIFI_DEVICES replaces it.
 */
interface WifiDirectGate {
    fun requireDiscoveryReady()
}

class WifiDirectPrerequisites(context: Context) : WifiDirectGate {

    private val appContext = context.applicationContext

    fun requireManager(): WifiP2pManager {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            throw TransportException(TransportError.WIFI_UNAVAILABLE)
        }
        val manager = appContext.getSystemService(WifiP2pManager::class.java)
            ?: throw TransportException(TransportError.WIFI_UNAVAILABLE)
        val wifi = appContext.getSystemService(WifiManager::class.java)
        val enabled = try {
            wifi?.isWifiEnabled ?: true
        } catch (error: SecurityException) {
            throw TransportException(TransportError.PERMISSION_DENIED, error)
        }
        if (!enabled) {
            throw TransportException(TransportError.WIFI_DISABLED)
        }
        return manager
    }

    override fun requireDiscoveryReady() {
        requireManager()
        val missing = MeshPermissions.wifiDirectPermissions(Build.VERSION.SDK_INT).any {
            ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            throw TransportException(TransportError.PERMISSION_DENIED)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 && !isLocationEnabled()) {
            throw TransportException(TransportError.LOCATION_DISABLED)
        }
    }

    private fun isLocationEnabled(): Boolean {
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return false
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
