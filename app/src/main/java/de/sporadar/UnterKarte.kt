package de.sporadar

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.android.gms.maps.model.LatLng
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

fun alterText(datum: String?): String? {
    if (datum == null) return null

    return try {
        val tage = ChronoUnit.DAYS.between(
            LocalDate.parse(datum.take(10)),
            LocalDate.now()
        )

        if (tage < 1) {
            "heute"
        } else if (tage == 1L) {
            "gestern"
        } else if (tage < 31) {
            "vor $tage Tagen"
        } else if (tage < 60) {
            "vor 1 Monat"
        } else if (tage < 365) {
            "vor " + (tage / 30) + " Monaten"
        } else if (tage < 730) {
            "vor 1 Jahr"
        } else {
            "vor " + (tage / 365) + " Jahren"
        }
    } catch (e: Exception) {
        null
    }
}

private fun entfernungInMetern(
    start: LatLng,
    ziel: LatLng
): Double {
    val erdradius = 6371000.0

    val lat1 = Math.toRadians(start.latitude)
    val lat2 = Math.toRadians(ziel.latitude)
    val deltaLat = Math.toRadians(ziel.latitude - start.latitude)
    val deltaLng = Math.toRadians(ziel.longitude - start.longitude)

    val a =
        sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
            cos(lat1) * cos(lat2) *
            sin(deltaLng / 2.0) * sin(deltaLng / 2.0)

    val c = 2.0 * atan2(
        sqrt(a),
        sqrt(1.0 - a)
    )

    return erdradius * c
}

private fun entfernungText(
    standort: LatLng,
    fund: Fund
): String {
    val ziel = LatLng(
        fund.lat,
        fund.lng
    )

    val meter = entfernungInMetern(
        start = standort,
        ziel = ziel
    )

    return when {
        meter < 1000.0 -> {
            "${meter.roundToInt()} m entfernt"
        }

        meter < 10000.0 -> {
            String.format(
                "%.1f km entfernt",
                meter / 1000.0
            )
        }

        else -> {
            "${(meter / 1000.0).roundToInt()} km entfernt"
        }
    }
}

@Composable
fun UnterKarte(
    anzahl: Int,
    laedt: Boolean,
    meldung: String?,
    standortHinweis: String?,
    wetter: Wetter?,
    fund: Fund?,
    standort: LatLng?,
    onSchliessen: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = 12.dp,
                end = 12.dp,
                bottom = 12.dp
            ),
        shape = RoundedCornerShape(28.dp),
        color = Farben.Karte.copy(alpha = 0.95f),
        border = BorderStroke(
            1.dp,
            Farben.Moos.copy(alpha = 0.25f)
        )
    ) {
        Column(
            Modifier
                .animateContentSize()
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            if (fund == null) {
                UebersichtInhalt(
                    anzahl = anzahl,
                    laedt = laedt,
                    meldung = meldung,
                    standortHinweis = standortHinweis,
                    wetter = wetter
                )
            } else {
                FundInhalt(
                    fund = fund,
                    standort = standort,
                    onSchliessen = onSchliessen
                )
            }
        }
    }
}

