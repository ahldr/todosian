package com.isotjs.todosian.data.places

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class AddressResult(val label: String, val latitude: Double, val longitude: Double)

/** Address search through Photon (OpenStreetMap data, no API key). */
class PhotonGeocoder(private val userAgent: String) {

    suspend fun search(query: String, nearLatitude: Double?, nearLongitude: Double?): List<AddressResult> =
        withContext(Dispatchers.IO) {
            val bias = if (nearLatitude != null && nearLongitude != null) "&lat=$nearLatitude&lon=$nearLongitude" else ""
            val url = URL("$ENDPOINT?q=${URLEncoder.encode(query, "UTF-8")}&limit=$LIMIT$bias")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", userAgent)
                parse(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }

    private fun parse(body: String): List<AddressResult> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { index ->
            val feature = features.getJSONObject(index)
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val street = listOf("street", "housenumber").mapNotNull { properties.optString(it).ifBlank { null } }
                .joinToString(" ")
            val label = listOf(properties.optString("name"), street, properties.optString("city"), properties.optString("country"))
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
            AddressResult(label.ifBlank { "?" }, coordinates.getDouble(1), coordinates.getDouble(0))
        }
    }

    private companion object {
        const val ENDPOINT = "https://photon.komoot.io/api/"
        const val LIMIT = 6
        const val TIMEOUT_MS = 8_000
    }
}
