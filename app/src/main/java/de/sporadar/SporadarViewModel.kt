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
import org.json.JSONObject

enum class Zeitraum(val label: String, val tage: Int?) {
    TAGE14("Letzte 14 Tage", 14),
    MONATE3("Letzte 3 Monate", 90),
    JAHR("Letztes Jahr", 365),
    ALLE("Alle Jahre", null)
}

class SporadarViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("sporadar", Context.MODE_PRIVATE)

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

    private val fundCache = LinkedHashMap<String, CacheEintrag>()
    private val wetterCache = LinkedHashMap<String, WetterEintrag>()

    private var aktuellerLadeSchluessel: String? = null

    private var taxonIds: Map<String, Long> = leseIds()
    private val aufgegeben = HashSet<String>()
    private val idSperre = Mutex()

    private data class CacheEintrag(
        val zeitpunkt: Long,
        val funde: List<Fund>
    )

    private data class WetterEintrag(
        val zeitpunkt: Long,
        val wetter: Wetter
    )

    private companion object {
        const val FUND_CACHE_DAUER = 3 * 60 * 1000L
        const val WETTER_CACHE_DAUER = 10 * 60 * 1000L
        const val MAX_FUND_CACHE = 10
        const val MAX_WETTER_CACHE = 6
        const val LADE_DEBOUNCE = 450L
    }

    private fun leseIds(): Map<String, Long> {
        val roh = prefs.getString("taxon_ids", null) ?: return emptyMap()

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

    private fun speichereIds(m: Map<String, Long>) {
        val o = JSONObject()

        for ((k, v) in m) {
            o.put(k, v)
        }

        prefs.edit()
            .putString("taxon_ids", o.toString())
            .apply()
    }

    private fun aktiveArten(): List<Pilzart> {
        return if (auswahl.isEmpty()) {
            Daten.arten
        } else {
            Daten.arten.filter { it.id in auswahl }
        }
    }

    private fun rundeWert(wert: Double, stellen: Double): Double {
        return round(wert / stellen) * stellen
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
            .joinToString(",") { "${it.key}:${it.value}" }

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

    private fun wetterCacheSchluessel(lat: Double, lng: Double): String {
        return "${rundeWert(lat, 0.1)}:${rundeWert(lng, 0.1)}"
    }

    private fun cachedFunde(schluessel: String): List<Fund>? {
        val eintrag = fundCache[schluessel] ?: return null

        if (System.currentTimeMillis() - eintrag.zeitpunkt > FUND_CACHE_DAUER) {
            fundCache.remove(schluessel)
            return null
        }

        return eintrag.funde
    }

    private fun speichereFunde(schluessel: String, funde: List<Fund>) {
        fundCache[schluessel] = CacheEintrag(
            zeitpunkt = System.currentTimeMillis(),
            funde = funde
        )

        while (fundCache.size > MAX_FUND_CACHE) {
            fundCache.remove(fundCache.keys.first())
        }
    }

    private fun cachedWetter(schluessel: String): Wetter? {
        val eintrag = wetterCache[schluessel] ?: return null

        if (System.currentTimeMillis() - eintrag.zeitpunkt > WETTER_CACHE_DAUER) {
            wetterCache.remove(schluessel)
            return null
        }

        return eintrag.wetter
    }

    private fun speichereWetter(schluessel: String, wetter: Wetter) {
        wetterCache[schluessel] = WetterEintrag(
            zeitpunkt = System.currentTimeMillis(),
            wetter = wetter
        )

        while (wetterCache.size > MAX_WETTER_CACHE) {
            wetterCache.remove(wetterCache.keys.first())
        }
    }

    fun toggle(id: String) {
        auswahl = if (id in auswahl) {
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

    fun setzeZeitraum(z: Zeitraum) {
        if (zeitraum == z) return

        zeitraum = z
        gewaehlteFundId = null
    }

    fun waehle(id: String?) {
        gewaehlteFundId = id
    }

    fun gewaehlterFund(): Fund? {
        return funde.firstOrNull { it.id == gewaehlteFundId }
    }

    fun ladeFunde(
        sued: Double,
        nord: Double,
        west: Double,
        ost: Double
    ) {
        if (nord - sued > 0.8 || ost - west > 1.2) {
            ladeJob?.cancel()
            aktuellerLadeSchluessel = null
            funde = emptyList()
            laedt = false
            meldung = "Näher heranzoomen, um Funde zu laden"
            return
        }

        val aktiv = aktiveArten()
        val ab = zeitraum.tage?.let {
            LocalDate.now().minusDays(it.toLong())
        }

        ladeJob?.cancel()

        val nr = ++ladeNr

        ladeJob = viewModelScope.launch {
            delay(LADE_DEBOUNCE)

            laedt = true
            meldung = null

            try {
                idSperre.withLock {
                    val fehlend = aktiv.filter {
                        !taxonIds.containsKey(it.id) &&
                            it.id !in aufgegeben
                    }

                    if (fehlend.isNotEmpty()) {
                        meldung = "Pilzarten werden beim ersten Start eingerichtet …"

                        val gefunden = Inat.taxonIds(fehlend)

                        taxonIds = taxonIds + gefunden

                        if (gefunden.isNotEmpty()) {
                            speichereIds(taxonIds)
                        }

                        for (a in fehlend) {
                            if (!gefunden.containsKey(a.id)) {
                                aufgegeben.add(a.id)
                            }
                        }

                        meldung = null
                    }
                }

                val ids = HashMap<String, Long>()

                for (a in aktiv) {
                    val t = taxonIds[a.id]

                    if (t != null) {
                        ids[a.id] = t
                    }
                }

                val schluessel = fundCacheSchluessel(
                    ids = ids,
                    sued = sued,
                    nord = nord,
                    west = west,
                    ost = ost,
                    ab = ab
                )

                if (schluessel == aktuellerLadeSchluessel) {
                    return@launch
                }

                aktuellerLadeSchluessel = schluessel

                val cache = cachedFunde(schluessel)

                if (cache != null) {
                    funde = cache
                    gewaehlteFundId = null

                    if (cache.isEmpty()) {
                        meldung = "Keine Funde in diesem Ausschnitt und Zeitraum"
                    }

                    return@launch
                }

                val neu = Inat.funde(
                    ids = ids,
                    sued = sued,
                    nord = nord,
                    west = west,
                    ost = ost,
                    ab = ab
                )

                speichereFunde(schluessel, neu)

                funde = neu
                gewaehlteFundId = null

                if (neu.isEmpty()) {
                    meldung = "Keine Funde in diesem Ausschnitt und Zeitraum"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                meldung = "Funddaten konnten nicht geladen werden"
                aktuellerLadeSchluessel = null
            } finally {
                if (nr == ladeNr) {
                    laedt = false
                }
            }
        }
    }

    fun ladeWetter(lat: Double, lng: Double) {
        val alt = wetterPos

        if (
            alt != null &&
            abs(alt.first - lat) < 0.2 &&
            abs(alt.second - lng) < 0.3
        ) {
            return
        }

        val schluessel = wetterCacheSchluessel(lat, lng)
        val cache = cachedWetter(schluessel)

        if (cache != null) {
            wetter = cache
            wetterPos = lat to lng
            return
        }

        wetterJob?.cancel()
        wetterPos = lat to lng

        wetterJob = viewModelScope.launch {
            try {
                val neu = OpenMeteo.hole(lat, lng)

                speichereWetter(schluessel, neu)
                wetter = neu
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                wetterPos = null
            }
        }
    }
}
