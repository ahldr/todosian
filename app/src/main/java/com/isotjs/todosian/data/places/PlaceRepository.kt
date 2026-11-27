package com.isotjs.todosian.data.places

import android.content.Context
import androidx.core.content.edit

class PlaceRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shortcuts(): Map<String, Place> =
        prefs.getStringSet(KEY_SHORTCUTS, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val (name, token) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                Place.resolve(token, emptyMap())?.let { name to it }
            }
            .toMap()

    fun save(name: String, place: Place) {
        val updated = shortcuts() + (name to place)
        prefs.edit { putStringSet(KEY_SHORTCUTS, updated.map { (n, p) -> "$n=${p.toToken()}" }.toSet()) }
    }

    fun delete(name: String) {
        val updated = shortcuts() - name
        prefs.edit { putStringSet(KEY_SHORTCUTS, updated.map { (n, p) -> "$n=${p.toToken()}" }.toSet()) }
    }

    private companion object {
        const val PREFS_NAME = "todosian_places"
        const val KEY_SHORTCUTS = "shortcuts"
    }
}
