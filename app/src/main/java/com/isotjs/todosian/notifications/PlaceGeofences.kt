package com.isotjs.todosian.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.isotjs.todosian.data.PreferencesManager
import com.isotjs.todosian.data.places.Place
import com.isotjs.todosian.data.places.PlaceRepository
import com.isotjs.todosian.data.scanMarkdownTree
import com.isotjs.todosian.data.settings.SharedPrefsAppSettingsRepository
import com.isotjs.todosian.utils.MarkdownParser
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

object PlaceGeofences {
    private const val MAX_GEOFENCES = 100

    data class PlacedTodo(val id: String, val text: String, val place: Place)

    /** Registers one geofence per open todo that has a resolvable place; removes all of them when disabled. */
    suspend fun sync(context: Context) {
        val appContext = context.applicationContext
        val client = LocationServices.getGeofencingClient(appContext)
        val pendingIntent = pendingIntent(appContext)
        val settings = SharedPrefsAppSettingsRepository(appContext).settings.first()
        if (!settings.locationReminders || !hasLocationPermissions(appContext)) {
            client.removeGeofences(pendingIntent)
            return
        }

        val geofences = runCatching { loadPlacedTodos(appContext) }.getOrDefault(emptyList())
            .take(MAX_GEOFENCES)
            .map { todo ->
                Geofence.Builder()
                    .setRequestId(todo.id)
                    .setCircularRegion(todo.place.latitude, todo.place.longitude, todo.place.radiusMeters)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                    .build()
            }
        if (geofences.isEmpty()) {
            client.removeGeofences(pendingIntent)
            return
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(geofences)
            .build()
        client.removeGeofences(pendingIntent).addOnCompleteListener {
            @Suppress("MissingPermission")
            client.addGeofences(request, pendingIntent)
        }
    }

    suspend fun loadPlacedTodos(context: Context): List<PlacedTodo> {
        val appContext = context.applicationContext
        val folderUri = PreferencesManager(appContext).getFolderUri() ?: return emptyList()
        val settings = SharedPrefsAppSettingsRepository(appContext).settings.first()
        val shortcuts = PlaceRepository(appContext).shortcuts()
        return scanMarkdownTree(appContext, folderUri, settings.includeSubfolders).files.flatMap { file ->
            val lines = appContext.contentResolver.openInputStream(file.ref)?.use { it.bufferedReader().readLines() }
                ?: return@flatMap emptyList()
            MarkdownParser.parse(lines, settings.toTaskMarkers()).mapNotNull { todo ->
                if (todo.isDone) return@mapNotNull null
                val place = todo.place?.let { Place.resolve(it, shortcuts) } ?: return@mapNotNull null
                PlacedTodo(todoId(file.ref, todo.id), todo.text, place)
            }
        }
    }

    private fun todoId(file: Uri, todoId: String): String =
        MessageDigest.getInstance("SHA-256").digest("$file\u001F$todoId".toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    private fun hasLocationPermissions(context: Context): Boolean {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            7002,
            Intent(context, PlaceGeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
}
