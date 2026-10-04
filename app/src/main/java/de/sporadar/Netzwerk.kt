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

private suspend fun holeJson(
    url: String
): JSONObject = withContext(Dispatchers.IO) {
    val c =
        URL(url).openConnection() as HttpURLConnection

    c.connectTimeout = 12000
    c.readTimeout = 20000

    c.setRequestProperty(
        "User-Agent",
        "Sporadar/0.5 (Android)"
    )

    c.setRequestProperty(
        "Accept",
        "application/json"
    )

    try {
        val code = c.responseCode

        if (code !in 200..299) {
            error("HTTP $code")
        }

        JSONObject(
            c.inputStream
                .bufferedReader()
                .use {
                    it.readText()
                }
        )
    } finally {
        c.disconnect()
    }
}

private fun JSONObject.text(
    name: String
): String? {
    if (isNull(name)) {
        return null
    }

    val value =
        optString(name)

    return if (value.isBlank()) {
        null
    } else {
        value
    }
}

object Inat {

    private const val BASIS =
        "https://api.inaturalist.org/v1"

    private suspend fun taxonId(
        lateinisch: String
    ): Long? {
        val url =
            BASIS +
                "/taxa?q=" +
                URLEncoder.encode(
                    lateinisch,
                    "UTF-8"
                ) +
                "&rank=species" +
                "&is_active=true" +
                "&per_page=5"

        val res =
            holeJson(url)
                .optJSONArray("results")
                ?: return null

        var erster: Long? = null

        for (i in 0 until res.length()) {
            val taxon =
                res.getJSONObject(i)

            if (
                taxon.optString("rank") !=
                "species"
            ) {
                continue
            }

            val id =
                taxon.optLong(
                    "id",
                    0L
                )

            if (id == 0L) {
                continue
            }

            if (
                taxon.optString("name")
                    .equals(
                        lateinisch,
                        true
                    )
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
        } catch (
            e: CancellationException
        ) {
            throw e
        } catch (
            e: Exception
        ) {
            null
        }
    }

    suspend fun taxonIds(
        arten: List<Pilzart>
    ): Map<String, Long> {
        val ergebnis =
            HashMap<String, Long>()

        val gruppen =
            arten.chunked(4)

        for (
            index in gruppen.indices
        ) {
            val gruppe =
                gruppen[index]

            val antworten =
                coroutineScope {
                    gruppe.map { art ->
                        async {
                            art.id to
                                taxonIdSicher(
                                    art.lateinisch
                                )
                        }
                    }.awaitAll()
                }

            for (
                (artId, taxonId)
                in antworten
            ) {
                if (taxonId != null) {
                    ergebnis[artId] =
                        taxonId
                }
            }

            if (
                index < gruppen.lastIndex
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

        val umgekehrt =
            HashMap<Long, String>()

        for (
            (artId, taxonId)
            in ids
        ) {
            umgekehrt[taxonId] =
                artId
        }

        val d1 =
            if (ab != null) {
                "&d1=$ab"
            } else {
                ""
            }

        val url =
            BASIS +
                "/observations?taxon_id=" +
                ids.values.joinToString(",") +
                "&swlat=$sued" +
                "&swlng=$west" +
                "&nelat=$nord" +
                "&nelng=$ost" +
                d1 +
                "&photos=true" +
                "&geo=true" +
                "&quality_grade=research,needs_id" +
                "&order_by=observed_on" +
                "&order=desc" +
                "&per_page=200" +
                "&locale=de"

        val result =
            holeJson(url)
                .getJSONArray("results")

        val liste =
            mutableListOf<Fund>()

        for (
            i in 0 until result.length()
        ) {
            val observation =
                result.getJSONObject(i)

            val koordinaten =
                observation
                    .optJSONObject(
                        "geojson"
                    )
                    ?.optJSONArray(
                        "coordinates"
                    )
                    ?: continue

            if (
                koordinaten.length() < 2
            ) {
                continue
            }

            val lng =
                koordinaten.getDouble(0)

            val lat =
                koordinaten.getDouble(1)

            val taxon =
                observation
                    .optJSONObject("taxon")
                    ?: continue

            var artId =
                umgekehrt[
                    taxon.optLong("id")
                ]

            if (artId == null) {
                val vorfahren =
                    taxon.optJSONArray(
                        "ancestor_ids"
                    )

                if (vorfahren != null) {
                    for (
                        k in 0 until
                            vorfahren.length()
                    ) {
                        val gefunden =
                            umgekehrt[
                                vorfahren.optLong(k)
                            ]

                        if (
                            gefunden != null
                        ) {
                            artId = gefunden
                            break
                        }
                    }
                }
            }

            val gefunden =
                artId ?: continue

            var foto: String? = null
            var credit: String? = null

            val fotos =
                observation.optJSONArray(
                    "photos"
                )

            if (
                fotos != null &&
                fotos.length() > 0
            ) {
                val fotoObjekt =
                    fotos.getJSONObject(0)

                val urlFoto =
                    fotoObjekt.text("url")

                if (urlFoto != null) {
                    foto =
                        urlFoto
                            .replace(
                                "square",
                                "medium"
                            )
                            .replace(
                                "http://",
                                "https://"
                            )

                    credit =
                        fotoObjekt.text(
                            "attribution"
                        )
                }
            }

            liste += Fund(
                id =
                    "inat:" +
                        observation
                            .optLong("id")
                            .toString(),
                artId = gefunden,
                lat = lat,
                lng = lng,
                datum =
                    observation.text(
                        "observed_on"
                    ),
                fotoUrl = foto,
                fotoCredit = credit,
                bestaetigt =
                    observation.optString(
                        "quality_grade"
                    ) == "research",
                ungenau =
                    observation.optBoolean(
                        "obscured",
                        false
                    ),
                quelle = "iNaturalist"
            )
        }

        return liste
    }
}

object Gbif {

    private const val BASIS =
        "https://api.gbif.org/v1"

    private suspend fun taxonKey(
        lateinisch: String
    ): Long? {
        val url =
            BASIS +
                "/species/match?name=" +
                URLEncoder.encode(
                    lateinisch,
                    "UTF-8"
                ) +
                "&verbose=false"

        val result =
            holeJson(url)

        val rank =
            result.optString("rank")

        val status =
            result.optString("status")

        val key =
            result.optLong(
                "usageKey",
                0L
            )

        if (
            key <= 0L ||
            rank.lowercase() !=
            "species"
        ) {
            return null
        }

        if (
            status.equals(
                "synonym",
                true
            )
        ) {
            val akzeptiert =
                result.optLong(
                    "acceptedUsageKey",
                    0L
                )

            if (
                akzeptiert > 0L
            ) {
                return akzeptiert
            }
        }

        return key
    }

    private suspend fun taxonKeySicher(
        lateinisch: String
    ): Long? {
        return try {
            taxonKey(lateinisch)
        } catch (
            e: CancellationException
        ) {
            throw e
        } catch (
            e: Exception
        ) {
            null
        }
    }

    suspend fun taxonKeys(
        arten: List<Pilzart>
    ): Map<String, Long> {
        val ergebnis =
            HashMap<String, Long>()

        val gruppen =
            arten.chunked(4)

        for (
            index in gruppen.indices
        ) {
            val gruppe =
                gruppen[index]

            val antworten =
                coroutineScope {
                    gruppe.map { art ->
                        async {
                            art.id to
                                taxonKeySicher(
                                    art.lateinisch
                                )
                        }
                    }.awaitAll()
                }

            for (
                (artId, taxonId)
                in antworten
            ) {
                if (taxonId != null) {
                    ergebnis[artId] =
                        taxonId
                }
            }

            if (
                index < gruppen.lastIndex
            ) {
                delay(500)
            }
        }

        return ergebnis
    }

    private fun datumPasst(
        datum: String?,
        ab: LocalDate?
    ): Boolean {
        if (ab == null) {
            return true
        }

        if (datum.isNullOrBlank()) {
            return false
        }

        val text =
            datum.take(10)

        return try {
            val d =
                LocalDate.parse(text)

            !d.isBefore(ab)
        } catch (
            e: Exception
        ) {
            false
        }
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

        val umgekehrt =
            HashMap<Long, String>()

        for (
            (artId, taxonKey)
            in ids
        ) {
            umgekehrt[taxonKey] =
                artId
        }

        val taxonParameter =
            ids.values.joinToString("") {
                "&taxon_key=$it"
            }

        val url =
            BASIS +
                "/occurrence/search" +
                "?limit=300" +
                "&offset=0" +
                taxonParameter +
                "&decimalLatitude=$sued,$nord" +
                "&decimalLongitude=$west,$ost" +
                "&has_coordinate=true" +
                "&has_geospatial_issue=false" +
                "&occurrence_status=present"

        val result =
            holeJson(url)
                .optJSONArray("results")
                ?: return emptyList()

        val liste =
            mutableListOf<Fund>()

        for (
            i in 0 until result.length()
        ) {
            val occurrence =
                result.optJSONObject(i)
                    ?: continue

            val lat =
                occurrence.optDouble(
                    "decimalLatitude",
                    Double.NaN
                )

            val lng =
                occurrence.optDouble(
                    "decimalLongitude",
                    Double.NaN
                )

            if (
                lat.isNaN() ||
                lng.isNaN()
            ) {
                continue
            }

            if (
                lat < sued ||
                lat > nord ||
                lng < west ||
                lng > ost
            ) {
                continue
            }

            val taxonKey =
                occurrence.optLong(
                    "taxonKey",
                    0L
                )

            var artId =
                umgekehrt[taxonKey]

            if (artId == null) {
                val speciesKey =
                    occurrence.optLong(
                        "speciesKey",
                        0L
                    )

                artId =
                    umgekehrt[
                        speciesKey
                    ]
            }

            val gefunden =
                artId ?: continue

            val datum =
                occurrence.text(
                    "eventDate"
                ) ?: occurrence.text(
                    "verbatimEventDate"
                )

            if (
                !datumPasst(
                    datum,
                    ab
                )
            ) {
                continue
            }

            val key =
                occurrence.optLong(
                    "key",
                    0L
                )

            if (key <= 0L) {
                continue
            }

            val basis =
                occurrence.text(
                    "basisOfRecord"
                )

            val qualitaet =
                occurrence.text(
                    "occurrenceStatus"
                )

            liste += Fund(
                id =
                    "gbif:$key",
                artId = gefunden,
                lat = lat,
                lng = lng,
                datum = datum,
                fotoUrl = null,
                fotoCredit = null,
                bestaetigt =
                    basis != null,
                ungenau =
                    occurrence.optBoolean(
                        "hasGeospatialIssue",
                        false
                    ),
                quelle = "GBIF"
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
                    )
                    .coerceIn(
                        0.0,
                        1.0
                    )
            }

            else -> {
                (
                    (obereGrenze - wert) /
                        (obereGrenze - optimalMax)
                    )
                    .coerceIn(
                        0.0,
                        1.0
                    )
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

        val d =
            holeJson(url)
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

        for (
            i in 0 until regen.length()
        ) {
            regen14 +=
                regen.optDouble(
                    i,
                    0.0
                )
        }

        var regen7 = 0.0

        val regen7Start =
            (
                regen.length() - 7
            ).coerceAtLeast(0)

        for (
            i in regen7Start until
                regen.length()
        ) {
            regen7 +=
                regen.optDouble(
                    i,
                    0.0
                )
        }

        var regen3 = 0.0

        val regen3Start =
            (
                regen.length() - 3
            ).coerceAtLeast(0)

        for (
            i in regen3Start until
                regen.length()
        ) {
            regen3 +=
                regen.optDouble(
                    i,
                    0.0
                )
        }

        var summe = 0.0
        var n = 0

        val tempStart =
            (
                temp.length() - 7
            ).coerceAtLeast(0)

        for (
            i in tempStart until
                temp.length()
        ) {
            val t =
                temp.optDouble(
                    i,
                    Double.NaN
                )

            if (!t.isNaN()) {
                summe += t
                n++
            }
        }

        val temp7 =
            if (n > 0) {
                summe / n
            } else {
                10.0
            }

        val regen3Score =
            bereichsScore(
                regen3,
                3.0,
                18.0,
                0.0,
                35.0
            )

        val regen7Score =
            bereichsScore(
                regen7,
                10.0,
                35.0,
                0.0,
                70.0
            )

        val regen14Score =
            bereichsScore(
                regen14,
                20.0,
                65.0,
                0.0,
                120.0
            )

        val tempScore =
            bereichsScore(
                temp7,
                8.0,
                18.0,
                2.0,
                24.0
            )

        val index =
            (
                regen3Score * 0.20 +
                    regen7Score * 0.35 +
                    regen14Score * 0.20 +
                    tempScore * 0.25
                )
                .toFloat()
                .coerceIn(
                    0f,
                    1f
                )

        return Wetter(
            regen14 = regen14,
            regen7 = regen7,
            regen3 = regen3,
            temp7 = temp7,
            index = index
        )
    }
}