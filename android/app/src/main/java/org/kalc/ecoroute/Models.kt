package org.kalc.ecoroute

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

data class Place(
    val id: String,
    val lat: Double,
    val lon: Double,
    val label: String,
    val title: String,
    val detail: String,
    val countryCode: String,
    val kind: String,
)

data class AddressState(
    val text: String = "",
    val selected: Place? = null,
    val suggestions: List<Place> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
)

data class IntermediateStop(
    val id: Long,
    val address: AddressState = AddressState(),
)

data class VehicleFields(
    val name: String = "Volvo XC90 B5 AWD",
    val fuelType: String = "diesel",
    val massKg: String = "2250",
    val dragAreaM2: String = "0.90",
    val efficiencyPercent: String = "34",
    val rollingResistance: String = "0.011",
    val auxiliaryKw: String = "0.8",
    val engineOverheadLh: String = "0.45",
) {
    companion object { val DEFAULT = VehicleFields() }
}

data class VehicleProfile(
    val id: String,
    val vehicle: VehicleFields,
    val catalogue: CatalogueVehicle? = null,
    val bodyStyle: VehicleBodyStyle? = null,
    val assumptionOrigin: VehicleAssumptionOrigin = VehicleAssumptionOrigin.MANUAL,
) {
    val name: String get() = vehicle.name

    fun validate(): VehicleValidation = vehicle.validate()
}

enum class VehicleBodyStyle(val wireValue: String, val label: String) {
    SMALL_CAR("small_car", "Small car"),
    SALOON("saloon", "Saloon / estate"),
    SUV("suv", "SUV / crossover"),
    LARGE_SUV("large_suv", "Large SUV / van");

    companion object { fun fromWire(value: String?) = entries.firstOrNull { it.wireValue == value } }
}

enum class VehicleAssumptionOrigin(val wireValue: String) {
    PILOT_PRESET("pilot_preset"), NEEDS_ESTIMATE("needs_estimate"), ESTIMATED("estimated"), MANUAL("manual");

    companion object { fun fromWire(value: String?) = entries.firstOrNull { it.wireValue == value } ?: MANUAL }
}

data class CatalogueSource(val name: String, val url: String, val license: String)

data class CatalogueVehicle(
    val id: String,
    val make: String,
    val model: String,
    val variant: String,
    val displayName: String,
    val fuelType: String,
    val fuelMode: String,
    val registrationYear: Int,
    val massKg: Double?,
    val massBasis: String,
    val powerKw: Double?,
    val engineCc: Double?,
    val wltpLPer100Km: Double?,
    val wltpMaxLPer100Km: Double?,
    val typeCode: String?,
    val variantCode: String?,
    val versionCode: String?,
    val supportedForModel: Boolean,
    val unsupportedReason: String?,
    val source: CatalogueSource,
    val bodyStyle: VehicleBodyStyle? = null,
    val bodyStyleSource: CatalogueSource? = null,
) {
    val isSupported: Boolean get() = supportedForModel && massKg != null && fuelMode in setOf("M", "H")

    fun toJson() = JSONObject()
        .put("id", id).put("make", make).put("model", model).put("variant", variant)
        .put("display_name", displayName).put("fuel_type", fuelType).put("fuel_mode", fuelMode)
        .put("registration_year", registrationYear).putNullable("mass_kg", massKg)
        .put("mass_basis", massBasis)
        .putNullable("power_kw", powerKw).putNullable("engine_cc", engineCc)
        .putNullable("wltp_l_per_100km", wltpLPer100Km)
        .putNullable("wltp_max_l_per_100km", wltpMaxLPer100Km)
        .putNullable("type", typeCode).putNullable("variant_code", variantCode)
        .putNullable("version_code", versionCode)
        .put("supported_for_model", supportedForModel).putNullable("unsupported_reason", unsupportedReason)
        .put("source", JSONObject().put("name", source.name).put("url", source.url).put("license", source.license))
        .putNullable("body_style", bodyStyle?.wireValue)
        .putNullable("body_style_source", bodyStyleSource?.let {
            JSONObject().put("name", it.name).put("url", it.url).put("license", it.license)
        })
}

data class VehicleCatalogueState(
    val query: String = "",
    val fuelFilter: String? = null,
    val results: List<CatalogueVehicle> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    val totalMatches: Int = 0,
    val truncated: Boolean = false,
    val coverageMessage: String = "",
    val nextOffset: Int? = null,
)

