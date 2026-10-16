package org.kalc.ecoroute

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TimeFuelGraphTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var api: GraphApi
    private lateinit var vm: PlannerViewModel
    @Before fun setUp() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("eco_route_vehicle",Context.MODE_PRIVATE).edit().clear().putBoolean("vehicle_setup_complete_v1",true).commit()
        api=GraphApi();vm=PlannerViewModel(app,api)
        compose.runOnIdle {
            vm.selectPlace(0,Place("a",46.0,14.0,"Alpha","Alpha","","SI","city"))
            vm.selectPlace(1,Place("b",46.0,14.02,"Bravo","Bravo","","SI","city"))
            vm.calculate()
        }
        compose.setContent { MaterialTheme { EcoApp(vm) } }
        compose.waitUntil(5000) { vm.state.value.journey != null }
    }
    private fun expand() {
        if (compose.onAllNodesWithContentDescription("Expand trip details").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("sheet-toggle").performClick()
    }
    private fun node(tag:String):SemanticsNodeInteraction {
        expand()
        compose.onNodeWithTag("planner-sheet").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    @Test fun tappingDotsChangesRouteSpeedsTotalsCsvAndDrivingPlan() {
        val graph=vm.state.value.tradeoffs!!
        assertEquals(3,graph.choices.size)
        assertEquals("fast",vm.state.value.selectedRoute!!.id)
        assertEquals(80.0,vm.state.value.selectedPlan!!.speedKmh.first(),0.0)
        // Tap the real last dot in the plot, then the fastest dot.
        node("time-fuel-plot").performTouchInput { click(Offset(width-18*compose.density.density,16*compose.density.density)) }
        compose.waitForIdle()
        assertEquals(graph.choices.last().id,vm.state.value.selectedChoiceId)
        assertEquals("local",vm.state.value.selectedRoute!!.id)
        assertEquals(55.0,vm.state.value.totals!!.minutes,0.0)
        assertEquals(2.7,vm.state.value.totals!!.liters,0.0)
        assertEquals(50.0,vm.state.value.selectedPlan!!.speedKmh.first(),0.0)
        val payload=JSONObject(vm.state.value.mapPayloadJson)
        assertEquals("local",payload.getJSONObject("route").getString("id"))
        assertEquals(50.0,payload.getJSONObject("plan").getJSONArray("speed_kmh").getDouble(0),0.0)
        assertEquals(0,payload.getJSONArray("alternatives").length())
        assertTrue(vm.exportCsv()!!.contains("\"55.0\",\"2.7\""))
        assertTrue(vm.exportCsv()!!.contains("\"50.0\",\"50.0\""))
        val guidance=DrivingGuide(vm.state.value.selectedRoute!!,vm.state.value.selectedPlan!!).update(DrivingFix(46.01,14.01,5f,1000,50.0,null),1000)
        assertEquals(50.0,guidance.advisoryKmh!!,0.0)
        assertEquals(3,payload.getJSONObject("route").getJSONArray("coordinates").length())
        screenshot("portrait-selected-local.png")
        node("time-fuel-plot").performTouchInput { click(Offset(54*compose.density.density,height-32*compose.density.density)) }
        compose.waitForIdle()
        assertEquals(graph.baseline.id,vm.state.value.selectedChoiceId)
        assertEquals(100.0,vm.state.value.selectedPlan!!.speedKmh.first(),0.0)
        assertEquals(0.0,vm.state.value.totals!!.litersSaved,0.0)
        assertEquals(1,api.starts)
        node("tradeoff-previous").assertIsNotEnabled()
        screenshot("portrait-fastest.png")
    }
    @Test fun accessibleSelectionAndButtonsUseTheSameWholeJourneyPoint() {
        node("time-fuel-plot").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertEquals(vm.state.value.tradeoffs!!.baseline.id,vm.state.value.selectedChoiceId)
        node("tradeoff-next").performClick()
        assertEquals(44.0,vm.state.value.totals!!.minutes,0.0)
        node("tradeoff-next").performClick()
        node("tradeoff-next").assertIsNotEnabled()
        node("tradeoff-previous").performClick()
        assertEquals(44.0,vm.state.value.totals!!.minutes,0.0)
    }
    @Test fun roundTripPointSelectsPlansForBothSectionsAndSurvivesSectionSwitch() {
        compose.runOnIdle { vm.setRoundTrip(true);vm.calculate() }
        compose.waitUntil(5000) { vm.state.value.journey?.legs?.size==2 }
        val graph=vm.state.value.tradeoffs!!
        val mixed=graph.choices.first { it.legs.map { l -> l.route.id }.distinct().size>1 }
        compose.runOnIdle { vm.selectChoice(mixed.id) }
        assertEquals(mixed.legs.map { it.route.id },vm.state.value.selectedRouteIds)
        assertEquals(mixed.minutes,vm.state.value.totals!!.minutes,0.0)
        val csv=vm.exportCsv()!!
        assertEquals(2,csv.lines().count { it.startsWith("\"section_total\"") })
        compose.runOnIdle { vm.selectLeg(1) }
        assertEquals(mixed.id,vm.state.value.selectedChoiceId)
        assertEquals(mixed.legs[1].route.id,vm.state.value.selectedRoute!!.id)
        assertEquals(mixed.legs[1].plan.speedKmh,vm.state.value.selectedPlan!!.speedKmh)
        node("time-fuel-plot").assertIsDisplayed()
        screenshot("roundtrip-graph.png")
    }
    @Test fun loadingErrorAndSinglePointDoNotLeaveAnOldSelectableGraph() {
        val gate=CompletableDeferred<Unit>();api.gate=gate
        compose.runOnIdle { vm.calculate() }
        compose.waitUntil(3000) { vm.state.value.isCalculating }
        assertNull(vm.state.value.tradeoffs);assertNull(vm.state.value.selectedChoice)
        api.fail=true;gate.complete(Unit)
        compose.waitUntil(3000) { vm.state.value.error != null }
        assertNull(vm.state.value.tradeoffs)
        api.gate=null;api.fail=false;api.single=true
        compose.runOnIdle { vm.calculate() }
        compose.waitUntil(3000) { vm.state.value.journey != null }
        assertEquals(1,vm.state.value.tradeoffs!!.choices.size)
        compose.onNodeWithTag("graph-balance").assertDoesNotExist()
        compose.onNodeWithTag("graph-most-saved").assertDoesNotExist()
        node("time-fuel-plot").assertIsDisplayed()
        compose.onNodeWithTag("planner-sheet").performScrollToNode(hasText("No lower-fuel alternative was found for this journey."))
        compose.onNodeWithText("No lower-fuel alternative was found for this journey.").assertIsDisplayed()
        screenshot("single-point.png")
    }
    @Test fun landscapeGraphAndSelectionRemainUsable() {
        compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(4000) { compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.setContent { MaterialTheme { EcoApp(vm) } } }
        node("time-fuel-plot").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        assertEquals("local",vm.state.value.selectedRoute!!.id)
        screenshot("landscape-graph.png")
        node("tradeoff-selection").assertIsDisplayed()
        screenshot("landscape-selection.png")
    }
    @Test fun unitsAndHighlightedChoicesPreserveRoutesAndExplainUnavailablePrices() {
        val graph = vm.state.value.tradeoffs!!
        node("graph-fastest").performClick()
        assertEquals(graph.baseline.id, vm.state.value.selectedChoiceId)
        node("graph-balance").performClick()
        assertEquals(graph.suggestedBalance!!.id, vm.state.value.selectedChoiceId)
        node("graph-most-saved").performClick()
        assertEquals(graph.mostFuelSaved.id, vm.state.value.selectedChoiceId)
        val csv = vm.exportCsv()
        node("graph-unit-euros").performClick().assertIsSelected()
        node("time-fuel-plot").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
            "Point 3 of 3, 15.0 minutes later, 2.60 euros saved"))
        screenshot("euros-graph.png")
        assertEquals(csv, vm.exportCsv())
        node("time-fuel-plot").performTouchInput { click(Offset(54*compose.density.density,height-32*compose.density.density)) }
        assertEquals(graph.baseline.id, vm.state.value.selectedChoiceId)
        node("graph-unit-litres").performClick().assertIsSelected()
        assertEquals(graph.baseline.id, vm.state.value.selectedChoiceId)
        node("graph-unit-euros").performClick()
        api.expiredPrice = true
        compose.runOnIdle { vm.refreshFuelPrice() }
        compose.waitUntil(3000) { vm.state.value.fuelPrice?.currentPrice(vm.state.value.vehicle.fuelType) == null }
        node("graph-unit-euros").assertIsNotEnabled()
        node("graph-unit-litres").assertIsSelected()
        assertEquals(graph.baseline.id, vm.state.value.selectedChoiceId)
        screenshot("unavailable-price.png")
    }

    private fun screenshot(name:String) {
        compose.waitForIdle()
        val root=compose.activity.window.decorView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val b=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(android.graphics.Canvas(b))
            val dir=File(compose.activity.getExternalFilesDir(null),"graph-evidence").apply { mkdirs() }
            File(dir,name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
        }
    }
    private class GraphApi:EcoService {
        var expiredPrice=false
        var starts=0;var gate:CompletableDeferred<Unit>?=null;var fail=false;var single=false
        override suspend fun searchPlaces(query:String)=emptyList<Place>()
        override suspend fun fuelPrice()=FuelPrice("EUR",mapOf("diesel" to 2.0),LocalDate.now().minusDays(1),LocalDate.now().plusDays(if(expiredPrice) -1 else 1),"","Test","")
        override suspend fun startPlan(originId:String,destinationId:String,vehicle:JSONObject,roundTrip:Boolean,timeAllowance:TimeAllowanceRequest)="unused"
        override suspend fun job(jobId:String)=JobState("error","unused")
        override suspend fun calculateJourney(originId:String,destinationId:String,vehicle:JSONObject,roundTrip:Boolean,timeAllowance:TimeAllowanceRequest,stops:List<String>,progress:(String)->Unit):String {
            starts++;gate?.await();if(fail) throw EcoApiException("Test offline")
            fun point(id:String)=JSONObject().put("id",id).put("lat",46.0).put("lon",if(id=="a")14.0 else 14.02).put("label",id)
            fun leg(a:String,b:String,scale:Double):JSONObject {
                fun plan(t:Double,f:Double,s:Double)=JSONObject().put("minutes",t*scale).put("liters",f*scale).put("speed_kmh",JSONArray(listOf(s,s)))
                fun route(id:String,t:Double,f:Double,s:Double):JSONObject {
                    val baseline=plan(t,f,s)
                    val plans=JSONObject().put("10",if(id=="fast" && !single)plan(44.0,3.4,80.0) else baseline)
                    return JSONObject().put("id",id).put("label",id).put("distance_km",50*scale).put("baseline",baseline).put("plans",plans)
                        .put("coordinates",JSONArray().put(JSONArray(listOf(46.0,14.0))).apply { if(id=="local")put(JSONArray(listOf(46.01,14.01))) }.put(JSONArray(listOf(46.0,14.02))))
                        .put("segments",JSONArray().put(JSONObject().put("start_m",0).put("end_m",50000*scale).put("name","Test road").put("road_class","motorway").put("limit_kmh",110)))
                }
                val routes=JSONArray().put(route("fast",40.0,4.0,100.0));if(!single)routes.put(route("local",55.0,2.7,50.0))
                return JSONObject().put("routes",routes).put("places",JSONArray().put(point(a)).put(point(b))).put("fastest_minutes",40*scale)
                    .put("recommendations",JSONObject().put("0","fast").put("5","fast").put("10","fast").put("15","fast"))
                    .put("time_allowance",JSONObject().put("mode","percent").put("options",JSONArray(listOf(0,5,10,15).map {
                        JSONObject().put("value",it).put("extra_minutes",40*scale*it/100).put("max_minutes",40*scale*(1+it/100.0))
                    })))
            }
            if(!roundTrip)return leg(originId,destinationId,1.0).toString()
            return JSONObject().put("trip_type","round_trip").put("round_trip",true)
                .put("legs",JSONArray().put(leg(originId,destinationId,1.0)).put(leg(destinationId,originId,.5)))
                .put("places",JSONArray().put(point(originId)).put(point(destinationId)).put(point(originId))).toString()
        }
    }
}
