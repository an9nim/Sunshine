package com.sunshine.mesh.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionTest {

    @Test
    fun newerPatchIsDetected() {
        assertEquals(true, isNewerVersion("v0.1.1", "0.1.0"))
    }

    @Test
    fun newerMinorIsDetected() {
        assertEquals(true, isNewerVersion("0.2.0", "0.1.9"))
    }

    @Test
    fun newerMajorIsDetected() {
        assertEquals(true, isNewerVersion("v1.0.0", "0.9.9"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertEquals(false, isNewerVersion("v0.1.0", "0.1.0"))
    }

    @Test
    fun olderVersionIsNotNewer() {
        assertEquals(false, isNewerVersion("v0.0.9", "0.1.0"))
    }

    @Test
    fun prereleaseSuffixIsIgnored() {
        assertEquals(true, isNewerVersion("v0.2.0-beta.1", "0.1.0"))
    }

    @Test
    fun shortVersionIsPadded() {
        assertEquals(true, isNewerVersion("v0.2", "0.1.9"))
    }

    @Test
    fun unreadableVersionReturnsNull() {
        assertNull(isNewerVersion("release-latest", "0.1.0"))
    }
}