data class VehicleCatalogueSearchResult(
    val vehicles: List<CatalogueVehicle>,
    val totalMatches: Int,
    val truncated: Boolean,
    val coverageMessage: String,
    val nextOffset: Int?,
)

fun VehicleBodyStyle.estimatedFields(fuelType: String, massKg: String, name: String): VehicleFields {
    val diesel = fuelType == "diesel"
    val values = when (this) {
        VehicleBodyStyle.SMALL_CAR -> listOf("0.60", "0.010", "0.6", if (diesel) "0.35" else "0.45")
        VehicleBodyStyle.SALOON -> listOf("0.70", "0.010", "0.7", if (diesel) "0.40" else "0.50")
        VehicleBodyStyle.SUV -> listOf("0.85", "0.011", "0.8", if (diesel) "0.45" else "0.60")
        VehicleBodyStyle.LARGE_SUV -> listOf("0.95", "0.012", "0.9", if (diesel) "0.50" else "0.65")
    }
    return VehicleFields(
        name = name, fuelType = fuelType, massKg = massKg,
        dragAreaM2 = values[0], efficiencyPercent = if (diesel) "34" else "28",
        rollingResistance = values[1], auxiliaryKw = values[2], engineOverheadLh = values[3],
    )
}

private fun JSONObject.putNullable(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)

object VehicleKeys {
    const val NAME = "name"
    const val FUEL_TYPE = "fuel_type"
    const val MASS_KG = "mass_kg"
    const val DRAG_AREA_M2 = "drag_area_m2"
    const val EFFICIENCY_PERCENT = "efficiency_percent"
    const val ROLLING_RESISTANCE = "rolling_resistance"
    const val AUXILIARY_KW = "auxiliary_kw"
    const val ENGINE_OVERHEAD_L_H = "engine_overhead_l_h"
    val ALL = listOf(NAME, FUEL_TYPE, MASS_KG, DRAG_AREA_M2, EFFICIENCY_PERCENT, ROLLING_RESISTANCE, AUXILIARY_KW, ENGINE_OVERHEAD_L_H)
}

data class VehicleValidation(val payload: JSONObject? = null, val error: String? = null)

fun VehicleFields.validate(): VehicleValidation {
    if (name.trim().isEmpty() || name.trim().length > 80) {
        return VehicleValidation(error = "Enter a vehicle name (up to 80 characters).")
    }
    if (fuelType !in setOf("diesel", "petrol")) {
        return VehicleValidation(error = "Choose diesel or petrol.")
    }
    fun number(label: String, text: String, low: Double, high: Double): Double? {
        val value = text.trim().toDoubleOrNull()
        return value?.takeIf { it.isFinite() && it in low..high }
    }
    val mass = number("mass", massKg, 600.0, 6000.0)
        ?: return VehicleValidation(error = "Vehicle mass must be between 600 and 6000 kg.")
    val drag = number("drag area", dragAreaM2, .3, 2.5)
        ?: return VehicleValidation(error = "Drag area must be between 0.3 and 2.5 m².")
    val efficiency = number("efficiency", efficiencyPercent, 15.0, 45.0)
        ?: return VehicleValidation(error = "Efficiency must be between 15% and 45%.")
    val rolling = number("rolling resistance", rollingResistance, .005, .03)
        ?: return VehicleValidation(error = "Rolling resistance must be between 0.005 and 0.03.")
    val auxiliary = number("auxiliary power", auxiliaryKw, 0.0, 5.0)
        ?: return VehicleValidation(error = "Auxiliary power must be between 0 and 5 kW.")
    val overhead = number("engine overhead", engineOverheadLh, 0.0, 3.0)
        ?: return VehicleValidation(error = "Engine overhead must be between 0 and 3 L/h.")
    return VehicleValidation(
        payload = JSONObject()
            .put("name", name.trim())
            .put("fuel_type", fuelType)
            .put("mass_kg", mass)
            .put("drag_area_m2", drag)
            .put("tank_to_wheel_efficiency", efficiency / 100.0)
            .put("rolling_resistance", rolling)
            .put("auxiliary_kw", auxiliary)
            .put("engine_overhead_l_h", overhead),
    )
}

data class GeoPoint(val lat: Double, val lon: Double)

