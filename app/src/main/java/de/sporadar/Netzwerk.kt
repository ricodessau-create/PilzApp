package de.sporadar

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private suspend fun holeJson(url: String): JSONObject = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 25000
    c.setRequestProperty("User-Agent", "Sporadar/0.2 (Android)")
    try {
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    } finally {
        c.disconnect()
    }
}

object Gbif {
    // Menschliche Beobachtungen in Deutschland ab 2015 im Kartenausschnitt
    suspend fun funde(
        arten: List<Pilzart>,
        sued: Double,
        nord: Double,
        west: Double,
        ost: Double
    ): List<Fund> {
        val namen = arten.joinToString("&") {
            "scientificName=" + URLEncoder.encode(it.lateinisch, "UTF-8")
        }
        val url = "https://api.gbif.org/v1/occurrence/search?$namen" +
            "&country=DE&hasCoordinate=true&hasGeospatialIssue=false" +
            "&occurrenceStatus=PRESENT&basisOfRecord=HUMAN_OBSERVATION" +
            "&year=2015,2026&decimalLatitude=$sued,$nord&decimalLongitude=$west,$ost&limit=300"

        val res = holeJson(url).getJSONArray("results")
        val liste = mutableListOf<Fund>()
        for (i in 0 until res.length()) {
            val o = res.getJSONObject(i)
            if (!o.has("decimalLatitude") || !o.has("decimalLongitude")) continue

            val name = o.optString("species", o.optString("scientificName", ""))
            val art = arten.firstOrNull { it.lateinisch.equals(name, true) }
                ?: arten.firstOrNull { o.optString("scientificName").startsWith(it.lateinisch, true) }
                ?: continue

            var foto: String? = null
            var credit: String? = null
            val media = o.optJSONArray("media")
            if (media != null) {
                for (m in 0 until media.length()) {
                    val mo = media.getJSONObject(m)
                    if (mo.optString("type") == "StillImage" && mo.has("identifier")) {
                        foto = mo.getString("identifier").replace("http://", "https://")
                        credit = listOf(mo.optString("creator"), mo.optString("license"))
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                            .ifBlank { null }
                        break
                    }
                }
            }
            liste += Fund(
                id = o.optString("key"),
                artId = art.id,
                lat = o.getDouble("decimalLatitude"),
                lng = o.getDouble("decimalLongitude"),
                datum = o.optString("eventDate").take(10).ifBlank { null },
                fotoUrl = foto,
                fotoCredit = credit
            )
        }
        return liste
    }
}

data class Wetter(val regen14: Double, val temp7: Double, val index: Float)

object OpenMeteo {
    // Einfacher Index: 60 % Regen der letzten 14 Tage, 40 % Temperatur der letzten 7 Tage
    suspend fun hole(lat: Double, lng: Double): Wetter {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng" +
            "&daily=precipitation_sum,temperature_2m_mean&past_days=14&forecast_days=1&timezone=auto"
        val d = holeJson(url).getJSONObject("daily")
        val regen = d.getJSONArray("precipitation_sum")
        val temp = d.getJSONArray("temperature_2m_mean")

        var r = 0.0
        for (i in 0 until regen.length()) r += regen.optDouble(i, 0.0)

        var summe = 0.0
        var n = 0
        for (i in (temp.length() - 7).coerceAtLeast(0) until temp.length()) {
            val t = temp.optDouble(i, Double.NaN)
            if (!t.isNaN()) {
                summe += t
                n++
            }
        }
        val t7 = if (n > 0) summe / n else 10.0

        val regenScore = (r / 40.0).coerceIn(0.0, 1.0)
        val tempScore = when {
            t7 in 8.0..18.0 -> 1.0
            t7 < 8.0 -> ((t7 - 2.0) / 6.0).coerceIn(0.0, 1.0)
            else -> ((24.0 - t7) / 6.0).coerceIn(0.0, 1.0)
        }
        return Wetter(r, t7, (0.6 * regenScore + 0.4 * tempScore).toFloat())
    }
}
