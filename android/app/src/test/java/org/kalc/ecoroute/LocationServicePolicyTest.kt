package org.kalc.ecoroute

import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationServicePolicyTest {
    @Test
    fun `providers prefer framework fused then gps then network`() {
        val enabled = setOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, "other")
        assertEquals(
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
            currentLocationProviderPriority(enabled),
        )
    }

    @Test
    fun `fresh finite fix with reported accuracy is usable`() {
        assertTrue(isUsableCurrentLocation(45.5, 13.6, 25f, true, 2_000))
    }

    @Test
    fun `stale inaccurate and malformed fixes are rejected`() {
        assertFalse(isUsableCurrentLocation(45.5, 13.6, 25f, true, 30_001))
        assertFalse(isUsableCurrentLocation(45.5, 13.6, 0f, false, 1_000))
        assertFalse(isUsableCurrentLocation(Double.NaN, 13.6, 25f, true, 1_000))
        assertFalse(isUsableCurrentLocation(91.0, 13.6, 25f, true, 1_000))
    }
}
