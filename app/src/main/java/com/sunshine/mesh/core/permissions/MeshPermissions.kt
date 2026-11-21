package com.sunshine.mesh.core.permissions

import android.Manifest

object MeshPermissions {

    /**
     * Android 13 replaced location with nearby-device permissions, but Wi-Fi
     * Direct on API 31-32 still needs fine location. API 24-30 has no runtime
     * Bluetooth permission, so location is the only prompt there.
     */
    fun discoveryPermissions(sdkInt: Int): List<String> = buildList {
        if (sdkInt <= 32) {
            // Android 12+ requires FINE and COARSE to be requested together.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (sdkInt >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (sdkInt >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }
}
