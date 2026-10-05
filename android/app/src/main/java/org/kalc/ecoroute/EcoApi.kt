package org.kalc.ecoroute

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface EcoService {
    suspend fun searchPlaces(query: String): List<Place>
    suspend fun registerCurrentPlace(latitude: Double, longitude: Double): Place =
        throw EcoApiException("This service does not support current location yet.")
    suspend fun searchVehicles(query: String, fuelType: String? = null, limit: Int = 30, offset: Int = 0) =
        VehicleCatalogueSearchResult(emptyList(), 0, false, "", null)
    suspend fun startPlan(originId: String, destinationId: String, vehicle: JSONObject, roundTrip: Boolean, timeAllowance: TimeAllowanceRequest): String
    suspend fun startPlan(
        originId: String,
        destinationId: String,
        vehicle: JSONObject,
        roundTrip: Boolean,
        timeAllowance: TimeAllowanceRequest,
        stops: List<String>,
    ): String {
        if (stops.isNotEmpty()) throw EcoApiException("This planning service does not support intermediate stops yet.")
        return startPlan(originId, destinationId, vehicle, roundTrip, timeAllowance)
    }
    suspend fun job(jobId: String): JobState
    suspend fun fuelPrice(): FuelPrice
    suspend fun calculateJourney(originId: String, destinationId: String, vehicle: JSONObject,
        roundTrip: Boolean, timeAllowance: TimeAllowanceRequest, stops: List<String>, progress: (String) -> Unit): String {
        val id = startPlan(originId, destinationId, vehicle, roundTrip, timeAllowance, stops)
        while (true) {
            val state = job(id)
            when (state.status) {
                "complete" -> return state.resultJson ?: throw EcoApiException("The calculation result is incomplete.")
                "error" -> throw EcoApiException(state.message)
                "running" -> progress(state.message)
                else -> throw EcoApiException("The calculation state is unknown.")
            }
            kotlinx.coroutines.delay(1000)
        }
    }
}

data class JobState(
    val status: String,
    val message: String,
    val resultJson: String? = null,
)

/** Direct public data, device-local catalogue and fuel optimization. */
class EcoApi(context: android.content.Context) : EcoService {
    private val online = PublicDataClient(context.applicationContext)
    private val planner = HybridPlanner(online)
    private val catalogue = LocalVehicleCatalogue(context.applicationContext)
    private val places = java.util.concurrent.ConcurrentHashMap<String, Place>()
    override suspend fun searchPlaces(query: String): List<Place> {
        require(query.trim().length in 3..180)
        val url = PublicDataClient.PHOTON + "?q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8") + "&limit=6&lang=en"
        val features = JSONObject(online.request(url, null, PublicDataClient.SEVEN_DAYS)).getJSONArray("features")
        val result = mutableListOf<Place>()
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i); val props = feature.getJSONObject("properties")
            val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
            if (coords.length() != 2) continue
            val lon = coords.getDouble(0); val lat = coords.getDouble(1)
            if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) continue
            fun text(key: String) = if (props.isNull(key)) "" else props.optString(key)
            val street = listOf(text("street"), text("housenumber")).filter(String::isNotBlank).joinToString(" ")
            val title = street.ifBlank { text("name").ifBlank { text("city") } }
            if (title.isBlank()) continue
            val parts = listOf(title, text("name"), text("city"), text("district"), text("state"), text("postcode"), text("country").ifBlank { text("countrycode").uppercase() }).filter(String::isNotBlank).distinct()
            val label = parts.joinToString(", ")
            if (result.any { it.label == label && RoadLoadModel.distance(GeoPoint(it.lat, it.lon), GeoPoint(lat, lon)) < 50 }) continue
            val p = Place(id = java.util.UUID.nameUUIDFromBytes("$lat,$lon,$label".toByteArray()).toString(), lat = lat, lon = lon,
                label = label, title = title, detail = parts.drop(1).joinToString(", "), countryCode = text("countrycode").uppercase(), kind = text("type").ifBlank { "place" })
            places[p.id] = p; result.add(p)
        }
        return result
    }
    override suspend fun registerCurrentPlace(latitude: Double, longitude: Double): Place {
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
        val p = Place(java.util.UUID.randomUUID().toString(), latitude, longitude, "Current location", "Current location", "Selected on this device", "", "current_location")
        places[p.id] = p; return p
    }
    override suspend fun searchVehicles(query: String, fuelType: String?, limit: Int, offset: Int) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        catalogue.search(query, fuelType, limit, offset)
    }
    override suspend fun calculateJourney(originId: String, destinationId: String, vehicle: JSONObject, roundTrip: Boolean,
        timeAllowance: TimeAllowanceRequest, stops: List<String>, progress: (String) -> Unit): String {
        require(stops.size <= 8)
        val selected = (listOf(originId) + stops + destinationId).map { places[it] ?: throw EcoApiException("Search and confirm each address again.") }
        return planner.calculate(selected, vehicle, roundTrip, timeAllowance, progress)
    }
    override suspend fun fuelPrice() = OfficialFuelPrice.parse(online.request(PublicDataClient.FUEL_PRICE, null, 60L * 60 * 1000))
    override suspend fun startPlan(originId: String, destinationId: String, vehicle: JSONObject, roundTrip: Boolean, timeAllowance: TimeAllowanceRequest): String =
        throw EcoApiException("This app calculates directly on the device.")
    override suspend fun job(jobId: String): JobState = throw EcoApiException("This app has no remote calculation jobs.")
}

class EcoApiException(message: String) : IOException(message)
class EcoNetworkException(message: String, cause: Throwable) : IOException(message, cause)

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

private fun String.encodePathSegment(): String = buildString {
    for (byte in this@encodePathSegment.toByteArray(Charsets.UTF_8)) {
        val value = byte.toInt() and 0xff
        val character = value.toChar()
        if (character.isLetterOrDigit() || character in "-._~") append(character)
        else append("%${value.toString(16).uppercase().padStart(2, '0')}")
    }
}
