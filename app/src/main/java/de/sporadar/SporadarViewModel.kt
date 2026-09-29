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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class Zeitraum(val label: String, val tage: Int?) {
    TAGE14("Letzte 14 Tage", 14),
    MONATE3("Letzte 3 Monate", 90),
    JAHR("Letztes Jahr", 365),
    ALLE("Alle Jahre", null)
}

class SporadarViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("sporadar", Context.MODE_PRIVATE)

    // Mehrfachauswahl: leer = alle Arten
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
    private var ladeNr = 0
    private var wetterPos: Pair<Double, Double>? = null

    // Pilzart-ID -> iNaturalist-Taxon-ID (wird einmal ermittelt und gespeichert)
    private var taxonIds: Map<String, Long> = leseIds()
    private val aufgegeben = HashSet<String>()
    private val idSperre = Mutex()

    private fun leseIds(): Map<String, Long> {
        val roh = prefs.getString("taxon_ids", null) ?: return emptyMap()
        return try {
            val o = JSONObject(roh)
            val m = HashMap<String, Long>()
            for (k in o.keys()) m[k] = o.getLong(k)
            m
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun speichereIds(m: Map<String, Long>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        prefs.edit().putString("taxon_ids", o.toString()).apply()
    }

    private fun aktiveArten(): List<Pilzart> =
        if (auswahl.isEmpty()) Daten.arten else Daten.arten.filter { it.id in auswahl }

    fun toggle(id: String) {
        auswahl = if (id in auswahl) auswahl - id else auswahl + id
        gewaehlteFundId = null
    }

    fun alleAnzeigen() {
        auswahl = emptySet()
        gewaehlteFundId = null
    }

    fun setzeZeitraum(z: Zeitraum) {
        zeitraum = z
        gewaehlteFundId = null
    }

    fun waehle(id: String?) {
        gewaehlteFundId = id
    }

    fun gewaehlterFund(): Fund? = funde.firstOrNull { it.id == gewaehlteFundId }

    fun ladeFunde(sued: Double, nord: Double, west: Double, ost: Double) {
        if (nord - sued > 0.8 || ost - west > 1.2) {
            ladeJob?.cancel()
            funde = emptyList()
            laedt = false
            meldung = "Näher heranzoomen, um Funde zu laden"
            return
        }
        ladeJob?.cancel()
        val nr = ++ladeNr
        val aktiv = aktiveArten()
        val ab: LocalDate? = zeitraum.tage?.let { LocalDate.now().minusDays(it.toLong()) }
        ladeJob = viewModelScope.launch {
            laedt = true
            meldung = null
            try {
                // Beim allerersten Start: iNaturalist-IDs der Pilzarten einmalig ermitteln
                idSperre.withLock {
                    val fehlend = aktiv.filter { !taxonIds.containsKey(it.id) && it.id !in aufgegeben }
                    if (fehlend.isNotEmpty()) {
                        meldung = "Pilzarten werden beim ersten Start eingerichtet …"
                        withContext(NonCancellable) {
                            val gefunden = Inat.taxonIds(fehlend)
                            taxonIds = taxonIds + gefunden
                            if (gefunden.isNotEmpty()) speichereIds(taxonIds)
                            for (a in fehlend) {
                                if (!gefunden.containsKey(a.id)) aufgegeben.add(a.id)
                            }
                        }
                        meldung = null
                    }
                }

                val ids = HashMap<String, Long>()
                for (a in aktiv) {
                    val t = taxonIds[a.id]
                    if (t != null) ids[a.id] = t
                }

                val neu = Inat.funde(ids, sued, nord, west, ost, ab)
                funde = neu
                gewaehlteFundId = null
                if (neu.isEmpty()) meldung = "Keine Funde in diesem Ausschnitt und Zeitraum"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                meldung = "Funddaten konnten nicht geladen werden"
            } finally {
                if (nr == ladeNr) laedt = false
            }
        }
    }

    fun ladeWetter(lat: Double, lng: Double) {
        val alt = wetterPos
        if (alt != null && abs(alt.first - lat) < 0.2 && abs(alt.second - lng) < 0.3) return
        wetterPos = lat to lng
        viewModelScope.launch {
            try {
                wetter = OpenMeteo.hole(lat, lng)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                wetterPos = null
            }
        }
    }
}

// ENDE
