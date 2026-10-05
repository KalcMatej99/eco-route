package org.kalc.ecoroute

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HybridArchitectureTest {
    @Test fun bundledCatalogueAndCurrentPlaceRequireNoServer() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val api = EcoApi(application)
        val result = api.searchVehicles("Volvo XC90 B5")
        assertEquals(5, result.totalMatches)
        assertTrue(result.vehicles.all { it.supportedForModel && it.fuelType == "diesel" && it.massKg != null })
        val point = api.registerCurrentPlace(46.05,14.5)
        assertEquals("current_location",point.kind)
        assertEquals(46.05,point.lat,0.0)
    }
    @Test fun localTimeoutClearsBusyStateAndShowsRetry() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences("eco_route_vehicle",Context.MODE_PRIVATE).edit().clear().putBoolean("vehicle_setup_complete_v1",true).commit()
        val api = object : EcoService {
            override suspend fun searchPlaces(query: String) = emptyList<Place>()
            override suspend fun startPlan(originId: String, destinationId: String, vehicle: JSONObject, roundTrip: Boolean, timeAllowance: TimeAllowanceRequest) = "unused"
            override suspend fun job(jobId: String) = JobState("error","unused")
            override suspend fun fuelPrice() = FuelPrice("EUR",emptyMap(),null,null,"","","")
            override suspend fun calculateJourney(originId: String, destinationId: String, vehicle: JSONObject, roundTrip: Boolean, timeAllowance: TimeAllowanceRequest, stops: List<String>, progress: (String) -> Unit): String = withTimeout(20) { delay(1000); "unreachable" }
        }
        val vm = PlannerViewModel(application,api)
        vm.selectPlace(0,Place("a",46.0,14.0,"A","A","","","place"))
        vm.selectPlace(1,Place("b",46.06,14.0,"B","B","","","place"))
        vm.calculate()
        repeat(100) {
            if (vm.state.value.error != null) return@repeat
            Thread.sleep(20)
        }
        assertFalse(vm.state.value.isCalculating);assertNull(vm.state.value.progressMessage)
        assertTrue(vm.state.value.error.orEmpty().contains("stopped"))
    }
}
