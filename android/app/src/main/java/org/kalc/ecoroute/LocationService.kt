package org.kalc.ecoroute

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class CurrentLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMillis: Long,
    val approximate: Boolean,
)

open class LocationServiceException(message: String) : Exception(message)
class LocationPermissionException : LocationServiceException("Location permission is required.")
class LocationDisabledException : LocationServiceException("Turn on device location and try again.")
class LocationUnavailableException : LocationServiceException("A fresh location fix was not available. Try again outdoors.")

class LocationService(
    context: Context,
    private val locationManager: LocationManager = context.getSystemService(LocationManager::class.java),
    private val executor: Executor = ContextCompat.getMainExecutor(context),
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val appContext = context.applicationContext

    suspend fun getCurrentLocation(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): CurrentLocation {
        require(timeoutMillis > 0) { "Location timeout must be positive." }
        val hasCoarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!hasCoarse && !hasFine) throw LocationPermissionException()
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) throw LocationDisabledException()

        val providers = currentLocationProviderPriority(
            locationManager.getProviders(true).toSet(),
        )
        if (providers.isEmpty()) throw LocationUnavailableException()

        val deadline = elapsedRealtimeMillis() + timeoutMillis
        for ((index, provider) in providers.withIndex()) {
            val remainingMillis = deadline - elapsedRealtimeMillis()
            if (remainingMillis <= 0) break
            val providersRemaining = providers.size - index
            val providerBudget = (remainingMillis / providersRemaining).coerceAtLeast(1L)
            val location = withTimeoutOrNull(providerBudget) { requestCurrentLocation(provider) }
            if (location != null) {
                val ageMillis = (elapsedRealtimeMillis() - LocationCompat.getElapsedRealtimeMillis(location))
                    .coerceAtLeast(0L)
                if (isUsableCurrentLocation(
                        location.latitude,
                        location.longitude,
                        location.accuracy,
                        location.hasAccuracy(),
                        ageMillis,
                    )
                ) {
                    return CurrentLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy,
                        timestampMillis = location.time,
                        approximate = !hasFine,
                    )
                }
            }
        }
        throw LocationUnavailableException()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun requestCurrentLocation(provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(
                    locationManager,
                    provider,
                    cancellationSignal,
                    executor,
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (_: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val MAX_FIX_AGE_MS = 30_000L
    }
}

internal fun currentLocationProviderPriority(enabledProviders: Set<String>): List<String> =
    listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter(enabledProviders::contains)

internal fun isUsableCurrentLocation(
    latitude: Double,
    longitude: Double,
    accuracyMeters: Float,
    hasAccuracy: Boolean,
    ageMillis: Long,
): Boolean =
    latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        hasAccuracy && accuracyMeters.isFinite() && accuracyMeters >= 0f &&
        ageMillis in 0..LocationService.MAX_FIX_AGE_MS