data class Plan(
    val minutes: Double,
    val liters: Double,
    val sensitivityLiters: List<Double>,
    val speedKmh: List<Double>,
    val rawJson: String,
)

data class Segment(
    val startM: Double,
    val endM: Double,
    val name: String,
    val roadClass: String,
    val limitKmh: Double?,
    val heightStartM: Double,
    val heightEndM: Double,
    val rawJson: String,
)

data class RouteReport(
    val id: String,
    val label: String,
    val distanceKm: Double,
    val providerMinutes: Double,
    val knownSpeedLimitPct: Double,
    val ascentM: Double,
    val descentM: Double,
    val coordinates: List<GeoPoint>,
    val baseline: Plan,
    val plans: Map<Int, Plan>,
    val segments: List<Segment>,
    val rawJson: String,
)

data class ReportVehicle(val name: String, val fuelType: String, val massKg: Double)

enum class TimeAllowanceMode(val wireValue: String) {
    PERCENT("percent"), MINUTES("minutes");

    companion object {
        fun fromWire(value: String) = requireNotNull(entries.firstOrNull { it.wireValue == value }) {
            "Unknown time allowance mode."
        }
    }
}

data class TimeAllowanceOption(val value: Int, val extraMinutes: Double, val maxMinutes: Double)

data class TimeAllowance(val mode: TimeAllowanceMode, val options: List<TimeAllowanceOption>) {
    fun option(value: Int) = options.firstOrNull { it.value == value }
}

data class TimeAllowanceRequest(val mode: TimeAllowanceMode, val minutes: Int? = null) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode.wireValue).apply {
        if (mode == TimeAllowanceMode.MINUTES) {
            val customMinutes = requireNotNull(minutes) { "Custom minutes are required." }
            require(customMinutes in 1..120) { "Custom minutes must be between 1 and 120." }
            put("minutes", customMinutes)
        }
    }
}

data class LegReport(
    val generatedAt: String,
    val places: List<Place>,
    val routes: List<RouteReport>,
    val recommendations: Map<Int, String>,
    val fastestMinutes: Double,
    val warnings: List<String>,
    val vehicle: ReportVehicle?,
    val timeAllowance: TimeAllowance,
    val rawJson: String,
)

data class Journey(
    val tripType: String,
    val legs: List<LegReport>,
    val fullRouteJson: String,
    val places: List<Place> = emptyList(),
    val roundTrip: Boolean = tripType == "round_trip",
)

fun Journey.matchesTimeAllowance(request: TimeAllowanceRequest): Boolean {
    val expected = if (request.mode == TimeAllowanceMode.PERCENT) setOf(0, 5, 10, 15)
    else setOf(0, requireNotNull(request.minutes))
    return legs.all { leg ->
        val options = leg.timeAllowance.options
        leg.timeAllowance.mode == request.mode &&
            options.size == expected.size && options.map { it.value }.toSet() == expected &&
            leg.recommendations.keys == expected && options.all { option ->
                val expectedExtra = if (request.mode == TimeAllowanceMode.PERCENT) {
                    leg.fastestMinutes * option.value / 100.0
                } else option.value.toDouble()
                option.extraMinutes.isFinite() && option.maxMinutes.isFinite() &&
                    abs(option.extraMinutes - expectedExtra) <= ALLOWANCE_TOLERANCE_MINUTES &&
                    abs(option.maxMinutes - leg.fastestMinutes - expectedExtra) <= ALLOWANCE_TOLERANCE_MINUTES
            }
    }
}

private const val ALLOWANCE_TOLERANCE_MINUTES = 1e-5

data class FuelPrice(
    val currency: String,
    val prices: Map<String, Double>,
    val validFrom: LocalDate?,
    val validUntil: LocalDate?,
    val checkedAt: String,
    val sourceName: String,
    val sourceUrl: String,
    val countryCode: String = "SI",
    val refreshUnavailable: Boolean = false,
) {
    fun currentPrice(fuelType: String, today: LocalDate = LocalDate.now(ZoneId.of("Europe/Ljubljana"))): Double? {
        val price = prices[fuelType]
        return price?.takeIf {
            currency == "EUR" && it.isFinite() && it > 0.0 &&
                validFrom != null && validUntil != null && today in validFrom..validUntil
        }
    }
}

