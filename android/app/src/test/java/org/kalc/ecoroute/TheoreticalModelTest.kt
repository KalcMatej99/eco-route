package org.kalc.ecoroute

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TheoreticalModelTest {
    private fun fixture() = JSONArray(javaClass.getResource("/model-vectors.json")!!.readText()).getJSONObject(0)
    private fun prepare(limit: Any?, speed: Int, theoretical: Boolean): RoadLoadModel.Route {
        val c = fixture().getJSONObject("candidate")
        val trace = c.getJSONObject("trace")
        val edges = trace.getJSONArray("edges")
        for (i in 0 until edges.length()) {
            val e = edges.getJSONObject(i).put("road_class","residential").put("speed",speed)
            if (limit == null) e.remove("speed_limit") else e.put("speed_limit",limit)
        }
        return RoadLoadModel.prepare("r","Route",c.getJSONObject("trip"),trace,theoretical)
    }
    @Test fun theoreticalProfilesDoNotDependOnPostedLimitsOrProviderSpeeds() {
        val vehicle = RoadLoadModel.Vehicle.fromJson(fixture().getJSONObject("vehicle"))
        val reference = prepare(30,20,true)
        val baseline = RoadLoadModel.evaluate(reference,reference.caps,vehicle)
        for ((limit,speed) in listOf(10 to 10, 90 to 90, 200 to 200, "unlimited" to 30, null to 70)) {
            val r = prepare(limit,speed,true)
            assertArrayEquals(reference.caps,r.caps,0.0)
            val actual = RoadLoadModel.evaluate(r,r.caps,vehicle)
            assertEquals(baseline.seconds,actual.seconds,0.0)
            assertEquals(baseline.liters,actual.liters,0.0)
            assertTrue(r.segments.all { it.adjustable && it.cap == 130.0/3.6 })
            assertTrue(r.caps.all { it.isFinite() && it <= 130.0/3.6 })
            // Keep real source limits for inspection; ignoring them does not erase them.
            r.segments.forEach {
                if (limit is Int) assertEquals(limit.toDouble(),it.json.getDouble("limit_kmh"),0.0)
                else assertTrue(it.json.isNull("limit_kmh"))
                assertEquals(limit == "unlimited",it.json.getBoolean("limit_unlimited"))
            }
        }
        assertTrue(reference.caps.max() * 3.6 > 30)
        val optimized = RoadLoadModel.optimize(reference,RoadLoadModel.graph(reference,vehicle),baseline.seconds*1.1,vehicle)!!
        assertTrue(optimized.seconds <= baseline.seconds*1.1 + 1e-5)
        assertTrue(optimized.liters <= baseline.liters + 1e-8)
    }
    @Test fun normalModeStillUsesProviderAndMappedCeilings() {
        val normal = prepare(30,60,false)
        assertTrue(normal.segments.all { it.cap == 30.0/3.6 && !it.adjustable })
        assertTrue(normal.caps.all { it <= 30.0/3.6 })
        assertFalse(normal.metadata.getBoolean("ignore_speed_limits"))
        assertTrue(prepare(90,20,false).segments.all { it.cap == 20.0/3.6 })
    }
    @Test fun theoreticalSettingFlowsThroughRoundTripRoutesAndJsonWithoutChangingOnlineRequests() = runBlocking {
        val c = fixture(); val candidate=c.getJSONObject("candidate");val trace=candidate.getJSONObject("trace")
        val coords=RoadLoadModel.decodePolyline(trace.getString("shape"))
        fun encode(points: List<GeoPoint>): String = buildString {
            var lat=0L;var lon=0L
            for (p in points) {
                val a=kotlin.math.round(p.lat*1e6).toLong();val b=kotlin.math.round(p.lon*1e6).toLong()
                for (d in listOf(a-lat,b-lon)) {
                    var value=if (d<0) (d shl 1).inv() else d shl 1
                    while(value>=32) { append(((32L or (value and 31))+63).toInt().toChar());value=value shr 5 }
                    append((value+63).toInt().toChar())
                }
                lat=a;lon=b
            }
        }
        val online=OnlineData { url,payload,_ ->
            assertFalse(payload!!.has("ignore_speed_limits"))
            assertFalse(payload.has("vehicle"))
            if (url.endsWith("/route")) {
                val start=payload.getJSONArray("locations").getJSONObject(0)
                val reverse=RoadLoadModel.distance(GeoPoint(start.getDouble("lat"),start.getDouble("lon")),coords.last())<1
                val shape=encode(if (reverse) coords.reversed() else coords)
                JSONObject().put("trip",JSONObject(candidate.getJSONObject("trip").toString()).put("summary",JSONObject().put("time",300).put("length",coords.zipWithNext().sumOf { (a,b) -> RoadLoadModel.distance(a,b) }/1000)).put("legs",JSONArray().put(JSONObject().put("shape",shape)))).toString()
            } else JSONObject(trace.toString()).put("shape",payload.getString("encoded_polyline")).toString()
        }
        val places=listOf(coords.first(),coords.last()).mapIndexed { i,p -> Place("p$i",p.lat,p.lon,"Place $i","Place $i","","","place") }
        val raw=HybridPlanner(online).calculate(places,c.getJSONObject("vehicle"),true,TimeAllowanceRequest(TimeAllowanceMode.PERCENT),true) {}
        val root=JSONObject(raw);assertTrue(root.getBoolean("ignore_speed_limits"))
        val parsed=EcoJson.parseJourney(raw)
        assertTrue(parsed.ignoresSpeedLimits)
        assertTrue(parsed.legs.all { it.routes.all { r -> r.ignoreSpeedLimits } })
        assertTrue(parsed.matchesTimeAllowance(TimeAllowanceRequest(TimeAllowanceMode.PERCENT)))
        assertTrue(parsed.legs.all { it.routes.any { r -> r.plans.keys.containsAll(listOf(20,30,45,60)) } })
        val tradeoffs = JourneyTradeoffs.build(parsed)
        assertTrue(tradeoffs.choices.all { it.legs.size == 2 && tradeoffs.extraPercent(it).isFinite() })
    }
}
