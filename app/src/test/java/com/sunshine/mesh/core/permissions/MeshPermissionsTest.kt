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
    fun api31AddsBluetoothAndKeepsLocationForWifiDirect() {
        val permissions = MeshPermissions.discoveryPermissions(31)

        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
    }

    @Test
    fun api33UsesNearbyDevicesWithoutLocation() {
        val permissions = MeshPermissions.discoveryPermissions(33)

        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
    }
}