data class TripTotals(
    val liters: Double,
    val minutes: Double,
    val distanceKm: Double,
    val consumptionLPer100Km: Double,
    val eligible: Boolean,
    val fuelCostEur: Double?,
    val addedMinutes: Double,
    val baselineLiters: Double,
    val litersSaved: Double,
    val fuelCostSavedEur: Double?,
)

fun calculateTripTotals(
    journey: Journey,
    selectedRouteIds: List<String>,
    allowanceValue: Int,
    fuelPrice: FuelPrice?,
    fuelType: String,
): TripTotals? {
    var liters = 0.0
    var minutes = 0.0
    var distance = 0.0
    var fastestMinutes = 0.0
    var baselineLiters = 0.0
    var eligible = true
    journey.legs.forEachIndexed { index, leg ->
        val route = leg.routes.find { it.id == selectedRouteIds.getOrNull(index) }
            ?: leg.routes.firstOrNull() ?: return null
        val selectedPlan = route.plans[allowanceValue]
        eligible = eligible && selectedPlan != null
        val plan = selectedPlan ?: route.baseline
        liters += plan.liters
        minutes += plan.minutes
        distance += route.distanceKm
        fastestMinutes += leg.fastestMinutes
        baselineLiters += leg.routes.minByOrNull { it.baseline.minutes }?.baseline?.liters ?: route.baseline.liters
    }
    val price = fuelPrice?.currentPrice(fuelType)
    return TripTotals(
        liters = liters,
        minutes = minutes,
        distanceKm = distance,
        consumptionLPer100Km = if (distance > 0.0) liters / distance * 100.0 else 0.0,
        eligible = eligible,
        fuelCostEur = price?.times(liters),
        addedMinutes = (minutes - fastestMinutes).coerceAtLeast(0.0),
        baselineLiters = baselineLiters,
        litersSaved = baselineLiters - liters,
        fuelCostSavedEur = price?.times(baselineLiters - liters),
    )
}

