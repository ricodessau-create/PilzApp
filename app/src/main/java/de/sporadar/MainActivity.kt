package de.sporadar

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent { SporadarTheme { SporadarScreen() } }
    }
}

// Runder Marker in der Farbe der Pilzart
fun markerIcon(farbe: Long, gewaehlt: Boolean): BitmapDescriptor {
    val px = if (gewaehlt) 88 else 60
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(px / 2f, px / 2f, px / 2f, p)
    p.color = farbe.toInt()
    c.drawCircle(px / 2f, px / 2f, px / 2f - 7f, p)
    return BitmapDescriptorFactory.fromBitmap(bmp)
}

@SuppressLint("MissingPermission")
@Composable
fun SporadarScreen(vm: SporadarViewModel = viewModel()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var hatStandort by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var standort by remember { mutableStateOf<LatLng?>(null) }
    var standortHinweis by remember { mutableStateOf<String?>(null) }
    var kartenBereit by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        hatStandort = res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    val rechte = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    LaunchedEffect(Unit) {
        if (!hatStandort) launcher.launch(rechte)
    }

    // Startansicht: ganz Deutschland, bis der Standort da ist
    val kamera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(51.16, 10.45), 6f)
    }

    // Standort automatisch suchen, sobald die Freigabe da ist (bis zu 4 Versuche)
    LaunchedEffect(hatStandort) {
        if (hatStandort) {
            standortHinweis = "Standort wird gesucht …"
            var p: LatLng? = null
            var versuch = 0
            while (p == null && versuch < 4) {
                p = holeStandort(ctx)
                if (p == null) {
                    versuch++
                    delay(2000)
                }
            }
            if (p != null) {
                standort = p
                standortHinweis = null
                kamera.move(CameraUpdateFactory.newLatLngZoom(p, 12f))
            } else {
                standortHinweis = "Standort nicht gefunden. Schalte den Standort am Handy ein und tippe auf 📍."
            }
        } else {
            standortHinweis = "Standortfreigabe fehlt. Tippe auf 📍, um sie zu erteilen."
        }
    }

    // Funde und Wetter laden, sobald die Karte stillsteht
    LaunchedEffect(kamera.isMoving, vm.auswahl, vm.zeitraum, kartenBereit) {
        if (kartenBereit && !kamera.isMoving) {
            delay(500)
            val b = kamera.projection?.visibleRegion?.latLngBounds
            if (b != null) {
                vm.ladeFunde(
                    b.southwest.latitude,
                    b.northeast.latitude,
                    b.southwest.longitude,
                    b.northeast.longitude
                )
                val ziel = kamera.position.target
                vm.ladeWetter(ziel.latitude, ziel.longitude)
            }
        }
    }

    val gewaehlt = vm.gewaehlterFund()

    Box(Modifier.fillMaxSize().background(Farben.Wald)) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = kamera,
            properties = MapProperties(
                isMyLocationEnabled = hatStandort,
                mapStyleOptions = MapStyleOptions(KartenStil)
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false,
                compassEnabled = false
            ),
            contentPadding = PaddingValues(top = 200.dp, bottom = 230.dp),
            onMapLoaded = { kartenBereit = true },
            onMapClick = { vm.waehle(null) }
        ) {
            val icons = remember { HashMap<String, BitmapDescriptor>() }
            vm.funde.forEach { f ->
                key(f.id) {
                    val art = Daten.art(f.artId)
                    val istGewaehlt = gewaehlt?.id == f.id
                    val icon = icons.getOrPut(art.id + "_" + istGewaehlt) {
                        markerIcon(art.farbe, istGewaehlt)
                    }
                    val markerState = remember { MarkerState(LatLng(f.lat, f.lng)) }
                    Marker(
                        state = markerState,
                        icon = icon,
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = if (istGewaehlt) 1f else 0f,
                        onClick = {
                            vm.waehle(f.id)
                            true
                        }
                    )
                }
            }
        }

        KopfBereich(
            auswahl = vm.auswahl,
            zeitraum = vm.zeitraum,
            onToggle = { id -> vm.toggle(id) },
            onAlle = { vm.alleAnzeigen() },
            onZeitraum = { z -> vm.setzeZeitraum(z) },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        Column(Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().padding(end = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Farben.Moos)
                        .clickable {
                            if (!hatStandort) {
                                launcher.launch(rechte)
                            } else {
                                scope.launch {
                                    val p = holeStandort(ctx)
                                    if (p != null) {
                                        standort = p
                                        standortHinweis = null
                                        kamera.animate(CameraUpdateFactory.newLatLngZoom(p, 13f), 800)
                                    } else {
                                        standortHinweis = "Standort nicht gefunden. Schalte den Standort am Handy ein."
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("📍", fontSize = 22.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            UnterKarte(
                anzahl = vm.funde.size,
                laedt = vm.laedt,
                meldung = vm.meldung,
                standortHinweis = standortHinweis,
                wetter = vm.wetter,
                fund = gewaehlt,
                onSchliessen = { vm.waehle(null) }
            )
        }
    }
}

// ENDE
