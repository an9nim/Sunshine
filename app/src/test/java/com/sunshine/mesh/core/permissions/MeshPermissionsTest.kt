package com.sunshine.mesh.core.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshPermissionsTest {

    @Test
    fun api24RequestsFineAndCoarseLocation() {
        val permissions = MeshPermissions.discoveryPermissions(24)

        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            permissions,
        )
    }

    @Test
    fun api24BleScanUsesFineLocationOnly() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            MeshPermissions.bleScanPermissions(24),
        )
        assertTrue(MeshPermissions.bleAdvertisePermissions(24).isEmpty())
        assertTrue(MeshPermissions.bleConnectPermissions(24).isEmpty())
    }

    @Test
    fun api30StillUsesFineLocation() {
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            MeshPermissions.bleScanPermissions(30),
        )
    }

    @Test
    fun api31AddsBluetoothAndKeepsLocationForWifiDirect() {
        val permissions = MeshPermissions.discoveryPermissions(31)

        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
    }

    @Test
    fun api31BlePermissionsAreSplitByOperation() {
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN),
            MeshPermissions.bleScanPermissions(31),
        )
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE),
            MeshPermissions.bleAdvertisePermissions(31),
        )
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_CONNECT),
            MeshPermissions.bleConnectPermissions(31),
        )
    }

    @Test
    fun api33UsesNearbyDevicesWithoutLocation() {
        val permissions = MeshPermissions.discoveryPermissions(33)

        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
    }

    @Test
    fun api34KeepsAdvertisePermission() {
        val permissions = MeshPermissions.discoveryPermissions(34)

        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in permissions)
    }

    @Test
    fun api30HasNoForegroundServiceRuntimePermissions() {
        assertTrue(MeshPermissions.foregroundServicePermissions(30).isEmpty())
    }

    @Test
    fun api33RequiresPostNotifications() {
        val permissions = MeshPermissions.foregroundServicePermissions(33)

        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), permissions)
    }

    @Test
    fun api34AddsConnectedDeviceFgsPermission() {
        val permissions = MeshPermissions.foregroundServicePermissions(34)

        assertTrue(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
    }
}
