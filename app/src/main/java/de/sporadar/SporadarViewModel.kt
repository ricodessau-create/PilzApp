package de.sporadar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SporadarViewModel : ViewModel() {
    // Mehrfachauswahl: leer = alle Arten
    var auswahl by mutableStateOf(setOf<String>())
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
        ladeJob = viewModelScope.launch {
            laedt = true
            meldung = null
            try {
                val neu = Gbif.funde(aktiveArten(), sued, nord, west, ost)
                funde = neu
                gewaehlteFundId = null
                if (neu.isEmpty()) meldung = "Keine Funde in diesem Ausschnitt"
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
