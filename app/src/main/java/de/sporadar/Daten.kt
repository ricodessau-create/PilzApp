package de.sporadar

data class Pilzart(
    val id: String,
    val name: String,
    val farbe: Long,
    val baeume: List<String>,
    val saison: String
)

data class Fund(val id: String, val artId: String, val lat: Double, val lng: Double)

object Daten {
    // Beispielwerte - werden später über GBIF/Wikipedia/Forstdaten erweitert
    val arten = listOf(
        Pilzart("steinpilz", "Steinpilz", 0xFFFFB020, listOf("Fichte", "Buche", "Eiche", "Kiefer"), "Jun – Okt"),
        Pilzart("maronenroehrling", "Maronenröhrling", 0xFFD98A4E, listOf("Kiefer", "Fichte"), "Jul – Nov"),
        Pilzart("birkenpilz", "Birkenpilz", 0xFFE8E2C6, listOf("Birke"), "Jun – Okt"),
        Pilzart("pfifferling", "Pfifferling", 0xFFFFD23F, listOf("Buche", "Fichte", "Eiche"), "Jun – Okt"),
        Pilzart("butterpilz", "Butterpilz", 0xFFB9E36B, listOf("Kiefer"), "Jul – Okt"),
    )

    // Nur Testdaten zum Ausprobieren von Karte und Filter
    val testFunde = listOf(
        Fund("1", "steinpilz", 51.80, 12.20),
        Fund("2", "maronenroehrling", 51.81, 12.22),
        Fund("3", "birkenpilz", 51.79, 12.18),
        Fund("4", "pfifferling", 51.82, 12.19),
        Fund("5", "butterpilz", 51.78, 12.23),
        Fund("6", "steinpilz", 51.83, 12.24),
    )

    fun art(id: String) = arten.first { it.id == id }
}
