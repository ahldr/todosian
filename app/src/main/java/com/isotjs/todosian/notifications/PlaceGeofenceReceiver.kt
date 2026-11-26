package com.isotjs.todosian.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.isotjs.todosian.MainActivity
import com.isotjs.todosian.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate

class PlaceGeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }.toSet()

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                PlaceGeofences.loadPlacedTodos(context)
                    .filter { it.id in ids && !alreadyNotifiedToday(context, it.id) }
                    .forEach { todo ->
                        notify(context, todo)
                        markNotified(context, todo.id)
                    }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, todo: PlaceGeofences.PlacedTodo) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_place_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_place_title))
            .setContentText(todo.text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(todo.id.hashCode(), notification) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun alreadyNotifiedToday(context: Context, id: String): Boolean =
        prefs(context).getString(id, null) == LocalDate.now().toString()

    private fun markNotified(context: Context, id: String) =
        prefs(context).edit { putString(id, LocalDate.now().toString()) }

    private companion object {
        const val CHANNEL_ID = "todosian_place_reminders"
        const val STATE_PREFS = "todosian_place_reminder_state"
    }
}
