package org.kalc.ecoroute

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

class PlannerViewModel private constructor(
    application: Application,
    private val api: EcoService,
    private val ioDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, EcoApi(application), Dispatchers.IO)

    internal constructor(application: Application, api: EcoService) : this(application, api, Dispatchers.Unconfined)

    private val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @Suppress("unused")
    private val loadMigrationComplete = migrateVersionFourLoad()
    private val initialVehicle = loadVehicle()
    private val initialCatalogue = loadCatalogueVehicle()
    private val initialBodyStyle = VehicleBodyStyle.fromWire(preferences.getString(VEHICLE_BODY_STYLE, null))
    private val initialAssumptionOrigin = VehicleAssumptionOrigin.fromWire(preferences.getString(VEHICLE_ASSUMPTION_ORIGIN, null)
        ?: if (initialVehicle == VehicleFields.DEFAULT) VehicleAssumptionOrigin.PILOT_PRESET.wireValue else VehicleAssumptionOrigin.MANUAL.wireValue)
    private val vehicleProfileStore = VehicleProfileStore(preferences)
    private val initialProfileState = vehicleProfileStore.loadOrMigrate(VehicleProfile(
        id = "legacy",
        vehicle = initialVehicle,
        catalogue = initialCatalogue,
        bodyStyle = initialBodyStyle,
        assumptionOrigin = initialAssumptionOrigin,
    ))
    private val initialActiveProfile = requireNotNull(
        initialProfileState.profiles.firstOrNull { it.id == initialProfileState.activeProfileId },
    )
    private val initialTripLoad = preferences.getString(TRIP_LOAD_KG, "0") ?: "0"
    private val initialTripLoadError = tripLoadError(initialTripLoad, initialActiveProfile.vehicle.massKg)
    private val _state = MutableStateFlow(UiState(
        vehicle = initialActiveProfile.vehicle,
        vehicleDraft = initialActiveProfile.vehicle,
        vehicleCatalogue = initialActiveProfile.catalogue,
        vehicleDraftCatalogue = initialActiveProfile.catalogue,
        vehicleBodyStyle = initialActiveProfile.bodyStyle,
        vehicleDraftBodyStyle = initialActiveProfile.bodyStyle,
        vehicleAssumptionOrigin = initialActiveProfile.assumptionOrigin,
        vehicleDraftAssumptionOrigin = initialActiveProfile.assumptionOrigin,
        vehicleProfiles = initialProfileState.profiles,
        activeVehicleProfileId = initialProfileState.activeProfileId,
        editingVehicleProfileId = initialProfileState.activeProfileId,
        tripLoadKg = initialTripLoad,
        tripLoadError = initialTripLoadError,
        vehicleSetupComplete = preferences.getBoolean(VEHICLE_SETUP_COMPLETE, false),
        vehicleSetupIsLegacyReview = !preferences.getBoolean(VEHICLE_SETUP_COMPLETE, false) &&
            VehicleKeys.ALL.any(preferences::contains),
    ))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val addressJobs = arrayOfNulls<Job>(2)
    private val addressSearchGeneration = longArrayOf(0, 0)
    private val stopAddressJobs = mutableMapOf<Long, Job>()
    private val stopSearchGeneration = mutableMapOf<Long, Long>()
    private var nextStopId = 1L
    private var vehicleSearchJob: Job? = null
    private var calculationJob: Job? = null
    private var calculationGeneration = 0L
    private var locationJob: Job? = null
    private var locationGeneration = 0L

    fun consumeCurrentLocationSelection() {
        _state.update { it.copy(locationSelectionPending = false) }
    }

    fun cancelCurrentLocation() {
        locationGeneration++
        locationJob?.cancel()
        locationJob = null
        _state.update { it.copy(isLocating = false, locationSelectionPending = false) }
    }

    fun locationPermissionDenied() {
        cancelCurrentLocation()
        _state.update { it.copy(locationError = "Location permission was not granted. Allow it in app settings or enter your starting address.") }
    }

    fun requestCurrentLocation(getFix: suspend () -> CurrentLocation = { LocationService(getApplication()).getCurrentLocation() }) {
        cancelCurrentLocation()
        val generation = locationGeneration
        addressJobs[0]?.cancel()
        addressSearchGeneration[0]++
        invalidateCalculation()
        clearJourney()
        _state.update { it.copy(isLocating = true, locationError = null, locationNotice = null,
            origin = it.origin.copy(isSearching = false, suggestions = emptyList())) }
        locationJob = viewModelScope.launch {
            try {
                val (fix, place) = withTimeout(30_000L) {
                    val position = getFix()
                    position to withContext(ioDispatcher) { api.registerCurrentPlace(position.latitude, position.longitude) }
                }
                if (generation != locationGeneration) return@launch
                invalidateCalculation()
                val accuracy = kotlin.math.ceil(fix.accuracyMeters.toDouble()).toInt()
                val notice = if (fix.approximate) "Approximate location · accuracy about $accuracy m. Check the starting point on the map."
                    else "Location accuracy about $accuracy m. Check road access on the map."
                _state.update { derive(it.copy(
                    origin = AddressState(text = place.title, selected = place.copy(detail = notice)),
                    isLocating = false, locationError = null, locationNotice = notice,
                    locationSelectionVersion = it.locationSelectionVersion + 1,
                    locationSelectionPending = true,
                    error = null,
                    journey = null, selectedRouteIds = emptyList(), activeLegIndex = 0,
                )) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (generation == locationGeneration) _state.update { it.copy(isLocating = false,
                    locationError = if(error is kotlinx.coroutines.TimeoutCancellationException) "Location request took too long. Try again or enter a starting address."
                        else error.message ?: "Could not get your location. Try again or enter a starting address.") }
            }
        }
    }

    init {
        refreshFuelPrice()
    }

    fun editAddress(index: Int, text: String) {
        if (index !in 0..1) return
        if (index == 0) { cancelCurrentLocation(); _state.update { it.copy(locationError = null, locationNotice = null) } }
        invalidateCalculation()
        addressJobs[index]?.cancel()
        val searchGeneration = ++addressSearchGeneration[index]
        updateAddress(index) { it.copy(text = text, selected = null, suggestions = emptyList(), isSearching = false, error = null) }
        clearJourney()
        if (text.trim().length < 3) return
        updateAddress(index) { it.copy(isSearching = true) }
        addressJobs[index] = viewModelScope.launch {
            delay(ADDRESS_DEBOUNCE_MS)
            search(index, text.trim(), searchGeneration)
        }
    }

    fun selectPlace(index: Int, place: Place) {
        if (index !in 0..1) return
        if (index == 0) { cancelCurrentLocation(); _state.update { it.copy(locationError = null, locationNotice = null) } }
        invalidateCalculation()
        addressJobs[index]?.cancel()
        addressSearchGeneration[index]++
        updateAddress(index) {
            it.copy(text = place.label, selected = place, suggestions = emptyList(), isSearching = false, error = null)
        }
        clearJourney()
    }

    fun retryAddress(index: Int) {
        if (index !in 0..1) return
        val query = address(index).text.trim()
        if (query.length < 3) {
            updateAddress(index) { it.copy(error = "Enter at least 3 characters.", isSearching = false) }
            return
        }
        addressJobs[index]?.cancel()
        val searchGeneration = ++addressSearchGeneration[index]
        updateAddress(index) { it.copy(isSearching = true, error = null, suggestions = emptyList()) }
        addressJobs[index] = viewModelScope.launch { search(index, query, searchGeneration) }
    }

    fun swap() {
        cancelCurrentLocation()
        invalidateCalculation()
        addressJobs.forEach { it?.cancel() }
        addressSearchGeneration.indices.forEach { addressSearchGeneration[it]++ }
        stopAddressJobs.values.forEach { it.cancel() }
        stopAddressJobs.clear()
        stopSearchGeneration.keys.toList().forEach { id -> stopSearchGeneration[id] = (stopSearchGeneration[id] ?: 0L) + 1 }
        _state.update { current ->
            derive(current.copy(
                origin = current.destination.settled(),
                destination = current.origin.settled(),
                stops = current.stops.reversed().map { it.copy(address = it.address.settled()) },
                journey = null,
                selectedRouteIds = emptyList(),
                activeLegIndex = 0,
                error = null,
            ))
        }
    }

    fun addStop(): Long? {
        val current = _state.value
        val currentDestination = current.destination.selected
        if (current.origin.selected == null || currentDestination == null) {
            _state.update { it.copy(error = "Choose a starting point and destination before adding the next destination.") }
            return null
        }
        if (current.stops.size >= MAX_INTERMEDIATE_STOPS) {
            _state.update { it.copy(error = "Choose no more than 8 intermediate stops.") }
            return null
        }
        invalidateCalculation()
        val id = nextStopId++
        _state.update { derive(it.copy(
            stops = it.stops + IntermediateStop(id, it.destination),
            destination = AddressState(), journey = null,
            selectedRouteIds = emptyList(), activeLegIndex = 0, error = null,
        )) }
        return id
    }

    fun editStop(id: Long, text: String) {
        if (_state.value.stops.none { it.id == id }) return
        invalidateCalculation()
        stopAddressJobs.remove(id)?.cancel()
        val searchGeneration = (stopSearchGeneration[id] ?: 0L) + 1
        stopSearchGeneration[id] = searchGeneration
        updateStop(id) { it.copy(text = text, selected = null, suggestions = emptyList(), isSearching = false, error = null) }
        clearJourney()
        if (text.trim().length < 3) return
        updateStop(id) { it.copy(isSearching = true) }
        stopAddressJobs[id] = viewModelScope.launch {
            delay(ADDRESS_DEBOUNCE_MS)
            searchStop(id, text.trim(), searchGeneration)
        }
    }

    fun selectStopPlace(id: Long, place: Place) {
        if (_state.value.stops.none { it.id == id }) return
        invalidateCalculation()
        stopAddressJobs.remove(id)?.cancel()
        stopSearchGeneration[id] = (stopSearchGeneration[id] ?: 0L) + 1
        updateStop(id) { it.copy(text = place.label, selected = place, suggestions = emptyList(), isSearching = false, error = null) }
        clearJourney()
    }

    fun retryStop(id: Long) {
        val query = _state.value.stops.find { it.id == id }?.address?.text?.trim() ?: return
        if (query.length < 3) {
            updateStop(id) { it.copy(error = "Enter at least 3 characters.", isSearching = false) }
            return
        }
        stopAddressJobs.remove(id)?.cancel()
        val searchGeneration = (stopSearchGeneration[id] ?: 0L) + 1
        stopSearchGeneration[id] = searchGeneration
        updateStop(id) { it.copy(isSearching = true, error = null, suggestions = emptyList()) }
        stopAddressJobs[id] = viewModelScope.launch { searchStop(id, query, searchGeneration) }
    }

    fun removeStop(id: Long) {
        if (_state.value.stops.none { it.id == id }) return
        invalidateCalculation()
        stopAddressJobs.remove(id)?.cancel()
        stopSearchGeneration.remove(id)
        _state.update { derive(it.copy(
            stops = it.stops.filterNot { stop -> stop.id == id }, journey = null,
            selectedRouteIds = emptyList(), activeLegIndex = 0, error = null,
        )) }
    }

    fun moveStop(id: Long, offset: Int) {
        if (offset !in setOf(-1, 1)) return
        val current = _state.value
        val from = current.stops.indexOfFirst { it.id == id }
        val to = from + offset
        if (from < 0) return
        if (offset == 1 && from == current.stops.lastIndex) {
            val destination = current.destination
            val stopAddress = current.stops[from].address
            if (destination.selected == null || destination.isSearching || stopAddress.selected == null || stopAddress.isSearching) return
            invalidateCalculation()
            stopAddressJobs.remove(id)?.cancel()
            stopSearchGeneration[id] = (stopSearchGeneration[id] ?: 0L) + 1
            addressJobs[1]?.cancel()
            addressSearchGeneration[1]++
            val reordered = current.stops.toMutableList()
            reordered[from] = reordered[from].copy(address = destination.settled())
            _state.update { derive(it.copy(
                destination = stopAddress.settled(),
                stops = reordered, journey = null, selectedRouteIds = emptyList(), activeLegIndex = 0, error = null,
            )) }
            return
        }
        if (to !in current.stops.indices) return
        invalidateCalculation()
        val reordered = current.stops.toMutableList().apply { add(to, removeAt(from)) }
        _state.update { derive(it.copy(
            stops = reordered, journey = null, selectedRouteIds = emptyList(), activeLegIndex = 0, error = null,
        )) }
    }

    fun cancelCalculation() {
        invalidateCalculation()
        _state.update { it.copy(error = null) }
    }

    fun clearTrip() {
        cancelCurrentLocation()
        invalidateCalculation()
        addressJobs.forEach { it?.cancel() }
        addressSearchGeneration.indices.forEach { addressSearchGeneration[it]++ }
        stopAddressJobs.values.forEach { it.cancel() }
        stopAddressJobs.clear()
        _state.update { derive(it.copy(
            origin = AddressState(), destination = AddressState(), stops = emptyList(), journey = null,
            selectedRouteIds = emptyList(), activeLegIndex = 0, error = null,
        )) }
    }

    fun beginVehicleSettings(id: String? = null) {
        val current = _state.value
        val targetId = id ?: current.activeVehicleProfileId
        val profile = current.vehicleProfiles.firstOrNull { it.id == targetId } ?: return
        vehicleSearchJob?.cancel()
        _state.update { it.copy(
            vehicleDraft = profile.vehicle, vehicleDraftCatalogue = profile.catalogue,
            vehicleDraftBodyStyle = profile.bodyStyle,
            vehicleDraftAssumptionOrigin = profile.assumptionOrigin,
            editingVehicleProfileId = profile.id, vehicleDraftIsNew = false,
            vehicleDraftError = null, vehicleCatalogueSearch = VehicleCatalogueState(),
        ) }
    }

    fun beginAddVehicle() {
        vehicleSearchJob?.cancel()
        _state.update { it.copy(
            vehicleDraft = VehicleFields.DEFAULT,
            vehicleDraftCatalogue = null,
            vehicleDraftBodyStyle = null,
            vehicleDraftAssumptionOrigin = VehicleAssumptionOrigin.PILOT_PRESET,
            editingVehicleProfileId = null, vehicleDraftIsNew = true,
            vehicleDraftError = null, vehicleCatalogueSearch = VehicleCatalogueState(),
        ) }
    }

    fun selectVehicleProfile(id: String): Boolean {
        val current = _state.value
        val profile = current.vehicleProfiles.firstOrNull { it.id == id } ?: return false
        if (id == current.activeVehicleProfileId) return true
        if (!vehicleProfileStore.save(current.vehicleProfiles, id)) return false
        mirrorLegacyProfile(profile)
        invalidateCalculation()
        _state.update { derive(it.copy(
            vehicle = profile.vehicle,
            vehicleDraft = profile.vehicle,
            vehicleCatalogue = profile.catalogue,
            vehicleDraftCatalogue = profile.catalogue,
            vehicleBodyStyle = profile.bodyStyle,
            vehicleDraftBodyStyle = profile.bodyStyle,
            vehicleAssumptionOrigin = profile.assumptionOrigin,
            vehicleDraftAssumptionOrigin = profile.assumptionOrigin,
            activeVehicleProfileId = id,
            editingVehicleProfileId = id,
            vehicleDraftIsNew = false,
            tripLoadError = tripLoadError(it.tripLoadKg, profile.vehicle.massKg),
            journey = null, selectedRouteIds = emptyList(), activeLegIndex = 0, error = null,
        )) }
        return true
    }

    fun deleteVehicleProfile(id: String): Boolean {
        val current = _state.value
        if (current.vehicleProfiles.none { it.id == id }) return false
        if (current.vehicleProfiles.size == 1) {
            _state.update { it.copy(vehicleDraftError = "Keep at least one saved vehicle.") }
            return false
        }
        val remaining = current.vehicleProfiles.filterNot { it.id == id }
        val deletingActive = id == current.activeVehicleProfileId
        val nextActiveId = if (deletingActive) remaining.first().id else current.activeVehicleProfileId
        if (!vehicleProfileStore.save(remaining, nextActiveId)) {
            _state.update { it.copy(vehicleDraftError = "Vehicle could not be removed. Please try again.") }
            return false
        }
        val nextActive = requireNotNull(remaining.firstOrNull { it.id == nextActiveId })
        if (deletingActive) {
            mirrorLegacyProfile(nextActive)
            invalidateCalculation()
        }
        _state.update { derive(it.copy(
            vehicleProfiles = remaining,
            activeVehicleProfileId = nextActiveId,
            vehicle = nextActive.vehicle,
            vehicleCatalogue = nextActive.catalogue,
            vehicleBodyStyle = nextActive.bodyStyle,
            vehicleAssumptionOrigin = nextActive.assumptionOrigin,
            vehicleDraft = nextActive.vehicle,
            vehicleDraftCatalogue = nextActive.catalogue,
            vehicleDraftBodyStyle = nextActive.bodyStyle,
            vehicleDraftAssumptionOrigin = nextActive.assumptionOrigin,
            editingVehicleProfileId = nextActiveId,
            vehicleDraftIsNew = false,
            vehicleDraftError = null,
            tripLoadError = tripLoadError(it.tripLoadKg, nextActive.vehicle.massKg),
            journey = if (deletingActive) null else it.journey,
            selectedRouteIds = if (deletingActive) emptyList() else it.selectedRouteIds,
            activeLegIndex = if (deletingActive) 0 else it.activeLegIndex,
            error = if (deletingActive) null else it.error,
        )) }
        return true
    }

    fun editVehicleDraft(key: String, value: String) {
        val old = _state.value.vehicleDraft
        var changed = when (key) {
            VehicleKeys.NAME -> old.copy(name = value)
            VehicleKeys.FUEL_TYPE -> old.copy(fuelType = value)
            VehicleKeys.MASS_KG -> old.copy(massKg = value)
            VehicleKeys.DRAG_AREA_M2 -> old.copy(dragAreaM2 = value)
            VehicleKeys.EFFICIENCY_PERCENT -> old.copy(efficiencyPercent = value)
            VehicleKeys.ROLLING_RESISTANCE -> old.copy(rollingResistance = value)
            VehicleKeys.AUXILIARY_KW -> old.copy(auxiliaryKw = value)
            VehicleKeys.ENGINE_OVERHEAD_L_H -> old.copy(engineOverheadLh = value)
            else -> return
        }
        if (changed == old) return
        val basicEdit = key in setOf(VehicleKeys.NAME, VehicleKeys.FUEL_TYPE, VehicleKeys.MASS_KG)
        _state.update { it.copy(
            vehicleDraft = changed, vehicleDraftError = null,
            vehicleDraftCatalogue = if (basicEdit) null else it.vehicleDraftCatalogue,
            vehicleDraftBodyStyle = if (basicEdit || key !in setOf(VehicleKeys.NAME, VehicleKeys.FUEL_TYPE, VehicleKeys.MASS_KG)) null else it.vehicleDraftBodyStyle,
            vehicleDraftAssumptionOrigin = VehicleAssumptionOrigin.MANUAL,
        ) }
    }

    fun resetVehicleDraft() {
        _state.update { it.copy(
            vehicleDraft = VehicleFields.DEFAULT, vehicleDraftCatalogue = null, vehicleDraftBodyStyle = null,
            vehicleDraftAssumptionOrigin = VehicleAssumptionOrigin.PILOT_PRESET,
            vehicleDraftError = null,
        ) }
    }

    fun discardVehicleDraft() {
        vehicleSearchJob?.cancel()
        val active = _state.value.vehicleProfiles.first { it.id == _state.value.activeVehicleProfileId }
        _state.update { it.copy(
            vehicleDraft = active.vehicle, vehicleDraftCatalogue = active.catalogue,
            vehicleDraftBodyStyle = active.bodyStyle,
            vehicleDraftAssumptionOrigin = active.assumptionOrigin,
            editingVehicleProfileId = active.id, vehicleDraftIsNew = false,
            vehicleDraftError = null, vehicleCatalogueSearch = VehicleCatalogueState(),
        ) }
    }

    fun editVehicleCatalogueQuery(value: String) {
        vehicleSearchJob?.cancel()
        _state.update { it.copy(vehicleCatalogueSearch = it.vehicleCatalogueSearch.copy(
            query = value, results = emptyList(), isSearching = false, error = null,
            totalMatches = 0, truncated = false, coverageMessage = "",
            nextOffset = null,
        )) }
        if (value.trim().length < 2) return
        _state.update { it.copy(vehicleCatalogueSearch = it.vehicleCatalogueSearch.copy(isSearching = true)) }
        vehicleSearchJob = viewModelScope.launch {
            delay(VEHICLE_SEARCH_DEBOUNCE_MS)
            searchVehicles(value.trim())
        }
    }

    fun setVehicleCatalogueFuelFilter(value: String?) {
        if (value != null && value !in setOf("diesel", "petrol")) return
        vehicleSearchJob?.cancel()
        _state.update { it.copy(vehicleCatalogueSearch = it.vehicleCatalogueSearch.copy(
            fuelFilter = value, results = emptyList(), isSearching = false, error = null, nextOffset = null,
        )) }
        val query = _state.value.vehicleCatalogueSearch.query.trim()
        if (query.length >= 2) {
            _state.update { it.copy(vehicleCatalogueSearch = it.vehicleCatalogueSearch.copy(isSearching = true)) }
            vehicleSearchJob = viewModelScope.launch { searchVehicles(query) }
        }
    }

    fun retryVehicleCatalogueSearch() {
        val query = _state.value.vehicleCatalogueSearch.query.trim()
        if (query.length < 2) return
        vehicleSearchJob?.cancel()
        _state.update { it.copy(vehicleCatalogueSearch = it.vehicleCatalogueSearch.copy(isSearching = true, error = null)) }
        vehicleSearchJob = viewModelScope.launch { searchVehicles(query) }
    }

    fun loadMoreVehicleCatalogue() {
        val search = _state.value.vehicleCatalogueSearch
        val offset = search.nextOffset ?: return
        if (search.isSearching) return
        _state.update { it.copy(vehicleCatalogueSearch = search.copy(isSearching = true, error = null)) }
        vehicleSearchJob = viewModelScope.launch { searchVehicles(search.query.trim(), offset) }
    }

    fun selectCatalogueVehicle(vehicle: CatalogueVehicle) {
        if (!vehicle.isSupported) {
            _state.update { it.copy(vehicleDraftError = vehicle.unsupportedReason ?: "This catalogue entry needs manual vehicle details.") }
            return
        }
        val mass = formatVehicleNumber(requireNotNull(vehicle.massKg))
        val style = vehicle.bodyStyle
        val fields = style?.estimatedFields(vehicle.fuelType, mass, vehicle.displayName) ?: VehicleFields(
            name = vehicle.displayName, fuelType = vehicle.fuelType, massKg = mass,
            dragAreaM2 = "", efficiencyPercent = "", rollingResistance = "",
            auxiliaryKw = "", engineOverheadLh = "",
        )
        _state.update { it.copy(
            vehicleDraft = fields,
            vehicleDraftCatalogue = vehicle, vehicleDraftBodyStyle = style,
            vehicleDraftAssumptionOrigin = if (style == null) VehicleAssumptionOrigin.NEEDS_ESTIMATE else VehicleAssumptionOrigin.ESTIMATED,
            vehicleDraftError = null,
            vehicleCatalogueSearch = VehicleCatalogueState(query = vehicle.displayName),
        ) }
    }

    fun selectVehicleBodyStyle(style: VehicleBodyStyle) {
        val current = _state.value
        val vehicle = current.vehicleDraft
        _state.update { it.copy(
            vehicleDraft = style.estimatedFields(vehicle.fuelType, vehicle.massKg, vehicle.name),
            vehicleDraftBodyStyle = style,
            vehicleDraftAssumptionOrigin = VehicleAssumptionOrigin.ESTIMATED,
            vehicleDraftError = null,
        ) }
    }

    fun useManualVehicleEntry() {
        vehicleSearchJob?.cancel()
        _state.update { it.copy(
            vehicleDraftCatalogue = null,
            vehicleDraftBodyStyle = null,
            vehicleDraftAssumptionOrigin = VehicleAssumptionOrigin.MANUAL,
            vehicleDraftError = null,
            vehicleCatalogueSearch = VehicleCatalogueState(),
        ) }
    }

    fun editTripLoad(value: String) {
        if (value == _state.value.tripLoadKg) return
        invalidateCalculation()
        val error = tripLoadError(value, _state.value.vehicle.massKg)
        preferences.edit().putString(TRIP_LOAD_KG, value).apply()
        _state.update { derive(it.copy(
            tripLoadKg = value, tripLoadError = error, journey = null,
            selectedRouteIds = emptyList(), activeLegIndex = 0, error = null,
        )) }
    }

    fun resetTripLoad() = editTripLoad("0")

    fun saveVehicleDraft(): Boolean {
        val current = _state.value
        val draft = current.vehicleDraft
        if (current.vehicleDraftCatalogue != null) {
            if (current.vehicleDraftAssumptionOrigin == VehicleAssumptionOrigin.NEEDS_ESTIMATE) {
                _state.update { it.copy(vehicleDraftError = "Choose the closest body style for the estimated assumptions, or enter them manually.") }
                return false
            }
        }
        val validation = draft.validate()
        if (validation.payload == null) {
            _state.update { it.copy(vehicleDraftError = validation.error) }
            return false
        }
        val profileId = if (current.vehicleDraftIsNew) UUID.randomUUID().toString()
            else current.editingVehicleProfileId ?: current.activeVehicleProfileId
        val profile = VehicleProfile(
            id = profileId, vehicle = draft,
            catalogue = current.vehicleDraftCatalogue,
            bodyStyle = current.vehicleDraftBodyStyle,
            assumptionOrigin = current.vehicleDraftAssumptionOrigin,
        )
        val profiles = if (current.vehicleDraftIsNew) current.vehicleProfiles + profile
            else current.vehicleProfiles.map { if (it.id == profileId) profile else it }
        if (profiles.none { it.id == profileId }) {
            _state.update { it.copy(vehicleDraftError = "That saved vehicle no longer exists.") }
            return false
        }
        val activeId = if (current.vehicleDraftIsNew) profileId else current.activeVehicleProfileId
        val saved = vehicleProfileStore.save(profiles, activeId)
        if (!saved) {
            _state.update { it.copy(vehicleDraftError = "Vehicle settings could not be saved. Please try again.") }
            return false
        }
        val activeProfile = requireNotNull(profiles.firstOrNull { it.id == activeId })
        val activeChanged = activeId != current.activeVehicleProfileId ||
            (profileId == current.activeVehicleProfileId && current.vehicleDraftDirty)
        mirrorLegacyProfile(activeProfile)
        if (activeChanged) invalidateCalculation()
        _state.update { derive(it.copy(
            vehicle = activeProfile.vehicle, vehicleDraft = profile.vehicle, vehicleSetupComplete = true,
            vehicleCatalogue = activeProfile.catalogue,
            vehicleDraftCatalogue = profile.catalogue,
            vehicleBodyStyle = activeProfile.bodyStyle,
            vehicleDraftBodyStyle = profile.bodyStyle,
            vehicleAssumptionOrigin = activeProfile.assumptionOrigin,
            vehicleDraftAssumptionOrigin = profile.assumptionOrigin,
            vehicleProfiles = profiles, activeVehicleProfileId = activeId,
            editingVehicleProfileId = profileId, vehicleDraftIsNew = false,
            tripLoadError = tripLoadError(it.tripLoadKg, activeProfile.vehicle.massKg),
            vehicleSetupIsLegacyReview = false, vehicleDraftError = null,
            journey = if (activeChanged) null else it.journey,
            selectedRouteIds = if (activeChanged) emptyList() else it.selectedRouteIds,
            activeLegIndex = if (activeChanged) 0 else it.activeLegIndex,
            error = if (activeChanged) null else it.error,
        )) }
        return true
    }

    fun setRoundTrip(value: Boolean) {
        invalidateCalculation()
        _state.update { derive(it.copy(
            roundTrip = value, journey = null, selectedRouteIds = emptyList(), activeLegIndex = 0,
            isCalculating = false, progressMessage = null, error = null,
        )) }
    }

    fun setPercentAllowance(value: Int) {
        if (value !in _state.value.percentAllowanceValues || _state.value.allowanceMode != TimeAllowanceMode.PERCENT) return
        _state.update { current ->
            val selected = current.journey?.legs?.map { it.recommendations[value].orEmpty() }.orEmpty()
            derive(current.copy(allowanceValue = value, selectedRouteIds = selected))
        }
    }

    fun setAllowanceMode(mode: TimeAllowanceMode) {
        if (mode == _state.value.allowanceMode) return
        invalidateCalculation()
        _state.update { current ->
            val value = if (mode == TimeAllowanceMode.PERCENT) 10
            else current.customMinutesText.toIntOrNull()?.takeIf { it in 1..120 } ?: 10
            derive(current.copy(
                allowanceMode = mode, allowanceValue = value, journey = null,
                selectedRouteIds = emptyList(), activeLegIndex = 0, error = null,
            ))
        }
    }

    fun editCustomMinutes(value: String) {
        if (value == _state.value.customMinutesText) return
        invalidateCalculation()
        val parsed = value.toIntOrNull()
        _state.update { current ->
            derive(current.copy(
                customMinutesText = value,
                allowanceValue = parsed?.takeIf { it in 1..120 } ?: current.allowanceValue,
                journey = null, selectedRouteIds = emptyList(), activeLegIndex = 0, error = null,
            ))
        }
    }

    fun calculate() {
        if (_state.value.isLocating) {
            _state.update { it.copy(error = "Wait for your location or cancel the location request before calculating.") }
            return
        }
        if (calculationJob?.isActive == true) return
        val current = _state.value
        val origin = current.origin.selected
        val destination = current.destination.selected
        if (origin == null || destination == null) {
            val missing = if (origin == null) "starting point" else "destination"
            _state.update { it.copy(error = "Choose a $missing from the address suggestions.") }
            return
        }
        val missingStop = current.stops.indexOfFirst { it.address.selected == null }
        if (missingStop >= 0) {
            _state.update { it.copy(error = "Choose stop ${missingStop + 1} from the address suggestions.") }
            return
        }
        val validation = current.vehicle.validate()
        if (validation.payload == null) {
            _state.update { it.copy(error = validation.error) }
            return
        }
        val tripLoad = current.tripLoadKg.trim().toDoubleOrNull()
        val loadError = tripLoadError(current.tripLoadKg, current.vehicle.massKg)
        if (tripLoad == null || loadError != null) {
            _state.update { it.copy(tripLoadError = loadError, error = loadError) }
            return
        }
        val requestVehicle = JSONObject(validation.payload.toString()).apply {
            put("mass_kg", getDouble("mass_kg") + tripLoad)
        }
        val timeAllowance = when (current.allowanceMode) {
            TimeAllowanceMode.PERCENT -> TimeAllowanceRequest(TimeAllowanceMode.PERCENT)
            TimeAllowanceMode.MINUTES -> {
                val minutes = current.customMinutesText.toIntOrNull()
                if (minutes == null || minutes !in 1..120) {
                    _state.update { it.copy(error = "Custom extra time must be a whole number from 1 to 120 minutes.") }
                    return
                }
                TimeAllowanceRequest(TimeAllowanceMode.MINUTES, minutes)
            }
        }
        val generation = ++calculationGeneration
        calculationJob = viewModelScope.launch {
            _state.update {
                derive(it.copy(journey = null, isCalculating = true, progressMessage = "Preparing your journey…", error = null))
            }
            try {
                val expectedLegs = current.stops.size + 1 + if (current.roundTrip) 1 else 0
                val resultJson = withTimeout(ONE_WAY_TIMEOUT_MS * expectedLegs.coerceIn(1, 10)) {
                    withContext(ioDispatcher) {
                        api.calculateJourney(origin.id, destination.id, requestVehicle, current.roundTrip, timeAllowance,
                            current.stops.map { requireNotNull(it.address.selected).id }) { message ->
                            if (generation == calculationGeneration) _state.update { it.copy(progressMessage = message) }
                        }
                    }
                }
                val journey = withContext(ioDispatcher) { EcoJson.parseJourney(resultJson) }
                validateItinerary(journey, origin, current.stops.map { requireNotNull(it.address.selected) }, destination, current.roundTrip)
                validateTimeAllowance(journey, timeAllowance)
                if (generation != calculationGeneration) return@launch
                val selected = journey.legs.map { leg ->
                    leg.recommendations[_state.value.allowanceValue]
                        ?: leg.routes.firstOrNull()?.id.orEmpty()
                }
                _state.update {
                    derive(it.copy(
                        journey = journey, activeLegIndex = 0, selectedRouteIds = selected,
                        isCalculating = false, progressMessage = null, error = null,
                    ))
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                _state.update {
                    derive(it.copy(
                        isCalculating = false, progressMessage = null,
                        error = if (error is kotlinx.coroutines.TimeoutCancellationException) "This calculation took too long and was stopped. Check your internet connection or try a shorter journey." else error.message ?: "The calculation could not finish. Please try again.",
                    ))
                }
            }
        }
    }

    fun selectLeg(index: Int) {
        val journey = _state.value.journey ?: return
        if (index !in journey.legs.indices) return
        _state.update { derive(it.copy(activeLegIndex = index)) }
    }

    fun selectRoute(id: String) {
        val current = _state.value
        val leg = current.journey?.legs?.getOrNull(current.activeLegIndex) ?: return
        if (leg.routes.none { it.id == id }) return
        val selections = current.selectedRouteIds.toMutableList()
        while (selections.size < legCount(current)) selections += ""
        selections[current.activeLegIndex] = id
        _state.update { derive(it.copy(selectedRouteIds = selections)) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun refreshFuelPrice() {
        viewModelScope.launch {
            try {
                val price = withContext(ioDispatcher) { api.fuelPrice() }
                _state.update { derive(it.copy(fuelPrice = price)) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update { current ->
                    derive(current.copy(fuelPrice = current.fuelPrice?.copy(refreshUnavailable = true)))
                }
            }
        }
    }

    fun exportCsv(): String? {
        val current = _state.value
        val journey = current.journey ?: return null
        val rows = mutableListOf<List<Any>>(
            listOf("row_type", "section", "from", "to", "start_km", "end_km", "road", "mapped_limit_kmh", "entry_kmh", "exit_kmh", "elevation_start_m", "elevation_end_m", "minutes", "liters", "distance_km"),
        )
        journey.legs.forEachIndexed { legIndex, leg ->
            val route = leg.routes.find { it.id == current.selectedRouteIds.getOrNull(legIndex) }
                ?: leg.routes.firstOrNull() ?: return null
            val plan = route.plans[current.allowanceValue] ?: route.baseline
            val from = leg.places.firstOrNull()?.label.orEmpty()
            val to = leg.places.lastOrNull()?.label.orEmpty()
            rows += listOf("section_total", legIndex + 1, from, to, "", "", route.label, "", "", "", "", "", plan.minutes, plan.liters, route.distanceKm)
            route.segments.forEachIndexed { index, segment ->
                rows += listOf(
                    "segment", legIndex + 1, from, to, segment.startM / 1000.0, segment.endM / 1000.0, segment.name,
                    segment.limitKmh ?: "", plan.speedKmh.getOrNull(index) ?: "",
                    plan.speedKmh.getOrNull(index + 1) ?: "", segment.heightStartM, segment.heightEndM,
                    "", "", "",
                )
            }
        }
        current.totals?.let { rows += listOf("journey_total", "", "", "", "", "", "", "", "", "", "", "", it.minutes, it.liters, it.distanceKm) }
        return rows.joinToString("\r\n") { row -> row.joinToString(",") { csvCell(it) } }
    }

    fun exportFileName(): String = "eco-route-journey-speed-plan.csv"

    private suspend fun search(index: Int, query: String, generation: Long) {
        try {
            val places = withTimeout(ADDRESS_TIMEOUT_MS) { withContext(ioDispatcher) { api.searchPlaces(query) } }
            if (addressSearchGeneration[index] != generation || address(index).text.trim() != query) return
            updateAddress(index) {
                it.copy(
                    suggestions = places, isSearching = false,
                    error = if (places.isEmpty()) "No matching places found. Add a town, postcode, or country." else null,
                )
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            if (addressSearchGeneration[index] != generation || address(index).text.trim() != query) return
            val message = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                "Address search took too long. Check your internet connection, then retry."
            } else error.message ?: "Address search unavailable. Please retry."
            updateAddress(index) { it.copy(suggestions = emptyList(), isSearching = false, error = message) }
        }
    }

    private suspend fun searchVehicles(query: String, offset: Int = 0) {
        try {
            val filter = _state.value.vehicleCatalogueSearch.fuelFilter
            val result = withTimeout(VEHICLE_SEARCH_TIMEOUT_MS) {
                withContext(ioDispatcher) { api.searchVehicles(query, filter, offset = offset) }
            }
            val current = _state.value.vehicleCatalogueSearch
            if (current.query.trim() != query || current.fuelFilter != filter) return
            _state.update { it.copy(vehicleCatalogueSearch = current.copy(
                results = if(offset == 0) result.vehicles else current.results + result.vehicles, isSearching = false,
                error = if (result.vehicles.isEmpty()) "No matching 2024 registrations found. Try make and model, or enter details manually." else null,
                totalMatches = result.totalMatches, truncated = result.truncated,
                coverageMessage = result.coverageMessage,
                nextOffset = result.nextOffset,
            )) }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            if (_state.value.vehicleCatalogueSearch.query.trim() != query) return
            val message = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                "Vehicle search took too long. Check your internet connection, then retry."
            } else error.message ?: "Vehicle catalogue unavailable. Enter details manually or retry."
            _state.update { it.copy(vehicleCatalogueSearch = it.vehicleCatalogueSearch.copy(
                results = emptyList(), isSearching = false, error = message,
            )) }
        }
    }

    private fun derive(source: UiState): UiState {
        val journey = source.journey
        val legIndex = source.activeLegIndex.coerceIn(0, maxOf(0, (journey?.legs?.size ?: 1) - 1))
        val leg = journey?.legs?.getOrNull(legIndex)
        val routeId = source.selectedRouteIds.getOrNull(legIndex)
        val route = leg?.routes?.find { it.id == routeId } ?: leg?.routes?.firstOrNull()
        val plan = route?.plans?.get(source.allowanceValue) ?: route?.baseline
        val totals = journey?.let {
            calculateTripTotals(
                journey = it, selectedRouteIds = source.selectedRouteIds,
                allowanceValue = source.allowanceValue, fuelPrice = source.fuelPrice,
                fuelType = source.vehicle.fuelType,
            )
        }
        return source.copy(
            activeLegIndex = legIndex, selectedLeg = leg, selectedRoute = route, selectedPlan = plan,
            totals = totals, fullRouteJson = journey?.fullRouteJson,
        )
    }

    private fun updateAddress(index: Int, transform: (AddressState) -> AddressState) {
        _state.update { current ->
            if (index == 0) current.copy(origin = transform(current.origin))
            else current.copy(destination = transform(current.destination))
        }
    }

    private fun updateStop(id: Long, transform: (AddressState) -> AddressState) {
        _state.update { current ->
            current.copy(stops = current.stops.map { stop ->
                if (stop.id == id) stop.copy(address = transform(stop.address)) else stop
            })
        }
    }

    private suspend fun searchStop(id: Long, query: String, generation: Long) {
        try {
            val places = withTimeout(ADDRESS_TIMEOUT_MS) { withContext(ioDispatcher) { api.searchPlaces(query) } }
            val stop = _state.value.stops.find { it.id == id } ?: return
            if (stopSearchGeneration[id] != generation || stop.address.text.trim() != query) return
            updateStop(id) {
                it.copy(
                    suggestions = places, isSearching = false,
                    error = if (places.isEmpty()) "No matching places found. Add a town, postcode, or country." else null,
                )
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            val stop = _state.value.stops.find { it.id == id } ?: return
            if (stopSearchGeneration[id] != generation || stop.address.text.trim() != query) return
            val message = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                "Address search took too long. Check your internet connection, then retry."
            } else error.message ?: "Address search unavailable. Please retry."
            updateStop(id) { it.copy(suggestions = emptyList(), isSearching = false, error = message) }
        }
    }

    private fun address(index: Int): AddressState = if (index == 0) _state.value.origin else _state.value.destination

    private fun clearJourney() {
        _state.update { derive(it.copy(journey = null, selectedRouteIds = emptyList(), activeLegIndex = 0, error = null)) }
    }

    private fun invalidateCalculation() {
        calculationGeneration++
        calculationJob?.cancel()
        calculationJob = null
        _state.update { it.copy(isCalculating = false, progressMessage = null) }
    }

    private fun legCount(state: UiState) = state.journey?.legs?.size ?: 0

    private fun AddressState.settled() = copy(suggestions = emptyList(), isSearching = false, error = null)

    private fun validateTimeAllowance(journey: Journey, request: TimeAllowanceRequest) {
        if (!journey.matchesTimeAllowance(request)) throw EcoApiException(
            "The calculation did not match this time allowance. Please calculate again.",
        )
    }

    private fun validateItinerary(
        journey: Journey,
        origin: Place,
        stops: List<Place>,
        destination: Place,
        roundTrip: Boolean,
    ) {
        if (stops.isEmpty()) return
        val expectedPlaces = buildList {
            add(origin)
            addAll(stops)
            add(destination)
            if (roundTrip) add(origin)
        }
        val valid = journey.tripType == "multi_stop" && journey.roundTrip == roundTrip &&
            journey.places.map { it.id } == expectedPlaces.map { it.id } &&
            journey.legs.size == expectedPlaces.size - 1 &&
            journey.legs.withIndex().all { (index, leg) ->
                leg.places.map { it.id } == listOf(expectedPlaces[index].id, expectedPlaces[index + 1].id)
            }
        if (!valid) throw EcoApiException(
            "The calculation returned a different stop order. Please calculate again.",
        )
    }

    private fun mirrorLegacyProfile(profile: VehicleProfile) {
        val vehicle = profile.vehicle
        preferences.edit()
            .putString(VehicleKeys.NAME, vehicle.name)
            .putString(VehicleKeys.FUEL_TYPE, vehicle.fuelType)
            .putString(VehicleKeys.MASS_KG, vehicle.massKg)
            .putString(VehicleKeys.DRAG_AREA_M2, vehicle.dragAreaM2)
            .putString(VehicleKeys.EFFICIENCY_PERCENT, vehicle.efficiencyPercent)
            .putString(VehicleKeys.ROLLING_RESISTANCE, vehicle.rollingResistance)
            .putString(VehicleKeys.AUXILIARY_KW, vehicle.auxiliaryKw)
            .putString(VehicleKeys.ENGINE_OVERHEAD_L_H, vehicle.engineOverheadLh)
            .putString(VEHICLE_CATALOGUE_JSON, profile.catalogue?.toJson()?.toString())
            .putString(VEHICLE_BODY_STYLE, profile.bodyStyle?.wireValue)
            .putString(VEHICLE_ASSUMPTION_ORIGIN, profile.assumptionOrigin.wireValue)
            .putBoolean(VEHICLE_SETUP_COMPLETE, true)
            .apply()
    }

    private fun loadVehicle() = VehicleFields(
        name = preferences.getString(VehicleKeys.NAME, VehicleFields.DEFAULT.name) ?: VehicleFields.DEFAULT.name,
        fuelType = preferences.getString(VehicleKeys.FUEL_TYPE, VehicleFields.DEFAULT.fuelType) ?: VehicleFields.DEFAULT.fuelType,
        massKg = preferences.getString(VehicleKeys.MASS_KG, VehicleFields.DEFAULT.massKg) ?: VehicleFields.DEFAULT.massKg,
        dragAreaM2 = preferences.getString(VehicleKeys.DRAG_AREA_M2, VehicleFields.DEFAULT.dragAreaM2) ?: VehicleFields.DEFAULT.dragAreaM2,
        efficiencyPercent = preferences.getString(VehicleKeys.EFFICIENCY_PERCENT, VehicleFields.DEFAULT.efficiencyPercent) ?: VehicleFields.DEFAULT.efficiencyPercent,
        rollingResistance = preferences.getString(VehicleKeys.ROLLING_RESISTANCE, VehicleFields.DEFAULT.rollingResistance) ?: VehicleFields.DEFAULT.rollingResistance,
        auxiliaryKw = preferences.getString(VehicleKeys.AUXILIARY_KW, VehicleFields.DEFAULT.auxiliaryKw) ?: VehicleFields.DEFAULT.auxiliaryKw,
        engineOverheadLh = preferences.getString(VehicleKeys.ENGINE_OVERHEAD_L_H, VehicleFields.DEFAULT.engineOverheadLh) ?: VehicleFields.DEFAULT.engineOverheadLh,
    )

    private fun loadCatalogueVehicle(): CatalogueVehicle? = preferences.getString(VEHICLE_CATALOGUE_JSON, null)
        ?.let { json -> runCatching { EcoJson.parseCatalogueVehicle(JSONObject(json)) }.getOrNull() }

    private fun migrateVersionFourLoad(): Boolean {
        if (preferences.getBoolean(LOAD_MIGRATION_COMPLETE, false)) return true
        val catalogue = preferences.getString(VEHICLE_CATALOGUE_JSON, null)
            ?.let { json -> runCatching { EcoJson.parseCatalogueVehicle(JSONObject(json)) }.getOrNull() }
        val oldLoad = preferences.getString(VEHICLE_ADDITIONAL_LOAD_KG_V4, null)
            ?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -75.0..1500.0 }
        val savedMass = preferences.getString(VehicleKeys.MASS_KG, null)?.toDoubleOrNull()
        val expectedMass = if (catalogue?.massKg != null && oldLoad != null) catalogue.massKg + oldLoad else null
        val canSplit = savedMass != null && expectedMass != null && kotlin.math.abs(savedMass - expectedMass) < 0.01
        val editor = preferences.edit().putBoolean(LOAD_MIGRATION_COMPLETE, true)
        if (canSplit) {
            editor.putString(VehicleKeys.MASS_KG, formatVehicleNumber(requireNotNull(catalogue?.massKg)))
                .putString(TRIP_LOAD_KG, formatVehicleNumber(requireNotNull(oldLoad)))
        } else if (!preferences.contains(TRIP_LOAD_KG)) {
            editor.putString(TRIP_LOAD_KG, "0")
        }
        editor.remove(VEHICLE_ADDITIONAL_LOAD_KG_V4).commit()
        return true
    }

    private fun tripLoadError(value: String, baseMassText: String): String? {
        val load = value.trim().toDoubleOrNull()
        return if (load == null || !load.isFinite() || load !in -75.0..1500.0) {
            "Passengers, luggage and driver adjustment must be between -75 and 1500 kg."
        } else {
            val baseMass = baseMassText.trim().toDoubleOrNull()
            if (baseMass != null && baseMass + load !in 600.0..6000.0) {
                "Vehicle mass plus this trip's load must be between 600 and 6000 kg."
            } else null
        }
    }

    private fun formatVehicleNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    private fun csvCell(value: Any): String {
        var text = value.toString()
        if (text.firstOrNull() in listOf('=', '+', '@', '-')) text = "'$text"
        return "\"${text.replace("\"", "\"\"")}\""
    }

    companion object {
        private const val PREFERENCES = "eco_route_vehicle"
        private const val VEHICLE_SETUP_COMPLETE = "vehicle_setup_complete_v1"
        private const val VEHICLE_CATALOGUE_JSON = "vehicle_catalogue_json_v1"
        private const val VEHICLE_BODY_STYLE = "vehicle_body_style_v1"
        private const val VEHICLE_ASSUMPTION_ORIGIN = "vehicle_assumption_origin_v1"
        private const val VEHICLE_ADDITIONAL_LOAD_KG_V4 = "vehicle_additional_load_kg_v1"
        private const val TRIP_LOAD_KG = "trip_load_kg_v1"
        private const val LOAD_MIGRATION_COMPLETE = "vehicle_load_migrated_v5"
        private const val VEHICLE_SEARCH_DEBOUNCE_MS = 450L
        private const val VEHICLE_SEARCH_TIMEOUT_MS = 12_000L
        private const val ADDRESS_DEBOUNCE_MS = 500L
        private const val ADDRESS_TIMEOUT_MS = 9_000L
        private const val POLL_INTERVAL_MS = 1_200L
        private const val ONE_WAY_TIMEOUT_MS = 8 * 60_000L
        private const val ROUND_TRIP_TIMEOUT_MS = 16 * 60_000L
        const val MAX_INTERMEDIATE_STOPS = 8
    }
}