@Composable
fun UebersichtInhalt(
    anzahl: Int,
    laedt: Boolean,
    meldung: String?,
    standortHinweis: String?,
    wetter: Wetter?
) {
    val titel = if (laedt) {
        "Suche Funde …"
    } else {
        "$anzahl Funde"
    }

    val untertitel = meldung
        ?: "im Kartenausschnitt · essbare Pilze"

    Column {
        Text(
            titel,
            color = Farben.Schrift,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp
        )

        Text(
            untertitel,
            color = Farben.SchriftGedimmt,
            fontSize = 13.sp
        )

        if (standortHinweis != null) {
            Spacer(Modifier.height(6.dp))

            Text(
                standortHinweis,
                color = Farben.Amber,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        if (wetter != null) {
            val prozent = (wetter.index * 100).roundToInt()
            val regenMm = wetter.regen14.roundToInt()
            val tempText = String.format(
                "%.1f",
                wetter.temp7
            )

            val detail =
                "Regen (14 Tage): $regenMm mm · Ø Temperatur (7 Tage): $tempText °C"

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Wetter-Index",
                    color = Farben.Schrift,
                    fontSize = 14.sp
                )

                Text(
                    "$prozent %",
                    color = Farben.Moos,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { wetter.index },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Farben.Moos,
                trackColor = Farben.Wald
            )

            Spacer(Modifier.height(8.dp))

            Text(
                detail,
                color = Farben.SchriftGedimmt,
                fontSize = 12.sp
            )

            Text(
                "Schätzwert aus Regen und Temperatur, kein Fundgarant.",
                color = Farben.SchriftGedimmt,
                fontSize = 11.sp
            )
        } else {
            Text(
                "Wetter wird geladen …",
                color = Farben.SchriftGedimmt,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            "Funde: iNaturalist (Community-Beobachtungen) · Wetter: Open-Meteo",
            color = Farben.SchriftGedimmt,
            fontSize = 10.sp
        )
    }
}

@Composable
fun FundInhalt(
    fund: Fund,
    standort: LatLng?,
    onSchliessen: () -> Unit
) {
    val art = Daten.art(fund.artId)
    val jetztSaison = art.istSaison(
        LocalDate.now().monthValue
    )

    val teile = fund.datum
        ?.take(10)
        ?.split("-")

    val datum = if (
        teile != null &&
        teile.size == 3
    ) {
        teile[2] + "." +
            teile[1] + "." +
            teile[0]
    } else {
        fund.datum
    }

    val alter = alterText(fund.datum)

    val datumZeile = if (
        datum != null &&
        alter != null
    ) {
        "Beobachtet am $datum ($alter)"
    } else if (datum != null) {
        "Beobachtet am $datum"
    } else {
        null
    }

    val saisonText = if (jetztSaison) {
        "Saison: " + art.saison + " · jetzt Saison ✅"
    } else {
        "Saison: " + art.saison
    }

    val statusText = if (fund.bestaetigt) {
        "✅ Bestimmung von der Community bestätigt"
    } else {
        "⚠️ Bestimmung noch nicht bestätigt, kann falsch sein"
    }

    val distanz = standort?.let {
        entfernungText(
            standort = it,
            fund = fund
        )
    }

    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                Modifier.weight(1f)
            ) {
                Text(
                    art.name,
                    color = Color(art.farbe),
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )

                Text(
                    art.lateinisch,
                    color = Farben.SchriftGedimmt,
                    fontStyle = FontStyle.Italic,
                    fontSize = 13.sp
                )
            }

            Text(
                "✕",
                color = Farben.SchriftGedimmt,
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable {
                        onSchliessen()
                    }
                    .padding(8.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            saisonText,
            color = if (jetztSaison) {
                Farben.Moos
            } else {
                Farben.SchriftGedimmt
            },
            fontSize = 13.sp
        )

        if (datumZeile != null) {
            Text(
                datumZeile,
                color = Farben.SchriftGedimmt,
                fontSize = 13.sp
            )
        }

        if (distanz != null) {
            Text(
                "📍 $distanz",
                color = Farben.Moos,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        Text(
            statusText,
            color = if (fund.bestaetigt) {
                Farben.Moos
            } else {
                Farben.Amber
            },
            fontSize = 12.sp
        )

        if (fund.ungenau) {
            Text(
                "📍 Ort ist vom Melder absichtlich ungenau",
                color = Farben.SchriftGedimmt,
                fontSize = 12.sp
            )
        }

        if (fund.fotoUrl != null) {
            Spacer(Modifier.height(12.dp))

            AsyncImage(
                model = fund.fotoUrl,
                contentDescription = art.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(16.dp))
            )

            if (fund.fotoCredit != null) {
                Text(
                    "Foto: " + fund.fotoCredit,
                    color = Farben.SchriftGedimmt,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "Umfeld",
            color = Farben.SchriftGedimmt,
            fontSize = 11.sp
        )

        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.horizontalScroll(
                rememberScrollState()
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            art.umfeld.forEach { u ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Farben.Wald
                ) {
                    Text(
                        "🌲 " + u,
                        color = Farben.Schrift,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 6.dp
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            art.hinweis,
            color = Farben.Schrift,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "⚠️ Nie ohne Prüfung durch einen Pilzsachverständigen essen.",
            color = Farben.Amber,
            fontSize = 11.sp
        )
    }
}