data class UiState(
    val origin: AddressState = AddressState(),
    val destination: AddressState = AddressState(),
    val stops: List<IntermediateStop> = emptyList(),
    val vehicle: VehicleFields = VehicleFields.DEFAULT,
    val vehicleDraft: VehicleFields = vehicle,
    val vehicleCatalogue: CatalogueVehicle? = null,
    val vehicleDraftCatalogue: CatalogueVehicle? = vehicleCatalogue,
    val vehicleBodyStyle: VehicleBodyStyle? = null,
    val vehicleDraftBodyStyle: VehicleBodyStyle? = vehicleBodyStyle,
    val vehicleAssumptionOrigin: VehicleAssumptionOrigin = VehicleAssumptionOrigin.PILOT_PRESET,
    val vehicleDraftAssumptionOrigin: VehicleAssumptionOrigin = vehicleAssumptionOrigin,
    val vehicleProfiles: List<VehicleProfile> = emptyList(),
    val activeVehicleProfileId: String = "",
    val editingVehicleProfileId: String? = null,
    val vehicleDraftIsNew: Boolean = false,
    val tripLoadKg: String = "0",
    val tripLoadError: String? = null,
    val vehicleCatalogueSearch: VehicleCatalogueState = VehicleCatalogueState(),
    val vehicleSetupComplete: Boolean = false,
    val vehicleSetupIsLegacyReview: Boolean = false,
    val vehicleDraftError: String? = null,
    val isLocating: Boolean = false,
    val locationSelectionVersion: Long = 0,
    val locationSelectionPending: Boolean = false,
    val locationError: String? = null,
    val locationNotice: String? = null,
    val roundTrip: Boolean = false,
    val allowanceMode: TimeAllowanceMode = TimeAllowanceMode.PERCENT,
    val allowanceValue: Int = 10,
    val percentAllowanceValues: List<Int> = listOf(0, 5, 10, 15),
    val customMinutesText: String = "10",
    val isCalculating: Boolean = false,
    val progressMessage: String? = null,
    val error: String? = null,
    val journey: Journey? = null,
    val activeLegIndex: Int = 0,
    val selectedRouteIds: List<String> = emptyList(),
    val fuelPrice: FuelPrice? = null,
    val selectedLeg: LegReport? = null,
    val selectedRoute: RouteReport? = null,
    val selectedPlan: Plan? = null,
    val totals: TripTotals? = null,
    val fullRouteJson: String? = null,
) {
    val vehicleDraftDirty: Boolean
        get() {
            if (vehicleDraftIsNew) return true
            val edited = vehicleProfiles.firstOrNull { it.id == editingVehicleProfileId }
                ?: vehicleProfiles.firstOrNull { it.id == activeVehicleProfileId }
            return edited == null || vehicleDraft != edited.vehicle || vehicleDraftCatalogue != edited.catalogue ||
                vehicleDraftBodyStyle != edited.bodyStyle || vehicleDraftAssumptionOrigin != edited.assumptionOrigin
        }

    val draftIsNew: Boolean get() = vehicleDraftIsNew

    val activeVehicleProfile: VehicleProfile?
        get() = vehicleProfiles.firstOrNull { it.id == activeVehicleProfileId }

    val selectedPlanEligible: Boolean
        get() = selectedRoute?.plans?.containsKey(allowanceValue) == true

    val selectedAllowanceOption: TimeAllowanceOption?
        get() = selectedLeg?.timeAllowance?.option(allowanceValue)

    val mapPayloadJson: String
        get() {
            val route = selectedRoute
            val plan = selectedPlan
            val hasRoute = route != null && plan != null
            fun placeJson(place: Place?): Any = place?.let {
                JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon)
                    .put("title", it.title).put("label", it.label)
            } ?: JSONObject.NULL
            val alternatives = JSONArray()
            if (hasRoute) selectedLeg?.routes?.filter { it.id != route?.id }?.forEach {
                alternatives.put(JSONObject(it.rawJson))
            }
            val orderedPlaces: List<Place?> = buildList {
                add(origin.selected)
                stops.forEach { add(it.address.selected) }
                add(destination.selected)
            }
            val mapPlaces = JSONArray().apply { orderedPlaces.forEach { put(placeJson(it)) } }
            val journeyRoutes = JSONArray().apply {
                journey?.legs?.forEachIndexed { index, leg ->
                    val selectedId = selectedRouteIds.getOrNull(index)
                    val selected = leg.routes.find { it.id == selectedId } ?: leg.routes.firstOrNull()
                    selected?.let { put(JSONObject(it.rawJson).put("legIndex", index)) }
                }
            }
            return JSONObject()
                .put("mode", if (hasRoute) "route" else if (orderedPlaces.any { it != null }) "places" else "idle")
                .put("origin", placeJson(origin.selected))
                .put("destination", placeJson(destination.selected))
                .put("places", mapPlaces)
                .put("route", if (hasRoute) JSONObject(requireNotNull(route).rawJson) else JSONObject.NULL)
                .put("plan", if (hasRoute) JSONObject(requireNotNull(plan).rawJson) else JSONObject.NULL)
                .put("alternatives", alternatives)
                .put("journeyRoutes", journeyRoutes)
                .put("activeLegIndex", activeLegIndex)
                .put("eligible", hasRoute && selectedPlanEligible)
                .put("returnLeg", journey?.roundTrip == true && activeLegIndex == journey.legs.lastIndex)
                .put("direction", if (journey?.roundTrip == true && activeLegIndex == journey.legs.lastIndex) "return" else "leg-${activeLegIndex + 1}")
                .put("allowanceValue", allowanceValue)
                .put("selectionKey", if (hasRoute) "$activeLegIndex:${route?.id}:${allowanceMode.wireValue}:$allowanceValue:${selectedLeg?.generatedAt}" else "preview")
                .toString()
        }
}

object EcoJson {
    fun parsePlaces(json: String): List<Place> {
        val values = JSONObject(json).getJSONArray("places")
        return (0 until values.length()).map { parsePlace(values.getJSONObject(it)) }
    }

    fun parseCatalogueSearch(json: String): VehicleCatalogueSearchResult {
        val root = JSONObject(json)
        val values = root.getJSONArray("vehicles")
        val vehicles = (0 until values.length()).map { parseCatalogueVehicle(values.getJSONObject(it)) }
        return VehicleCatalogueSearchResult(
            vehicles = vehicles,
            totalMatches = root.optInt("total_matches", vehicles.size),
            truncated = root.optBoolean("truncated", false),
            coverageMessage = root.optString("coverage"),
            nextOffset = if(root.isNull("next_offset")) null else root.optInt("next_offset").takeIf { it >= 0 },
        )
    }

