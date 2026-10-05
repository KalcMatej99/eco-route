package org.kalc.ecoroute

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class ModelsTest {
    @Test
    fun `map is available before planning and has no stale route in place preview`() {
        val idle = JSONObject(UiState().mapPayloadJson)
        assertEquals("idle", idle.getString("mode"))
        assertTrue(idle.isNull("route"))
        assertTrue(idle.isNull("destination"))
        val destination = Place("b", 45.5, 13.7, "Destination address", "Destination", "", "SI", "address")
        val preview = JSONObject(UiState(destination = AddressState(selected = destination)).mapPayloadJson)
        assertEquals("places", preview.getString("mode"))
        assertEquals(45.5, preview.getJSONObject("destination").getDouble("lat"), 0.0)
        assertTrue(preview.isNull("origin"))
        assertTrue(preview.isNull("route"))
        assertEquals(0, preview.getJSONArray("alternatives").length())
        assertFalse(preview.getBoolean("eligible"))
    }

    @Test
    fun `catalogue response preserves variant facts pagination and provenance`() {
        val result = EcoJson.parseCatalogueSearch("""{
          "vehicles":[{
            "id":"eea-1","make":"VOLVO","model":"XC90","display_name":"VOLVO XC90",
            "type":"L","variant":"LFK2","fuel_type":"diesel","fuel_mode":"H",
            "registration_year":2024,"mass_kg":2170,"power_kw":173,"engine_cc":1969,
            "wltp_l_per_100km":6.8,"wltp_max_l_per_100km":7.4,
            "body_style":"large_suv",
            "body_style_source":{"name":"US EPA 2024 body class reference","url":"https://example.test/epa","license":"Public domain"},
            "supported_for_model":true,"unsupported_reason":null,
            "source":{"name":"EEA / DG CLIMA","url":"https://example.test/eea","license":"CC BY 4.0"}
          }],"total_matches":1406,"truncated":true,"next_offset":30,
          "coverage":"2024 final registrations"
        }""")

        val vehicle = result.vehicles.single()
        assertEquals("LFK2", vehicle.variant)
        assertEquals("L", vehicle.typeCode)
        assertEquals(2170.0, vehicle.massKg!!, 0.0)
        assertEquals(7.4, vehicle.wltpMaxLPer100Km!!, 0.0)
        assertEquals(VehicleBodyStyle.LARGE_SUV, vehicle.bodyStyle)
        assertEquals("US EPA 2024 body class reference", vehicle.bodyStyleSource?.name)
        assertTrue(vehicle.isSupported)
        assertEquals(30, result.nextOffset)
        assertEquals(1406, result.totalMatches)
        val restored = EcoJson.parseCatalogueVehicle(vehicle.toJson())
        assertEquals("CC BY 4.0", restored.source.license)
        assertEquals("L", restored.typeCode)
        assertEquals(VehicleBodyStyle.LARGE_SUV, restored.bodyStyle)
    }

    @Test
    fun `body presets are explicit estimates and do not reuse XC90 drag`() {
        val small = VehicleBodyStyle.SMALL_CAR.estimatedFields("petrol", "1250", "Small hatch")
        val large = VehicleBodyStyle.LARGE_SUV.estimatedFields("diesel", "2400", "Large SUV")

        assertEquals("0.60", small.dragAreaM2)
        assertEquals("28", small.efficiencyPercent)
        assertEquals("0.95", large.dragAreaM2)
        assertEquals("34", large.efficiencyPercent)
        assertFalse(small.dragAreaM2 == VehicleFields.DEFAULT.dragAreaM2)
        assertFalse(large.dragAreaM2 == VehicleFields.DEFAULT.dragAreaM2)
    }

    @Test
    fun `one way report parses routes and preserves map objects`() {
        val journey = EcoJson.parseJourney(legJson("A", 2.5, 40.0, 50.0))

        assertEquals("one_way", journey.tripType)
        assertEquals(1, journey.legs.size)
        val route = journey.legs.single().routes.single()
        assertEquals("route-A", route.id)
        assertEquals(46.05, route.coordinates.first().lat, 0.0)
        assertEquals(2.5, route.plans.getValue(10).liters, 0.0)
        assertEquals("Main road", route.segments.single().name)
        assertEquals("route-A", JSONObject(route.rawJson).getString("id"))
        assertEquals(TimeAllowanceMode.PERCENT, journey.legs.single().timeAllowance.mode)
        assertEquals(4.0, journey.legs.single().timeAllowance.option(10)!!.extraMinutes, 0.0)
        assertEquals(44.0, journey.legs.single().timeAllowance.option(10)!!.maxMinutes, 0.0)
        assertTrue(journey.matchesTimeAllowance(TimeAllowanceRequest(TimeAllowanceMode.PERCENT)))
        assertFalse(journey.matchesTimeAllowance(TimeAllowanceRequest(TimeAllowanceMode.MINUTES, 10)))

        val state = UiState(
            journey = journey, selectedRouteIds = listOf(route.id),
            selectedLeg = journey.legs.single(), selectedRoute = route,
            selectedPlan = route.plans.getValue(10), fullRouteJson = journey.fullRouteJson,
        )
        val mapPayload = state.mapPayloadJson
        assertNotNull(mapPayload)
        val payload = JSONObject(mapPayload!!)
        assertEquals("route-A", payload.getJSONObject("route").getString("id"))
        assertTrue(payload.getBoolean("eligible"))
        assertEquals("route", payload.getString("mode"))
        val alternative = route.copy(id = "alternative", rawJson = JSONObject(route.rawJson).put("id", "alternative").toString())
        val withAlternative = state.copy(selectedLeg = state.selectedLeg!!.copy(routes = listOf(route, alternative)))
        val alternatives = JSONObject(withAlternative.mapPayloadJson).getJSONArray("alternatives")
        assertEquals(1, alternatives.length())
        assertEquals("alternative", alternatives.getJSONObject(0).getString("id"))
        val returning = JSONObject(withAlternative.copy(activeLegIndex = 1).mapPayloadJson)
        assertFalse(returning.getBoolean("returnLeg"))
        assertFalse(returning.getString("selectionKey") == payload.getString("selectionKey"))
    }

    @Test
    fun `multi stop journey parses all legs places totals and map context`() {
        val placeA = Place("a", 46.0, 14.0, "A address", "Alpha", "", "SI", "city")
        val placeB = Place("b", 46.2, 14.2, "B address", "Bravo", "", "SI", "city")
        val placeC = Place("c", 46.4, 14.4, "C address", "Charlie", "", "SI", "city")
        val places = org.json.JSONArray().apply {
            listOf(placeA, placeB, placeC, placeA).forEach { place ->
                put(JSONObject().put("id", place.id).put("lat", place.lat).put("lon", place.lon)
                    .put("label", place.label).put("title", place.title).put("detail", "")
                    .put("country_code", "SI").put("kind", "city"))
            }
        }
        val root = JSONObject().put("trip_type", "multi_stop").put("round_trip", true)
            .put("places", places)
            .put("legs", org.json.JSONArray().put(JSONObject(legJson("ab", 2.0, 44.0, 50.0)))
                .put(JSONObject(legJson("bc", 3.0, 45.0, 60.0)))
                .put(JSONObject(legJson("ca", 4.0, 46.0, 70.0))))
        val journey = EcoJson.parseJourney(root.toString())
        assertEquals("multi_stop", journey.tripType)
        assertTrue(journey.roundTrip)
        assertEquals(4, journey.places.size)
        assertEquals(3, journey.legs.size)
        val totals = calculateTripTotals(journey, listOf("route-ab", "route-bc", "route-ca"), 10, null, "diesel")!!
        assertEquals(9.0, totals.liters, 0.0)
        assertEquals(180.0, totals.distanceKm, 0.0)

        val state = UiState(
            origin = AddressState(selected = placeA), destination = AddressState(selected = placeC),
            stops = listOf(IntermediateStop(7, AddressState(selected = placeB))), journey = journey,
            activeLegIndex = 1, selectedRouteIds = listOf("route-ab", "route-bc", "route-ca"),
            selectedLeg = journey.legs[1], selectedRoute = journey.legs[1].routes.single(),
            selectedPlan = journey.legs[1].routes.single().plans.getValue(10),
        )
        val payload = JSONObject(state.mapPayloadJson)
        assertEquals(3, payload.getJSONArray("places").length())
        assertEquals(3, payload.getJSONArray("journeyRoutes").length())
        assertEquals(1, payload.getInt("activeLegIndex"))
        assertFalse(payload.getBoolean("returnLeg"))
        val returnPayload = JSONObject(state.copy(
            activeLegIndex = 2, selectedLeg = journey.legs[2], selectedRoute = journey.legs[2].routes.single(),
            selectedPlan = journey.legs[2].routes.single().plans.getValue(10),
        ).mapPayloadJson)
        assertTrue(returnPayload.getBoolean("returnLeg"))
    }

    @Test
    fun `round trip totals use each independently selected leg`() {
        val json = """{
          "trip_type":"round_trip",
          "legs":[${legJson("out", 3.0, 50.0, 60.0)},${legJson("back", 4.0, 60.0, 70.0)}],
          "totals":{"10":{"liters":7,"minutes":110,"distance_km":130}}
        }"""
        val journey = EcoJson.parseJourney(json)
        val price = FuelPrice(
            currency = "EUR", prices = mapOf("diesel" to 1.9),
            validFrom = LocalDate.of(2026, 10, 1), validUntil = LocalDate.of(2026, 10, 7),
            checkedAt = "", sourceName = "", sourceUrl = "",
        )

        val totals = calculateTripTotals(
            journey, listOf("route-out", "route-back"), 10, price, "diesel",
        )

        assertNotNull(totals)
        assertEquals(7.0, totals!!.liters, 0.0)
        assertEquals(110.0, totals.minutes, 0.0)
        assertEquals(130.0, totals.distanceKm, 0.0)
        assertEquals(7.0 / 130.0 * 100.0, totals.consumptionLPer100Km, 1e-9)
        assertEquals(13.3, totals.fuelCostEur!!, 1e-9)
        assertEquals(30.0, totals.addedMinutes, 0.0)
        assertEquals(7.0, totals.baselineLiters, 0.0)
        assertEquals(0.0, totals.litersSaved, 0.0)
        assertTrue(totals.eligible)
    }

    @Test
    fun `missing slack plan falls back to baseline and marks totals ineligible`() {
        val journey = EcoJson.parseJourney(legJson("A", 2.5, 40.0, 50.0))
        val totals = calculateTripTotals(journey, listOf("route-A"), 20, null, "diesel")!!

        assertEquals(3.5, totals.liters, 0.0)
        assertEquals(42.0, totals.minutes, 0.0)
        assertFalse(totals.eligible)
        assertNull(totals.fuelCostEur)
    }

    @Test
    fun `fuel quote is only current for supported in-period price`() {
        val price = EcoJson.parseFuelPrice("""{
          "currency":"EUR","prices":{"diesel":1.929,"petrol":1.76},
          "valid_from":"2026-09-29","valid_until":"2026-10-05",
          "checked_at":"2026-10-02T12:00:00Z","source_name":"gov","source_url":"https://example.test"
        }""")

        assertEquals(1.929, price.currentPrice("diesel", LocalDate.of(2026, 10, 2))!!, 0.0)
        assertNull(price.currentPrice("diesel", LocalDate.of(2026, 10, 6)))
        assertNull(price.currentPrice("electric", LocalDate.of(2026, 10, 2)))
    }

    @Test
    fun `vehicle validation converts percent and rejects out of range fields`() {
        val valid = VehicleFields.DEFAULT.validate()
        assertNull(valid.error)
        assertEquals(.34, valid.payload!!.getDouble("tank_to_wheel_efficiency"), 0.0)

        val invalid = VehicleFields.DEFAULT.copy(massKg = "599").validate()
        assertNull(invalid.payload)
        assertTrue(invalid.error!!.contains("600"))
    }

    @Test
    fun `time allowance request encodes percent and bounded custom minutes`() {
        val percent = TimeAllowanceRequest(TimeAllowanceMode.PERCENT).toJson()
        assertEquals("percent", percent.getString("mode"))
        assertFalse(percent.has("minutes"))

        val custom = TimeAllowanceRequest(TimeAllowanceMode.MINUTES, 37).toJson()
        assertEquals("minutes", custom.getString("mode"))
        assertEquals(37, custom.getInt("minutes"))

        listOf(null, 0, 121).forEach { invalid ->
            try {
                TimeAllowanceRequest(TimeAllowanceMode.MINUTES, invalid).toJson()
                fail("Expected custom minutes $invalid to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected: request objects cannot bypass the UI's 1–120 validation.
            }
        }
    }

    @Test
    fun `custom minute metadata and plan keys match submitted budget`() {
        val root = JSONObject(legJson("custom", 2.5, 40.0, 50.0))
        root.put("time_allowance", JSONObject("""{"mode":"minutes","options":[
          {"value":0,"extra_minutes":0,"max_minutes":40},
          {"value":37,"extra_minutes":37,"max_minutes":77}
        ]}"""))
        root.put("recommendations", JSONObject("""{"0":"route-custom","37":"route-custom"}"""))
        val plans = root.getJSONArray("routes").getJSONObject(0).getJSONObject("plans")
        plans.put("37", plans.getJSONObject("10")).remove("10")

        val journey = EcoJson.parseJourney(root.toString())

        assertTrue(journey.matchesTimeAllowance(TimeAllowanceRequest(TimeAllowanceMode.MINUTES, 37)))
        assertEquals(37.0, journey.legs.single().timeAllowance.option(37)!!.extraMinutes, 0.0)
        assertEquals(2.5, journey.legs.single().routes.single().plans.getValue(37).liters, 0.0)
    }

    @Test
    fun `round trip percentage minutes remain independent per leg`() {
        val outbound = JSONObject(legJson("out", 2.5, 43.0, 50.0))
        val returning = JSONObject(legJson("back", 2.7, 54.0, 52.0))
        returning.put("fastest_minutes", 50.0)
        returning.getJSONObject("time_allowance").put("options", org.json.JSONArray("""[
          {"value":0,"extra_minutes":0,"max_minutes":50},
          {"value":5,"extra_minutes":2.5,"max_minutes":52.5},
          {"value":10,"extra_minutes":5,"max_minutes":55},
          {"value":15,"extra_minutes":7.5,"max_minutes":57.5}
        ]"""))
        val wrapper = JSONObject().put("trip_type", "round_trip")
            .put("legs", org.json.JSONArray().put(outbound).put(returning))

        val journey = EcoJson.parseJourney(wrapper.toString())

        assertEquals(4.0, journey.legs[0].timeAllowance.option(10)!!.extraMinutes, 0.0)
        assertEquals(5.0, journey.legs[1].timeAllowance.option(10)!!.extraMinutes, 0.0)
        assertEquals(55.0, journey.legs[1].timeAllowance.option(10)!!.maxMinutes, 0.0)
    }

    @Test
    fun `allowance matching rejects incorrect server metadata`() {
        val wrongMinutes = JSONObject(legJson("wrong", 2.5, 43.0, 50.0))
        wrongMinutes.getJSONObject("time_allowance").getJSONArray("options")
            .getJSONObject(2).put("extra_minutes", 10.0)
        assertFalse(EcoJson.parseJourney(wrongMinutes.toString())
            .matchesTimeAllowance(TimeAllowanceRequest(TimeAllowanceMode.PERCENT)))

        val wrongMode = JSONObject(legJson("wrong-mode", 2.5, 43.0, 50.0))
        wrongMode.getJSONObject("time_allowance").put("mode", "percentage")
        try {
            EcoJson.parseJourney(wrongMode.toString())
            fail("Expected an unknown server allowance mode to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: live responses must not be interpreted as a different allowance mode.
        }
    }

    private fun legJson(suffix: String, liters: Double, minutes: Double, distance: Double) = """{
      "generated_at":"2026-10-02T12:00:00Z",
      "places":[
        {"id":"p1","lat":46.05,"lon":14.5,"label":"Ljubljana, Slovenia","title":"Ljubljana","detail":"Slovenia","country_code":"SI","kind":"city"},
        {"id":"p2","lat":45.5,"lon":13.6,"label":"Portorož, Slovenia","title":"Portorož","detail":"Slovenia","country_code":"SI","kind":"city"}
      ],
      "fastest_minutes":40.0,
      "warnings":[],
      "time_allowance":{"mode":"percent","options":[
        {"value":0,"extra_minutes":0.0,"max_minutes":40.0},
        {"value":5,"extra_minutes":2.0,"max_minutes":42.0},
        {"value":10,"extra_minutes":4.0,"max_minutes":44.0},
        {"value":15,"extra_minutes":6.0,"max_minutes":46.0}
      ]},
      "config":{"vehicle":{"name":"Test car","fuel_type":"diesel","mass_kg":1500}},
      "recommendations":{"0":"route-$suffix","5":"route-$suffix","10":"route-$suffix","15":"route-$suffix"},
      "routes":[{
        "id":"route-$suffix","label":"Motorway route","distance_km":$distance,
        "provider_minutes":39.0,"known_speed_limit_pct":95.0,"ascent_m":120,"descent_m":100,
        "coordinates":[[46.05,14.5],[45.5,13.6]],
        "baseline":{"minutes":42.0,"liters":3.5,"sensitivity_liters":[3.0,4.0],"speed_kmh":[0,80]},
        "plans":{"10":{"minutes":$minutes,"liters":$liters,"sensitivity_liters":[2.0,3.0],"speed_kmh":[0,75]}},
        "segments":[{"start_m":0,"end_m":1000,"name":"Main road","road_class":"primary","limit_kmh":90,"height_start_m":100,"height_end_m":110}]
      }]
    }"""
}
