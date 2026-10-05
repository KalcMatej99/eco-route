package org.kalc.ecoroute

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VehicleProfilesTest {
    private lateinit var application: Application

    @Before
    fun clearPreferences() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("eco_route_vehicle", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun legacyVehicleMigratesOnceWithStableIdAndFullProvenance() {
        val preferences = application.getSharedPreferences("eco_route_vehicle", Context.MODE_PRIVATE)
        preferences.edit()
            .putString(VehicleKeys.NAME, "Legacy estate")
            .putString(VehicleKeys.FUEL_TYPE, "petrol")
            .putString(VehicleKeys.MASS_KG, "1710")
            .putString(VehicleKeys.DRAG_AREA_M2, "0.71")
            .putString(VehicleKeys.EFFICIENCY_PERCENT, "29")
            .putString(VehicleKeys.ROLLING_RESISTANCE, "0.010")
            .putString(VehicleKeys.AUXILIARY_KW, "0.7")
            .putString(VehicleKeys.ENGINE_OVERHEAD_L_H, "0.5")
            .putString("vehicle_body_style_v1", VehicleBodyStyle.SALOON.wireValue)
            .putString("vehicle_assumption_origin_v1", VehicleAssumptionOrigin.ESTIMATED.wireValue)
            .commit()

        val first = PlannerViewModel(application, ProfileApi())
        val migrated = first.state.value.vehicleProfiles.single()
        assertEquals("Legacy estate", migrated.name)
        assertEquals(VehicleBodyStyle.SALOON, migrated.bodyStyle)
        assertEquals(VehicleAssumptionOrigin.ESTIMATED, migrated.assumptionOrigin)
        assertTrue(first.state.value.vehicleSetupIsLegacyReview)

        preferences.edit().putString(VehicleKeys.NAME, "Changed legacy mirror").commit()
        val relaunched = PlannerViewModel(application, ProfileApi())
        assertEquals(migrated.id, relaunched.state.value.activeVehicleProfileId)
        assertEquals("Legacy estate", relaunched.state.value.vehicle.name)
        assertEquals(1, relaunched.state.value.vehicleProfiles.size)
    }

    @Test
    fun addEditCancelSelectAndDeleteKeepProfilesAndTripLoadIndependent() {
        val vm = PlannerViewModel(application, ProfileApi())
        vm.editVehicleDraft(VehicleKeys.NAME, "First car")
        assertTrue(vm.saveVehicleDraft())
        val firstId = vm.state.value.activeVehicleProfileId
        vm.editTripLoad("88")

        vm.beginAddVehicle()
        vm.editVehicleDraft(VehicleKeys.NAME, "Discarded car")
        vm.discardVehicleDraft()
        assertEquals(listOf("First car"), vm.state.value.vehicleProfiles.map { it.name })
        assertEquals("First car", vm.state.value.vehicleDraft.name)

        vm.beginAddVehicle()
        vm.editVehicleDraft(VehicleKeys.NAME, "Second car")
        vm.editVehicleDraft(VehicleKeys.MASS_KG, "1900")
        assertTrue(vm.saveVehicleDraft())
        val secondId = vm.state.value.activeVehicleProfileId
        assertNotEquals(firstId, secondId)
        assertEquals("88", vm.state.value.tripLoadKg)

        assertTrue(vm.selectVehicleProfile(firstId))
        vm.beginVehicleSettings(secondId)
        vm.editVehicleDraft(VehicleKeys.NAME, "Second car edited")
        assertTrue(vm.saveVehicleDraft())
        assertEquals(firstId, vm.state.value.activeVehicleProfileId)
        assertEquals("First car", vm.state.value.vehicle.name)
        assertEquals("Second car edited", vm.state.value.vehicleProfiles.last().name)

        assertTrue(vm.selectVehicleProfile(secondId))
        assertTrue(vm.deleteVehicleProfile(secondId))
        assertEquals(firstId, vm.state.value.activeVehicleProfileId)
        assertEquals("First car", vm.state.value.vehicle.name)
        assertFalse(vm.deleteVehicleProfile(firstId))
        assertEquals("Keep at least one saved vehicle.", vm.state.value.vehicleDraftError)

        val relaunched = PlannerViewModel(application, ProfileApi())
        assertEquals(firstId, relaunched.state.value.activeVehicleProfileId)
        assertEquals("88", relaunched.state.value.tripLoadKg)
        assertEquals(listOf("First car"), relaunched.state.value.vehicleProfiles.map { it.name })
    }

    @Test
    fun switchingActiveProfileCancelsInFlightRouteAndNextRequestUsesSelection() {
        val api = ProfileApi(blockPlanning = true)
        val vm = PlannerViewModel(application, api)
        vm.editVehicleDraft(VehicleKeys.NAME, "First car")
        assertTrue(vm.saveVehicleDraft())
        val firstId = vm.state.value.activeVehicleProfileId
        vm.beginAddVehicle()
        vm.editVehicleDraft(VehicleKeys.NAME, "Second car")
        assertTrue(vm.saveVehicleDraft())
        val secondId = vm.state.value.activeVehicleProfileId

        vm.selectPlace(0, Place("a", 46.0, 14.0, "A", "A", "", "SI", "city"))
        vm.selectPlace(1, Place("b", 46.1, 14.1, "B", "B", "", "SI", "city"))
        vm.calculate()
        waitUntil { vm.state.value.isCalculating && api.vehicle != null }
        assertTrue(vm.state.value.isCalculating)
        assertTrue(vm.selectVehicleProfile(firstId))
        assertFalse(vm.state.value.isCalculating)
        assertEquals(null, vm.state.value.journey)

        api.blockPlanning = false
        vm.calculate()
        waitUntil { api.vehicle?.getString("name") == "First car" }
        assertEquals("First car", api.vehicle?.getString("name"))
        assertTrue(vm.selectVehicleProfile(secondId))
        vm.calculate()
        waitUntil { api.vehicle?.getString("name") == "Second car" }
        assertEquals("Second car", api.vehicle?.getString("name"))
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; Thread.sleep(20) }
        assertTrue(condition())
    }

    private class ProfileApi(var blockPlanning: Boolean = false) : EcoService {
        var vehicle: JSONObject? = null
        override suspend fun searchPlaces(query: String) = emptyList<Place>()
        override suspend fun startPlan(
            originId: String,
            destinationId: String,
            vehicle: JSONObject,
            roundTrip: Boolean,
            timeAllowance: TimeAllowanceRequest,
        ): String {
            this.vehicle = JSONObject(vehicle.toString())
            if (blockPlanning) delay(10_000)
            return "profile-test"
        }
        override suspend fun job(jobId: String) = JobState("error", "Expected profile test stop")
        override suspend fun fuelPrice() = FuelPrice("EUR", emptyMap(), null, null, "", "", "")
    }
}
