package de.sporadar

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class Zeitraum(val label: String, val tage: Int?) {
    TAGE14("Letzte 14 Tage", 14),
    MONATE3("Letzte 3 Monate", 90),
    JAHR("Letztes Jahr", 365),
    ALLE("Alle Jahre", null)
}

class SporadarViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences(
        "sporadar",
        Context.MODE_PRIVATE
    )

    var auswahl by mutableStateOf(setOf<String>())
        private set

    var zeitraum by mutableStateOf(Zeitraum.MONATE3)
        private set

    var gewaehlteFundId by mutableStateOf<String?>(null)
        private set

    var funde by mutableStateOf<List<Fund>>(emptyList())
        private set

    var laedt by mutableStateOf(false)
        private set

    var meldung by mutableStateOf<String?>(null)
        private set

    var wetter by mutableStateOf<Wetter?>(null)
        private set

    private var ladeJob: Job? = null
    private var wetterJob: Job? = null
    private var ladeNr = 0
    private var wetterPos: Pair<Double, Double>? = null

    private data class CacheEintrag(
        val zeitpunkt: Long,
        val funde: List<Fund>
    )

    private data class WetterEintrag(
        val zeitpunkt: Long,
        val wetter: Wetter
    )

    private val fundCache = ladePersistentenFundCache()
    private val wetterCache = ladePersistentenWetterCache()

    private var aktuellerLadeSchluessel: String? = null

    private var taxonIds: Map<String, Long> = leseIds()
    private val aufgegeben = HashSet<String>()
    private val idSperre = Mutex()

    private companion object {
        const val FUND_CACHE_DAUER = 3 * 60 * 1000L
        const val PERSISTENTER_CACHE_DAUER = 24 * 60 * 60 * 1000L
        const val WETTER_CACHE_DAUER = 10 * 60 * 1000L

        const val MAX_FUND_CACHE = 10
        const val MAX_WETTER_CACHE = 6

        const val LADE_DEBOUNCE = 450L

        const val FUND_CACHE_PREFS_KEY = "fund_cache"
        const val WETTER_CACHE_PREFS_KEY = "wetter_cache"
    }

    private fun leseIds(): Map<String, Long> {
        val roh = prefs.getString(
            "taxon_ids",
            null
        ) ?: return emptyMap()

        return try {
            val o = JSONObject(roh)
            val m = HashMap<String, Long>()

            for (k in o.keys()) {
                m[k] = o.getLong(k)
            }

            m
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun speichereIds(
        m: Map<String, Long>
    ) {
        val o = JSONObject()

        for ((k, v) in m) {
            o.put(k, v)
        }

        prefs.edit()
            .putString(
                "taxon_ids",
                o.toString()
            )
            .apply()
    }

    private fun aktiveArten(): List<Pilzart> {
        return if (auswahl.isEmpty()) {
            Daten.arten
        } else {
            Daten.arten.filter {
                it.id in auswahl
            }
        }
    }

    private fun rundeWert(
        wert: Double,
        stellen: Double
    ): Double {
        return round(
            wert / stellen
        ) * stellen
    }

    private fun fundCacheSchluessel(
        ids: Map<String, Long>,
        sued: Double,
        nord: Double,
        west: Double,
        ost: Double,
        ab: LocalDate?
    ): String {
        val idsText = ids.entries
            .sortedBy { it.key }
            .joinToString(",") {
                "${it.key}:${it.value}"
            }

        return buildString {
            append(idsText)
            append("|")
            append(zeitraum.name)
            append("|")
            append(ab ?: "ALL")
            append("|")
            append(rundeWert(sued, 0.01))
            append("|")
            append(rundeWert(nord, 0.01))
            append("|")
            append(rundeWert(west, 0.01))
            append("|")
            append(rundeWert(ost, 0.01))
        }
    }

    private fun wetterCacheSchluessel(
        lat: Double,
        lng: Double
    ): String {
        return "${rundeWert(lat, 0.1)}:" +
            rundeWert(lng, 0.1)
    }

    private fun cachedFunde(
        schluessel: String,
        allowStale: Boolean = false
    ): List<Fund>? {
        val eintrag =
            fundCache[schluessel]
                ?: return null

        val alter =
            System.currentTimeMillis() -
                eintrag.zeitpunkt

        if (
            !allowStale &&
            alter > FUND_CACHE_DAUER
        ) {
            return null
        }

        if (
            allowStale &&
            alter > PERSISTENTER_CACHE_DAUER
        ) {
            fundCache.remove(
                schluessel
            )
            return null
        }

        return eintrag.funde
    }

    private fun speichereFunde(
        schluessel: String,
        funde: List<Fund>
    ) {
        fundCache[schluessel] =
            CacheEintrag(
                zeitpunkt =
                    System.currentTimeMillis(),
                funde = funde
            )

        while (
            fundCache.size >
            MAX_FUND_CACHE
        ) {
            fundCache.remove(
                fundCache.keys.first()
            )
        }

        speichereFundCache()
    }

    private fun ladePersistentenFundCache():
        LinkedHashMap<String, CacheEintrag> {
        val cache =
            LinkedHashMap<String, CacheEintrag>()

        val roh = prefs.getString(
            FUND_CACHE_PREFS_KEY,
            null
        ) ?: return cache

        try {
            val array = JSONArray(roh)
            val jetzt =
                System.currentTimeMillis()

            for (i in 0 until array.length()) {
                val eintrag =
                    array.optJSONObject(i)
                        ?: continue

                val schluessel =
                    eintrag.optString(
                        "schluessel"
                    )

                val zeitpunkt =
                    eintrag.optLong(
                        "zeitpunkt",
                        0L
                    )

                val fundArray =
                    eintrag.optJSONArray(
                        "funde"
                    )

                if (
                    schluessel.isBlank() ||
                    zeitpunkt <= 0L ||
                    fundArray == null
                ) {
                    continue
                }

                if (
                    jetzt - zeitpunkt >
                    PERSISTENTER_CACHE_DAUER
                ) {
                    continue
                }

                val funde =
                    mutableListOf<Fund>()

                for (
                    j in 0 until fundArray.length()
                ) {
                    val fundObject =
                        fundArray.optJSONObject(j)
                            ?: continue

                    val id =
                        fundObject.optString(
                            "id"
                        )

                    val artId =
                        fundObject.optString(
                            "artId"
                        )

                    if (
                        id.isBlank() ||
                        artId.isBlank()
                    ) {
                        continue
                    }

                    val datum =
                        if (
                            fundObject.has(
                                "datum"
                            ) &&
                            !fundObject.isNull(
                                "datum"
                            )
                        ) {
                            fundObject.optString(
                                "datum"
                            )
                        } else {
                            null
                        }

                    val fotoUrl =
                        if (
                            fundObject.has(
                                "fotoUrl"
                            ) &&
                            !fundObject.isNull(
                                "fotoUrl"
                            )
                        ) {
                            fundObject.optString(
                                "fotoUrl"
                            )
                        } else {
                            null
                        }

                    val fotoCredit =
                        if (
                            fundObject.has(
                                "fotoCredit"
                            ) &&
                            !fundObject.isNull(
                                "fotoCredit"
                            )
                        ) {
                            fundObject.optString(
                                "fotoCredit"
                            )
                        } else {
                            null
                        }

                    funde += Fund(
                        id = id,
                        artId = artId,
                        lat =
                            fundObject.optDouble(
                                "lat"
                            ),
                        lng =
                            fundObject.optDouble(
                                "lng"
                            ),
                        datum = datum,
                        fotoUrl = fotoUrl,
                        fotoCredit = fotoCredit,
                        bestaetigt =
                            fundObject.optBoolean(
                                "bestaetigt",
                                false
                            ),
                        ungenau =
                            fundObject.optBoolean(
                                "ungenau",
                                false
                            )
                    )
                }

                cache[schluessel] =
                    CacheEintrag(
                        zeitpunkt = zeitpunkt,
                        funde = funde
                    )
            }
        } catch (e: Exception) {
            return LinkedHashMap()
        }

        while (
            cache.size >
            MAX_FUND_CACHE
        ) {
            cache.remove(
                cache.keys.first()
            )
        }

        return cache
    }

    private fun speichereFundCache() {
        try {
            val array = JSONArray()
            val jetzt =
                System.currentTimeMillis()

            for (
                (schluessel, eintrag)
                in fundCache
            ) {
                if (
                    jetzt - eintrag.zeitpunkt >
                    PERSISTENTER_CACHE_DAUER
                ) {
                    continue
                }

                val fundArray =
                    JSONArray()

                for (fund in eintrag.funde) {
                    val o = JSONObject()

                    o.put(
                        "id",
                        fund.id
                    )

                    o.put(
                        "artId",
                        fund.artId
                    )

                    o.put(
                        "lat",
                        fund.lat
                    )

                    o.put(
                        "lng",
                        fund.lng
                    )

                    if (
                        fund.datum != null
                    ) {
                        o.put(
                            "datum",
                            fund.datum
                        )
                    } else {
                        o.put(
                            "datum",
                            JSONObject.NULL
                        )
                    }

                    if (
                        fund.fotoUrl != null
                    ) {
                        o.put(
                            "fotoUrl",
                            fund.fotoUrl
                        )
                    } else {
                        o.put(
                            "fotoUrl",
                            JSONObject.NULL
                        )
                    }

                    if (
                        fund.fotoCredit != null
                    ) {
                        o.put(
                            "fotoCredit",
                            fund.fotoCredit
                        )
                    } else {
                        o.put(
                            "fotoCredit",
                            JSONObject.NULL
                        )
                    }

                    o.put(
                        "bestaetigt",
                        fund.bestaetigt
                    )

                    o.put(
                        "ungenau",
                        fund.ungenau
                    )

                    fundArray.put(o)
                }

                val cacheObject =
                    JSONObject()

                cacheObject.put(
                    "schluessel",
                    schluessel
                )

                cacheObject.put(
                    "zeitpunkt",
                    eintrag.zeitpunkt
                )

                cacheObject.put(
                    "funde",
                    fundArray
                )

                array.put(cacheObject)
            }

            prefs.edit()
                .putString(
                    FUND_CACHE_PREFS_KEY,
                    array.toString()
                )
                .apply()
        } catch (e: Exception) {
        }
    }

    private fun cachedWetter(
        schluessel: String,
        allowStale: Boolean = false
    ): Wetter? {
        val eintrag =
            wetterCache[schluessel]
                ?: return null

        val alter =
            System.currentTimeMillis() -
                eintrag.zeitpunkt

        if (
            !allowStale &&
            alter > WETTER_CACHE_DAUER
        ) {
            return null
        }

        if (
            allowStale &&
            alter > PERSISTENTER_CACHE_DAUER
        ) {
            wetterCache.remove(
                schluessel
            )
            return null
        }

        return eintrag.wetter
    }

    private fun speichereWetter(
        schluessel: String,
        wetter: Wetter
    ) {
        wetterCache[schluessel] =
            WetterEintrag(
                zeitpunkt =
                    System.currentTimeMillis(),
                wetter = wetter
            )

        while (
            wetterCache.size >
            MAX_WETTER_CACHE
        ) {
            wetterCache.remove(
                wetterCache.keys.first()
            )
        }

        speichereWetterCache()
    }

    private fun ladePersistentenWetterCache():
        LinkedHashMap<String, WetterEintrag> {
        val cache =
            LinkedHashMap<String, WetterEintrag>()

        val roh = prefs.getString(
            WETTER_CACHE_PREFS_KEY,
            null
        ) ?: return cache

        try {
            val array = JSONArray(roh)
            val jetzt =
                System.currentTimeMillis()

            for (i in 0 until array.length()) {
                val o =
                    array.optJSONObject(i)
                        ?: continue

                val schluessel =
                    o.optString(
                        "schluessel"
                    )

                val zeitpunkt =
                    o.optLong(
                        "zeitpunkt",
                        0L
                    )

                if (
                    schluessel.isBlank() ||
                    zeitpunkt <= 0L
                ) {
                    continue
                }

                if (
                    jetzt - zeitpunkt >
                    PERSISTENTER_CACHE_DAUER
                ) {
                    continue
                }

                val wetterObject =
                    o.optJSONObject(
                        "wetter"
                    ) ?: continue

                val wetter =
                    Wetter(
                        regen14 =
                            wetterObject.optDouble(
                                "regen14",
                                0.0
                            ),
                        regen7 =
                            wetterObject.optDouble(
                                "regen7",
                                0.0
                            ),
                        regen3 =
                            wetterObject.optDouble(
                                "regen3",
                                0.0
                            ),
                        temp7 =
                            wetterObject.optDouble(
                                "temp7",
                                10.0
                            ),
                        index =
                            wetterObject.optDouble(
                                "index",
                                0.0
                            ).toFloat()
                    )

                cache[schluessel] =
                    WetterEintrag(
                        zeitpunkt = zeitpunkt,
                        wetter = wetter
                    )
            }
        } catch (e: Exception) {
            return LinkedHashMap()
        }

        while (
            cache.size >
            MAX_WETTER_CACHE
        ) {
            cache.remove(
                cache.keys.first()
            )
        }

        return cache
    }

    private fun speichereWetterCache() {
        try {
            val array = JSONArray()
            val jetzt =
                System.currentTimeMillis()

            for (
                (schluessel, eintrag)
                in wetterCache
            ) {
                if (
                    jetzt - eintrag.zeitpunkt >
                    PERSISTENTER_CACHE_DAUER
                ) {
                    continue
                }

                val wetterObject =
                    JSONObject()

                wetterObject.put(
                    "regen14",
                    eintrag.wetter.regen14
                )

                wetterObject.put(
                    "regen7",
                    eintrag.wetter.regen7
                )

                wetterObject.put(
                    "regen3",
                    eintrag.wetter.regen3
                )

                wetterObject.put(
                    "temp7",
                    eintrag.wetter.temp7
                )

                wetterObject.put(
                    "index",
                    eintrag.wetter.index
                )

                val cacheObject =
                    JSONObject()

                cacheObject.put(
                    "schluessel",
                    schluessel
                )

                cacheObject.put(
                    "zeitpunkt",
                    eintrag.zeitpunkt
                )

                cacheObject.put(
                    "wetter",
                    wetterObject
                )

                array.put(cacheObject)
            }

            prefs.edit()
                .putString(
                    WETTER_CACHE_PREFS_KEY,
                    array.toString()
                )
                .apply()
        } catch (e: Exception) {
        }
    }

    fun toggle(id: String) {
        auswahl =
            if (id in auswahl) {
                auswahl - id
            } else {
                auswahl + id
            }

        gewaehlteFundId = null
    }

    fun alleAnzeigen() {
        auswahl = emptySet()
        gewaehlteFundId = null
    }

    fun setzeZeitraum(
        z: Zeitraum
    ) {
        if (zeitraum == z) {
            return
        }

        zeitraum = z
        gewaehlteFundId = null
    }

    fun waehle(
        id: String?
    ) {
        gewaehlteFundId = id
    }

    fun gewaehlterFund(): Fund? {
        return funde.firstOrNull {
            it.id == gewaehlteFundId
        }
    }

    fun ladeFunde(
        sued: Double,
        nord: Double,
        west: Double,
        ost: Double
    ) {
        if (
            nord - sued > 0.8 ||
            ost - west > 1.2
        ) {
            ladeJob?.cancel()

            aktuellerLadeSchluessel = null
            funde = emptyList()
            laedt = false

            meldung =
                "Näher heranzoomen, um Funde zu laden"

            return
        }

        val aktiv =
            aktiveArten()

        val ab =
            zeitraum.tage?.let {
                LocalDate.now()
                    .minusDays(
                        it.toLong()
                    )
            }

        ladeJob?.cancel()

        val nr = ++ladeNr

        ladeJob =
            viewModelScope.launch {
                delay(LADE_DEBOUNCE)

                laedt = true
                meldung = null

                try {
                    idSperre.withLock {
                        val fehlend =
                            aktiv.filter {
                                !taxonIds.containsKey(
                                    it.id
                                ) &&
                                    it.id !in aufgegeben
                            }

                        if (
                            fehlend.isNotEmpty()
                        ) {
                            meldung =
                                "Pilzarten werden beim ersten Start eingerichtet …"

                            val gefunden =
                                Inat.taxonIds(
                                    fehlend
                                )

                            taxonIds =
                                taxonIds + gefunden

                            if (
                                gefunden.isNotEmpty()
                            ) {
                                speichereIds(
                                    taxonIds
                                )
                            }

                            for (a in fehlend) {
                                if (
                                    !gefunden.containsKey(
                                        a.id
                                    )
                                ) {
                                    aufgegeben.add(
                                        a.id
                                    )
                                }
                            }

                            meldung = null
                        }
                    }

                    val ids =
                        HashMap<String, Long>()

                    for (a in aktiv) {
                        val t =
                            taxonIds[a.id]

                        if (t != null) {
                            ids[a.id] = t
                        }
                    }

                    val schluessel =
                        fundCacheSchluessel(
                            ids = ids,
                            sued = sued,
                            nord = nord,
                            west = west,
                            ost = ost,
                            ab = ab
                        )

                    if (
                        schluessel ==
                        aktuellerLadeSchluessel
                    ) {
                        return@launch
                    }

                    aktuellerLadeSchluessel =
                        schluessel

                    val cache =
                        cachedFunde(
                            schluessel
                        )

                    if (
                        cache != null
                    ) {
                        funde = cache
                        gewaehlteFundId = null

                        if (
                            cache.isEmpty()
                        ) {
                            meldung =
                                "Keine Funde in diesem Ausschnitt und Zeitraum"
                        }

                        return@launch
                    }

                    val neu =
                        Inat.funde(
                            ids = ids,
                            sued = sued,
                            nord = nord,
                            west = west,
                            ost = ost,
                            ab = ab
                        )

                    speichereFunde(
                        schluessel,
                        neu
                    )

                    funde = neu
                    gewaehlteFundId = null

                    if (
                        neu.isEmpty()
                    ) {
                        meldung =
                            "Keine Funde in diesem Ausschnitt und Zeitraum"
                    }
                } catch (
                    e: CancellationException
                ) {
                    throw e
                } catch (
                    e: Exception
                ) {
                    val offlineFunde =
                        cachedFunde(
                            schluessel =
                                aktuellerLadeSchluessel
                                    ?: "",
                            allowStale = true
                        )

                    if (
                        offlineFunde != null
                    ) {
                        funde =
                            offlineFunde

                        gewaehlteFundId =
                            null

                        meldung =
                            "Offline: gespeicherte Funde werden angezeigt"
                    } else {
                        funde = emptyList()

                        meldung =
                            "Keine Verbindung · noch keine Funde für diesen Ausschnitt gespeichert"
                    }
                } finally {
                    if (
                        nr == ladeNr
                    ) {
                        laedt = false
                    }
                }
            }
    }

    fun ladeWetter(
        lat: Double,
        lng: Double
    ) {
        val alt = wetterPos

        if (
            alt != null &&
            abs(
                alt.first - lat
            ) < 0.2 &&
            abs(
                alt.second - lng
            ) < 0.3
        ) {
            return
        }

        val schluessel =
            wetterCacheSchluessel(
                lat,
                lng
            )

        val cache =
            cachedWetter(
                schluessel
            )

        if (
            cache != null
        ) {
            wetter = cache
            wetterPos =
                lat to lng
            return
        }

        wetterJob?.cancel()

        wetterPos =
            lat to lng

        wetterJob =
            viewModelScope.launch {
                try {
                    val neu =
                        OpenMeteo.hole(
                            lat,
                            lng
                        )

                    speichereWetter(
                        schluessel,
                        neu
                    )

                    wetter = neu
                } catch (
                    e: CancellationException
                ) {
                    throw e
                } catch (
                    e: Exception
                ) {
                    val offlineWetter =
                        cachedWetter(
                            schluessel =
                                schluessel,
                            allowStale = true
                        )

                    if (
                        offlineWetter != null
                    ) {
                        wetter =
                            offlineWetter
                    } else {
                        wetterPos = null
                    }
                }
            }
    }
}