package org.kalc.ecoroute

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal interface DrivingLocationSource {
    fun fixes(): Flow<DrivingFix>
    fun nowMillis(): Long
}

/** Play-services independent foreground subscription; collection cancellation removes listeners. */
internal class AndroidDrivingLocationSource(context: Context) : DrivingLocationSource {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    override fun nowMillis() = SystemClock.elapsedRealtime()

    override fun fixes(): Flow<DrivingFix> = callbackFlow {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw LocationServiceException("Precise location is needed for driving mode. Enable it in app permissions.")
        }
        if (!LocationManagerCompat.isLocationEnabled(manager)) throw LocationDisabledException()
        // Prefer the hardware GPS: this works on phones without Google Play services.
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER)
            .firstOrNull { manager.getProviders(true).contains(it) } ?: throw LocationUnavailableException()
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                if (!location.hasAccuracy()) return
                val speed = location.speed.toDouble().takeIf {
                    location.hasSpeed() && it.isFinite() && it in 0.0..100.0 &&
                        (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 3f)
                }?.times(3.6)
                val bearing = location.bearing.toDouble().takeIf {
                    location.hasBearing() && it.isFinite() &&
                        (!location.hasBearingAccuracy() || location.bearingAccuracyDegrees <= 30f)
                }
                trySend(DrivingFix(location.latitude, location.longitude, location.accuracy,
                    location.elapsedRealtimeNanos / 1_000_000, speed, bearing))
            }
            override fun onProviderDisabled(provider: String) { close(LocationDisabledException()) }
        }
        try {
            LocationManagerCompat.requestLocationUpdates(manager, provider,
                LocationRequestCompat.Builder(1_000L).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(500L).setMinUpdateDistanceMeters(0f).build(),
                ContextCompat.getMainExecutor(app), listener)
        } catch (error: SecurityException) {
            manager.removeUpdates(listener)
            throw LocationPermissionException()
        }
        awaitClose { manager.removeUpdates(listener) }
    }
}
