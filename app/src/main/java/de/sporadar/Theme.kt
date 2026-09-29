package de.sporadar

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Farben {
    val Wald = Color(0xFF0B1410)
    val Karte = Color(0xFF13221B)
    val Moos = Color(0xFF7CFF6B)
    val Amber = Color(0xFFFFB020)
    val Schrift = Color(0xFFE8F5E9)
    val SchriftGedimmt = Color(0xFF8FB39C)
}

@Composable
fun SporadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Farben.Moos,
            onPrimary = Farben.Wald,
            secondary = Farben.Amber,
            background = Farben.Wald,
            surface = Farben.Karte,
            onSurface = Farben.Schrift
        ),
        content = content
    )
}

// Dunkler Waldlook für die Google-Karte
val KartenStil = """
[
  {"elementType":"geometry","stylers":[{"color":"#0d1a14"}]},
  {"elementType":"labels.text.fill","stylers":[{"color":"#7fa08c"}]},
  {"elementType":"labels.text.stroke","stylers":[{"color":"#0b1410"}]},
  {"featureType":"poi","elementType":"labels","stylers":[{"visibility":"off"}]},
  {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#123524"}]},
  {"featureType":"landscape.natural","elementType":"geometry","stylers":[{"color":"#10281c"}]},
  {"featureType":"road","elementType":"geometry","stylers":[{"color":"#1c2b24"}]},
  {"featureType":"road","elementType":"labels","stylers":[{"visibility":"off"}]},
  {"featureType":"transit","stylers":[{"visibility":"off"}]},
  {"featureType":"water","elementType":"geometry","stylers":[{"color":"#07211f"}]}
]
"""
