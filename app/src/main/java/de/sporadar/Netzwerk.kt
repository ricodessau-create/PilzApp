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
    c.connectTimeout = 12000
    c.readTimeout = 20000
    c.setRequestProperty("User-Agent", "Sporadar/0.4 (Android)")
    c.setRequestProperty("Accept", "application/json")

    try {
        val code = c.responseCode

        if (code !in 200..299) {
            error("HTTP $code")
        }

        JSONObject(
            c.inputStream.bufferedReader().use {
                it.readText()
            }
        )
    } finally {
        c.disconnect()
    }
}

private fun JSONObject.text(name: String): String? {
    if (isNull(name)) return null

    val s = optString(name)

    return if (s.isBlank()) {
        null
    } else {
        s
    }
}

object Inat {
    private const val BASIS = "https://api.inaturalist.org/v1"

    private suspend fun taxonId(
        lateinisch: String
    ): Long? {
        val url = BASIS + "/taxa?q=" +
            URLEncoder.encode(
                lateinisch,
                "UTF-8"
            ) +
            "&rank=species&is_active=true&per_page=5"

        val res = holeJson(url)
            .optJSONArray("results")
            ?: return null

        var erster: Long? = null

        for (i in 0 until res.length()) {
            val t = res.getJSONObject(i)

            if (t.optString("rank") != "species") {
                continue
            }

            val id = t.optLong(
                "id",
                0L
            )

            if (id == 0L) {
                continue
            }

            if (
                t.optString("name")
                    .equals(lateinisch, true)
            ) {
                return id
            }

            if (erster == null) {
                erster = id
            }
        }

        return erster
    }

