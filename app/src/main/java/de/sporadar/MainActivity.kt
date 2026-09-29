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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
import java.time.LocalDate
import kotlin.math.roundToInt
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
                PackageManager.PERMISSION_GRANTED
        )
    }
    var standort by remember { mutableStateOf<LatLng?>(null) }
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
    LaunchedEffect(Unit) { if (!hatStandort) launcher.launch(rechte) }

    // Startansicht: ganz Deutschland, bis der Standort da ist
    val kamera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(51.16, 10.45), 6f)
    }
    LaunchedEffect(hatStandort) {
        if (hatStandort) {
            LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    loc?.let {
                        val p = LatLng(it.latitude, it.longitude)
                        standort = p
                        kamera.move(CameraUpdateFactory.newLatLngZoom(p, 12f))
                    }
                }
        }
    }

    // Funde und Wetter laden, sobald die Karte stillsteht
    LaunchedEffect(kamera.isMoving, vm.auswahl, kartenBereit) {
        if (kartenBereit && !kamera.isMoving) {
            delay(500)
            val b = kamera.projection?.visibleRegion?.latLngBounds
            if (b != null) {
                vm.ladeFunde(
                    b.southwest.latitude, b.northeast.latitude,
                    b.southwest.longitude, b.northeast.longitude
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
            contentPadding = PaddingValues(top = 150.dp, bottom = 230.dp),
            onMapLoaded = { kartenBereit = true },
            onMapClick = { vm.waehle(null) }
        ) {
            val icons = remember { HashMap<String, BitmapDescriptor>() }
            vm.funde.forEach { f ->
                key(f.id) {
                    val art = Daten.art(f.artId)
                    val istGewaehlt = gewaehlt?.id == f.id
                    val icon = icons.getOrPut("${art.id}_$istGewaehlt") {
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
            onToggle = vm::toggle,
            onAlle = vm::alleAnzeigen,
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
                            val p = standort
                            if (p != null) {
                                scope.launch {
                                    kamera.animate(CameraUpdateFactory.newLatLngZoom(p, 13f), 800)
                                }
                            } else if (!hatStandort) {
                                launcher.launch(rechte)
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
                wetter = vm.wetter,
                fund = gewaehlt,
                onSchliessen = { vm.waehle(null) }
            )
        }
    }
}

@Composable
fun KopfBereich(
    auswahl: Set<String>,
    onToggle: (String) -> Unit,
    onAlle: () -> Unit,
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
    }
}

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
                Text(
                    if (laedt) "Suche Funde …" else "$anzahl Funde",
                    color = Farben.Schrift,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
                Text(
                    meldung ?: "im Kartenausschnitt · essbare Pilze",
                    color = Farben.SchriftGedimmt,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                if (wetter != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Wetter-Index", color = Farben.Schrift, fontSize = 14.sp)
                        Text(
                            "${(wetter.index * 100).roundToInt()} %",
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
                        "Regen (14 Tage): ${wetter.regen14.roundToInt()} mm · " +
                            "Ø Temperatur (7 Tage): ${"%.1f".format(wetter.temp7)} °C",
                        color = Farben.SchriftGedimmt,
                        fontSize = 12.sp
                    )
                    Text(
                        "Schätzwert aus Regen und Temperatur, kein Fundgarant.",
                        color = Farben.SchriftGedimmt,
                        fontSize = 11.sp
                    )
                } else {
                    Text("Wetter wird geladen …", color = Farben.SchriftGedimmt, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Daten: GBIF · Wetter: Open-Meteo",
                    color = Farben.SchriftGedimmt,
                    fontSize = 10.sp
                )
            } else {
                val art = Daten.art(fund.artId)
                val jetztSaison = art.istSaison(LocalDate.now().monthValue)
                val datum = fund.datum?.split("-")
                    ?.takeIf { it.size == 3 }
                    ?.let { "${it[2]}.${it[1]}.${it[0]}" }
                    ?: fund.datum

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
                    "Saison: ${art.saison}" + if (jetztSaison) " · jetzt Saison ✅" else "",
                    color = if (jetztSaison) Farben.Moos else Farben.SchriftGedimmt,
                    fontSize = 13.sp
                )
                if (datum != null) {
                    Text("Fund gemeldet am $datum", color = Farben.SchriftGedimmt, fontSize = 13.sp)
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
                            "Foto: ${fund.fotoCredit}",
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
                                "🌲 $u",
                                color = Farben.Schrift,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                         
