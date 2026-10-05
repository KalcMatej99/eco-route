package org.kalc.ecoroute

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsProperties
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.io.FileInputStream

private const val PREFS = "eco_route_vehicle"
private const val SETUP_COMPLETE = "vehicle_setup_complete_v1"

private class SeedVehiclePreferencesRule(
    private val seed: (android.content.SharedPreferences.Editor) -> Unit = {},
) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val preferences = InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val editor = preferences.edit()
            seed(editor)
            editor.commit()
            base.evaluate()
        }
    }
}

@RunWith(AndroidJUnit4::class)
class FreshVehicleSettingsTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules = org.junit.rules.RuleChain
        .outerRule(SeedVehiclePreferencesRule())
        .around(compose)

    @Before fun narrowScreen() { shell("wm size 945x2100") }
    @After fun restoreScreen() { shell("wm size reset") }

    @Test
    fun freshSetupValidatesHiddenAdvancedDraftThenSavesRotatesAndCancels() {
        compose.onNodeWithText("Your car, ready for every trip").assertIsDisplayed()
        Thread.sleep(1_000)
        compose.waitForIdle()
        saveScreenshot("v030-onboarding-360")
        compose.onNodeWithTag("vehicle-name").performTextReplacement("Family diesel")
        compose.onNodeWithTag("vehicle-mass").performTextReplacement("1850")
        compose.onNodeWithTag("vehicle-advanced-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("vehicle-drag").performTextReplacement("9")
        compose.onNodeWithTag("vehicle-advanced-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("vehicle-save").performScrollTo().performClick()

        compose.onNodeWithTag("vehicle-error").assertIsDisplayed()
        compose.onNodeWithTag("vehicle-drag").assertExists()
        compose.onNodeWithTag("vehicle-drag").performTextReplacement("0.72")

        compose.activityRule.scenario.recreate()
        assertEquals("Family diesel", compose.onNodeWithTag("vehicle-name").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("vehicle-save").performScrollTo().performClick()
        compose.onNodeWithTag("sheet-toggle").performClick()
        compose.onNodeWithTag("planner-sheet").performScrollToNode(hasText("Family diesel"))
        compose.onNodeWithText("Family diesel").assertExists()
        saveScreenshot("v030-plan-vehicle-summary-360")

        val preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        assertTrue(preferences.getBoolean(SETUP_COMPLETE, false))
        assertEquals("Family diesel", preferences.getString(VehicleKeys.NAME, null))
        assertEquals("0.72", preferences.getString(VehicleKeys.DRAG_AREA_M2, null))

        compose.onNodeWithContentDescription("Vehicle settings").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("vehicle-name").performTextReplacement("Discard me")
        compose.onNodeWithTag("vehicle-name").performClick()
        saveScreenshot("v030-settings-keyboard-360")
        compose.onNodeWithTag("vehicle-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("cars-done").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Vehicle settings").assertExists()
        assertEquals("Family diesel", preferences.getString(VehicleKeys.NAME, null))
        compose.onNodeWithContentDescription("Vehicle settings").performClick()
        compose.onNodeWithText("Edit").performClick()
        assertEquals("Family diesel", compose.onNodeWithTag("vehicle-name").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class LegacyVehicleSettingsTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules = org.junit.rules.RuleChain.outerRule(SeedVehiclePreferencesRule { editor ->
        editor.putString(VehicleKeys.NAME, "Previously saved wagon")
            .putString(VehicleKeys.FUEL_TYPE, "petrol")
            .putString(VehicleKeys.MASS_KG, "1725")
            .putString(VehicleKeys.DRAG_AREA_M2, "0.74")
            .putString(VehicleKeys.EFFICIENCY_PERCENT, "29")
            .putString(VehicleKeys.ROLLING_RESISTANCE, "0.012")
            .putString(VehicleKeys.AUXILIARY_KW, "0.7")
            .putString(VehicleKeys.ENGINE_OVERHEAD_L_H, "0.55")
    }).around(compose)

    @Test
    fun upgradeReviewIsPrefilledAndOnlyCompletesAfterExplicitSave() {
        compose.onNodeWithText("Review your saved vehicle").assertIsDisplayed()
        assertEquals("Previously saved wagon", compose.onNodeWithTag("vehicle-name").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("vehicle-fuel-petrol").assertIsSelected()
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        assertFalse(preferences.getBoolean(SETUP_COMPLETE, false))

        compose.onNodeWithTag("vehicle-save").performScrollTo().performClick()
        assertTrue(preferences.getBoolean(SETUP_COMPLETE, false))
        compose.onNodeWithTag("sheet-toggle").performClick()
        compose.onNodeWithTag("planner-sheet").performScrollToNode(hasText("Previously saved wagon"))
        compose.onNodeWithText("Previously saved wagon").assertExists()
    }
}

@RunWith(AndroidJUnit4::class)
class VehicleRequestPersistenceTest {
    @Test
    fun savedVehicleSurvivesNewViewModelAndIsUsedForRouteRequest() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val firstApi = CapturingApi()
        val first = PlannerViewModel(application, firstApi)
        first.editVehicleDraft(VehicleKeys.NAME, "Request vehicle")
        first.editVehicleDraft(VehicleKeys.MASS_KG, "1999")
        assertTrue(first.saveVehicleDraft())
        first.editTripLoad("75")

        val secondApi = CapturingApi()
        val relaunched = PlannerViewModel(application, secondApi)
        assertTrue(relaunched.state.value.vehicleSetupComplete)
        assertEquals("Request vehicle", relaunched.state.value.vehicle.name)
        val origin = Place("a", 46.0, 14.0, "A", "A", "", "SI", "city")
        val destination = Place("b", 46.1, 14.1, "B", "B", "", "SI", "city")
        relaunched.selectPlace(0, origin)
        relaunched.selectPlace(1, destination)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { relaunched.calculate() }
        val deadline = System.currentTimeMillis() + 2_000
        while (secondApi.vehicle == null && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertEquals("Request vehicle", secondApi.vehicle?.getString("name"))
        assertEquals("75", relaunched.state.value.tripLoadKg)
        assertEquals(2074.0, secondApi.vehicle?.getDouble("mass_kg") ?: 0.0, 0.0)
    }

    @Test
    fun effectiveMassIsValidatedAfterTripLoadIsAdded() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val api = CapturingApi()
        val vm = PlannerViewModel(application, api)
        vm.editVehicleDraft(VehicleKeys.MASS_KG, "5900")
        assertTrue(vm.saveVehicleDraft())
        vm.editTripLoad("200")
        vm.selectPlace(0, Place("a", 46.0, 14.0, "A", "A", "", "SI", "city"))
        vm.selectPlace(1, Place("b", 46.1, 14.1, "B", "B", "", "SI", "city"))

        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.calculate() }

        assertEquals(null, api.vehicle)
        assertTrue(vm.state.value.error.orEmpty().contains("between 600 and 6000 kg"))
    }

    private class CapturingApi : EcoService {
        var vehicle: JSONObject? = null
        override suspend fun searchPlaces(query: String) = emptyList<Place>()
        override suspend fun startPlan(originId: String, destinationId: String, vehicle: JSONObject, roundTrip: Boolean, timeAllowance: TimeAllowanceRequest): String {
            this.vehicle = JSONObject(vehicle.toString())
            return "test-job"
        }
        override suspend fun job(jobId: String) = JobState("error", "Expected test stop")
        override suspend fun fuelPrice() = FuelPrice("EUR", emptyMap(), null, null, "", "", "")
    }
}

@RunWith(AndroidJUnit4::class)
class VehicleCatalogueDraftTest {
    @Test
    fun catalogueSelectionAutoFillsBodyEstimateAndTripLoadStaysSeparate() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val catalogue = catalogueVehicle()
        val api = CatalogueApi(catalogue)
        val vm = PlannerViewModel(application, api)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.editVehicleDraft(VehicleKeys.NAME, "Saved custom car")
            assertTrue(vm.saveVehicleDraft())
            vm.beginVehicleSettings()
            vm.editVehicleCatalogueQuery("XC90 B5")
        }
        waitUntil { vm.state.value.vehicleCatalogueSearch.results.isNotEmpty() }

        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.selectCatalogueVehicle(catalogue) }
        assertEquals("Saved custom car", vm.state.value.vehicle.name)
        assertEquals("0.95", vm.state.value.vehicleDraft.dragAreaM2)
        assertEquals(VehicleAssumptionOrigin.ESTIMATED, vm.state.value.vehicleDraftAssumptionOrigin)
        assertEquals(catalogue.id, vm.state.value.vehicleDraftCatalogue?.id)
        assertEquals(VehicleBodyStyle.LARGE_SUV, vm.state.value.vehicleDraftBodyStyle)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.discardVehicleDraft() }
        assertEquals("Saved custom car", vm.state.value.vehicleDraft.name)
        assertEquals(null, vm.state.value.vehicleDraftCatalogue)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.selectCatalogueVehicle(catalogue)
            assertTrue(vm.saveVehicleDraft())
            vm.editTripLoad("-75")
        }
        assertEquals("2170", vm.state.value.vehicle.massKg)
        assertEquals("0.95", vm.state.value.vehicle.dragAreaM2)
        assertEquals(VehicleAssumptionOrigin.ESTIMATED, vm.state.value.vehicleAssumptionOrigin)

        val relaunched = PlannerViewModel(application, CatalogueApi(catalogue))
        assertEquals(catalogue.id, relaunched.state.value.vehicleCatalogue?.id)
        assertEquals("-75", relaunched.state.value.tripLoadKg)
        assertEquals(VehicleBodyStyle.LARGE_SUV, relaunched.state.value.vehicleBodyStyle)
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(predicate())
    }

    private class CatalogueApi(private val result: CatalogueVehicle) : EcoService {
        override suspend fun searchPlaces(query: String) = emptyList<Place>()
        override suspend fun searchVehicles(query: String, fuelType: String?, limit: Int, offset: Int) =
            VehicleCatalogueSearchResult(listOf(result), 1, false, "2024 final registrations", null)
        override suspend fun startPlan(originId: String, destinationId: String, vehicle: JSONObject, roundTrip: Boolean, timeAllowance: TimeAllowanceRequest) = "job"
        override suspend fun job(jobId: String) = JobState("error", "stop")
        override suspend fun fuelPrice() = FuelPrice("EUR", emptyMap(), null, null, "", "", "")
    }

    private fun catalogueVehicle() = CatalogueVehicle(
        id="eea-xc90",make="VOLVO",model="XC90",variant="LFK2",displayName="VOLVO XC90",
        fuelType="diesel",fuelMode="H",registrationYear=2024,massKg=2170.0,massBasis="Mass in running order, including a standard 75 kg driver",powerKw=173.0,
        engineCc=1969.0,wltpLPer100Km=6.8,wltpMaxLPer100Km=7.4,typeCode="L",
        variantCode="LFK2",versionCode=null,supportedForModel=true,unsupportedReason=null,
        source=CatalogueSource("EEA / DG CLIMA","https://example.test","CC BY 4.0"),
        bodyStyle=VehicleBodyStyle.LARGE_SUV,
        bodyStyleSource=CatalogueSource("US EPA 2024 body class reference","https://example.test/epa","Public domain"),
    )
}

