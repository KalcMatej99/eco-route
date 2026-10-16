package org.kalc.ecoroute

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Selected addresses only; unconfirmed queries and live GPS fixes are not history. */
internal object RecentPlaces {
    const val KEY = "recent_places_v1"
    const val MAX_SAVED = 20

    fun usable(place: Place) = place.id.isNotBlank() && place.title.isNotBlank() && place.label.isNotBlank() &&
        place.lat.isFinite() && place.lat in -90.0..90.0 && place.lon.isFinite() && place.lon in -180.0..180.0 &&
        place.kind != "current_location"

    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun same(a: Place, b: Place) = a.id == b.id ||
        (normalized(a.label) == normalized(b.label) &&
            RoadLoadModel.distance(GeoPoint(a.lat,a.lon),GeoPoint(b.lat,b.lon)) < 50)

    fun remember(history: List<Place>, place: Place): List<Place> =
        if (!usable(place)) history else (listOf(place) + history.filterNot { same(it,place) }).take(MAX_SAVED)

    fun read(value: String?): List<Place> {
        val entries = runCatching { JSONArray(value ?: "[]") }.getOrNull() ?: return emptyList()
        val result = mutableListOf<Place>()
        for (i in 0 until entries.length().coerceAtMost(MAX_SAVED)) {
            val place = runCatching {
                val p = entries.getJSONObject(i)
                Place(p.getString("id"),p.getDouble("lat"),p.getDouble("lon"),p.getString("label"),
                    p.getString("title"),p.optString("detail"),p.optString("country_code"),p.optString("kind"))
            }.getOrNull() ?: continue
            if (usable(place) && result.none { same(it,place) }) result.add(place)
        }
        return result
    }

    fun encode(history: List<Place>) = JSONArray(history.filter(::usable).take(MAX_SAVED).map { p ->
        JSONObject().put("id",p.id).put("lat",p.lat).put("lon",p.lon).put("label",p.label)
            .put("title",p.title).put("detail",p.detail).put("country_code",p.countryCode).put("kind",p.kind)
    }).toString()

    fun matching(history: List<Place>, query: String): List<Place> {
        val tokens = normalized(query).split(' ').filter(String::isNotBlank)
        return history.filter { p ->
            val words = normalized("${p.title} ${p.label} ${p.detail}").split(' ')
            tokens.all { token -> words.any { it.startsWith(token) } }
        }
    }

    data class Suggestions(val recent: List<Place>, val online: List<Place>)
    fun suggestions(history: List<Place>, address: AddressState, showWhenSelected: Boolean = false): Suggestions {
        if (address.selected != null && !showWhenSelected) return Suggestions(emptyList(),emptyList())
        val query = if (address.selected != null) "" else address.text
        val matches = matching(history,query)
        val recent = matches.take(if (query.isBlank()) 5 else 3)
        val online = if (address.selected != null) emptyList() else
            address.suggestions.filterNot { p -> recent.any { same(it,p) } }.take(5)
        return Suggestions(recent,online)
    }
}
