package org.kalc.ecoroute

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecentPlacesFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: Application
    private lateinit var api: SearchApi
    private lateinit var vm: PlannerViewModel
    private val a = Place("a",46.0,14.0,"Ljubljana, Slovenia","Ljubljana","Slovenia","SI","city")
    private val b = Place("b",46.0,14.02,"Šolska pot, Tolmin, 5220, Slovenia","Šolska pot","Tolmin, 5220, Slovenia","SI","street")

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("eco_route_vehicle",Context.MODE_PRIVATE).edit().clear()
            .putBoolean("vehicle_setup_complete_v1",true).putString("trip_load_kg_v1","125").commit()
        // Populate via real selections, then restore into a fresh session/service registry.
        val first = PlannerViewModel(app,SearchApi())
        first.selectPlace(0,a); first.selectPlace(1,b)
        api = SearchApi(); vm = PlannerViewModel(app,api)
        compose.setContent { screen() }
    }
    @Composable private fun screen() {
        val state by vm.state.collectAsStateWithLifecycle()
        MaterialTheme { MapPlannerScreen(state,vm,onOpenVehicle={},onExport={}) }
    }
    private fun open() {
        compose.onNodeWithTag("start-destination-search").performClick()
        compose.onNodeWithTag("destination-field").assertIsDisplayed()
    }
    private fun recent(id: String) = compose.onNodeWithTag("recent-address-suggestion-$id")

    @Test fun restoredRecentEndpointsCalculateWithoutAnotherAddressSearch() {
        open()
        recent("b").assertIsDisplayed().performClick()
        compose.onNodeWithTag("origin-field").assertIsDisplayed()
        recent("a").performScrollTo().performClick()
        compose.waitUntil(3000) { vm.state.value.journey != null }
        assertEquals(0,api.searches)
        assertEquals(listOf("a","b"),api.itinerary)
        assertEquals(a,api.registered["a"]); assertEquals(b,api.registered["b"])
        assertEquals(listOf("a","b"),vm.state.value.recentPlaces.map { it.id })
        compose.onNodeWithTag("route-map").assertIsDisplayed()
    }
    @Test fun existingDestinationCanBeReplacedByARecentWithoutTyping() {
        val c = b.copy(id="c",lon=14.03,title="Third place",label="Third place")
        compose.runOnIdle { vm.selectPlace(0,a); vm.selectPlace(1,c); vm.selectPlace(1,b) }
        compose.onNodeWithTag("edit-destination").performClick()
        recent("c").performScrollTo().assertIsDisplayed()
        assertEquals(b,vm.state.value.destination.selected)
        recent("c").performClick()
        compose.waitUntil(3000) { vm.state.value.journey != null }
        assertEquals(listOf("a","c"),api.itinerary)
        assertEquals(0,api.searches)
    }
    @Test fun immediateMatchingRecentsPrecedeDeduplicatedOnlineResults() {
        api.results = listOf(b.copy(id="duplicate"),b.copy(id="other",title="Solska cesta",label="Solska cesta, Tolmin"))
        open(); compose.onNodeWithTag("destination-field").performTextInput("solska")
        recent("b").assertExists()
        compose.waitUntil(3000) { vm.state.value.destination.suggestions.isNotEmpty() }
        compose.onNodeWithTag("address-suggestion-duplicate").assertDoesNotExist()
        val recentTop = recent("b").fetchSemanticsNode().boundsInRoot.top
        val onlineTop = compose.onNodeWithTag("address-suggestion-other").fetchSemanticsNode().boundsInRoot.top
        assertTrue(recentTop < onlineTop)
        screenshot("portrait-keyboard.png")
        assertEquals(2,vm.state.value.recentPlaces.size) // Typing/search results do not become history.
    }
    @Test fun recentCanBeSelectedWhileOnlineSearchIsPendingOrUnavailable() {
        api.gate = CompletableDeferred()
        open(); compose.onNodeWithTag("destination-field").performTextInput("sol")
        compose.waitUntil(3000) { api.searches == 1 }
        recent("b").performScrollTo().performClick()
        api.gate!!.complete(listOf(a))
        compose.waitForIdle()
        assertEquals(b,vm.state.value.destination.selected)
        assertTrue(vm.state.value.destination.suggestions.isEmpty())
        assertFalse(vm.state.value.destination.isSearching)
    }
    @Test fun onlineFailureLeavesRecentSelectionAvailable() {
        api.fail = true
        open(); compose.onNodeWithTag("destination-field").performTextInput("sol")
        compose.waitUntil(3000) { vm.state.value.destination.error != null }
        recent("b").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(b,vm.state.value.destination.selected)
        assertNull(vm.state.value.destination.error)
    }
    @Test fun removeAndClearPersistWithoutSelectingOrChangingCarAndLoad() {
        val vehicle = vm.state.value.vehicle
        open()
        compose.onNodeWithTag("remove-recent-address-suggestion-b").performScrollTo().performClick()
        recent("b").assertDoesNotExist(); assertNull(vm.state.value.destination.selected)
        assertEquals(listOf(a),PlannerViewModel(app,SearchApi()).state.value.recentPlaces)
        compose.onNodeWithTag("clear-recent-address-suggestion").performScrollTo().performClick()
        recent("a").assertDoesNotExist()
        val restored = PlannerViewModel(app,SearchApi()).state.value
        assertTrue(restored.recentPlaces.isEmpty())
        assertEquals(vehicle,restored.vehicle); assertEquals("125",restored.tripLoadKg)
        assertEquals(0,api.searches)
    }
    @Test fun unmatchedSearchHasNormalEmptyStateWithoutSavingQuery() {
        open(); compose.onNodeWithTag("destination-field").performTextInput("unknown")
        compose.waitUntil(3000) { vm.state.value.destination.error != null }
        recent("a").assertDoesNotExist(); recent("b").assertDoesNotExist()
        compose.onNodeWithText("No matching places found. Add a town, postcode, or country.").performScrollTo().assertIsDisplayed()
        assertEquals(2,vm.state.value.recentPlaces.size)
    }
    @Test fun stopsReuseTheSameHistoryAndPromoteSelectedPlace() {
        var stopId = 0L
        compose.runOnIdle {
            vm.selectPlace(0,a); vm.selectPlace(1,b)
            stopId = vm.addStop()!!
            vm.selectPlace(1,b.copy(id="c",lat=46.1,title="Third place",label="Third place"))
            vm.editStop(stopId,"")
        }
        compose.onNodeWithTag("sheet-toggle").performClick()
        compose.onNodeWithTag("recent-stop-suggestion-$stopId-b").performScrollTo().performClick()
        assertEquals(b,vm.state.value.stops.single().address.selected)
        assertEquals(b,vm.state.value.recentPlaces.first())
    }
    @Test fun landscapeHistoryRemainsScrollableAndSelectable() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(4000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.setContent { screen() } }
        open()
        recent("a").performScrollTo().assertIsDisplayed()
        screenshot("landscape.png")
        recent("a").performClick()
        assertEquals(a,vm.state.value.destination.selected)
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File(app.getExternalFilesDir(null),"recent-evidence/$name")
        file.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }
    private class SearchApi : EcoService {
        @Volatile var searches = 0
        var results = emptyList<Place>()
        var fail = false
        var gate: CompletableDeferred<List<Place>>? = null
        val registered = mutableMapOf<String,Place>()
        var itinerary = emptyList<String>()
        override fun registerSelectedPlace(place: Place) { registered[place.id] = place }
        override suspend fun searchPlaces(query: String): List<Place> {
            searches++
            if (fail) throw EcoNetworkException("Address search unavailable. Please retry.",java.io.IOException("offline test"))
            return gate?.await() ?: results
        }
        override suspend fun fuelPrice() = FuelPrice("EUR",emptyMap(),null,null,"","","")
        override suspend fun startPlan(originId: String,destinationId: String,vehicle: JSONObject,roundTrip: Boolean,timeAllowance: TimeAllowanceRequest) = "unused"
        override suspend fun job(jobId: String) = JobState("error","unused")
        override suspend fun calculateJourney(originId: String,destinationId: String,vehicle: JSONObject,roundTrip: Boolean,timeAllowance: TimeAllowanceRequest,stops: List<String>,progress: (String)->Unit): String {
            itinerary = listOf(originId) + stops + destinationId
            val from = registered.getValue(originId); val to = registered.getValue(destinationId)
            val plan = JSONObject().put("minutes",2).put("liters",.1).put("speed_kmh",JSONArray(listOf(80,100)))
            val route = JSONObject().put("id","r").put("label","Synthetic route").put("distance_km",2)
                .put("coordinates",JSONArray().put(JSONArray(listOf(from.lat,from.lon))).put(JSONArray(listOf(to.lat,to.lon))))
                .put("baseline",plan).put("plans",JSONObject().put("10",plan))
                .put("segments",JSONArray().put(JSONObject().put("start_m",0).put("end_m",2000).put("name","Test road").put("road_class","motorway").put("limit_kmh",90)))
            fun place(p: Place) = JSONObject().put("id",p.id).put("lat",p.lat).put("lon",p.lon).put("label",p.label)
            return JSONObject().put("trip_type","one_way").put("routes",JSONArray().put(route)).put("places",JSONArray().put(place(from)).put(place(to)))
                .put("fastest_minutes",2).put("recommendations",JSONObject().put("0","r").put("5","r").put("10","r").put("15","r"))
                .put("time_allowance",JSONObject().put("mode","percent").put("options",JSONArray((listOf(0,5,10,15)).map {
                    JSONObject().put("value",it).put("extra_minutes",2.0*it/100).put("max_minutes",2.0*(1+it/100.0))
                }))).toString()
        }
    }
}
