package de.sporadar

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// Sucht den aktuellen Standort. Erst eine frische Ortung, sonst der letzte bekannte Standort.
@SuppressLint("MissingPermission")
suspend fun holeStandort(ctx: Context): LatLng? {
    val client = LocationServices.getFusedLocationProviderClient(ctx)

    val aktuell: Location? = try {
        suspendCancellableCoroutine<Location?> { cont ->
            val anfrage = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setDurationMillis(15000)
                .setMaxUpdateAgeMillis(120000)
                .build()
            client.getCurrentLocation(anfrage, null)
                .addOnSuccessListener { l -> if (cont.isActive) cont.resume(l) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    } catch (e: SecurityException) {
        null
    }
    if (aktuell != null) return LatLng(aktuell.latitude, aktuell.longitude)

    val zuletzt: Location? = try {
        suspendCancellableCoroutine<Location?> { cont ->
            client.lastLocation
                .addOnSuccessListener { l -> if (cont.isActive) cont.resume(l) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    } catch (e: SecurityException) {
        null
    }
    return if (zuletzt != null) LatLng(zuletzt.latitude, zuletzt.longitude) else null
}

// ENDE
