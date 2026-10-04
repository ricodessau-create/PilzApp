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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun KopfBereich(
    auswahl: Set<String>,
    zeitraum: Zeitraum,
    onToggle: (String) -> Unit,
    onAlle: () -> Unit,
    onZeitraum: (Zeitraum) -> Unit,
    modifier: Modifier = Modifier
) {
    var suche by remember {
        mutableStateOf("")
    }

    val suchtext = suche
        .trim()
        .lowercase(Locale.GERMANY)

    val gefilterteArten = remember(suchtext) {
        if (suchtext.isBlank()) {
            Daten.arten
        } else {
            Daten.arten.filter { art ->
                art.name.lowercase(Locale.GERMANY).contains(suchtext) ||
                    art.lateinisch.lowercase(Locale.GERMANY).contains(suchtext) ||
                    art.id.lowercase(Locale.GERMANY).contains(suchtext)
            }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Farben.Wald.copy(alpha = 0.96f),
                        Color.Transparent
                    )
                )
            )
            .statusBarsPadding()
            .padding(
                top = 8.dp,
                bottom = 28.dp
            )
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "🍄",
                fontSize = 30.sp
            )

            Spacer(
                Modifier.width(10.dp)
            )

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

        Spacer(
            Modifier.height(12.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(
                    RoundedCornerShape(18.dp)
                )
                .background(Farben.Karte)
                .padding(
                    horizontal = 16.dp,
                    vertical = 11.dp
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🔎",
                    fontSize = 17.sp
                )

                Spacer(
                    Modifier.width(10.dp)
                )

                BasicTextField(
                    value = suche,
                    onValueChange = {
                        suche = it
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Farben.Schrift,
                        fontSize = 14.sp
                    ),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        if (suche.isBlank()) {
                            Text(
                                "Pilz suchen …",
                                color = Farben.SchriftGedimmt,
                                fontSize = 14.sp
                            )
                        }

                        innerTextField()
                    }
                )

                if (suche.isNotBlank()) {
                    Spacer(
                        Modifier.width(8.dp)
                    )

                    Text(
                        "✕",
                        color = Farben.SchriftGedimmt,
                        fontSize = 17.sp
                    )
                }
            }
        }

        if (suchtext.isNotBlank()) {
            Spacer(
                Modifier.height(5.dp)
            )

            Text(
                if (gefilterteArten.isEmpty()) {
                    "Keine passende Pilzart gefunden"
                } else {
                    "${gefilterteArten.size} passende Pilzarten"
                },
                color = Farben.SchriftGedimmt,
                fontSize = 11.sp,
                modifier = Modifier.padding(
                    horizontal = 20.dp
                )
            )
        }

        Spacer(
            Modifier.height(8.dp)
        )

        Row(
            Modifier
                .horizontalScroll(
                    rememberScrollState()
                )
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = auswahl.isEmpty(),
                onClick = onAlle,
                label = {
                    Text("Alle")
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Farben.Karte,
                    labelColor = Farben.Schrift,
                    selectedContainerColor = Farben.Moos,
                    selectedLabelColor = Farben.Wald
                )
            )

            gefilterteArten.forEach { art ->
                FilterChip(
                    selected = art.id in auswahl,
                    onClick = {
                        onToggle(art.id)
                    },
                    label = {
                        Text(art.name)
                    },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    Color(art.farbe)
                                )
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Farben.Karte,
                        labelColor = Farben.Schrift,
                        selectedContainerColor = Color(art.farbe)
                            .copy(alpha = 0.4f),
                        selectedLabelColor = Farben.Schrift
                    )
                )
            }
        }

        Spacer(
            Modifier.height(6.dp)
        )

        Row(
            Modifier
                .horizontalScroll(
                    rememberScrollState()
                )
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Zeitraum.values().forEach { z ->
                FilterChip(
                    selected = z == zeitraum,
                    onClick = {
                        onZeitraum(z)
                    },
                    label = {
                        Text(z.label)
                    },
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
