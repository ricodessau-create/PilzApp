package de.sporadar

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private suspend fun holeJson(url: String): JSONObject = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 25000
    c.setRequestProperty("User-Agent", "Sporadar/0.4 (Android)")
    try {
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    } finally {
        c.disconnect()
    }
}

// JSON-null sicher als Text lesen
private fun JSONObject.text(name: String): String? {
    if (isNull(name)) return null
    val s = optString(name)
    return if (s.isBlank()) null else s
}

object Inat {
    private const val BASIS = "https://api.inaturalist.org/v1"

    // Sucht die iNaturalist-Taxon-ID einer Art anhand des lateinischen Namens
    private suspend fun taxonId(lateinisch: String): Long? {
        val url = BASIS + "/taxa?q=" + URLEncoder.encode(lateinisch, "UTF-8") +
            "&rank=species&is_active=true&per_page=5"
        val res = holeJson(url).optJSONArray("results") ?: return null
        var erster: Long? = null
        for (i in 0 until res.length()) {
            val t = res.getJSONObject(i)
            if (t.optString("rank") != "species") continue
            val id = t.getLong("id")
            if (t.optString("name").equals(lateinisch, true)) return id
            if (erster == null) erster = id
        }
        return erster
    }

    private suspend fun taxonIdSicher(lateinisch: String): Long? {
        return try {
            taxonId(lateinisch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // Ermittelt die IDs mehrerer Arten (in kleinen Gruppen, um iNaturalist zu schonen)
    suspend fun taxonIds(arten: List<Pilzart>): Map<String, Long> {
        val ergebnis = HashMap<String, Long>()
        for (gruppe in arten.chunked(4)) {
            val antworten = coroutineScope {
                gruppe.map { art -> async { art.id to taxonIdSicher(art.lateinisch) } }.awaitAll()
            }
            for ((artId, tid) in antworten) {
                if (tid != null) ergebnis[artId] = tid
            }
            delay(700)
        }
        return ergebnis
    }

    // Neueste Beobachtungen im Kartenausschnitt. ids: Pilzart-ID -> iNaturalist-Taxon-ID
    suspend fun funde(
        ids: Map<String, Long>,
        sued: Double,
        nord: Double,
        west: Double,
        ost: Double,
        ab: LocalDate?
    ): List<Fund> {
        if (ids.isEmpty()) return emptyList()

        val umgekehrt = HashMap<Long, String>()
        for ((artId, tid) in ids) umgekehrt[tid] = artId

        val d1 = if (ab != null) "&d1=" + ab.toString() else ""
        val url = BASIS + "/observations?taxon_id=" + ids.values.joinToString(",") +
            "&swlat=" + sued + "&swlng=" + west +
            "&nelat=" + nord + "&nelng=" + ost +
            d1 +
            "&photos=true&geo=true&quality_grade=research,needs_id" +
            "&order_by=observed_on&order=desc&per_page=200&locale=de"

        val res = holeJson(url).getJSONArray("results")
        val liste = mutableListOf<Fund>()
        for (i in 0 until res.length()) {
            val o = res.getJSONObject(i)

            val koord = o.optJSONObject("geojson")?.optJSONArray("coordinates") ?: continue
            if (koord.length() < 2) continue
            val lng = koord.getDouble(0)
            val lat = koord.getDouble(1)

            val taxon = o.optJSONObject("taxon") ?: continue
            var artId: String? = umgekehrt[taxon.optLong("id")]
            if (artId == null) {
                val vorfahren = taxon.optJSONArray("ancestor_ids")
                if (vorfahren != null) {
                    for (k in 0 until vorfahren.length()) {
                        val a = umgekehrt[vorfahren.optLong(k)]
                        if (a != null) {
                            artId = a
                            break
                        }
                    }
                }
            }
            val gefunden = artId ?: continue

            var foto: String? = null
            var credit: String? = null
            val fotos = o.optJSONArray("photos")
            if (fotos != null && fotos.length() > 0) {
                val f = fotos.getJSONObject(0)
                val u = f.text("url")
                if (u != null) {
                    foto = u.replace("square", "medium").replace("http://", "https://")
                    credit = f.text("attribution")
                }
            }

            liste += Fund(
                id = o.optLong("id").toString(),
                artId = gefunden,
                lat = lat,
                lng = lng,
                datum = o.text("observed_on"),
                fotoUrl = foto,
                fotoCredit = credit,
                bestaetigt = o.optString("quality_grade") == "research",
                ungenau = o.optBoolean("obscured", false)
            )
        }
        return liste
    }
}

data class Wetter(val regen14: Double, val temp7: Double, val index: Float)

object OpenMeteo {
    // Einfacher Index: 60 % Regen der letzten 14 Tage, 40 % Temperatur der letzten 7 Tage
    suspend fun hole(lat: Double, lng: Double): Wetter {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat +
            "&longitude=" + lng +
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

// ENDE
