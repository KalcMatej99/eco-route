package org.kalc.ecoroute

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class StoredVehicleProfiles(
    val profiles: List<VehicleProfile>,
    val activeProfileId: String,
)

/** Owns the versioned, local profile document. Legacy single-vehicle keys remain a compatibility mirror. */
internal class VehicleProfileStore(private val preferences: SharedPreferences) {
    fun loadOrMigrate(legacy: VehicleProfile): StoredVehicleProfiles {
        read()?.let { return it }

        val first = legacy.copy(id = UUID.randomUUID().toString())
        val migrated = StoredVehicleProfiles(listOf(first), first.id)
        if (!preferences.getBoolean(MIGRATION_COMPLETE, false)) {
            write(migrated, markMigrationComplete = true)
        } else {
            // Recover a missing or damaged profile document without importing legacy state again.
            val recovered = first.copy(
                vehicle = VehicleFields.DEFAULT,
                catalogue = null,
                bodyStyle = null,
                assumptionOrigin = VehicleAssumptionOrigin.PILOT_PRESET,
            )
            write(StoredVehicleProfiles(listOf(recovered), recovered.id), markMigrationComplete = true)
            return StoredVehicleProfiles(listOf(recovered), recovered.id)
        }
        return migrated
    }

    fun save(profiles: List<VehicleProfile>, activeProfileId: String): Boolean {
        if (profiles.isEmpty() || profiles.any { it.id.isBlank() || it.validate().payload == null } ||
            profiles.map { it.id }.distinct().size != profiles.size || profiles.none { it.id == activeProfileId }
        ) return false
        return write(StoredVehicleProfiles(profiles, activeProfileId), markMigrationComplete = true)
    }

    private fun read(): StoredVehicleProfiles? {
        val raw = preferences.getString(PROFILES_JSON, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("version") != FORMAT_VERSION) return@runCatching null
            val array = root.getJSONArray("profiles")
            val profiles = buildList {
                for (index in 0 until array.length()) add(parseProfile(array.getJSONObject(index)))
            }
            val activeId = root.getString("active_profile_id")
            if (profiles.isEmpty() || profiles.map { it.id }.distinct().size != profiles.size ||
                profiles.none { it.id == activeId }
            ) null else StoredVehicleProfiles(profiles, activeId)
        }.getOrNull()
    }

    private fun write(state: StoredVehicleProfiles, markMigrationComplete: Boolean): Boolean {
        val profiles = JSONArray().apply { state.profiles.forEach { put(it.toJson()) } }
        val root = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("active_profile_id", state.activeProfileId)
            .put("profiles", profiles)
        return preferences.edit()
            .putString(PROFILES_JSON, root.toString())
            .putBoolean(MIGRATION_COMPLETE, markMigrationComplete)
            .commit()
    }

    private fun VehicleProfile.toJson() = JSONObject()
        .put("id", id)
        .put("vehicle", JSONObject()
            .put(VehicleKeys.NAME, vehicle.name)
            .put(VehicleKeys.FUEL_TYPE, vehicle.fuelType)
            .put(VehicleKeys.MASS_KG, vehicle.massKg)
            .put(VehicleKeys.DRAG_AREA_M2, vehicle.dragAreaM2)
            .put(VehicleKeys.EFFICIENCY_PERCENT, vehicle.efficiencyPercent)
            .put(VehicleKeys.ROLLING_RESISTANCE, vehicle.rollingResistance)
            .put(VehicleKeys.AUXILIARY_KW, vehicle.auxiliaryKw)
            .put(VehicleKeys.ENGINE_OVERHEAD_L_H, vehicle.engineOverheadLh))
        .put("catalogue", catalogue?.toJson() ?: JSONObject.NULL)
        .put("body_style", bodyStyle?.wireValue ?: JSONObject.NULL)
        .put("assumption_origin", assumptionOrigin.wireValue)

    private fun parseProfile(json: JSONObject): VehicleProfile {
        val fields = json.getJSONObject("vehicle")
        val catalogue = json.optJSONObject("catalogue")?.let(EcoJson::parseCatalogueVehicle)
        return VehicleProfile(
            id = json.getString("id").also { require(it.isNotBlank()) },
            vehicle = VehicleFields(
                name = fields.getString(VehicleKeys.NAME),
                fuelType = fields.getString(VehicleKeys.FUEL_TYPE),
                massKg = fields.getString(VehicleKeys.MASS_KG),
                dragAreaM2 = fields.getString(VehicleKeys.DRAG_AREA_M2),
                efficiencyPercent = fields.getString(VehicleKeys.EFFICIENCY_PERCENT),
                rollingResistance = fields.getString(VehicleKeys.ROLLING_RESISTANCE),
                auxiliaryKw = fields.getString(VehicleKeys.AUXILIARY_KW),
                engineOverheadLh = fields.getString(VehicleKeys.ENGINE_OVERHEAD_L_H),
            ),
            catalogue = catalogue,
            bodyStyle = VehicleBodyStyle.fromWire(json.optString("body_style").takeIf { it.isNotBlank() }),
            assumptionOrigin = VehicleAssumptionOrigin.fromWire(json.optString("assumption_origin")),
        )
    }

    companion object {
        internal const val PROFILES_JSON = "vehicle_profiles_json_v1"
        internal const val MIGRATION_COMPLETE = "vehicle_profiles_migrated_v1"
        private const val FORMAT_VERSION = 1
    }
}