@RunWith(AndroidJUnit4::class)
class VehicleCatalogueSearchStateTest {
    @Test
    fun versionFourCatalogueLoadMigratesOnceWithoutChangingEffectiveMass() {
        val application=ApplicationProvider.getApplicationContext<Application>()
        val preferences=application.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val catalogue=SearchStateApi.vehicle("legacy").copy(massKg=2170.0)
        preferences.edit().clear().putBoolean(SETUP_COMPLETE,true)
            .putString(VehicleKeys.NAME,catalogue.displayName).putString(VehicleKeys.FUEL_TYPE,"diesel")
            .putString(VehicleKeys.MASS_KG,"2295").putString(VehicleKeys.DRAG_AREA_M2,"0.95")
            .putString(VehicleKeys.EFFICIENCY_PERCENT,"34").putString(VehicleKeys.ROLLING_RESISTANCE,"0.012")
            .putString(VehicleKeys.AUXILIARY_KW,"0.9").putString(VehicleKeys.ENGINE_OVERHEAD_L_H,"0.5")
            .putString("vehicle_catalogue_json_v1",catalogue.toJson().toString())
            .putString("vehicle_additional_load_kg_v1","125").commit()

        val migrated=PlannerViewModel(application,SearchStateApi())
        assertEquals("2170",migrated.state.value.vehicle.massKg)
        assertEquals("125",migrated.state.value.tripLoadKg)
        assertFalse(preferences.contains("vehicle_additional_load_kg_v1"))

        preferences.edit().putString(VehicleKeys.MASS_KG,"2222")
            .putString("vehicle_additional_load_kg_v1","125")
            .putBoolean("vehicle_load_migrated_v5",false).commit()
        val manualOverride=PlannerViewModel(application,SearchStateApi())
        assertEquals("2222",manualOverride.state.value.vehicle.massKg)
        assertEquals("125",manualOverride.state.value.tripLoadKg)
    }

