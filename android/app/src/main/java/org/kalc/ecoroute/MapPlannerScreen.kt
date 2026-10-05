package org.kalc.ecoroute

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class AddressTarget(val index: Int) { ORIGIN(0), DESTINATION(1) }

private fun Double.display(digits: Int = 1) = String.format(Locale.UK, "% .${digits}f", this).trim()
private fun timeLabel(minutes: Double): String {
    val rounded = minutes.roundToInt()
    return if (rounded < 60) "${rounded} min" else "${rounded / 60}h ${rounded % 60}m"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapPlannerScreen(
    state: UiState,
    vm: PlannerViewModel,
    onOpenVehicle: () -> Unit,
    onExport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(sheetState)
    var activeAddress by rememberSaveable {
        mutableStateOf(if (state.destination.selected == null) AddressTarget.DESTINATION else AddressTarget.ORIGIN)
    }
    var editingAddress by rememberSaveable { mutableStateOf(false) }
    var rootHeightPx by remember { mutableIntStateOf(0) }
    var topOverlayBottomPx by remember { mutableIntStateOf(0) }
    var sheetTopPx by remember { mutableIntStateOf(0) }
    var previousJourney by remember { mutableStateOf(state.journey) }

    LaunchedEffect(state.journey) {
        if (state.journey != null && previousJourney == null) sheetState.partialExpand()
        previousJourney = state.journey
    }

    val collapseSheet: () -> Unit = { scope.launch { sheetState.partialExpand() }; Unit }
    BackHandler(enabled = editingAddress || sheetState.currentValue == SheetValue.Expanded) {
        if (editingAddress) {
            editingAddress = false
            keyboardController?.hide()
        }
        else if (sheetState.currentValue == SheetValue.Expanded) collapseSheet()
    }

    BoxWithConstraints {
    if (maxWidth > maxHeight && maxWidth >= 600.dp) {
        Row(Modifier.fillMaxSize().testTag("landscape-layout")) {
            RouteMap(
                payload = state.mapPayloadJson,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("route-map"),
                viewportInsets = MapViewportInsets(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                onRouteSelected = vm::selectRoute,
            )
            Surface(
                modifier = Modifier.widthIn(min = 300.dp, max = 340.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                    if (!imeVisible && (editingAddress || state.origin.selected == null || state.destination.selected == null)) {
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { MapTopBar(onOpenVehicle) }
                    }
                    Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        AddressOverlay(
                            state = state,
                            vm = vm,
                            active = activeAddress,
                            editing = editingAddress,
                            onActiveChange = { activeAddress = it; editingAddress = true },
                            onDoneEditing = { editingAddress = false },
                            onSecondEndpointSelected = {},
                            onOpenVehicle = onOpenVehicle,
                            maxExpandedHeight = if (imeVisible) 120.dp else 190.dp,
                            compactPrompt = imeVisible,
                        )
                    }
                    PlannerSheet(
                        state = state,
                        vm = vm,
                        expanded = true,
                        onToggleExpanded = {},
                        toggleEnabled = false,
                        onOpenVehicle = onOpenVehicle,
                        onExport = onExport,
                        onEditRoute = {
                            activeAddress = AddressTarget.DESTINATION
                            editingAddress = true
                        },
                        onNewRoute = {
                            vm.clearTrip()
                            activeAddress = AddressTarget.DESTINATION
                            editingAddress = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    } else BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 152.dp,
        sheetDragHandle = null,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetContent = {
            PlannerSheet(
                state = state,
                vm = vm,
                expanded = sheetState.currentValue == SheetValue.Expanded,
                onToggleExpanded = {
                    scope.launch {
                        if (sheetState.currentValue == SheetValue.Expanded) sheetState.partialExpand()
                        else sheetState.expand()
                    }
                },
                onOpenVehicle = onOpenVehicle,
                onExport = onExport,
                onEditRoute = {
                    activeAddress = AddressTarget.DESTINATION
                    editingAddress = true
                    collapseSheet()
                },
                onNewRoute = {
                    vm.clearTrip()
                    activeAddress = AddressTarget.DESTINATION
                    editingAddress = false
                    collapseSheet()
                },
                modifier = Modifier.onGloballyPositioned {
                    sheetTopPx = it.positionInRoot().y.roundToInt().coerceAtLeast(0)
                },
            )
        },
    ) { innerPadding ->
        Box(
            Modifier.fillMaxSize().padding(innerPadding).onGloballyPositioned { rootHeightPx = it.size.height },
        ) {
            val viewportInsets = MapViewportInsets(
                top = with(density) { topOverlayBottomPx.toDp() },
                bottom = with(density) { (rootHeightPx - sheetTopPx).coerceAtLeast(0).toDp() },
                start = 8.dp,
                end = 8.dp,
            )
            RouteMap(
                payload = state.mapPayloadJson,
                modifier = Modifier.fillMaxSize().testTag("route-map"),
                viewportInsets = viewportInsets,
                onRouteSelected = vm::selectRoute,
            )
            Column(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                    .onGloballyPositioned {
                        val rootY = it.positionInRoot().y
                        topOverlayBottomPx = (rootY + it.size.height).roundToInt()
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (editingAddress || state.origin.selected == null || state.destination.selected == null) {
                    MapTopBar(onOpenVehicle)
                }
                AddressOverlay(
                    state = state,
                    vm = vm,
                    active = activeAddress,
                    editing = editingAddress,
                    onActiveChange = { activeAddress = it; editingAddress = true },
                    onDoneEditing = { editingAddress = false },
                    onSecondEndpointSelected = { collapseSheet() },
                    onOpenVehicle = onOpenVehicle,
                    maxExpandedHeight = if (imeVisible) 300.dp else 420.dp,
                )
            }
        }
    }
    }
}

@Composable
private fun MapTopBar(onOpenVehicle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Route, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("Eco Route", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("PILOT", style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = onOpenVehicle, modifier = Modifier.testTag("vehicle-settings")) {
                Icon(Icons.Default.Settings, "Vehicle settings")
            }
        }
    }
}

@Composable
private fun AddressOverlay(
    state: UiState,
    vm: PlannerViewModel,
    active: AddressTarget,
    editing: Boolean,
    onActiveChange: (AddressTarget) -> Unit,
    onDoneEditing: () -> Unit,
    onSecondEndpointSelected: () -> Unit,
    onOpenVehicle: () -> Unit,
    maxExpandedHeight: androidx.compose.ui.unit.Dp,
    compactPrompt: Boolean = false,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true || grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) vm.requestCurrentLocation()
        else vm.locationPermissionDenied()
    }
    val focusRequester = remember { FocusRequester() }
    val address = if (active == AddressTarget.ORIGIN) state.origin else state.destination
    val bothSelected = state.origin.selected != null && state.destination.selected != null

    LaunchedEffect(active, editing) {
        if (editing) runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(state.locationSelectionPending) {
        if (state.locationSelectionPending) {
            vm.consumeCurrentLocationSelection()
        }
        if (state.locationSelectionPending && active == AddressTarget.ORIGIN && editing) {
            keyboard?.hide()
            if (state.destination.selected != null) {
                onDoneEditing()
                if (state.stops.isEmpty()) vm.calculate()
                onSecondEndpointSelected()
            } else onActiveChange(AddressTarget.DESTINATION)
        }
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .97f),
        shadowElevation = 5.dp,
        modifier = Modifier.fillMaxWidth().testTag("address-overlay"),
    ) {
        Column(
            Modifier.heightIn(max = maxExpandedHeight).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!editing && !bothSelected) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onActiveChange(AddressTarget.DESTINATION) }
                        .padding(horizontal = 4.dp).testTag("start-destination-search"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Where to?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Choose a destination to begin", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ChevronRight, "Enter destination")
                }
            } else if (!editing && bothSelected) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        CompactEndpoint(
                            icon = Icons.Default.TripOrigin,
                            label = "From",
                            value = state.origin.selected?.title.orEmpty(),
                            onClick = { onActiveChange(AddressTarget.ORIGIN) },
                            tag = "edit-origin",
                        )
                        CompactEndpoint(
                            icon = Icons.Default.LocationOn,
                            label = "To",
                            value = buildString {
                                if (state.stops.isNotEmpty()) append("Via ${state.stops.size} stop${if (state.stops.size == 1) "" else "s"} · ")
                                append(state.destination.selected?.title.orEmpty())
                            },
                            onClick = { onActiveChange(AddressTarget.DESTINATION) },
                            tag = "edit-destination",
                        )
                    }
                    IconButton(
                        onClick = { if (vm.addStop() != null) onActiveChange(AddressTarget.DESTINATION) },
                        enabled = state.stops.size < PlannerViewModel.MAX_INTERMEDIATE_STOPS,
                        modifier = Modifier.testTag("add-next-destination"),
                    ) {
                        Icon(Icons.Default.AddLocationAlt, "Add next destination")
                    }
                    IconButton(onClick = vm::swap, modifier = Modifier.testTag("swap-route")) {
                        Icon(Icons.Default.SwapVert, "Reverse journey order")
                    }
                    IconButton(onClick = onOpenVehicle, modifier = Modifier.testTag("vehicle-settings")) {
                        Icon(Icons.Default.Settings, "Vehicle settings")
                    }
                }
            } else {
                if (!compactPrompt && active == AddressTarget.ORIGIN && state.destination.selected != null) {
                    Text(
                        "To ${state.destination.selected.title}",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
                if (!compactPrompt) {
                    Text(
                        if (active == AddressTarget.DESTINATION) "Where do you want to go?" else "Where are you starting?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                OutlinedTextField(
                    value = address.text,
                    onValueChange = { vm.editAddress(active.index, it) },
                    label = { Text(if (active == AddressTarget.DESTINATION) "Destination" else "Starting point") },
                    placeholder = { Text("Address, town or postcode") },
                    leadingIcon = { Icon(if (active == AddressTarget.DESTINATION) Icons.Default.LocationOn else Icons.Default.TripOrigin, null) },
                    trailingIcon = {
                        when {
                            address.isSearching -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            address.selected != null -> Icon(Icons.Default.CheckCircle, "Address selected", tint = MaterialTheme.colorScheme.secondary)
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                        .testTag(if (active == AddressTarget.DESTINATION) "destination-field" else "origin-field"),
                    shape = RoundedCornerShape(16.dp),
                )
                if (active == AddressTarget.ORIGIN) {
                    OutlinedButton(onClick={
                        keyboard?.hide()
                        if (ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) vm.requestCurrentLocation()
                        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
                    },enabled=!state.isLocating,modifier=Modifier.fillMaxWidth().testTag("use-current-location")) {
                        if(state.isLocating) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                        else Icon(Icons.Default.MyLocation,null)
                        Spacer(Modifier.width(8.dp))
                        Text(if(state.isLocating) "Finding your location…" else "Use my location")
                    }
                    if(state.isLocating) TextButton(onClick=vm::cancelCurrentLocation) {Text("Cancel location request")}
                    state.locationError?.let { error ->
                        Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                        if(error.contains("permission",ignoreCase=true)) TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}) {Text("Open app settings")}
                        if(error.startsWith("Turn on")) TextButton(onClick={context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))}) {Text("Open location settings")}
                    }
                    Text("Your selected position stays here until you calculate, then coordinates are sent to the online road-data provider.",style=MaterialTheme.typography.bodySmall)
                }
                address.suggestions.take(5).forEach { place ->
                    Surface(
                        onClick = {
                            val completesTrip = if (active == AddressTarget.ORIGIN) state.destination.selected != null else state.origin.selected != null
                            vm.selectPlace(active.index, place)
                            keyboard?.hide()
                            if (completesTrip) {
                                onDoneEditing()
                                if (state.stops.isEmpty()) vm.calculate()
                                onSecondEndpointSelected()
                            } else {
                                onActiveChange(if (active == AddressTarget.DESTINATION) AddressTarget.ORIGIN else AddressTarget.DESTINATION)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().testTag("address-suggestion-${place.id}"),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(place.title, fontWeight = FontWeight.Medium)
                            if (place.detail.isNotBlank()) Text(place.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (address.selected == null && address.text.length >= 3 && !address.isSearching && address.suggestions.isEmpty() && address.error == null) {
                    Text("Choose an address from the suggestions.", style = MaterialTheme.typography.bodySmall)
                }
                address.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.retryAddress(active.index) }) { Text("Retry address search") }
                }
                if (bothSelected) TextButton(onClick = onDoneEditing, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
            if (state.origin.selected?.kind == "current_location") state.locationNotice?.let {
                Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("location-accuracy-notice"))
            }
        }
    }
}

@Composable
private fun CompactEndpoint(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, onClick: () -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 2.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, maxLines = 1, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannerSheet(
    state: UiState,
    vm: PlannerViewModel,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    toggleEnabled: Boolean = true,
    onOpenVehicle: () -> Unit,
    onExport: () -> Unit,
    onEditRoute: () -> Unit,
    onNewRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.journey) {
        if (state.journey != null) listState.scrollToItem(0)
    }
    LazyColumn(
        modifier.fillMaxWidth().heightIn(min = 152.dp, max = 680.dp).navigationBarsPadding().testTag("planner-sheet"),
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SheetPeek(state, expanded, onToggleExpanded, toggleEnabled)
        }
        item { HorizontalDivider() }
        if (state.isCalculating) {
            item {
                SheetSection {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.progressMessage ?: "Planning your route…", fontWeight = FontWeight.SemiBold)
                    Text("You can keep using the map while Eco Route compares options.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::cancelCalculation) { Text("Cancel calculation") }
                }
            }
        }
        state.error?.let { error ->
            item {
                SheetSection {
                    Text("Route could not be calculated", style = MaterialTheme.typography.titleMedium)
                    Text(error, color = MaterialTheme.colorScheme.error)
                    Row {
                        Button(onClick = vm::calculate) { Text("Retry") }
                        TextButton(onClick = vm::clearError) { Text("Dismiss") }
                    }
                }
            }
        }
        if (state.journey != null) {
            item { SheetSection { LegSelector(state, vm) } }
            state.selectedLeg?.let { leg ->
                items(leg.routes, key = { it.id }) { route -> RouteOption(state, leg, route, vm::selectRoute) }
            }
            state.totals?.let { totals ->
                item {
                    SheetSection {
                        Text("Journey total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (!totals.eligible) {
                            Text("The selected route exceeds your time allowance; its fastest baseline is shown.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        }
                        Text("Estimated added driving time: ${totals.addedMinutes.display()} min versus the fastest modeled route${if (state.journey.legs.size > 1) "s" else ""}.")
                        if (totals.litersSaved >= 0) {
                            Text("Estimated saving: ${totals.litersSaved.display(2)} L${totals.fuelCostSavedEur?.let { " · €${it.display(2)}" }.orEmpty()} versus the fastest modeled route${if (state.journey.legs.size > 1) "s" else ""}.")
                        } else {
                            Text("Estimated extra fuel: ${abs(totals.litersSaved).display(2)} L versus the fastest modeled route${if (state.journey.legs.size > 1) "s" else ""}.")
                        }
                        Text("Fuel cost excludes tolls and parking.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                SheetSection {
                    OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export whole journey as CSV")
                    }
                }
            }
        }
        item {
            SheetSection {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Trip options", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row {
                        if (state.origin.selected != null || state.destination.selected != null) {
                            TextButton(onClick = onNewRoute, modifier = Modifier.testTag("new-route")) { Text("New route") }
                        }
                        TextButton(onClick = onEditRoute) { Text("Edit route") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !state.roundTrip, onClick = { vm.setRoundTrip(false) }, label = { Text("One way") })
                    FilterChip(selected = state.roundTrip, onClick = { vm.setRoundTrip(true) }, label = { Text("Return to start") })
                }
                IntermediateStopsEditor(state, vm, onEditRoute)
                Allowance(state, vm)
                TripLoad(state, vm)
                if (state.origin.selected != null && state.destination.selected != null && !state.isCalculating) {
                    Button(onClick = vm::calculate, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("recalculate")) {
                        Text(if (state.journey == null) "Calculate journey" else "Recalculate journey")
                    }
                }
            }
        }
        item { SheetSection { VehicleSummary(state, onOpenVehicle) } }
        item { SheetSection { PriceInfo(state, vm) } }
        item {
            SheetSection {
                Text("How to read this plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                state.selectedRoute?.let { Text("${it.knownSpeedLimitPct.display(0)}% of this route has mapped speed limits.") }
                state.selectedPlan?.sensitivityLiters?.takeIf { it.size >= 2 }?.let {
                    Text("Alternative vehicle assumptions give ${it[0].display()}–${it[1].display()} L. This is a sensitivity range, not a confidence interval.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Fuel estimates include elevation, vehicle mass, aerodynamic drag, rolling resistance and auxiliary loads. They use the uncalibrated road-load-v2 engine-on model; hybrid battery control and fuel cutoff are not simulated. Traffic, wind and weather are not included.", style = MaterialTheme.typography.bodySmall)
                Text("This map is a planning guide, not live navigation. Follow actual signs and conditions.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                state.selectedLeg?.warnings?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Fuel and speed optimization runs on your phone. Car search and saved cars stay here. Address searches go to Photon; selected coordinates and route geometry go to the Valhalla road-data service; map tiles come from OpenStreetMap. Internet is required for new road data. Local road/address caches expire after 7 days and are cleaned on use. Location is optional, with no account, analytics or background tracking.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun pointLetter(index: Int): String = ('A'.code + index).toChar().toString()

@Composable
private fun IntermediateStopsEditor(state: UiState, vm: PlannerViewModel, onEditDestination: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    Text("Stops", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (state.stops.isEmpty()) {
        Text("Add intermediate places to build an ordered A → B → C journey.", style = MaterialTheme.typography.bodySmall)
    }
    state.stops.forEachIndexed { index, stop ->
        val address = stop.address
        Card(Modifier.fillMaxWidth().testTag("stop-${stop.id}"), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${pointLetter(index + 1)} · Stop ${index + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { vm.moveStop(stop.id, -1) }, enabled = index > 0,
                        modifier = Modifier.testTag("stop-up-${stop.id}"),
                    ) { Icon(Icons.Default.ArrowUpward, "Move stop ${index + 1} earlier") }
                    IconButton(
                        onClick = { vm.moveStop(stop.id, 1) },
                        enabled = index < state.stops.lastIndex ||
                            (address.selected != null && !address.isSearching && state.destination.selected != null && !state.destination.isSearching),
                        modifier = Modifier.testTag("stop-down-${stop.id}"),
                    ) { Icon(Icons.Default.ArrowDownward, "Move stop ${index + 1} later") }
                    IconButton(onClick = { vm.removeStop(stop.id) }, modifier = Modifier.testTag("stop-remove-${stop.id}")) {
                        Icon(Icons.Default.Delete, "Remove stop ${index + 1}")
                    }
                }
                OutlinedTextField(
                    value = address.text,
                    onValueChange = { vm.editStop(stop.id, it) },
                    label = { Text("Stop ${index + 1}") },
                    placeholder = { Text("Address, town or postcode") },
                    leadingIcon = { Icon(Icons.Default.LocationOn, null) },
                    trailingIcon = {
                        when {
                            address.isSearching -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            address.selected != null -> Icon(Icons.Default.CheckCircle, "Address selected", tint = MaterialTheme.colorScheme.secondary)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("stop-field-${stop.id}"),
                    shape = RoundedCornerShape(14.dp),
                )
                address.suggestions.take(5).forEach { place ->
                    Surface(
                        onClick = { vm.selectStopPlace(stop.id, place); keyboard?.hide() },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().testTag("stop-suggestion-${stop.id}-${place.id}"),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(place.title, fontWeight = FontWeight.Medium)
                            if (place.detail.isNotBlank()) Text(place.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (address.selected == null && address.text.length >= 3 && !address.isSearching && address.suggestions.isEmpty() && address.error == null) {
                    Text("Choose an address from the suggestions.", style = MaterialTheme.typography.bodySmall)
                }
                address.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.retryStop(stop.id) }) { Text("Retry address search") }
                }
            }
        }
    }
    OutlinedButton(
        onClick = { if (vm.addStop() != null) onEditDestination() },
        enabled = state.origin.selected != null && state.destination.selected != null && state.stops.size < PlannerViewModel.MAX_INTERMEDIATE_STOPS,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add-stop"),
    ) {
        Icon(Icons.Default.AddLocationAlt, null)
        Spacer(Modifier.width(8.dp))
        Text(if (state.stops.isEmpty()) "Add next destination" else "Add another destination (${state.stops.size}/8)")
    }
    if (state.stops.isNotEmpty()) {
        val labels = buildList {
            state.origin.selected?.title?.let(::add)
            state.stops.forEach { add(it.address.selected?.title ?: "Choose stop") }
            state.destination.selected?.title?.let(::add)
        }
        Text("Order: ${labels.joinToString(" → ")}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("stop-order-summary"))
    }
}

@Composable
private fun SheetPeek(state: UiState, expanded: Boolean, onToggleExpanded: () -> Unit, toggleEnabled: Boolean) {
    val interaction = if (toggleEnabled) Modifier.clickable(onClick = onToggleExpanded) else Modifier
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (toggleEnabled) 151.dp else 116.dp).then(interaction)
            .padding(horizontal = 20.dp, vertical = 10.dp).testTag("sheet-toggle")
            .semantics { if (toggleEnabled) contentDescription = if (expanded) "Collapse trip details" else "Expand trip details" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.width(36.dp).height(4.dp).align(Alignment.CenterHorizontally)) {
                HorizontalDivider(thickness = 4.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            when {
                state.isCalculating -> {
                    Text("Calculating route…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(state.progressMessage ?: "Comparing time and fuel", maxLines = 2)
                }
                state.totals != null -> {
                    val totals = state.totals
                    val multiSection = (state.journey?.legs?.size ?: 0) > 1
                    val legacyRoundTrip = state.journey?.tripType == "round_trip"
                    Text(
                        if (legacyRoundTrip) "Round-trip total · map: ${if (state.activeLegIndex == 0) "outbound" else "return"}"
                        else if (multiSection) "Journey total · map: section ${state.activeLegIndex + 1} of ${state.journey?.legs?.size}"
                        else state.selectedRoute?.label ?: "Selected journey",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                        Text(totals?.fuelCostEur?.let { "€${it.display(2)}" } ?: "${totals?.liters?.display(2)} L", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("${timeLabel(totals?.minutes ?: 0.0)} · ${totals?.distanceKm?.display()} km", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        if (totals?.fuelCostEur != null) "Estimated fuel cost · ${totals.liters.display(2)} L · ${totals.consumptionLPer100Km.display()} L/100 km"
                        else "Estimated fuel · ${totals?.consumptionLPer100Km?.display()} L/100 km",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
                state.error != null -> {
                    Text("Route needs attention", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Expand for the error and retry.", style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    Text("Plan details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Destination first, then choose your starting point.", style = MaterialTheme.typography.bodyMedium)
                    Text("Trip options and vehicle load", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (toggleEnabled) Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, null)
    }
}

@Composable
private fun SheetSection(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun RouteOption(state: UiState, leg: LegReport, route: RouteReport, onSelect: (String) -> Unit) {
    val plan = route.plans[state.allowanceValue] ?: route.baseline
    val fastestBaseline = leg.routes.minByOrNull { it.baseline.minutes }?.baseline ?: route.baseline
    val litersSaved = fastestBaseline.liters - plan.liters
    val addedMinutes = (plan.minutes - leg.fastestMinutes).coerceAtLeast(0.0)
    val selected = state.selectedRoute?.id == route.id
    Card(
        onClick = { onSelect(route.id) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("route-option-${route.id}"),
        shape = RoundedCornerShape(18.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(route.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text("${plan.liters.display(2)} L · ${timeLabel(plan.minutes)} · ${route.distanceKm.display()} km")
            state.fuelPrice?.currentPrice(leg.vehicle?.fuelType ?: state.vehicle.fuelType)?.let {
                Text("€${(plan.liters * it).display(2)} estimated fuel cost", style = MaterialTheme.typography.bodyMedium)
            }
            Text("Modeled +${addedMinutes.display()} min vs fastest · ${if (litersSaved >= 0) "save" else "use"} ${abs(litersSaved).display(2)} L", style = MaterialTheme.typography.bodySmall)
            Text(
                if (route.plans[state.allowanceValue] == null) "Outside allowance · showing baseline"
                else if (leg.recommendations[state.allowanceValue] == route.id) "Lowest estimated fuel within allowance"
                else "Within allowance",
                color = if (route.plans[state.allowanceValue] == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
