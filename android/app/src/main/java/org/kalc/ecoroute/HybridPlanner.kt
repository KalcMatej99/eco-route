package org.kalc.ecoroute

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.coroutines.coroutineContext

/** Online road data in; all vehicle physics, speed search and totals run locally. */
internal class HybridPlanner(private val online: OnlineData) {
    suspend fun calculate(places: List<Place>, vehicleJson: JSONObject, roundTrip: Boolean,
        allowance: TimeAllowanceRequest, progress: (String) -> Unit): String {
        require(places.size in 2..10)
        require(allowance.mode != TimeAllowanceMode.MINUTES || allowance.minutes in 1..120)
        val itinerary = places + if (roundTrip) listOf(places.first()) else emptyList()
        for ((a, b) in itinerary.zipWithNext()) {
            require(a.id != b.id && RoadLoadModel.distance(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)) >= 300) { "Choose consecutive locations at least 300 meters apart." }
        }
        val legs = JSONArray()
        for ((i, pair) in itinerary.zipWithNext().withIndex()) {
            legs.put(calculateLeg(pair.first, pair.second, vehicleJson, allowance) { progress("Section ${i + 1} of ${itinerary.size - 1} · $it") })
        }
        if (places.size == 2 && !roundTrip) return legs.getJSONObject(0).toString()
        val totals = JSONObject()
        val keys = legs.getJSONObject(0).getJSONObject("recommendations").keys()
        for (key in keys) {
            var liters = 0.0; var minutes = 0.0; var distance = 0.0
            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i); val routeId = leg.getJSONObject("recommendations").getString(key)
                val routes = leg.getJSONArray("routes")
                val r = (0 until routes.length()).map { routes.getJSONObject(it) }.first { it.getString("id") == routeId }
                val p = r.getJSONObject("plans").getJSONObject(key)
                liters += p.getDouble("liters"); minutes += p.getDouble("minutes"); distance += r.getDouble("distance_km")
            }
            totals.put(key, JSONObject().put("liters", liters).put("minutes", minutes).put("distance_km", distance))
        }
        return JSONObject().put("trip_type", if (places.size > 2) "multi_stop" else "round_trip")
            .put("round_trip", roundTrip).put("places", JSONArray(itinerary.map(::placeJson)))
            .put("legs", legs).put("totals", totals).toString()
    }

    private suspend fun calculateLeg(a: Place, b: Place, vehicleJson: JSONObject, allowance: TimeAllowanceRequest, progress: (String) -> Unit): JSONObject {
        val candidates = mutableListOf<RoadLoadModel.Route>(); val shapes = mutableSetOf<String>(); val warnings = mutableSetOf<String>()
        val points = JSONArray(listOf(JSONObject().put("lat", a.lat).put("lon", a.lon), JSONObject().put("lat", b.lat).put("lon", b.lon)))
        for ((id, label, options) in listOf(
            Triple("motorway", "Motorway route", JSONObject().put("use_highways", 1)),
            Triple("balanced", "Balanced route", JSONObject().put("use_highways", .5)),
            Triple("shorter", "Shorter route", JSONObject().put("shortest", true)),
            Triple("local", "Fewer motorways", JSONObject().put("use_highways", 0)),
        )) {
            coroutineContext.ensureActive(); progress("Finding ${label.lowercase()}…")
            try {
                val auto = JSONObject(options.toString()).put("speed_types", JSONArray()).put("use_tolls", 1)
                val request = JSONObject().put("locations", points).put("costing", "auto").put("alternates", 1).put("units", "kilometers").put("costing_options", JSONObject().put("auto", auto))
                val response = JSONObject(online.request(PublicDataClient.ROUTER + "/route", request, PublicDataClient.SEVEN_DAYS))
                val alternates = response.optJSONArray("alternates") ?: JSONArray()
                val results = listOf(response) + (0 until alternates.length()).map { alternates.getJSONObject(it) }
                for ((index, result) in results.withIndex()) {
                    val trip = result.getJSONObject("trip")
                    if (trip.getJSONArray("legs").length() != 1 || trip.getJSONObject("summary").getDouble("length") > 500) continue
                    val shape = trip.getJSONArray("legs").getJSONObject(0).getString("shape")
                    if (!shapes.add(shape)) continue
                    progress("Reading elevation and speed limits…")
                    val traceRequest = JSONObject().put("encoded_polyline", shape).put("costing", "auto").put("shape_match", "edge_walk").put("units", "kilometers").put("elevation_interval", 100)
                        .put("costing_options", JSONObject().put("auto", JSONObject().put("speed_types", JSONArray())))
                    val trace = JSONObject(online.request(PublicDataClient.ROUTER + "/trace_attributes", traceRequest, PublicDataClient.SEVEN_DAYS))
                    val route = withContext(Dispatchers.Default) {
                        val context = coroutineContext
                        RoadLoadModel.prepare(if (index == 0) id else "$id-alt-$index", label + if (index > 0) " alternative" else "", trip, trace) { context.ensureActive() }
                    }
                    if (RoadLoadModel.distance(route.coordinates.first(), GeoPoint(a.lat, a.lon)) > 1000 || RoadLoadModel.distance(route.coordinates.last(), GeoPoint(b.lat, b.lon)) > 1000) {
                        warnings.add("A route was omitted because road access was too far from the selected location."); continue
                    }
                    if (route.metadata.getDouble("structure_name_fallback_km") > 0) warnings.add("Some viaduct elevations were approximated between endpoints because bridge tags were missing.")
                    candidates.add(route)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                warnings.add("$label could not be assessed with complete road/elevation data.")
            }
        }
        if (candidates.isEmpty()) throw EcoApiException("No route with usable elevation was found. Check your internet connection and choose road-accessible places; each section must be under 500 km.")
        return withContext(Dispatchers.Default) {
            val context = coroutineContext; val checkpoint = { context.ensureActive() }
            val vehicle = RoadLoadModel.Vehicle.fromJson(vehicleJson)
            val baselines = candidates.map { RoadLoadModel.evaluate(it, it.caps, vehicle) }
            val fastest = baselines.minOf { it.seconds } / 60
            val values = if (allowance.mode == TimeAllowanceMode.PERCENT) listOf(0, 5, 10, 15) else listOf(0, requireNotNull(allowance.minutes))
            val deadlines = values.associateWith { (fastest + if (allowance.mode == TimeAllowanceMode.PERCENT) fastest * it / 100 else it.toDouble()) * 60 }
            val routes = JSONArray(); var computeNanos = 0L
            for ((index, r) in candidates.withIndex()) {
                checkpoint(); progress("Optimizing speeds on your phone · route ${index + 1} of ${candidates.size}…")
                val started = System.nanoTime()
                val plans = JSONObject()
                if (baselines[index].seconds <= deadlines.values.max()) {
                    val graph = RoadLoadModel.graph(r, vehicle, checkpoint)
                    for ((value, deadline) in deadlines) RoadLoadModel.optimize(r, graph, deadline, vehicle, checkpoint)?.let { plans.put(value.toString(), RoadLoadModel.pack(r, it, vehicle)) }
                }
                computeNanos += System.nanoTime() - started
                routes.put(JSONObject(r.metadata.toString()).put("coordinates", JSONArray(r.coordinates.map { JSONArray(listOf(it.lat, it.lon)) }))
                    .put("baseline", RoadLoadModel.pack(r, baselines[index], vehicle)).put("plans", plans).put("segments", JSONArray(r.segments.map { it.json })))
            }
            val recommendations = JSONObject()
            for (value in values) {
                val eligible = (0 until routes.length()).map { routes.getJSONObject(it) }.filter { it.getJSONObject("plans").has(value.toString()) }
                val winner = eligible.minByOrNull { it.getJSONObject("plans").getJSONObject(value.toString()).getDouble("liters") } ?: throw EcoApiException("Unable to calculate a feasible speed plan. Try another trip.")
                recommendations.put(value.toString(), winner.getString("id"))
            }
            JSONObject().put("generated_at", Instant.now().toString()).put("fuel_model_version", RoadLoadModel.VERSION)
                .put("calculation_location", "device").put("optimization_duration_ms", computeNanos / 1e6)
                .put("config", JSONObject().put("vehicle", vehicleJson)).put("places", JSONArray(listOf(placeJson(a), placeJson(b))))
                .put("points", points).put("routes", routes).put("recommendations", recommendations).put("fastest_minutes", fastest)
                .put("warnings", JSONArray(warnings.toList())).put("time_allowance", JSONObject().put("mode", allowance.mode.wireValue).put("options", JSONArray(values.map { value ->
                    JSONObject().put("value", value).put("extra_minutes", deadlines.getValue(value) / 60 - fastest).put("max_minutes", deadlines.getValue(value) / 60)
                }))).put("data_note", "Online road data cached on this device for up to 7 days. No traffic. Uncalibrated engine-on estimate; no hybrid battery or fuel-cutoff simulation.")
                .put("method", "Finite candidate routes and discrete motorway speeds; local dynamic programming/Pareto refinement, not a guaranteed global optimum.")
        }
    }
    companion object {
        fun placeJson(p: Place) = JSONObject().put("id", p.id).put("lat", p.lat).put("lon", p.lon).put("label", p.label)
            .put("title", p.title).put("detail", p.detail).put("country_code", p.countryCode).put("kind", p.kind)
    }
}
