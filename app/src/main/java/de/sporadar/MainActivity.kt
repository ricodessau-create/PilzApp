package de.sporadar

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.clustering.Clustering
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            )
        )

        setContent {
            SporadarTheme {
                SporadarScreen()
            }
        }
    }
}

data class PilzClusterItem(
    val fund: Fund,
    val farbe: Long,
    val ausgewaehlt: Boolean
) : ClusterItem {

    override fun getPosition(): LatLng {
        return LatLng(
            fund.lat,
            fund.lng
        )
    }

    override fun getTitle(): String {
        return fund.id
    }

    override fun getSnippet(): String? {
        return null
    }

    override fun getZIndex(): Float {
        return if (ausgewaehlt) {
            1f
        } else {
            0f
        }
    }
}

@Composable
fun PilzEinzelMarker(
    farbe: Long,
    ausgewaehlt: Boolean
) {
    val groesse = if (ausgewaehlt) {
        44.dp
    } else {
        30.dp
    }

    val innereGroesse = if (ausgewaehlt) {
        34.dp
    } else {
        22.dp
    }

    Box(
        modifier = Modifier
            .size(groesse)
            .shadow(
                elevation = if (ausgewaehlt) 6.dp else 3.dp,
                shape = CircleShape
            )
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(innereGroesse)
                .clip(CircleShape)
                .background(Color(farbe))
        )
    }
}

@Composable
fun PilzClusterIcon(
    anzahl: Int
) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .shadow(
                elevation = 6.dp,
                shape = CircleShape
            )
            .clip(CircleShape)
            .background(Farben.Moos),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = anzahl.toString(),
            fontSize = 17.sp
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun SporadarScreen(
    vm: SporadarViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var hatStandort by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var standort by remember {
        mutableStateOf<LatLng?>(null)
    }

    var standortHinweis by remember {
        mutableStateOf<String?>(null)
    }

    var kartenBereit by remember {
        mutableStateOf(false)
    }

    var letzteLadeSignatur by remember {
        mutableStateOf<String?>(null)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        hatStandort =
            res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    val rechte = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    LaunchedEffect(Unit) {
        if (!hatStandort) {
            launcher.launch(rechte)
        }
    }

    val kamera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(51.16, 10.45),
            6f
        )
    }

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

                kamera.move(
                    CameraUpdateFactory.newLatLngZoom(
                        p,
                        12f
                    )
                )
            } else {
                standortHinweis =
                    "Standort nicht gefunden. Schalte den Standort am Handy ein und tippe auf 📍."
            }
        } else {
            standortHinweis =
                "Standortfreigabe fehlt. Tippe auf 📍, um sie zu erteilen."
        }
    }

    LaunchedEffect(
        kamera.isMoving,
        vm.auswahl,
        vm.zeitraum,
        kartenBereit
    ) {
        if (!kartenBereit || kamera.isMoving) {
            return@LaunchedEffect
        }

        delay(450)

        if (kamera.isMoving) {
            return@LaunchedEffect
        }

        val b = kamera.projection?.visibleRegion?.latLngBounds
            ?: return@LaunchedEffect

        val sued = b.southwest.latitude
        val nord = b.northeast.latitude
        val west = b.southwest.longitude
        val ost = b.northeast.longitude

        val signatur = buildString {
            append("%.2f".format(sued))
            append("|")
            append("%.2f".format(nord))
            append("|")
            append("%.2f".format(west))
            append("|")
            append("%.2f".format(ost))
            append("|")
            append(vm.auswahl.sorted().joinToString(","))
            append("|")
            append(vm.zeitraum.name)
        }

        if (signatur == letzteLadeSignatur) {
            return@LaunchedEffect
        }

        letzteLadeSignatur = signatur

        vm.ladeFunde(
            sued = sued,
            nord = nord,
            west = west,
            ost = ost
        )

        val ziel = kamera.position.target

        vm.ladeWetter(
            lat = ziel.latitude,
            lng = ziel.longitude
        )
    }

    val gewaehlt = vm.gewaehlterFund()

    val clusterItems = remember(
        vm.funde,
        gewaehlt
    ) {
        vm.funde.mapNotNull { fund ->
            val art = Daten.arten.firstOrNull {
                it.id == fund.artId
            } ?: return@mapNotNull null

            PilzClusterItem(
                fund = fund,
                farbe = art.farbe,
                ausgewaehlt = gewaehlt?.id == fund.id
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Farben.Wald)
    ) {
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
            contentPadding = PaddingValues(
                top = 200.dp,
                bottom = 230.dp
            ),
            onMapLoaded = {
                kartenBereit = true
            },
            onMapClick = {
                vm.waehle(null)
            }
        ) {
            Clustering(
                items = clusterItems,
                onClusterClick = {
                    false
                },
                onClusterItemClick = { item ->
                    vm.waehle(item.fund.id)
                    true
                },
                clusterContent = { cluster ->
                    PilzClusterIcon(
                        anzahl = cluster.size
                    )
                },
                clusterItemContent = { item ->
                    PilzEinzelMarker(
                        farbe = item.farbe,
                        ausgewaehlt = item.ausgewaehlt
                    )
                }
            )
        }

        KopfBereich(
            auswahl = vm.auswahl,
            zeitraum = vm.zeitraum,
            onToggle = { id ->
                vm.toggle(id)
            },
            onAlle = {
                vm.alleAnzeigen()
            },
            onZeitraum = { z ->
                vm.setzeZeitraum(z)
            },
            modifier = Modifier.align(
                Alignment.TopCenter
            )
        )

        Column(
            Modifier.align(
                Alignment.BottomCenter
            )
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .shadow(
                            8.dp,
                            CircleShape
                        )
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

                                        kamera.animate(
                                            CameraUpdateFactory.newLatLngZoom(
                                                p,
                                                13f
                                            ),
                                            800
                                        )
                                    } else {
                                        standortHinweis =
                                            "Standort nicht gefunden. Schalte den Standort am Handy ein."
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "📍",
                        fontSize = 22.sp
                    )
                }
            }

            Spacer(
                Modifier.height(10.dp)
            )

            UnterKarte(
                anzahl = vm.funde.size,
                laedt = vm.laedt,
                meldung = vm.meldung,
                standortHinweis = standortHinweis,
                wetter = vm.wetter,
                fund = gewaehlt,
                standort = standort,
                onSchliessen = {
                    vm.waehle(null)
                }
            )
        }
    }
}
