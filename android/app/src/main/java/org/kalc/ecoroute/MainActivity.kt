package org.kalc.ecoroute

import android.os.Bundle
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme(primary=Color(0xFFC6BEFF), secondary=Color(0xFF9ED9C2))
                else lightColorScheme(primary=Color(0xFF5142D8), secondary=Color(0xFF246B53), background=Color(0xFFF7F7FC), surface=Color(0xFFF7F7FC))
            MaterialTheme(colorScheme=colors) { EcoApp() }
        }
    }
}

private fun Double.f(digits: Int = 1) = String.format(Locale.UK, "%.${digits}f", this)
private fun duration(minutes: Double): String { val rounded=minutes.roundToInt(); return "${rounded/60}h ${rounded%60}m" }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EcoApp(vm: PlannerViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshFuelPrice() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.cancelCurrentLocation() }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var vehicleEditorOpen by rememberSaveable { mutableStateOf(false) }
    var deletingVehicleId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val showingVehicleEditor = !state.vehicleSetupComplete || (settingsOpen && vehicleEditorOpen)
    val requestCloseVehicleEditor: () -> Unit = {
        if (state.vehicleDraftDirty) showDiscardDialog = true
        else if (!state.vehicleSetupComplete) (context as? Activity)?.finish()
        else { vm.discardVehicleDraft(); vehicleEditorOpen = false }
        Unit
    }
    BackHandler(enabled = showingVehicleEditor) {
        requestCloseVehicleEditor()
    }
    BackHandler(enabled = settingsOpen && !showingVehicleEditor) { settingsOpen = false }
    val snackbar = remember { SnackbarHostState() }
    var exportContent by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val success = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(exportContent.orEmpty().toByteArray()) }
                    ?: error("Could not open document")
            }.isSuccess }
            snackbar.showSnackbar(if (success) "Route CSV saved" else "Could not save CSV. Please try again.")
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (showingVehicleEditor || settingsOpen) {
            Scaffold(topBar = {
                TopAppBar(title = { Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.Route, null, tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp)); Text("Eco Route", fontWeight=FontWeight.Bold)
                } }, navigationIcon={
                    if(settingsOpen && state.vehicleSetupComplete) IconButton(onClick={ if(vehicleEditorOpen) requestCloseVehicleEditor() else settingsOpen=false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack,if(vehicleEditorOpen) "Back to saved cars" else "Back to trip") }
                })
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment=Alignment.TopCenter) {
                if (showingVehicleEditor) {
                    VehicleEditor(
                        state = state,
                        vm = vm,
                        onboarding = !state.vehicleSetupComplete,
                        onSaved = { vehicleEditorOpen=false },
                        onCancel = { vm.discardVehicleDraft(); vehicleEditorOpen=false },
                    )
                } else {
                    SavedCarsSettings(
                        state=state,
                        onSelect=vm::selectVehicleProfile,
                        onAdd={ vm.beginAddVehicle(); vehicleEditorOpen=true },
                        onEdit={ vm.beginVehicleSettings(it); vehicleEditorOpen=true },
                        onDelete={ deletingVehicleId=it },
                        onDone={ settingsOpen=false },
                    )
                }
            }
        }
        } else {
            MapPlannerScreen(
                state = state,
                vm = vm,
                onOpenVehicle = { settingsOpen = true },
                onExport = {
                    exportContent = vm.exportCsv()
                    exportContent?.let { export.launch(vm.exportFileName()) }
                },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (showDiscardDialog) AlertDialog(
        onDismissRequest={showDiscardDialog=false},
        title={Text("Discard vehicle changes?")},
        text={Text("Your saved vehicle will stay unchanged.")},
        confirmButton={TextButton(onClick={
            vm.discardVehicleDraft(); showDiscardDialog=false
            if(state.vehicleSetupComplete) vehicleEditorOpen=false else (context as? Activity)?.finish()
        }) { Text("Discard changes") }},
        dismissButton={TextButton(onClick={showDiscardDialog=false}) { Text("Keep editing") }},
    )
    deletingVehicleId?.let { id ->
        val profile=state.vehicleProfiles.find { it.id==id }
        if(profile!=null) AlertDialog(
            onDismissRequest={deletingVehicleId=null},
            title={Text("Remove saved car?")},
            text={Text("Remove ${profile.vehicle.name} from this device?")},
            confirmButton={TextButton(onClick={vm.deleteVehicleProfile(id);deletingVehicleId=null}) {Text("Remove")}},
            dismissButton={TextButton(onClick={deletingVehicleId=null}) {Text("Keep car")}},
        )
    }
}

@Composable
private fun SavedCarsSettings(state:UiState,onSelect:(String)->Boolean,onAdd:()->Unit,onEdit:(String)->Unit,onDelete:(String)->Unit,onDone:()->Unit) {
    LazyColumn(
        Modifier.widthIn(max=760.dp).fillMaxSize().padding(horizontal=20.dp).testTag("saved-cars-list"),
        contentPadding=PaddingValues(vertical=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Your cars",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            Text("Choose the car for your next calculation. Each car keeps its own vehicle details.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(state.vehicleProfiles.size,key={state.vehicleProfiles[it].id}) { index ->
            val profile=state.vehicleProfiles[index]
            val selected=profile.id==state.activeVehicleProfileId
            Card(onClick={onSelect(profile.id)},modifier=Modifier.fillMaxWidth().testTag("saved-car-${profile.id}"),shape=RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(if(selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,if(selected) "Selected car" else "Select car",tint=MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.vehicle.name,fontWeight=FontWeight.SemiBold)
                            Text("${profile.vehicle.fuelType.replaceFirstChar { it.uppercase() }} · ${profile.vehicle.massKg} kg",style=MaterialTheme.typography.bodyMedium)
                            if(selected) Text("Using for this trip",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={onEdit(profile.id)},modifier=Modifier.testTag("edit-car-${profile.id}")) {Text("Edit")}
                        TextButton(onClick={onDelete(profile.id)},enabled=state.vehicleProfiles.size>1,modifier=Modifier.testTag("delete-car-${profile.id}")) {Text("Remove")}
                    }
                }
            }
        }
        item {Button(onClick=onAdd,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("add-car")) {Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("Add car")}}
        item {Text("Cars are saved on this device. Passengers and luggage remain a separate trip adjustment.",style=MaterialTheme.typography.bodySmall)}
        item {OutlinedButton(onClick=onDone,modifier=Modifier.fillMaxWidth().testTag("cars-done")) {Text("Back to trip")}}
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

@Composable
private fun AddressField(label: String,index: Int,address: AddressState,vm: PlannerViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value=address.text,onValueChange={vm.editAddress(index,it)},label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),trailingIcon={
        if(address.isSearching) CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
        else if(address.selected!=null) Icon(Icons.Default.CheckCircle,"Address selected",tint=MaterialTheme.colorScheme.secondary)
    })
    if(address.selected==null && address.text.length>=3 && !address.isSearching && address.error==null && address.suggestions.isEmpty()) Text("Keep typing, then select an address from the suggestions.",style=MaterialTheme.typography.bodySmall)
    address.suggestions.forEach { place ->
        Surface(onClick={vm.selectPlace(index,place);keyboard?.hide()},shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(place.title,fontWeight=FontWeight.Medium)
                if(place.detail.isNotEmpty()) Text(place.detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    address.error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall); TextButton(onClick={vm.retryAddress(index)}) { Text("Retry address search") } }
}

@Composable
private fun VehicleField(label:String,value:String,onValueChange:(String)->Unit,numeric:Boolean=true,tag:String) {
    OutlinedTextField(value=value,onValueChange=onValueChange,label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth().testTag(tag),shape=RoundedCornerShape(14.dp),keyboardOptions=KeyboardOptions(keyboardType=if(numeric) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable
internal fun VehicleSummary(state: UiState, onEdit: () -> Unit) {
    val vehicle=state.vehicle
    val tripLoad=state.tripLoadKg.toDoubleOrNull()
    val baseMass=vehicle.massKg.toDoubleOrNull()
    Panel {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Car for this trip",style=MaterialTheme.typography.titleMedium)
                Text(vehicle.name,fontWeight=FontWeight.SemiBold)
                Text("${vehicle.fuelType.replaceFirstChar { it.uppercase() }} · ${vehicle.massKg} kg vehicle mass",style=MaterialTheme.typography.bodySmall)
                if(tripLoad != null && tripLoad != 0.0 && baseMass != null) Text("Current trip: ${if(tripLoad > 0) "+" else ""}${tripLoad.f(0)} kg · ${(baseMass + tripLoad).f(0)} kg planning mass",style=MaterialTheme.typography.bodySmall)
                state.vehicleCatalogue?.let { Text("2024 EEA registration catalogue · ${state.vehicleBodyStyle?.label ?: "manual assumptions"}",style=MaterialTheme.typography.labelSmall) }
            }
            TextButton(onClick=onEdit) { Text("Choose car") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleEditor(
    state: UiState,
    vm: PlannerViewModel,
    onboarding: Boolean,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var bodyOverride by rememberSaveable { mutableStateOf(false) }
    var catalogueExpanded by rememberSaveable { mutableStateOf(true) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.vehicleDraftError) {
        if (state.vehicleDraftError != null) {
            advanced = true
            scroll.animateScrollTo(0)
        }
    }
    LaunchedEffect(state.vehicleDraftCatalogue?.id) {
        if(state.vehicleDraftCatalogue != null) {
            catalogueExpanded=false
            keyboard?.hide()
            delay(100)
            scroll.animateScrollTo(minOf(scroll.maxValue,450))
        }
    }
    LaunchedEffect(state.vehicleCatalogueSearch.results.firstOrNull()?.id) {
        if(catalogueExpanded && state.vehicleCatalogueSearch.results.isNotEmpty()) {
            delay(100)
            scroll.animateScrollTo(minOf(scroll.maxValue,650))
        }
    }
    Column(
        Modifier.widthIn(max=760.dp).fillMaxSize().imePadding().verticalScroll(scroll).padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if(onboarding && state.vehicleSetupIsLegacyReview) "Review your saved vehicle"
            else if(onboarding) "Your car, ready for every trip" else if(state.vehicleDraftIsNew) "Add a car" else "Edit car",
            style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold,
        )
        Text(
            if(onboarding && state.vehicleSetupIsLegacyReview) "Review the vehicle carried over from your previous app version, then save to continue."
            else if(onboarding) "Check this pilot preset and adjust the three details you know. You can refine the physical assumptions later."
            else "Save the details for this car. Choose which saved car to use for each trip.",
            color=MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.vehicleDraftError?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("vehicle-error")) }
        Panel {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text("Find your car",style=MaterialTheme.typography.titleLarge)
                TextButton(onClick={catalogueExpanded=!catalogueExpanded}) { Text(if(catalogueExpanded) "Hide" else "Search") }
            }
            if(catalogueExpanded) {
                Text("Free catalogue of final 2024 registrations in the EU, Iceland and Norway. This is the registration year, not the model year.",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value=state.vehicleCatalogueSearch.query,
                    onValueChange=vm::editVehicleCatalogueQuery,
                    label={Text("Make, model or variant code")},
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth().testTag("vehicle-catalogue-query"),
                    shape=RoundedCornerShape(14.dp),
                    trailingIcon={if(state.vehicleCatalogueSearch.isSearching) CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)},
                )
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(null,"diesel","petrol").forEach { fuel -> FilterChip(
                        selected=state.vehicleCatalogueSearch.fuelFilter==fuel,
                        onClick={vm.setVehicleCatalogueFuelFilter(fuel)},
                        label={Text(fuel?.replaceFirstChar { it.uppercase() } ?: "All fuels")},
                        modifier=Modifier.testTag("vehicle-catalogue-fuel-${fuel ?: "all"}"),
                    ) }
                }
                state.vehicleCatalogueSearch.results.forEach { vehicle -> CatalogueResultCard(vehicle) { vm.selectCatalogueVehicle(vehicle) } }
                state.vehicleCatalogueSearch.nextOffset?.let {
                    OutlinedButton(onClick=vm::loadMoreVehicleCatalogue,enabled=!state.vehicleCatalogueSearch.isSearching,modifier=Modifier.fillMaxWidth().testTag("vehicle-catalogue-more")) { Text("Show more (${state.vehicleCatalogueSearch.totalMatches} matches)") }
                }
                state.vehicleCatalogueSearch.coverageMessage.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                state.vehicleCatalogueSearch.error?.let { error ->
                    Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick=vm::retryVehicleCatalogueSearch) { Text("Retry vehicle search") }
                }
                OutlinedButton(onClick={
                    vm.useManualVehicleEntry();catalogueExpanded=false;keyboard?.hide()
                    scope.launch { delay(100);scroll.animateScrollTo(minOf(scroll.maxValue,450)) }
                },modifier=Modifier.fillMaxWidth().testTag("vehicle-enter-manually")) { Text("Enter details manually") }
                Text("Electric and plug-in vehicles are outside this fuel-only model. You can always enter a diesel or petrol vehicle manually.",style=MaterialTheme.typography.bodySmall)
            }
        }
        Panel {
            Text("Vehicle",style=MaterialTheme.typography.titleLarge)
            when {
                state.vehicleDraftCatalogue != null -> CatalogueSelectionDetails(state.vehicleDraftCatalogue)
                state.vehicleDraftAssumptionOrigin == VehicleAssumptionOrigin.PILOT_PRESET -> Text("Pilot preset: Volvo XC90 B5 AWD. Review these values for your car.",style=MaterialTheme.typography.bodySmall)
                else -> Text("Manually entered vehicle details.",style=MaterialTheme.typography.bodySmall)
            }
            VehicleField("Vehicle name or model",state.vehicleDraft.name,{vm.editVehicleDraft(VehicleKeys.NAME,it)},numeric=false,tag="vehicle-name")
            Text("Fuel type",style=MaterialTheme.typography.labelLarge)
            if(state.vehicleDraftCatalogue != null) {
                Text(
                    "${state.vehicleDraft.fuelType.replaceFirstChar { it.uppercase() }} · ${if(state.vehicleDraftCatalogue.fuelMode=="H") "hybrid in the registry" else "combustion"}",
                    style=MaterialTheme.typography.bodyMedium,
                    modifier=Modifier.testTag("vehicle-powertrain-summary"),
                )
            } else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("diesel","petrol").forEach { fuel ->
                    FilterChip(
                        selected=state.vehicleDraft.fuelType==fuel,
                        onClick={vm.editVehicleDraft(VehicleKeys.FUEL_TYPE,fuel)},
                        label={Text(fuel.replaceFirstChar { it.uppercase() })},
                        modifier=Modifier.testTag("vehicle-fuel-$fuel"),
                    )
                }
            }
            if(state.vehicleDraftCatalogue?.massKg != null) {
                Text("Catalogue mass: ${state.vehicleDraftCatalogue.massKg.f(0)} kg. ${state.vehicleDraftCatalogue.massBasis}. Do not add the driver again.",style=MaterialTheme.typography.bodySmall)
                Text("Passengers and luggage are set separately for each trip on the planning screen.",style=MaterialTheme.typography.bodySmall)
            } else VehicleField("Vehicle mass (kg)",state.vehicleDraft.massKg,{vm.editVehicleDraft(VehicleKeys.MASS_KG,it)},tag="vehicle-mass")
            if(state.vehicleDraftCatalogue != null) {
                Text("Estimated assumptions",style=MaterialTheme.typography.titleMedium)
                val bodyMissing=state.vehicleDraftAssumptionOrigin==VehicleAssumptionOrigin.NEEDS_ESTIMATE
                if(bodyMissing) Text("Choose the closest body style so Eco Route can fill generic physical estimates.",style=MaterialTheme.typography.bodySmall)
                else {
                    Text(
                        state.vehicleDraftBodyStyle?.let { "${it.label} · generic physical estimate" } ?: "Manually adjusted physical assumptions",
                        style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("vehicle-body-summary"),
                    )
                    if(state.vehicleDraftBodyStyle==state.vehicleDraftCatalogue.bodyStyle) {
                        state.vehicleDraftCatalogue.bodyStyleSource?.takeIf { it.name.isNotBlank() }?.let { source ->
                            Text("Body class matched from ${source.name}.",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick={bodyOverride=!bodyOverride},modifier=Modifier.testTag("vehicle-body-change")) {
                        Text(if(bodyOverride) "Hide body choices" else "Change body estimate")
                    }
                }
                if(bodyMissing || bodyOverride) {
                    Text("These presets are estimates, not calibrated vehicle measurements.",style=MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        VehicleBodyStyle.entries.forEach { style -> FilterChip(
                            selected=state.vehicleDraftBodyStyle==style,
                            onClick={vm.selectVehicleBodyStyle(style)},
                            label={Text(style.label)},modifier=Modifier.testTag("vehicle-body-${style.wireValue}"),
                        ) }
                    }
                }
                if(state.vehicleDraftCatalogue.fuelMode=="H") Text("Hybrid in the registry. This estimate does not model battery charge or electric driving separately.",style=MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick={advanced=!advanced},modifier=Modifier.testTag("vehicle-advanced-toggle")) {
                Text(if(advanced) "Hide advanced assumptions" else "Advanced physical assumptions")
                Icon(if(advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)
            }
            if (advanced) {
                Text(if(state.vehicleDraftAssumptionOrigin==VehicleAssumptionOrigin.ESTIMATED) "Estimated from the selected body style. Review or edit these values; edits become manual assumptions." else "These are manual starting assumptions for the fuel model. Changing a name or fuel type does not automatically calibrate them to a specific vehicle.",style=MaterialTheme.typography.bodySmall)
                VehicleField("Drag area Cd × A (m²)",state.vehicleDraft.dragAreaM2,{vm.editVehicleDraft(VehicleKeys.DRAG_AREA_M2,it)},tag="vehicle-drag")
                VehicleField("Tank-to-wheel efficiency (%)",state.vehicleDraft.efficiencyPercent,{vm.editVehicleDraft(VehicleKeys.EFFICIENCY_PERCENT,it)},tag="vehicle-efficiency")
                VehicleField("Rolling resistance coefficient",state.vehicleDraft.rollingResistance,{vm.editVehicleDraft(VehicleKeys.ROLLING_RESISTANCE,it)},tag="vehicle-rolling")
                VehicleField("Auxiliary power (kW)",state.vehicleDraft.auxiliaryKw,{vm.editVehicleDraft(VehicleKeys.AUXILIARY_KW,it)},tag="vehicle-auxiliary")
                VehicleField("Engine overhead (L/h)",state.vehicleDraft.engineOverheadLh,{vm.editVehicleDraft(VehicleKeys.ENGINE_OVERHEAD_L_H,it)},tag="vehicle-overhead")
                TextButton(onClick={vm.resetVehicleDraft()},modifier=Modifier.testTag("vehicle-reset")) { Text("Use XC90 pilot preset") }
            }
        }
        Text("Saved on this device. Fuel and speed calculations run on your phone; your vehicle details stay here. No account is required.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick={if(vm.saveVehicleDraft()) onSaved()},
            modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("vehicle-save"),
            shape=RoundedCornerShape(16.dp),
        ) { Text(if(onboarding) "Save and continue" else "Save vehicle") }
        if(!onboarding) OutlinedButton(onClick=onCancel,modifier=Modifier.fillMaxWidth().testTag("vehicle-cancel")) { Text("Cancel") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CatalogueResultCard(vehicle: CatalogueVehicle,onSelect:()->Unit) {
    Card(
        onClick=onSelect,enabled=vehicle.isSupported,
        modifier=Modifier.fillMaxWidth().testTag("catalogue-result-${vehicle.id}"),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(vehicle.displayName,fontWeight=FontWeight.SemiBold)
            Text(listOfNotNull(
                vehicle.typeCode?.takeIf { it.isNotBlank() }?.let { "Type $it" },
                vehicle.variant.takeIf { it.isNotBlank() }?.let { "Variant $it" },
                vehicle.powerKw?.let { "${it.f(0)} kW" },vehicle.engineCc?.let { "${it.f(0)} cc" },
                vehicle.massKg?.let { "${it.f(0)} kg" },
            ).joinToString(" · "),style=MaterialTheme.typography.bodySmall)
            val fuelMode = if(vehicle.fuelMode=="H") "Hybrid (registry)" else "Combustion"
            Text("${vehicle.fuelType.replaceFirstChar { it.uppercase() }} · $fuelMode · registered in ${vehicle.registrationYear}",style=MaterialTheme.typography.bodySmall)
            val wltp = vehicle.wltpLPer100Km?.let { low -> vehicle.wltpMaxLPer100Km?.takeIf { it > low }?.let { "WLTP ${low.f()}–${it.f()} L/100 km" } ?: "WLTP ${low.f()} L/100 km" }
            wltp?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            if(!vehicle.isSupported) Text(vehicle.unsupportedReason ?: "Enter this vehicle manually.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CatalogueSelectionDetails(vehicle: CatalogueVehicle) {
    val context=LocalContext.current
    Text("Catalogue selection · registered in ${vehicle.registrationYear}, not a model year",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
    Text("${vehicle.make} ${vehicle.model} · type ${vehicle.typeCode ?: "—"} · variant ${vehicle.variant.ifBlank { "—" }}",style=MaterialTheme.typography.bodySmall)
    val wltp=vehicle.wltpLPer100Km?.let { low -> vehicle.wltpMaxLPer100Km?.takeIf { it>low }?.let { "WLTP ${low.f()}–${it.f()} L/100 km" } ?: "WLTP ${low.f()} L/100 km" }
    Text(listOfNotNull(vehicle.powerKw?.let { "${it.f(0)} kW" },vehicle.engineCc?.let { "${it.f(0)} cc" },wltp).joinToString(" · "),style=MaterialTheme.typography.bodySmall)
    if(wltp!=null) Text("WLTP is shown to identify the variant; the route fuel estimate is not calibrated to it.",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(vehicle.source.url))) }}) { Text("${vehicle.source.name} · ${vehicle.source.license}") }
}

@Composable
internal fun TripLoad(state: UiState, vm: PlannerViewModel) {
    Text("Passengers & luggage",style=MaterialTheme.typography.titleMedium)
    Text("Optional for the current trip. This adjustment is remembered until you change or reset it; the saved vehicle stays unchanged.",style=MaterialTheme.typography.bodySmall)
    VehicleField("Additional trip weight (kg)",state.tripLoadKg,vm::editTripLoad,tag="trip-load")
    Text(
        if(state.vehicleCatalogue != null) "Catalogue mass already includes a standard 75 kg driver. Add passengers and luggage; if useful, include only the driver's difference from 75 kg."
        else "Enter only passenger, luggage or driver weight that is not already included in your saved vehicle mass.",
        style=MaterialTheme.typography.bodySmall,
    )
    state.tripLoadError?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("trip-load-error")) }
    if(state.tripLoadKg != "0") TextButton(onClick=vm::resetTripLoad,modifier=Modifier.testTag("trip-load-reset")) { Text("Reset to 0 kg") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Allowance(state:UiState,vm:PlannerViewModel) {
    Text("Your time, your choice",style=MaterialTheme.typography.titleMedium)
    Text("Choose a maximum time allowance for each section. More time can enable a lower-fuel plan, but does not guarantee a saving.",style=MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        FilterChip(selected=state.allowanceMode==TimeAllowanceMode.PERCENT,onClick={vm.setAllowanceMode(TimeAllowanceMode.PERCENT)},label={Text("Percentage")},modifier=Modifier.testTag("allowance-mode-percent"))
        FilterChip(selected=state.allowanceMode==TimeAllowanceMode.MINUTES,onClick={vm.setAllowanceMode(TimeAllowanceMode.MINUTES)},label={Text("Custom minutes · advanced")},modifier=Modifier.testTag("allowance-mode-minutes"))
    }
    if (state.allowanceMode == TimeAllowanceMode.PERCENT) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            state.percentAllowanceValues.forEach { percent ->
                val extra = state.selectedLeg?.timeAllowance?.option(percent)?.extraMinutes
                FilterChip(selected=state.allowanceValue==percent,onClick={vm.setPercentAllowance(percent)},modifier=Modifier.testTag("allowance-percent-$percent"),label={
                    if(percent==0) Text("Fastest") else Column {
                        Text("+$percent%")
                        extra?.let { Text("up to ${it.f()} min",style=MaterialTheme.typography.labelSmall) }
                    }
                })
            }
        }
        Text(if(state.selectedLeg!=null) "Minute equivalents use this section's fastest modeled time. Applying the same percentage per section gives the same percentage bound for the whole journey." else "The exact minute allowance is calculated separately for each section.",style=MaterialTheme.typography.labelSmall)
    } else {
        OutlinedTextField(
            value=state.customMinutesText,
            onValueChange=vm::editCustomMinutes,
            label={Text("Extra minutes (1–120)")},
            supportingText={Text("Added separately to every section. A custom value requires a new calculation.")},
            isError=state.customMinutesText.toIntOrNull()?.let { it !in 1..120 } ?: true,
            singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
            modifier=Modifier.fillMaxWidth(),
            shape=RoundedCornerShape(14.dp),
        )
        state.selectedAllowanceOption?.let { Text("Maximum modeled driving time for this section: ${it.maxMinutes.f()} min.",style=MaterialTheme.typography.labelSmall) }
    }
}

@Composable
internal fun LegSelector(state:UiState,vm:PlannerViewModel) {
    val journey=state.journey ?: return
    if(journey.legs.size>1) Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement=Arrangement.spacedBy(8.dp),
    ) {
        journey.legs.forEachIndexed { index, leg ->
            val from=(leg.places.firstOrNull()?.title ?: ('A'.code+index).toChar().toString())
            val to=(leg.places.lastOrNull()?.title ?: ('A'.code+index+1).toChar().toString())
            val isReturn=journey.roundTrip && index==journey.legs.lastIndex
            val label = when {
                journey.tripType == "round_trip" && index == 0 -> "A → B · Outbound"
                journey.tripType == "round_trip" && isReturn -> "B → A · Return"
                else -> "${('A'.code+index).toChar()} → ${if(isReturn) 'A' else ('A'.code+index+1).toChar()} · ${if(isReturn) "Return" else "$from to $to"}"
            }
            FilterChip(
                selected=state.activeLegIndex==index,
                onClick={vm.selectLeg(index)},
                label={Text(label,maxLines=1)},
                modifier=Modifier.testTag("leg-$index"),
            )
        }
    }
}

@Composable
private fun Metric(value:String,label:String) {
    Column { Text(value,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold); Text(label,style=MaterialTheme.typography.labelSmall) }
}

@Composable
internal fun PriceInfo(state:UiState,vm:PlannerViewModel) {
    val fuelType = state.selectedLeg?.vehicle?.fuelType ?: state.vehicle.fuelType
    val price = state.fuelPrice?.currentPrice(fuelType)
    val context = LocalContext.current
    Panel {
        Text("Fuel price reference",style=MaterialTheme.typography.titleSmall)
        Text(if(price!=null) "${fuelType.replaceFirstChar { it.uppercase() }} · €${price.f(3)}/L" else "Current fuel price unavailable",fontWeight=FontWeight.SemiBold)
        Text("Official Slovenia reference for non-motorway stations. Prices abroad and on motorways can differ. Cost = estimated liters × current price.",style=MaterialTheme.typography.bodySmall)
        if(price!=null) Text("Valid ${state.fuelPrice?.validFrom} – ${state.fuelPrice?.validUntil}",style=MaterialTheme.typography.labelSmall)
        if (state.fuelPrice?.refreshUnavailable == true) Text("Refresh unavailable. Showing the last price only while its validity period still applies.", style=MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick={vm.refreshFuelPrice()}) { Text("Refresh price") }
            state.fuelPrice?.sourceUrl?.takeIf { it.startsWith("https://") }?.let { source -> TextButton(onClick={runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source))) }}) { Text("Official source") } }
        }
    }
}
