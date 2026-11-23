package com.sunshine.mesh.core.permissions

import android.Manifest
import android.annotation.SuppressLint

@SuppressLint("InlinedApi")
object MeshPermissions {

    /**
     * Everything the discovery screen asks for today: BLE for Stage 3 and
     * Wi-Fi Direct for the later transport. BLE-specific callers should use
     * the narrower functions below so a missing Wi-Fi permission cannot block
     * scanning.
     */
    fun discoveryPermissions(sdkInt: Int): List<String> = buildList {
        addAll(blePermissions(sdkInt))
        if (sdkInt <= 32) {
            // Android 12+ requires FINE and COARSE to be requested together;
            // Wi-Fi Direct still uses location on API 31-32.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (sdkInt >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.distinct()

    /**
     * API 24-30 has no runtime Bluetooth permission, so fine location is the
     * only gate for a BLE scan. From API 31 the nearby-device permission is
     * used instead.
     */
    fun bleScanPermissions(sdkInt: Int): List<String> = buildList {
        if (sdkInt <= 30) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }

    fun bleAdvertisePermissions(sdkInt: Int): List<String> =
        if (sdkInt >= 31) {
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyList()
        }

    fun bleConnectPermissions(sdkInt: Int): List<String> =
        if (sdkInt >= 31) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }

    fun blePermissions(sdkInt: Int): List<String> =
        (
            bleScanPermissions(sdkInt) +
                bleAdvertisePermissions(sdkInt) +
                bleConnectPermissions(sdkInt)
            ).distinct()

    /**
     * Permissions tied to the connected-device foreground service. The FGS
     * type permission is install-time on API 34+; POST_NOTIFICATIONS is the
     * runtime gate for the ongoing notification on API 33+.
     */
    fun foregroundServicePermissions(sdkInt: Int): List<String> = buildList {
        if (sdkInt >= 34) {
            add(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE)
        }
        if (sdkInt >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