    private suspend fun taxonIdSicher(
        lateinisch: String
    ): Long? {
        return try {
            taxonId(lateinisch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    suspend fun taxonIds(
        arten: List<Pilzart>
    ): Map<String, Long> {
        val ergebnis = HashMap<String, Long>()

        for (
            index in arten.chunked(4).indices
        ) {
            val gruppe = arten.chunked(4)[index]

            val antworten = coroutineScope {
                gruppe.map { art ->
                    async {
                        art.id to taxonIdSicher(
                            art.lateinisch
                        )
                    }
                }.awaitAll()
            }

            for ((artId, tid) in antworten) {
                if (tid != null) {
                    ergebnis[artId] = tid
                }
            }

            if (
                index < arten.chunked(4).lastIndex
            ) {
                delay(500)
            }
        }

        return ergebnis
    }

    suspend fun funde(
        ids: Map<String, Long>,
        sued: Double,
        nord: Double,
        west: Double,
        ost: Double,
        ab: LocalDate?
    ): List<Fund> {
        if (ids.isEmpty()) {
            return emptyList()
        }

        val umgekehrt = HashMap<Long, String>()

        for ((artId, tid) in ids) {
            umgekehrt[tid] = artId
        }

        val d1 = if (ab != null) {
            "&d1=$ab"
        } else {
            ""
        }

        val url =
            BASIS +
                "/observations?taxon_id=" +
                ids.values.joinToString(",") +
                "&swlat=" + sued +
                "&swlng=" + west +
                "&nelat=" + nord +
                "&nelng=" + ost +
                d1 +
                "&photos=true" +
                "&geo=true" +
                "&quality_grade=research,needs_id" +
                "&order_by=observed_on" +
                "&order=desc" +
                "&per_page=200" +
                "&locale=de"

        val res = holeJson(url)
            .getJSONArray("results")

        val liste = mutableListOf<Fund>()

        for (i in 0 until res.length()) {
            val o = res.getJSONObject(i)

            val koord = o.optJSONObject("geojson")
                ?.optJSONArray("coordinates")
                ?: continue

            if (koord.length() < 2) {
                continue
            }

            val lng = koord.getDouble(0)
            val lat = koord.getDouble(1)

            val taxon = o.optJSONObject("taxon")
                ?: continue

            var artId: String? =
                umgekehrt[taxon.optLong("id")]

            if (artId == null) {
                val vorfahren =
                    taxon.optJSONArray("ancestor_ids")

                if (vorfahren != null) {
                    for (k in 0 until vorfahren.length()) {
                        val a =
                            umgekehrt[
                                vorfahren.optLong(k)
                            ]

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

            if (
                fotos != null &&
                fotos.length() > 0
            ) {
                val f = fotos.getJSONObject(0)
                val u = f.text("url")

                if (u != null) {
                    foto = u
                        .replace(
                            "square",
                            "medium"
                        )
                        .replace(
                            "http://",
                            "https://"
                        )

                    credit = f.text(
                        "attribution"
                    )
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
                bestaetigt =
                    o.optString(
                        "quality_grade"
                    ) == "research",
                ungenau =
                    o.optBoolean(
                        "obscured",
                        false
                    )
            )
        }

        return liste
    }
}

data class Wetter(
    val regen14: Double,
    val regen7: Double,
    val regen3: Double,
    val temp7: Double,
    val index: Float
)

object OpenMeteo {

    private fun bereichsScore(
        wert: Double,
        optimalMin: Double,
        optimalMax: Double,
        untereGrenze: Double,
        obereGrenze: Double
    ): Double {
        return when {
            wert in optimalMin..optimalMax -> {
                1.0
            }

            wert < optimalMin -> {
                (
                    (wert - untereGrenze) /
                        (optimalMin - untereGrenze)
                    ).coerceIn(0.0, 1.0)
            }

            else -> {
                (
                    (obereGrenze - wert) /
                        (obereGrenze - optimalMax)
                    ).coerceIn(0.0, 1.0)
            }
        }
    }

    suspend fun hole(
        lat: Double,
        lng: Double
    ): Wetter {
        val url =
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat" +
                "&longitude=$lng" +
                "&daily=precipitation_sum,temperature_2m_mean" +
                "&past_days=14" +
                "&forecast_days=1" +
                "&timezone=auto"

        val d = holeJson(url)
            .getJSONObject("daily")

        val regen =
            d.getJSONArray(
                "precipitation_sum"
            )

        val temp =
            d.getJSONArray(
                "temperature_2m_mean"
            )

        var regen14 = 0.0

        for (i in 0 until regen.length()) {
            regen14 += regen.optDouble(
                i,
                0.0
            )
        }

        var regen7 = 0.0

        val regen7Start =
            (regen.length() - 7)
                .coerceAtLeast(0)

        for (
            i in regen7Start until regen.length()
        ) {
            regen7 += regen.optDouble(
                i,
                0.0
            )
        }

        var regen3 = 0.0

        val regen3Start =
            (regen.length() - 3)
                .coerceAtLeast(0)

        for (
            i in regen3Start until regen.length()
        ) {
            regen3 += regen.optDouble(
                i,
                0.0
            )
        }

        var summe = 0.0
        var n = 0

        val tempStart =
            (temp.length() - 7)
                .coerceAtLeast(0)

        for (
            i in tempStart until temp.length()
        ) {
            val t = temp.optDouble(
                i,
                Double.NaN
            )

            if (!t.isNaN()) {
                summe += t
                n++
            }
        }

        val temp7 = if (n > 0) {
            summe / n
        } else {
            10.0
        }

        val regen3Score =
            bereichsScore(
                wert = regen3,
                optimalMin = 3.0,
                optimalMax = 18.0,
                untereGrenze = 0.0,
                obereGrenze = 35.0
            )

        val regen7Score =
            bereichsScore(
                wert = regen7,
                optimalMin = 10.0,
                optimalMax = 35.0,
                untereGrenze = 0.0,
                obereGrenze = 70.0
            )

        val regen14Score =
            bereichsScore(
                wert = regen14,
                optimalMin = 20.0,
                optimalMax = 65.0,
                untereGrenze = 0.0,
                obereGrenze = 120.0
            )

        val tempScore =
            bereichsScore(
                wert = temp7,
                optimalMin = 8.0,
                optimalMax = 18.0,
                untereGrenze = 2.0,
                obereGrenze = 24.0
            )

        val index =
            (
                regen3Score * 0.20 +
                    regen7Score * 0.35 +
                    regen14Score * 0.20 +
                    tempScore * 0.25
                ).toFloat()
                    .coerceIn(0f, 1f)

        return Wetter(
            regen14 = regen14,
            regen7 = regen7,
            regen3 = regen3,
            temp7 = temp7,
            index = index
        )
    }
}
