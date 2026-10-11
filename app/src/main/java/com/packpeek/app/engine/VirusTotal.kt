package com.packpeek.app.engine

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Result of looking a file's SHA-256 up in a threat database. */
sealed interface HashLookup {
    data object Running : HashLookup

    /** No API key in Settings; the browser link still works. */
    data object NoKey : HashLookup

    /** The database has never seen this file. */
    data object NotFound : HashLookup

    data class Found(
        val malicious: Int,
        val suspicious: Int,
        val engines: Int,
        val label: String?,
    ) : HashLookup

    data class Failed(val reason: Reason) : HashLookup

    enum class Reason { KEY, QUOTA, NETWORK }
}

/**
 * VirusTotal file report lookup. Only the SHA-256 leaves the device, with the user's own API key,
 * and only when the user taps the button. The APK itself is never uploaded.
 */
object VirusTotal {
    fun guiUrl(sha256: String) = "https://www.virustotal.com/gui/file/$sha256"

    suspend fun lookUp(sha256: String, apiKey: String): HashLookup =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext HashLookup.NoKey
            require(Regex("[0-9a-f]{64}").matches(sha256))
            val connection = URL("https://www.virustotal.com/api/v3/files/$sha256").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("x-apikey", apiKey.trim())
                connection.setRequestProperty("accept", "application/json")
                when (connection.responseCode) {
                    200 -> parse(connection.inputStream.bufferedReader().use { it.readText() })
                    404 -> HashLookup.NotFound
                    401, 403 -> HashLookup.Failed(HashLookup.Reason.KEY)
                    429 -> HashLookup.Failed(HashLookup.Reason.QUOTA)
                    else -> HashLookup.Failed(HashLookup.Reason.NETWORK)
                }
            } catch (e: Exception) {
                HashLookup.Failed(HashLookup.Reason.NETWORK)
            } finally {
                connection.disconnect()
            }
        }

    /** Reads last_analysis_stats and the suggested threat label from a v3 file object. */
    fun parse(json: String): HashLookup {
        val attributes = JSONObject(json).getJSONObject("data").getJSONObject("attributes")
        val stats = attributes.optJSONObject("last_analysis_stats") ?: JSONObject()
        val malicious = stats.optInt("malicious")
        val suspicious = stats.optInt("suspicious")
        val engines = stats.keys().asSequence().sumOf { stats.optInt(it) }
        val label =
            attributes
                .optJSONObject("popular_threat_classification")
                ?.optString("suggested_threat_label")
                ?.takeIf { it.isNotBlank() }
        return HashLookup.Found(malicious, suspicious, engines, label)
    }
}
