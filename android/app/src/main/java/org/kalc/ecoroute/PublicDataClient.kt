package org.kalc.ecoroute

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun interface OnlineData {
    suspend fun request(url: String, payload: JSONObject?, ttlMs: Long): String
}

/** Only public data providers; there is no private server endpoint or fallback. */
internal class PublicDataClient(context: Context) : OnlineData {
    private val directory = File(context.cacheDir, "public-route-data").apply { mkdirs() }
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .followSslRedirects(false).build()

    override suspend fun request(url: String, payload: JSONObject?, ttlMs: Long): String = withContext(Dispatchers.IO) {
        require(url.startsWith(PHOTON) || url.startsWith(ROUTER + "/") || url == FUEL_PRICE)
        val key = MessageDigest.getInstance("SHA-256").digest((url + "\n" + payload?.toString().orEmpty()).toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$key.json")
        fun cached(): String? = file.takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() in 0 until ttlMs }?.readText()
        // Cache retention is enforced even when the requested response is fresh.
        synchronized(directoryLock) {
            val files = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.sortedBy { it.lastModified() }
            var size = files.sumOf { it.length() }
            for (old in files) if (System.currentTimeMillis() - old.lastModified() > SEVEN_DAYS || size > 32L * 1024 * 1024) {
                val length = old.length(); if (old.delete()) size -= length
            }
        }
        cached()?.let { return@withContext it }
        val address = url.startsWith(PHOTON)
        val gate = if (address) addressGate else dataGate
        gate.withLock {
            cached()?.let { return@withLock it }
            val last = if (address) lastAddress else lastData
            val elapsed = System.nanoTime() / 1_000_000 - last
            delay((1100 - elapsed).coerceAtLeast(0))
            val now = System.nanoTime() / 1_000_000
            if (address) lastAddress = now else lastData = now
            val builder = Request.Builder().url(url).header("User-Agent", "EcoRouteAndroid/0.9 (org.kalc.ecoroute; personal trip planner)")
            if (payload != null) builder.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            val body = coroutineScope {
                val call = client.newCall(builder.build())
                // Keep the Call cancellable until its response body has been read.
                val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    val response = try { call.await() }
                    catch (error: IOException) { throw EcoNetworkException("The online map or price service could not be reached. Check your internet connection and retry.", error) }
                    val jobContext = coroutineContext
                    response.use {
                        if (!it.isSuccessful) throw EcoApiException(if (it.code == 429) "The map service is busy. Wait a moment and retry." else "The online data service could not finish (${it.code}). Please retry.")
                        val source = it.body ?: throw EcoApiException("The online service returned no data.")
                        require(source.contentLength() <= 16L * 1024 * 1024) { "The route data is too large." }
                        val bytes = source.byteStream().use { stream ->
                            val output = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                            while (true) { jobContext.ensureActive(); val count = stream.read(chunk); if (count < 0) break; require(output.size() + count <= 16 * 1024 * 1024) { "The route data is too large." }; output.write(chunk, 0, count) }
                            output.toByteArray()
                        }
                        String(bytes, Charsets.UTF_8)
                    }
                } finally { cancelWatcher.cancel() }
            }
            if (url != FUEL_PRICE) {
                val json = JSONObject(body)
                if (json.has("error") || (json.optJSONObject("trip")?.optInt("status", 0) ?: 0) != 0) throw EcoApiException("The map service could not find usable roads. Try another location.")
            }
            val temporary = File.createTempFile("response-", ".tmp", directory)
            try { temporary.writeText(body); if (!temporary.renameTo(file)) temporary.delete() } finally { temporary.delete() }
            body
        }
    }
    companion object {
        const val PHOTON = "https://photon.komoot.io/api/"
        const val ROUTER = "https://valhalla1.openstreetmap.de"
        const val FUEL_PRICE = "https://www.gov.si/teme/cene-naftnih-derivatov/"
        const val SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000
        private val addressGate = Mutex(); private val dataGate = Mutex()
        private val directoryLock = Any()
        private var lastAddress = 0L; private var lastData = 0L
    }
}