    fun parseCatalogueVehicle(value: JSONObject): CatalogueVehicle {
        val source = value.getJSONObject("source")
        val bodyStyleSource = value.optJSONObject("body_style_source")
        return CatalogueVehicle(
            id = value.getString("id"), make = value.getString("make"), model = value.getString("model"),
            variant = value.optString("variant"),
            displayName = value.optString("display_name").ifBlank { listOf(value.getString("make"), value.getString("model")).joinToString(" ") },
            fuelType = value.getString("fuel_type"), fuelMode = value.optString("fuel_mode", "ICE"),
            registrationYear = value.getInt("registration_year"), massKg = value.optFiniteDouble("mass_kg"),
            massBasis = value.optString("mass_basis", "Mass in running order, including a standard 75 kg driver"),
            powerKw = value.optFiniteDouble("power_kw"), engineCc = value.optFiniteDouble("engine_cc"),
            wltpLPer100Km = value.optFiniteDouble("wltp_l_per_100km"),
            wltpMaxLPer100Km = value.optFiniteDouble("wltp_max_l_per_100km"),
            typeCode = value.optNullableString("type"), variantCode = value.optNullableString("variant"),
            versionCode = null,
            supportedForModel = value.optBoolean("supported_for_model", false),
            unsupportedReason = value.optNullableString("unsupported_reason"),
            source = CatalogueSource(source.getString("name"), source.getString("url"), source.getString("license")),
            bodyStyle = VehicleBodyStyle.fromWire(value.optNullableString("body_style")),
            bodyStyleSource = bodyStyleSource?.let {
                CatalogueSource(it.optString("name"), it.optString("url"), it.optString("license"))
            },
        )
    }

    fun parseJourney(json: String): Journey {
        val root = JSONObject(json)
        val tripType = root.optString("trip_type", "one_way")
        val multiStop = tripType == "multi_stop"
        val roundTrip = if (multiStop) root.optBoolean("round_trip", false) else tripType == "round_trip"
        val legs = if (roundTrip || multiStop) {
            val values = root.getJSONArray("legs")
            (0 until values.length()).map { parseLeg(values.getJSONObject(it)) }
        } else {
            listOf(parseLeg(root))
        }
        require(legs.isNotEmpty()) { "The route response did not contain a journey." }
        val placesJson = root.optJSONArray("places")
        val places = if (placesJson == null) {
            legs.mapNotNull { it.places.firstOrNull() } + listOfNotNull(legs.lastOrNull()?.places?.lastOrNull())
        } else (0 until placesJson.length()).map { parsePlace(placesJson.getJSONObject(it)) }
        return Journey(tripType, legs, root.toString(), places, roundTrip)
    }

    fun parseFuelPrice(json: String): FuelPrice {
        val root = JSONObject(json)
        val pricesJson = root.getJSONObject("prices")
        val prices = buildMap {
            for (key in pricesJson.keys()) {
                pricesJson.optFiniteDouble(key)?.let { put(key, it) }
            }
        }
        return FuelPrice(
            currency = root.optString("currency"),
            prices = prices,
            validFrom = root.optDate("valid_from"),
            validUntil = root.optDate("valid_until"),
            checkedAt = root.optString("checked_at"),
            sourceName = root.optString("source_name"),
            sourceUrl = root.optString("source_url"),
        )
    }

    private fun parseLeg(value: JSONObject): LegReport {
        val routeValues = value.getJSONArray("routes")
        val routes = (0 until routeValues.length()).map { parseRoute(routeValues.getJSONObject(it)) }
        require(routes.isNotEmpty()) { "The route response did not contain route options." }
        val recommendationsJson = value.getJSONObject("recommendations")
        val recommendations = buildMap {
            for (key in recommendationsJson.keys()) {
                key.toIntOrNull()?.let { put(it, recommendationsJson.getString(key)) }
            }
        }
        val placesJson = value.optJSONArray("places")
        val places = if (placesJson == null) emptyList() else
            (0 until placesJson.length()).map { parsePlace(placesJson.getJSONObject(it)) }
        val warningValues = value.optJSONArray("warnings")
        val warnings = if (warningValues == null) emptyList() else
            (0 until warningValues.length()).map { warningValues.optString(it) }.filter { it.isNotBlank() }
        val vehicleJson = value.optJSONObject("config")?.optJSONObject("vehicle")
        val vehicle = vehicleJson?.let {
            ReportVehicle(it.optString("name"), it.optString("fuel_type", "diesel"), it.optDouble("mass_kg", 0.0))
        }
        val timeAllowance = parseTimeAllowance(value, recommendations.keys)
        return LegReport(
            generatedAt = value.optString("generated_at"), places = places, routes = routes,
            recommendations = recommendations, fastestMinutes = value.getDouble("fastest_minutes"),
            warnings = warnings, vehicle = vehicle, timeAllowance = timeAllowance, rawJson = value.toString(),
        )
    }

