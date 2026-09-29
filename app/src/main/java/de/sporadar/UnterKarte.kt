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
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun UnterKarte(
    anzahl: Int,
    laedt: Boolean,
    meldung: String?,
    wetter: Wetter?,
    fund: Fund?,
    onSchliessen: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = Farben.Karte.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, Farben.Moos.copy(alpha = 0.25f))
    ) {
        Column(
            Modifier
                .animateContentSize()
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            if (fund == null) {
                UebersichtInhalt(anzahl, laedt, meldung, wetter)
            } else {
                FundInhalt(fund, onSchliessen)
            }
        }
    }
}

@Composable
fun UebersichtInhalt(
    anzahl: Int,
    laedt: Boolean,
    meldung: String?,
    wetter: Wetter?
) {
    val titel = if (laedt) "Suche Funde …" else "$anzahl Funde"
    val untertitel = meldung ?: "im Kartenausschnitt · essbare Pilze"

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
        Spacer(Modifier.height(16.dp))

        if (wetter != null) {
            val prozent = (wetter.index * 100).roundToInt()
            val regenMm = wetter.regen14.roundToInt()
            val tempText = String.format("%.1f", wetter.temp7)
            val detail = "Regen (14 Tage): $regenMm mm · Ø Temperatur (7 Tage): $tempText °C"

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Wetter-Index", color = Farben.Schrift, fontSize = 14.sp)
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
            Text(detail, color = Farben.SchriftGedimmt, fontSize = 12.sp)
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
            "Daten: GBIF · Wetter: Open-Meteo",
            color = Farben.SchriftGedimmt,
            fontSize = 10.sp
        )
    }
}

@Composable
fun FundInhalt(fund: Fund, onSchliessen: () -> Unit) {
    val art = Daten.art(fund.artId)
    val jetztSaison = art.istSaison(LocalDate.now().monthValue)

    val teile = fund.datum?.split("-")
    val datum = if (teile != null && teile.size == 3) {
        teile[2] + "." + teile[1] + "." + teile[0]
    } else {
        fund.datum
    }
    val saisonText = if (jetztSaison) {
        "Saison: " + art.saison + " · jetzt Saison ✅"
    } else {
        "Saison: " + art.saison
    }

    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
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
                    .clickable { onSchliessen() }
                    .padding(8.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            saisonText,
            color = if (jetztSaison) Farben.Moos else Farben.SchriftGedimmt,
            fontSize = 13.sp
        )
        if (datum != null) {
            Text(
                "Fund gemeldet am $datum",
                color = Farben.SchriftGedimmt,
                fontSize = 13.sp
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
        Text("Umfeld", color = Farben.SchriftGedimmt, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            art.umfeld.forEach { u ->
                Surface(shape = RoundedCornerShape(50), color = Farben.Wald) {
                    Text(
                        "🌲 " + u,
                        color = Farben.Schrift,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(art.hinweis, color = Farben.Schrift, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "⚠️ Nie ohne Prüfung durch einen Pilzsachverständigen essen.",
            color = Farben.Amber,
            fontSize = 11.sp
        )
    }
}

// ENDE
