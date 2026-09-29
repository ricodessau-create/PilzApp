package de.sporadar

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun KopfBereich(
    auswahl: Set<String>,
    zeitraum: Zeitraum,
    onToggle: (String) -> Unit,
    onAlle: () -> Unit,
    onZeitraum: (Zeitraum) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Farben.Wald.copy(alpha = 0.96f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .padding(top = 8.dp, bottom = 28.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🍄", fontSize = 30.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "SPORADAR",
                    color = Farben.Moos,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    letterSpacing = 4.sp
                )
                Text(
                    "Finde. Jage. Sammle.",
                    color = Farben.SchriftGedimmt,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Pilzarten (Mehrfachauswahl)
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = auswahl.isEmpty(),
                onClick = onAlle,
                label = { Text("Alle") },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Farben.Karte,
                    labelColor = Farben.Schrift,
                    selectedContainerColor = Farben.Moos,
                    selectedLabelColor = Farben.Wald
                )
            )
            Daten.arten.forEach { art ->
                FilterChip(
                    selected = art.id in auswahl,
                    onClick = { onToggle(art.id) },
                    label = { Text(art.name) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(art.farbe))
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Farben.Karte,
                        labelColor = Farben.Schrift,
                        selectedContainerColor = Color(art.farbe).copy(alpha = 0.4f),
                        selectedLabelColor = Farben.Schrift
                    )
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Zeitraum (Einzelauswahl)
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Zeitraum.values().forEach { z ->
                FilterChip(
                    selected = z == zeitraum,
                    onClick = { onZeitraum(z) },
                    label = { Text(z.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Farben.Karte,
                        labelColor = Farben.SchriftGedimmt,
                        selectedContainerColor = Farben.Amber,
                        selectedLabelColor = Farben.Wald
                    )
                )
            }
        }
    }
}

// ENDE
