package de.sporadar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class SporadarViewModel : ViewModel() {
    // Mehrfachauswahl: leer = alle Arten anzeigen
    var auswahl by mutableStateOf(setOf<String>())
        private set

    var gewaehlteFundId by mutableStateOf<String?>(null)
        private set

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

    fun sichtbareFunde(): List<Fund> =
        if (auswahl.isEmpty()) Daten.testFunde
        else Daten.testFunde.filter { it.artId in auswahl }

    fun gewaehlterFund(): Fund? =
        Daten.testFunde.firstOrNull { it.id == gewaehlteFundId }
}
