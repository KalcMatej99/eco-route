package org.kalc.ecoroute

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapFirstUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var api: MapFirstApi
    private lateinit var vm: PlannerViewModel

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences("eco_route_vehicle", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("vehicle_setup_complete_v1", true).commit()
        api = MapFirstApi()
        vm = PlannerViewModel(application, api)
        compose.setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            MaterialTheme {
                MapPlannerScreen(state, vm, onOpenVehicle = {}, onExport = {})
            }
        }
    }

    @Test
    fun destinationFirstThenOriginCalculatesOnceAndKeepsMapVisible() {
        compose.onNodeWithTag("route-map").assertIsDisplayed()
        compose.onNodeWithTag("start-destination-search").assertIsDisplayed().performClick()
        compose.waitUntil(3_000) { compose.onAllNodes(hasTestTag("destination-field")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("destination-field").performTextInput("Portoroz")
        Thread.sleep(700)
        compose.waitUntil(3_000) { compose.onAllNodes(hasTestTag("address-suggestion-destination")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("address-suggestion-destination").performClick()

        compose.onNodeWithTag("origin-field").assertIsDisplayed().performTextInput("Ljubljana")
        Thread.sleep(700)
        compose.waitUntil(3_000) { compose.onAllNodes(hasTestTag("address-suggestion-origin")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("address-suggestion-origin").performClick()

        try {
            compose.waitUntil(3_000) { api.starts == 1 && vm.state.value.journey != null }
        } catch (error: Exception) {
            throw AssertionError("Calculation starts=${api.starts}, error=${vm.state.value.error}, busy=${vm.state.value.isCalculating}", error)
        }
        assertEquals(1, api.starts)
        compose.onNodeWithTag("route-map").assertIsDisplayed()
        compose.onNodeWithTag("sheet-toggle").assertExists()
        compose.onNodeWithTag("edit-origin").assertIsDisplayed()
        compose.onNodeWithTag("edit-destination").assertIsDisplayed()
        compose.onNodeWithTag("sheet-toggle").performClick()
        compose.onNodeWithTag("planner-sheet").performScrollToNode(androidx.compose.ui.test.hasText("Trip options"))
        compose.onNodeWithText("Trip options").assertIsDisplayed()
        compose.onNodeWithTag("new-route").performScrollTo().performClick()
        compose.onNodeWithTag("start-destination-search").assertIsDisplayed()
        compose.onNodeWithTag("route-map").assertIsDisplayed()
        assertEquals(null, vm.state.value.journey)
    }

    @Test
    fun sheetHasExplicitExpandAndBackClosesSearch() {
        compose.onNodeWithTag("sheet-toggle").performClick()
        compose.onNodeWithText("Trip options").assertIsDisplayed()
        compose.onNodeWithTag("sheet-toggle").performClick()

        compose.onNodeWithTag("start-destination-search").performClick()
        compose.onNodeWithTag("destination-field").assertIsDisplayed()
        compose.onNodeWithTag("destination-field").performTextInput("Portoroz")
        Thread.sleep(700)
        compose.waitUntil(3_000) { compose.onAllNodes(hasTestTag("address-suggestion-destination-5")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("address-suggestion-destination-5").performScrollTo().assertIsDisplayed()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.onNodeWithTag("start-destination-search").assertIsDisplayed()
    }

    @Test
    fun addNextDestinationPreservesOrderAndFinalCanMoveOrBeRemoved() {
        val a = Place("a", 46.0, 14.0, "Alpha", "Alpha", "", "SI", "city")
        val b = Place("b", 46.1, 14.1, "Bravo", "Bravo", "", "SI", "city")
        val c = Place("c", 46.2, 14.2, "Charlie", "Charlie", "", "SI", "city")
        val d = Place("d", 46.3, 14.3, "Delta", "Delta", "", "SI", "city")
        vm.selectPlace(0, a)
        vm.selectPlace(1, b)

        val bravoStop = vm.addStop()!!
        assertEquals("b", vm.state.value.stops.single().address.selected?.id)
        assertNull(vm.state.value.destination.selected)
        vm.selectPlace(1, c)
        val charlieStop = vm.addStop()!!
        vm.selectPlace(1, d)
        assertEquals(listOf("a", "b", "c", "d"), orderedIds(vm.state.value))

        vm.moveStop(charlieStop, -1)
        assertEquals(listOf("a", "c", "b", "d"), orderedIds(vm.state.value))
        vm.moveStop(bravoStop, 1)
        assertEquals(listOf("a", "c", "d", "b"), orderedIds(vm.state.value))
        vm.removeStop(charlieStop)
        assertEquals(listOf("a", "d", "b"), orderedIds(vm.state.value))
        assertNull(vm.state.value.journey)

        vm.editStop(bravoStop, "pending replacement")
        vm.moveStop(bravoStop, 1)
        assertEquals("b", vm.state.value.destination.selected?.id)
        assertNull(vm.state.value.stops.single().address.selected)
        vm.swap()
        assertTrue(vm.state.value.stops.none { it.address.isSearching || it.address.suggestions.isNotEmpty() })
    }

    @Test
    fun intermediateStopLimitIsEightAndEveryAdditionNeedsANewFinalPlace() {
        val origin = Place("origin-max", 46.0, 14.0, "Origin", "Origin", "", "SI", "city")
        vm.selectPlace(0, origin)
        repeat(8) { index ->
            vm.selectPlace(1, Place("p$index", 46.1 + index / 100.0, 14.1, "P$index", "P$index", "", "SI", "city"))
            assertTrue(vm.addStop() != null)
        }
        vm.selectPlace(1, Place("final", 47.0, 15.0, "Final", "Final", "", "SI", "city"))
        assertNull(vm.addStop())
        assertEquals(8, vm.state.value.stops.size)
        assertTrue(vm.state.value.error.orEmpty().contains("8"))
    }

    private fun orderedIds(state: UiState): List<String> = buildList {
        state.origin.selected?.id?.let(::add)
        state.stops.mapNotNullTo(this) { it.address.selected?.id }
        state.destination.selected?.id?.let(::add)
    }

    private class MapFirstApi : EcoService {
        var starts = 0

        override suspend fun searchPlaces(query: String): List<Place> = if (query.contains("Port", true)) {
            listOf(Place("destination", 45.51, 13.59, "Portorož, Slovenia", "Portorož", "Slovenia", "SI", "city")) +
                (2..5).map { Place("destination-$it", 45.51 + it / 100.0, 13.59, "Portorož result $it, Slovenia", "Portorož result $it", "A deliberately long regional address detail for scrolling", "SI", "city") }
        } else {
            listOf(Place("origin", 46.05, 14.50, "Ljubljana, Slovenia", "Ljubljana", "Slovenia", "SI", "city"))
        }

        override suspend fun startPlan(
            originId: String,
            destinationId: String,
            vehicle: JSONObject,
            roundTrip: Boolean,
            timeAllowance: TimeAllowanceRequest,
        ): String {
            starts++
            return "map-first-job"
        }

        override suspend fun job(jobId: String) = JobState(
            "complete", "", JSONObject(journeyJson).getJSONObject("outbound").toString(),
        )
        override suspend fun fuelPrice() = FuelPrice("EUR", mapOf("diesel" to 1.9), null, null, "", "", "")

        private val journeyJson = """{
          "trip_type":"one_way",
          "outbound":{
            "generated_at":"2026-10-03T10:00:00Z",
            "places":[
              {"id":"origin","lat":46.05,"lon":14.5,"label":"Ljubljana, Slovenia","title":"Ljubljana","detail":"Slovenia","country_code":"SI","kind":"city"},
              {"id":"destination","lat":45.51,"lon":13.59,"label":"Portorož, Slovenia","title":"Portorož","detail":"Slovenia","country_code":"SI","kind":"city"}
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
            "recommendations":{"0":"route-main","5":"route-main","10":"route-main","15":"route-main"},
            "routes":[{
              "id":"route-main","label":"Motorway route","distance_km":100.0,
              "provider_minutes":39.0,"known_speed_limit_pct":95.0,"ascent_m":120,"descent_m":100,
              "coordinates":[[46.05,14.5],[45.51,13.59]],
              "baseline":{"minutes":42.0,"liters":7.0,"sensitivity_liters":[6.5,7.5],"speed_kmh":[0,80]},
              "plans":{"10":{"minutes":44.0,"liters":6.5,"sensitivity_liters":[6.0,7.0],"speed_kmh":[0,75]}},
              "segments":[{"start_m":0,"end_m":100000,"name":"A1","road_class":"motorway","limit_kmh":130,"height_start_m":100,"height_end_m":10}]
            }]
          }
        }"""
    }

}
