package org.kalc.ecoroute

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurrentLocationFlowTest {
    private lateinit var application: Application
    @Before fun setup() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("eco_route_vehicle", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun findingLocationBlocksPlanningAndManualAddressCancelsThePendingFix() {
        val api=LocationApi()
        val vm=PlannerViewModel(application,api)
        vm.selectPlace(0,place("old"));vm.selectPlace(1,place("destination"))
        val fix=CompletableDeferred<CurrentLocation>()
        vm.requestCurrentLocation { fix.await() }
        vm.calculate()
        assertTrue(vm.state.value.isLocating)
        assertEquals(0,api.starts)
        vm.editAddress(0,"Manual start")
        fix.complete(CurrentLocation(46.05,14.50,25f,System.currentTimeMillis(),false))
        Thread.sleep(100)
        assertEquals("Manual start",vm.state.value.origin.text)
        assertNull(vm.state.value.origin.selected)
        assertFalse(vm.state.value.isLocating)
        assertFalse(vm.state.value.locationSelectionPending)
    }
    @Test fun approximateSuccessHasAccuracyAndIsConsumedOnceWhileFailurePreservesOrigin() {
        val vm=PlannerViewModel(application,LocationApi())
        vm.requestCurrentLocation {CurrentLocation(46.05,14.50,1800f,System.currentTimeMillis(),true)}
        waitUntil {vm.state.value.origin.selected?.kind=="current_location"}
        assertTrue(vm.state.value.locationNotice.orEmpty().contains("Approximate"))
        assertTrue(vm.state.value.locationNotice.orEmpty().contains("1800 m"))
        assertTrue(vm.state.value.locationSelectionPending)
        vm.consumeCurrentLocationSelection()
        assertFalse(vm.state.value.locationSelectionPending)
        val version=vm.state.value.locationSelectionVersion
        vm.requestCurrentLocation {throw LocationDisabledException()}
        waitUntil {!vm.state.value.isLocating}
        assertEquals("current",vm.state.value.origin.selected?.id)
        assertEquals(version,vm.state.value.locationSelectionVersion)
        assertTrue(vm.state.value.locationError.orEmpty().contains("Turn on"))
        vm.locationPermissionDenied()
        assertTrue(vm.state.value.locationError.orEmpty().contains("permission"))
    }
    private fun waitUntil(condition:()->Boolean) {repeat(100) {if(condition()) return;Thread.sleep(20)};assertTrue(condition())}
    private fun place(id:String)=Place(id,46.0,14.0,id,id,"","SI","city")
    private class LocationApi:EcoService {
        var starts=0
        override suspend fun searchPlaces(query:String)=emptyList<Place>()
        override suspend fun registerCurrentPlace(latitude:Double,longitude:Double)=Place("current",latitude,longitude,"Current location","Current location","","","current_location")
        override suspend fun startPlan(originId:String,destinationId:String,vehicle:JSONObject,roundTrip:Boolean,timeAllowance:TimeAllowanceRequest):String {starts++;return "test"}
        override suspend fun job(jobId:String)=JobState("error","test")
        override suspend fun fuelPrice()=FuelPrice("EUR",emptyMap(),null,null,"","","")
    }
}
