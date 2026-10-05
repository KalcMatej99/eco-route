package org.kalc.ecoroute

import org.jsoup.Jsoup
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal object OfficialFuelPrice {
    fun parse(html: String, today: LocalDate = LocalDate.now(ZoneId.of("Europe/Ljubljana"))): FuelPrice {
        var dieselColumn = -1; var petrolColumn = -1
        val matches = mutableListOf<JSONObject>()
        val pattern = Regex("od (\\d+)\\.\\s*(\\d+)\\.\\s*(?:(\\d{4})\\s*)?do (\\d+)\\.\\s*(\\d+)\\.\\s*(\\d{4})")
        for (row in Jsoup.parse(html).select("tr")) {
            val cells = row.select("td,th").map { it.text().replace(Regex("[\\s\\u00a0]+"), " ").trim() }
            if (cells.any { it.lowercase().contains("dizel") && it.lowercase().contains("liter") }) {
                dieselColumn = cells.indexOfFirst { it.lowercase().contains("dizel") && it.lowercase().contains("liter") }
                petrolColumn = cells.indexOfFirst { it.contains("95") && it.lowercase().contains("liter") }; continue
            }
            if (dieselColumn < 0 || petrolColumn < 0 || cells.size <= maxOf(dieselColumn, petrolColumn)) continue
            val match = pattern.matchEntire(cells[0]) ?: continue
            val g = match.groupValues; val y2 = g[6].toInt()
            val y1 = g[3].toIntOrNull() ?: y2 - if (g[2].toInt() > g[5].toInt()) 1 else 0
            val start = LocalDate.of(y1, g[2].toInt(), g[1].toInt()); val end = LocalDate.of(y2, g[5].toInt(), g[4].toInt())
            if (today < start || today > end) continue
            fun price(index: Int): Double {
                require(Regex("\\d+,\\d{3}").matches(cells[index])) { "The current official fuel quote is unreadable." }
                return cells[index].replace(',', '.').toDouble().also { require(it > 0) }
            }
            matches.add(JSONObject().put("prices", JSONObject().put("diesel", price(dieselColumn)).put("petrol", price(petrolColumn)))
                .put("currency", "EUR").put("valid_from", start.toString()).put("valid_until", end.toString())
                .put("checked_at", Instant.now().toString()).put("source_name", "GOV.SI").put("source_url", PublicDataClient.FUEL_PRICE))
        }
        if (matches.size != 1) throw EcoApiException("A current official fuel price is unavailable. Fuel use remains available; cost is not estimated with an expired or example price.")
        return EcoJson.parseFuelPrice(matches.single().toString())
    }
}
