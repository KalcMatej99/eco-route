package org.kalc.ecoroute

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TheoreticalModeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: Application
    private lateinit var api: ModeApi
    private lateinit var vm: PlannerViewModel
    @Before fun setUp() {
        app=ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("eco_route_vehicle",Context.MODE_PRIVATE).edit().clear()
            .putBoolean("vehicle_setup_complete_v1",true).putString("trip_load_kg_v1","125").commit()
        api=ModeApi();vm=PlannerViewModel(app,api)
        compose.runOnIdle {
            vm.selectPlace(0,Place("a",46.0,14.0,"Alpha","Alpha","","SI","city"))
            vm.selectPlace(1,Place("b",46.0,14.02,"Bravo","Bravo","","SI","city"))
            vm.calculate()
        }
        compose.setContent { MaterialTheme { EcoApp(vm) } }
        compose.waitUntil(5000) { vm.state.value.journey != null }
    }
    private fun open() { compose.onNodeWithTag("sheet-toggle").performClick() }
    private fun node(tag:String):SemanticsNodeInteraction {
        compose.onNodeWithTag("planner-sheet").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun toggle() { node("ignore-speed-limits").performClick() }
    private fun recalculate() { node("recalculate").performClick();compose.waitUntil(5000) { vm.state.value.journey != null };compose.waitForIdle();
        val collapsed=compose.onAllNodesWithContentDescription("Expand trip details").fetchSemanticsNodes().isNotEmpty()
        if (collapsed) compose.onNodeWithTag("sheet-toggle").performClick()
    }
    @Test fun defaultOffAndPersistentSettingPreservesExistingCarLoadAndHistory() {
        assertFalse(vm.state.value.ignoreSpeedLimits)
        val before=vm.state.value
        compose.runOnIdle { vm.setIgnoreSpeedLimits(true) }
        val restored=PlannerViewModel(app,ModeApi()).state.value
        assertTrue(restored.ignoreSpeedLimits)
        assertEquals(before.vehicle,restored.vehicle)
        assertEquals(before.vehicleProfiles,restored.vehicleProfiles)
        assertEquals(before.tripLoadKg,restored.tripLoadKg)
        assertEquals(before.recentPlaces,restored.recentPlaces)
        compose.runOnIdle { vm.setIgnoreSpeedLimits(false) }
        assertFalse(PlannerViewModel(app,ModeApi()).state.value.ignoreSpeedLimits)
    }
    @Test fun toggleRecalculatesTheoreticalPlanLabelsCsvAndRequiresNormalPlanForDriving() {
        open();toggle()
        assertNull(vm.state.value.journey)
        recalculate()
        assertEquals(listOf(false,true),api.modes)
        assertTrue(vm.state.value.journey!!.ignoresSpeedLimits)
        node("theoretical-mode-label").assertIsDisplayed()
        node("start-driving").assertIsNotEnabled()
        compose.runOnIdle { vm.startDriving() };assertFalse(vm.state.value.driving.active)
        val csv=vm.exportCsv()!!
        assertTrue(csv.lines().first().substringAfterLast(',').trim('"') == "calculation_mode")
        assertTrue(csv.lines().drop(1).all { it.substringAfterLast(',').trim('"') == "theoretical_ignore_speed_limits" })
        assertTrue(vm.exportFileName().contains("theoretical"))
        screenshot("portrait-theoretical-results.png")
        toggle();recalculate()
        node("start-driving").assertIsEnabled()
        assertFalse(vm.state.value.journey!!.ignoresSpeedLimits)
        assertTrue(vm.exportCsv()!!.contains("mapped_speed_limits"))
    }
    @Test fun modeChangeCancelsPendingCalculationAndCannotAcceptItsStaleResult() {
        val gate=CompletableDeferred<Unit>();api.gate=gate
        compose.runOnIdle { vm.calculate() }
        compose.waitUntil(3000) { vm.state.value.isCalculating }
        compose.runOnIdle { vm.setIgnoreSpeedLimits(true) }
        assertFalse(vm.state.value.isCalculating);assertNull(vm.state.value.journey)
        api.staleFailure=true;gate.complete(Unit);compose.waitForIdle()
        assertNull(vm.state.value.journey);assertNull(vm.state.value.error)
        assertTrue(vm.state.value.ignoreSpeedLimits)
    }
    @Test fun mismatchedOrFailedResponseKeepsTheoreticalModeAndNoDrivingPlan() {
        compose.runOnIdle { vm.setIgnoreSpeedLimits(true);api.badMode=true;vm.calculate() }
        compose.waitUntil(3000) { vm.state.value.error != null }
        assertTrue(vm.state.value.error!!.contains("speed-limit setting"))
        assertNull(vm.state.value.journey)
        compose.runOnIdle { api.badMode=false;api.fail=true;vm.calculate() }
        compose.waitUntil(3000) { vm.state.value.error=="Test offline" }
        assertTrue(vm.state.value.ignoreSpeedLimits);assertNull(vm.state.value.journey)
        open();compose.onNodeWithText("Test offline").performScrollTo().assertIsDisplayed()
        screenshot("theoretical-error.png")
    }
    @Test fun landscapeSettingAndTheoreticalResultRemainUsable() {
        compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(4000) { compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.setContent { MaterialTheme { EcoApp(vm) } } }
        open();toggle();recalculate()
        node("theoretical-mode-label").assertIsDisplayed()
        node("start-driving").assertIsNotEnabled()
        screenshot("landscape-theoretical-results.png")
    }
    private fun screenshot(name:String) {
        val root=compose.activity.window.decorView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            File(app.getExternalFilesDir(null),"theoretical-evidence").apply { mkdirs() }.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }
    private class ModeApi:EcoService {
        val modes=mutableListOf<Boolean>()
        var gate:CompletableDeferred<Unit>?=null
        var badMode=false;var fail=false;var staleFailure=false
        override suspend fun searchPlaces(query:String)=emptyList<Place>()
        override suspend fun fuelPrice()=FuelPrice("EUR",emptyMap(),null,null,"","","")
        override suspend fun startPlan(originId:String,destinationId:String,vehicle:JSONObject,roundTrip:Boolean,timeAllowance:TimeAllowanceRequest)="unused"
        override suspend fun job(jobId:String)=JobState("error","unused")
        override suspend fun calculateJourney(originId:String,destinationId:String,vehicle:JSONObject,roundTrip:Boolean,timeAllowance:TimeAllowanceRequest,stops:List<String>,ignoreSpeedLimits:Boolean,progress:(String)->Unit):String {
            modes.add(ignoreSpeedLimits)
            gate?.let { withContext(NonCancellable) { it.await();if (staleFailure) throw EcoApiException("Stale failure") } }
            if(fail) throw EcoApiException("Test offline")
            val mode=ignoreSpeedLimits && !badMode
            val plan=JSONObject().put("minutes",2).put("liters",.1).put("speed_kmh",JSONArray(listOf(if(mode)100 else 40,if(mode)100 else 40)))
            val route=JSONObject().put("id","r").put("label","Synthetic route").put("distance_km",2).put("ignore_speed_limits",mode)
                .put("coordinates",JSONArray().put(JSONArray(listOf(46.0,14.0))).put(JSONArray(listOf(46.0,14.02))))
                .put("baseline",plan).put("plans",JSONObject().put("10",plan))
                .put("segments",JSONArray().put(JSONObject().put("start_m",0).put("end_m",2000).put("name","Test road").put("road_class","residential").put("limit_kmh",50)))
            fun place(id:String,lon:Double)=JSONObject().put("id",id).put("lat",46.0).put("lon",lon).put("label",if(id=="a")"Alpha" else "Bravo")
            return JSONObject().put("routes",JSONArray().put(route)).put("places",JSONArray().put(place(originId,14.0)).put(place(destinationId,14.02)))
                .put("fastest_minutes",2).put("recommendations",JSONObject().put("0","r").put("5","r").put("10","r").put("15","r"))
                .put("time_allowance",JSONObject().put("mode","percent").put("options",JSONArray(listOf(0,5,10,15).map {
                    JSONObject().put("value",it).put("extra_minutes",2.0*it/100).put("max_minutes",2.0*(1+it/100.0))
                }))).toString()
        }
    }
}
