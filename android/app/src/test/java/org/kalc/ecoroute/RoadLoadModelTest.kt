package org.kalc.ecoroute

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RoadLoadModelTest {
    private fun vectors(): JSONArray = JSONArray(javaClass.getResource("/model-vectors.json")!!.readText())
    private fun checkVectors(cases: JSONArray) {
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i); val candidate = c.getJSONObject("candidate")
            val r = RoadLoadModel.prepare(candidate.getString("id"), candidate.getString("label"), candidate.getJSONObject("trip"), candidate.getJSONObject("trace"))
            val v = RoadLoadModel.Vehicle.fromJson(c.getJSONObject("vehicle"))
            assertEquals(c.getInt("segments"), r.segments.size)
            assertEquals(c.getDouble("distance"), r.metadata.getDouble("distance_km") * 1000, 1e-6)
            assertEquals(c.getDouble("ascent"), r.metadata.getDouble("ascent_m"), 1e-6)
            val graph = RoadLoadModel.graph(r, v); val profiles = c.getJSONArray("profiles")
            for (j in 0 until profiles.length()) {
                val expected = profiles.getJSONObject(j); val budget = expected.getDouble("deadline")
                val p = RoadLoadModel.optimize(r, graph, budget, v)!!
                assertEquals("litres case $i/$j", expected.getDouble("liters"), p.liters, 1e-8)
                assertEquals("seconds case $i/$j", expected.getDouble("seconds"), p.seconds, 1e-5)
                assertTrue(p.seconds <= budget + 1e-5)
                // Equal-cost profiles can permute cruise speeds on identical flat sections.
                // Compare fuel/time exactly; independently enforce each cap and transition.
                for (k in p.speeds.indices) assertTrue(p.speeds[k] <= r.caps[k] + 1e-7)
                for (k in r.segments.indices) {
                    val acceleration = (p.speeds[k + 1] * p.speeds[k + 1] - p.speeds[k] * p.speeds[k]) / (2 * r.segments[k].length)
                    assertTrue(acceleration >= -1.0 - 1e-6 && acceleration <= .7 + 1e-6)
                }
                assertEquals(expected.getDouble("gap"), p.optimization!!.getDouble("fuel_gap_liters"), 1e-8)
            }
        }
    }
    @Test fun auditedPythonReferenceParityAcrossFuelsGradesAndDeadlines() = checkVectors(vectors())
    @Test fun privateSavedRoutesWhenSupplied() {
        val path = System.getenv("ECO_PRIVATE_MODEL_FIXTURE") ?: return
        checkVectors(JSONArray(File(path).readText()))
    }
    @Test fun forceSignCrossingIsSubdivisionInvariant() {
        val v = RoadLoadModel.Vehicle(1800.0, .8, .011, .34, .8, .45, 35.8)
        val s = RoadLoadModel.Segment(250.0, 100.0, 70.0, 40.0, true, JSONObject())
        val whole = RoadLoadModel.segmentCost(s, 10.0, 30.0, v)
        for (count in listOf(2, 7, 100)) {
            var liters = 0.0; var seconds = 0.0
            for (i in 0 until count) {
                val v0 = kotlin.math.sqrt(100.0 + 800.0 * i / count); val v1 = kotlin.math.sqrt(100.0 + 800.0 * (i + 1) / count)
                val cost = RoadLoadModel.segmentCost(s.copy(length = 250.0 / count, h0 = 100.0 - 30.0 * i / count, h1 = 100.0 - 30.0 * (i + 1) / count), v0, v1, v)
                liters += cost.liters; seconds += cost.seconds
            }
            assertEquals(whole.liters, liters, 1e-10); assertEquals(whole.seconds, seconds, 1e-10)
        }
    }
    @Test fun missingTerrainFailsInsteadOfAssumingFlat() {
        val c = vectors().getJSONObject(0).getJSONObject("candidate")
        c.getJSONObject("trace").remove("elevation")
        assertThrows(IllegalArgumentException::class.java) { RoadLoadModel.prepare("test", "test", c.getJSONObject("trip"), c.getJSONObject("trace")) }
    }
    @Test fun cancellationIsObservedInsideOptimizer() {
        val c = vectors().getJSONObject(0); val candidate = c.getJSONObject("candidate")
        val r = RoadLoadModel.prepare("test", "test", candidate.getJSONObject("trip"), candidate.getJSONObject("trace"))
        val v = RoadLoadModel.Vehicle.fromJson(c.getJSONObject("vehicle")); var checkpoints = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            RoadLoadModel.optimize(r, RoadLoadModel.graph(r, v), c.getJSONArray("profiles").getJSONObject(1).getDouble("deadline"), v) {
                if (++checkpoints > 5) throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
    }
    @Test fun localPlannerPreservesMultiStopAndReturnTotalsWithoutSendingVehicleInputs() = runBlocking {
        val c = vectors().getJSONObject(0); val trace = c.getJSONObject("candidate").getJSONObject("trace")
        val shapes = mutableMapOf<String, JSONObject>()
        fun encode(coords: List<GeoPoint>): String = buildString {
            var lat = 0L; var lon = 0L
            for (p in coords) {
                val nextLat = kotlin.math.round(p.lat * 1e6).toLong(); val nextLon = kotlin.math.round(p.lon * 1e6).toLong()
                for (change in listOf(nextLat - lat, nextLon - lon)) {
                    var value = if (change < 0) (change shl 1).inv() else change shl 1
                    while (value >= 32) { append(((32L or (value and 31)) + 63).toInt().toChar()); value = value shr 5 }
                    append((value + 63).toInt().toChar())
                }
                lat = nextLat; lon = nextLon
            }
        }
        val online = OnlineData { url, payload, _ ->
            assertTrue(url.startsWith(PublicDataClient.ROUTER))
            assertFalse(payload!!.has("vehicle")); assertFalse(payload.has("mass_kg"))
            if (url.endsWith("/route")) {
                val points = payload.getJSONArray("locations")
                val from = points.getJSONObject(0).getDouble("lat"); val to = points.getJSONObject(1).getDouble("lat")
                val shape = encode((0..60).map { GeoPoint(from + (to - from) * it / 60, 14.0) })
                shapes[shape] = JSONObject(trace.toString()).put("shape", shape)
                JSONObject().put("trip", JSONObject().put("summary", JSONObject().put("length", 6.7).put("time", 300))
                    .put("legs", JSONArray(listOf(JSONObject().put("shape", shape))))).toString()
            } else shapes.getValue(payload.getString("encoded_polyline")).toString()
        }
        fun place(id: String, lat: Double) = Place(id,lat,14.0,id,id,"","","place")
        val places = listOf(place("a",46.0),place("b",46.06),place("c",46.12))
        val raw = HybridPlanner(online).calculate(places, c.getJSONObject("vehicle"), true, TimeAllowanceRequest(TimeAllowanceMode.PERCENT)) {}
        val result = JSONObject(raw); val journey = EcoJson.parseJourney(raw)
        assertEquals("multi_stop", journey.tripType); assertTrue(journey.roundTrip)
        assertEquals(listOf("a","b","c","a"),journey.places.map { it.id }); assertEquals(3,journey.legs.size)
        for (key in listOf(0,5,10,15)) {
            val total = result.getJSONObject("totals").getJSONObject(key.toString())
            val liters = journey.legs.sumOf { leg -> leg.routes.first { it.id == leg.recommendations[key] }.plans.getValue(key).liters }
            assertEquals(liters,total.getDouble("liters"),1e-10)
        }
        assertTrue(journey.legs.all { JSONObject(it.rawJson).getString("calculation_location") == "device" })
    }
}