    private fun parseTimeAllowance(value: JSONObject, recommendationKeys: Set<Int>): TimeAllowance {
        val allowance = value.optJSONObject("time_allowance")
        if (allowance == null) {
            val fastest = value.getDouble("fastest_minutes")
            return TimeAllowance(
                TimeAllowanceMode.MINUTES,
                recommendationKeys.sorted().map { TimeAllowanceOption(it, it.toDouble(), fastest + it) },
            )
        }
        val optionsJson = allowance.optJSONArray("options") ?: JSONArray()
        val options = (0 until optionsJson.length()).map { index ->
            val option = optionsJson.getJSONObject(index)
            TimeAllowanceOption(
                value = option.getInt("value"),
                extraMinutes = option.getDouble("extra_minutes"),
                maxMinutes = option.getDouble("max_minutes"),
            )
        }
        return TimeAllowance(TimeAllowanceMode.fromWire(allowance.optString("mode")), options)
    }

    private fun parseRoute(value: JSONObject): RouteReport {
        val coordinatesJson = value.getJSONArray("coordinates")
        val coordinates = (0 until coordinatesJson.length()).mapNotNull { index ->
            val pair = coordinatesJson.optJSONArray(index) ?: return@mapNotNull null
            if (pair.length() < 2) null else GeoPoint(pair.getDouble(0), pair.getDouble(1))
        }
        val plansJson = value.optJSONObject("plans") ?: JSONObject()
        val plans = buildMap {
            for (key in plansJson.keys()) {
                key.toIntOrNull()?.let { slack -> put(slack, parsePlan(plansJson.getJSONObject(key))) }
            }
        }
        val segmentsJson = value.optJSONArray("segments") ?: JSONArray()
        val segments = (0 until segmentsJson.length()).map { parseSegment(segmentsJson.getJSONObject(it)) }
        return RouteReport(
            id = value.getString("id"), label = value.getString("label"),
            distanceKm = value.getDouble("distance_km"),
            providerMinutes = value.optDouble("provider_minutes", 0.0),
            knownSpeedLimitPct = value.optDouble("known_speed_limit_pct", 0.0),
            ascentM = value.optDouble("ascent_m", 0.0), descentM = value.optDouble("descent_m", 0.0),
            coordinates = coordinates, baseline = parsePlan(value.getJSONObject("baseline")),
            plans = plans, segments = segments, rawJson = value.toString(),
        )
    }

    private fun parsePlan(value: JSONObject): Plan = Plan(
        minutes = value.getDouble("minutes"), liters = value.getDouble("liters"),
        sensitivityLiters = value.optJSONArray("sensitivity_liters").doubles(),
        speedKmh = value.optJSONArray("speed_kmh").doubles(), rawJson = value.toString(),
    )

    private fun parseSegment(value: JSONObject) = Segment(
        startM = value.getDouble("start_m"), endM = value.getDouble("end_m"),
        name = value.optString("name"), roadClass = value.optString("road_class"),
        limitKmh = value.optFiniteDouble("limit_kmh"),
        heightStartM = value.optDouble("height_start_m", 0.0),
        heightEndM = value.optDouble("height_end_m", 0.0), rawJson = value.toString(),
    )

    private fun parsePlace(value: JSONObject) = Place(
        id = value.getString("id"), lat = value.getDouble("lat"), lon = value.getDouble("lon"),
        label = value.getString("label"), title = value.optString("title", value.getString("label")),
        detail = value.optString("detail"), countryCode = value.optString("country_code"),
        kind = value.optString("kind", "place"),
    )
}

private fun JSONObject.optFiniteDouble(key: String): Double? {
    if (isNull(key)) return null
    return optDouble(key, Double.NaN).takeIf { it.isFinite() }
}

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optDate(key: String): LocalDate? =
    optString(key).takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun JSONArray?.doubles(): List<Double> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optDouble(it, Double.NaN).takeIf(Double::isFinite) }
