package org.kalc.ecoroute

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DrivingModeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: PlannerViewModel
    private lateinit var api: DrivingApi
    private lateinit var source: FakeDrivingLocation

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences("eco_route_vehicle",Context.MODE_PRIVATE).edit().clear()
            .putBoolean("vehicle_setup_complete_v1",true).commit()
        api = DrivingApi()
        vm = PlannerViewModel(application, api)
        source = FakeDrivingLocation()
        vm.setDrivingLocationSource(source)
        compose.setContent { androidx.compose.material3.MaterialTheme { EcoApp(vm) } }
        compose.runOnIdle {
            vm.selectPlace(0, Place("a",46.0,14.0,"Alpha","Alpha","","SI","place"))
            vm.selectPlace(1, Place("b",46.0,14.02,"Bravo","Bravo","","SI","place"))
            vm.calculate()
        }
        compose.waitUntil(5000) { vm.state.value.journey != null }
    }

    @After fun tearDown() { InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.stopDriving() } }

    private fun start() {
        compose.onNodeWithTag("sheet-toggle").performClick()
        compose.onNodeWithTag("planner-sheet").performScrollToNode(hasTestTag("start-driving"))
        compose.onNodeWithTag("start-driving").performClick()
        compose.onNodeWithTag("confirm-start-driving").performClick()
        compose.waitUntil(4000) { source.subscribers == 1 }
        compose.onNodeWithTag("driving-screen").assertIsDisplayed()
    }

    @Test fun liveMovementLossOffRouteAndStop() {
        start()
        source.emit(14.004)
        compose.waitUntil(3000) { vm.state.value.driving.advisoryKmh != null }
        val first = vm.state.value.driving.advisoryKmh!!
        source.emit(14.0045)
        compose.waitUntil(3000) { (vm.state.value.driving.advisoryKmh ?: 0.0) > first }
        compose.onNodeWithTag("driving-advisory").assertIsDisplayed()
        compose.onNodeWithTag("driving-actual-speed").assertTextEquals("80")
        screenshot("portrait.png")
        source.emit(14.005, 46.002)
        compose.waitUntil(3000) { vm.state.value.driving.message.contains("Off route") }
        compose.onNodeWithTag("driving-advisory").assertTextEquals("—")
        source.emit(14.005)
        compose.waitUntil(3000) { vm.state.value.driving.advisoryKmh != null }
        compose.waitUntil(8000) { vm.state.value.driving.message.contains("GPS signal lost") }
        compose.onNodeWithTag("driving-advisory").assertTextEquals("—")
        compose.onNodeWithTag("stop-driving").performClick()
        compose.waitUntil(3000) { source.subscribers == 0 }
        assertFalse(vm.state.value.driving.active)
        compose.onNodeWithTag("route-map").assertIsDisplayed()
    }

    @Test fun missingLimitStillDisplaysChangingAdvisory() {
        api.limit = null
        compose.runOnIdle { vm.calculate() }
        compose.waitUntil(5000) { vm.state.value.selectedRoute != null && vm.state.value.selectedRoute?.segments?.firstOrNull()?.limitKmh == null && !vm.state.value.isCalculating }
        start()
        source.emit(14.005)
        compose.waitUntil(3000) { vm.state.value.driving.advisoryKmh != null }
        compose.onNodeWithTag("driving-advisory").assertTextEquals("85")
        compose.onNodeWithText("Mapped limit: Unknown").assertIsDisplayed()
        screenshot("unknown-limit.png")
        source.emit(14.006)
        compose.waitUntil(3000) { (vm.state.value.driving.advisoryKmh ?: 0.0) > 86 }
        compose.onNodeWithTag("driving-advisory").assertTextEquals("86")
    }

    @Test fun explicitUnlimitedIsDisplayedSeparately() {
        api.limit = null; api.unlimited = true
        compose.runOnIdle { vm.calculate() }
        compose.waitUntil(5000) { vm.state.value.selectedRoute?.segments?.firstOrNull()?.limitUnlimited == true }
        start(); source.emit(14.005)
        compose.waitUntil(3000) { vm.state.value.driving.advisoryKmh != null }
        compose.onNodeWithTag("driving-advisory").assertTextEquals("85")
        compose.onNodeWithText("Mapped limit: Unlimited").assertIsDisplayed()
    }

    @Test fun pauseResumeAndArrival() {
        start()
        source.emit(14.0199, speed = 0.0)
        compose.waitUntil(3000) { vm.state.value.driving.remainingMeters != null }
        compose.runOnIdle { vm.setDrivingVisible(false) }
        compose.waitUntil(3000) { source.subscribers == 0 }
        assertNull(vm.state.value.driving.advisoryKmh)
        compose.runOnIdle { vm.setDrivingVisible(true) }
        compose.waitUntil(3000) { source.subscribers == 1 }
        source.emit(14.02, speed = 0.0)
        compose.waitUntil(3000) { vm.state.value.driving.remainingMeters != null }
        SystemClock.sleep(50)
        source.emit(14.02, speed = 0.0)
        compose.waitUntil(3000) { vm.state.value.driving.arrived }
        compose.onNodeWithTag("driving-status").assertTextEquals("Section complete")
        assertNull(vm.state.value.driving.advisoryKmh)
    }

    @Test fun landscapeKeepsMapAndControlsVisible() {
        start()
        source.emit(14.005)
        compose.waitUntil(3000) { vm.state.value.driving.advisoryKmh != null }
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(4000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent { androidx.compose.material3.MaterialTheme { EcoApp(vm) } }
        }
        compose.waitUntil(4000) { source.subscribers == 1 }
        source.emit(14.005)
        compose.waitUntil(3000) { vm.state.value.driving.advisoryKmh != null }
        compose.onNodeWithTag("driving-advisory").assertIsDisplayed()
        compose.onNodeWithTag("stop-driving").performScrollTo().assertIsDisplayed()
        screenshot("landscape.png")

    }

    @Test fun arrivalContinuesToSelectedReturnSection() {
        compose.runOnIdle { vm.setRoundTrip(true); vm.calculate() }
        compose.waitUntil(5000) { vm.state.value.journey?.legs?.size == 2 }
        start()
        source.emit(14.02,speed = 0.0)
        compose.waitUntil(3000) { vm.state.value.driving.remainingMeters != null }
        SystemClock.sleep(50)
        source.emit(14.02,speed = 0.0)
        compose.waitUntil(3000) { vm.state.value.driving.arrived }
        compose.onNodeWithTag("continue-driving").performScrollTo().performClick()
        compose.waitUntil(3000) { vm.state.value.activeLegIndex == 1 && source.subscribers == 1 }
        assertFalse(vm.state.value.driving.arrived)
        assertNull(vm.state.value.driving.advisoryKmh)
        assertEquals(14.02,vm.state.value.selectedRoute!!.coordinates.first().lon,.0001)
    }

    @Test fun realAndroidLocationSubscriptionReceivesAndReleasesGps() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = app.getSystemService(android.location.LocationManager::class.java)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set ${app.packageName} android:mock_location allow").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        @Suppress("DEPRECATION")
        manager.addTestProvider("gps",false,false,false,false,true,true,true,
            android.location.Criteria.POWER_HIGH,android.location.Criteria.ACCURACY_FINE)
        manager.setTestProviderEnabled("gps",true)
        try {
            compose.runOnIdle { vm.setDrivingLocationSource(AndroidDrivingLocationSource(app)); vm.startDriving() }
            val location = android.location.Location("gps").apply {
                latitude = 46.0; longitude = 14.005; accuracy = 5f; speed = 20f; bearing = 90f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            // Wait for the coroutine to register with LocationManager, then inject the fresh fix.
            compose.waitForIdle()
            manager.setTestProviderLocation("gps",location)
            compose.waitUntil(5000) { vm.state.value.driving.advisoryKmh != null }
            assertEquals(72.0,vm.state.value.driving.fix!!.speedKmh!!,.01)
            compose.runOnIdle { vm.stopDriving() }
            assertFalse(vm.state.value.driving.active)
            manager.setTestProviderLocation("gps",location)
            compose.waitForIdle()
            assertNull(vm.state.value.driving.fix)
        } finally {
            compose.runOnIdle { vm.stopDriving() }
            manager.removeTestProvider("gps")
        }
    }

    private fun screenshot(name: String) {
        // Native Compose can be ready before a recreated WebView finishes loading its map.
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        fun findWebView(view: android.view.View): android.webkit.WebView? {
            if (view is android.webkit.WebView) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        compose.waitUntil(8000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                findWebView(compose.activity.window.decorView)?.evaluateJavascript(
                    "document.body.classList.contains('driving') && document.querySelectorAll('.leaflet-overlay-pane path').length > 0"
                ) { ready.set(it == "true") }
            }
            ready.get()
        }
        compose.waitForIdle()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val output = File(app.getExternalFilesDir(null), "driving-evidence/$name")
        output.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }

    private class FakeDrivingLocation : DrivingLocationSource {
        @Volatile var subscribers = 0
        @Volatile var scope: ProducerScope<DrivingFix>? = null
        override fun nowMillis() = SystemClock.elapsedRealtime()
        override fun fixes() = callbackFlow {
            scope = this; subscribers++
            awaitClose { scope = null; subscribers-- }
        }
        fun emit(lon: Double, lat: Double = 46.0, speed: Double = 80.0) {
            check(scope?.trySend(DrivingFix(lat,lon,5f,nowMillis(),speed,90.0))?.isSuccess == true)
        }
    }

    private class DrivingApi : EcoService {
        var limit: Double? = 90.0
        var unlimited = false
        override suspend fun searchPlaces(query: String) = emptyList<Place>()
        override suspend fun startPlan(originId: String,destinationId: String,vehicle: JSONObject,roundTrip: Boolean,timeAllowance: TimeAllowanceRequest) = "unused"
        override suspend fun job(jobId: String) = JobState("error","unused")
        override suspend fun fuelPrice() = FuelPrice("EUR",emptyMap(),null,null,"","","")
        override suspend fun calculateJourney(originId: String,destinationId: String,vehicle: JSONObject,roundTrip: Boolean,timeAllowance: TimeAllowanceRequest,stops: List<String>,progress: (String)->Unit): String {
            val leg = """{
          "trip_type":"one_way","generated_at":"synthetic-test","fastest_minutes":2,
          "places":[{"id":"a","lat":46,"lon":14,"label":"Alpha"},{"id":"b","lat":46,"lon":14.02,"label":"Bravo"}],
          "recommendations":{"0":"r","5":"r","10":"r","15":"r"},
          "time_allowance":{"mode":"percent","options":[{"value":0,"extra_minutes":0,"max_minutes":2},{"value":5,"extra_minutes":0.1,"max_minutes":2.1},{"value":10,"extra_minutes":0.2,"max_minutes":2.2},{"value":15,"extra_minutes":0.3,"max_minutes":2.3}]},
          "routes":[{"id":"r","label":"Synthetic route","distance_km":2,
            "coordinates":[[46,14],[46,14.02]],
            "baseline":{"minutes":2,"liters":0.1,"speed_kmh":[80,100]},
            "plans":{"10":{"minutes":2.2,"liters":0.09,"speed_kmh":[80,100]}},
            "segments":[{"start_m":0,"end_m":2000,"distance_m":2000,"name":"Test road","road_class":"motorway","limit_kmh":90,"optimize":true,"height_start_m":0,"height_end_m":0}]}]
        }"""
            val forward = JSONObject(leg)
            forward.getJSONArray("routes").getJSONObject(0).getJSONArray("segments").getJSONObject(0)
                .put("limit_kmh", limit ?: JSONObject.NULL).put("limit_unlimited", unlimited)
            if (!roundTrip) return forward.toString()
            val reverse = JSONObject(forward.toString())
            reverse.getJSONArray("routes").getJSONObject(0).put("coordinates",org.json.JSONArray("[[46,14.02],[46,14]]"))
            return JSONObject().put("trip_type","round_trip").put("legs",org.json.JSONArray().put(forward).put(reverse)).toString()
        }
    }
}
