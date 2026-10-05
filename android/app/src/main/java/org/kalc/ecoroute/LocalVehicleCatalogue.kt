package org.kalc.ecoroute

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream

/** The reviewed public EEA snapshot is searchable without a network connection. */
internal class LocalVehicleCatalogue(private val context: Context) {
    private val lock = Any()
    private fun database(): SQLiteDatabase = synchronized(lock) {
        val file = File(context.noBackupFilesDir, "eea2024-v1.db")
        if (!file.exists()) {
            val temporary = File(file.parentFile, "eea2024-v1.db.tmp")
            try {
                context.assets.open("catalogue/eea2024-v1.sqlite.gzip").use { input ->
                    GZIPInputStream(input).use { zip -> temporary.outputStream().use(zip::copyTo) }
                }
                check(temporary.renameTo(file)) { "Could not install the car catalogue." }
            } finally { temporary.delete() }
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }
    fun search(query: String, fuel: String?, limit: Int, offset: Int): VehicleCatalogueSearchResult {
        require(query.trim().length in 2..80 && limit in 1..30 && offset in 0..100000)
        require(fuel == null || fuel in setOf("petrol", "diesel"))
        val words = tokens(query)
        require(words.isNotEmpty() && words.size <= 12) { "Enter a shorter make, model or engine search." }
        // Tokens contain only normalized letters/digits. Bare prefixes work with
        // both Android FTS4 query parsers; quoted wildcards differ by SQLite build.
        val match = words.joinToString(" ") { "$it*" }
        val where = "search_index MATCH ?" + if (fuel != null) " AND v.fuel_type = ?" else ""
        val args = (listOf(match) + listOfNotNull(fuel)).toTypedArray()
        database().use { db ->
            val total = db.rawQuery("SELECT COUNT(*) FROM search_index JOIN vehicles v ON v.rowid=search_index.rowid WHERE $where", args).use { it.moveToFirst(); it.getInt(0) }
            val vehicles = JSONArray()
            db.rawQuery("SELECT v.data FROM search_index JOIN vehicles v ON v.rowid=search_index.rowid WHERE $where ORDER BY v.supported DESC,v.frequency DESC,v.id LIMIT ? OFFSET ?", args + arrayOf(limit.toString(), offset.toString())).use {
                while (it.moveToNext()) vehicles.put(JSONObject(it.getString(0)))
            }
            return EcoJson.parseCatalogueSearch(JSONObject().put("vehicles", vehicles).put("total_matches", total)
                .put("truncated", total > offset + limit).put("next_offset", if (total > offset + limit) offset + limit else JSONObject.NULL)
                .put("coverage", "EEA final 2024 registrations: EU, Iceland and Norway. Petrol/diesel including non-plug-in hybrids; estimated vehicle assumptions. Not an exhaustive model-year catalogue. EVs and plug-in cars are not supported.").toString())
        }
    }
    companion object {
        internal fun tokens(text: String): List<String> {
            val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("([\\p{L}])(\\p{N})"), "$1 $2").replace(Regex("(\\p{N})([\\p{L}])"), "$1 $2")
            return Regex("[\\p{L}\\p{N}]+").findAll(normalized).map { it.value }.toList()
        }
    }
}