    @Test
    fun latestQueryWinsAndEmptyAndErrorStatesAreActionable() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().commit()
        val api = SearchStateApi()
        val vm = PlannerViewModel(application,api)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { vm.editVehicleCatalogueQuery("old") }
        Thread.sleep(550)
        instrumentation.runOnMainSync { vm.editVehicleCatalogueQuery("new") }
        waitUntil { !vm.state.value.vehicleCatalogueSearch.isSearching }
        assertEquals(listOf("new"),vm.state.value.vehicleCatalogueSearch.results.map { it.id })

        instrumentation.runOnMainSync { vm.editVehicleCatalogueQuery("empty") }
        waitUntil { !vm.state.value.vehicleCatalogueSearch.isSearching }
        assertTrue(vm.state.value.vehicleCatalogueSearch.error!!.contains("No matching"))

        instrumentation.runOnMainSync { vm.editVehicleCatalogueQuery("error") }
        waitUntil { !vm.state.value.vehicleCatalogueSearch.isSearching }
        assertTrue(vm.state.value.vehicleCatalogueSearch.error!!.contains("catalogue offline"))
    }

    @Test
    fun versionFourPreferencesLoadWithoutCatalogueMetadata() {
        val application=ApplicationProvider.getApplicationContext<Application>()
        val preferences=application.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        preferences.edit().clear().putBoolean(SETUP_COMPLETE,true)
            .putString(VehicleKeys.NAME,"v4 custom car").putString(VehicleKeys.FUEL_TYPE,"petrol")
            .putString(VehicleKeys.MASS_KG,"1600").putString(VehicleKeys.DRAG_AREA_M2,"0.73")
            .putString(VehicleKeys.EFFICIENCY_PERCENT,"28").putString(VehicleKeys.ROLLING_RESISTANCE,"0.011")
            .putString(VehicleKeys.AUXILIARY_KW,"0.7").putString(VehicleKeys.ENGINE_OVERHEAD_L_H,"0.5").commit()

        val vm=PlannerViewModel(application,SearchStateApi())
        assertTrue(vm.state.value.vehicleSetupComplete)
        assertEquals("v4 custom car",vm.state.value.vehicle.name)
        assertEquals(null,vm.state.value.vehicleCatalogue)
        assertEquals(VehicleAssumptionOrigin.MANUAL,vm.state.value.vehicleAssumptionOrigin)
    }

    private fun waitUntil(predicate:()->Boolean) {
        val deadline=System.currentTimeMillis()+4_000
        while(!predicate()&&System.currentTimeMillis()<deadline) Thread.sleep(20)
        assertTrue(predicate())
    }

    private class SearchStateApi:EcoService {
        override suspend fun searchPlaces(query:String)=emptyList<Place>()
        override suspend fun searchVehicles(query:String,fuelType:String?,limit:Int,offset:Int):VehicleCatalogueSearchResult {
            if(query=="old") kotlinx.coroutines.delay(800)
            if(query=="error") throw EcoApiException("catalogue offline")
            val values=if(query=="empty") emptyList() else listOf(vehicle(query))
            return VehicleCatalogueSearchResult(values,values.size,false,"coverage",null)
        }
        override suspend fun startPlan(originId:String,destinationId:String,vehicle:JSONObject,roundTrip:Boolean,timeAllowance:TimeAllowanceRequest)="job"
        override suspend fun job(jobId:String)=JobState("error","stop")
        override suspend fun fuelPrice()=FuelPrice("EUR",emptyMap(),null,null,"","","")

        companion object {
        fun vehicle(id:String)=CatalogueVehicle(
            id=id,make="TEST",model=id,variant="V1",displayName="TEST $id",fuelType="diesel",fuelMode="M",
            registrationYear=2024,massKg=1400.0,massBasis="Mass in running order, including a standard 75 kg driver",
            powerKw=100.0,engineCc=1900.0,wltpLPer100Km=5.0,wltpMaxLPer100Km=5.5,typeCode="T",
            variantCode="V1",versionCode=null,supportedForModel=true,unsupportedReason=null,
            source=CatalogueSource("EEA","https://example.test","CC BY 4.0"),
        )
        }
    }
}
