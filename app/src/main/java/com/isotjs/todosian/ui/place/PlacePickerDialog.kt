package com.isotjs.todosian.ui.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.isotjs.todosian.R
import com.isotjs.todosian.data.places.Place
import com.isotjs.todosian.data.places.PlaceRepository
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

private val shortcutName = Regex("""[\w-]+""")

@Composable
fun PlacePickerDialog(
    initialToken: String?,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { PlaceRepository(context) }
    var shortcuts by remember { mutableStateOf(repository.shortcuts()) }
    val initial = remember { initialToken?.let { Place.resolve(it, shortcuts) } }
    var point by remember { mutableStateOf(initial?.let { GeoPoint(it.latitude, it.longitude) }) }
    var radius by remember { mutableStateOf((initial?.radiusMeters ?: Place.DEFAULT_RADIUS_METERS).toInt().toString()) }
    var newShortcut by remember { mutableStateOf("") }

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(if (point != null) 16.0 else 3.0)
            point?.let { controller.setCenter(it) }
        }
    }
    DisposableEffect(mapView) { onDispose { mapView.onDetach() } }

    fun showMarker() {
        mapView.overlays.removeAll { it is Marker }
        point?.let { p -> mapView.overlays.add(Marker(mapView).apply { position = p }) }
        mapView.invalidate()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.place_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clipToBounds(),
                ) {
                    AndroidView(
                        factory = {
                            mapView.overlays.add(
                                MapEventsOverlay(object : MapEventsReceiver {
                                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                        point = p
                                        showMarker()
                                        return true
                                    }
    
                                    override fun longPressHelper(p: GeoPoint) = false
                                }),
                            )
                            showMarker()
                            mapView
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                OutlinedTextField(
                    value = radius,
                    onValueChange = { radius = it.filter(Char::isDigit) },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.place_picker_radius)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newShortcut,
                    onValueChange = { newShortcut = it.trim().removePrefix("@") },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.place_picker_shortcut_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shortcuts.forEach { (name, place) ->
                        InputChip(
                            selected = false,
                            onClick = {
                                point = GeoPoint(place.latitude, place.longitude)
                                radius = place.radiusMeters.toInt().toString()
                                mapView.controller.setCenter(point)
                                showMarker()
                            },
                            label = { Text(text = "@$name") },
                            trailingIcon = {
                                TextButton(onClick = {
                                    repository.delete(name)
                                    shortcuts = repository.shortcuts()
                                }) { Text(text = "×") }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = point != null,
                onClick = {
                    val p = point ?: return@TextButton
                    val place = Place(p.latitude, p.longitude, radius.toFloatOrNull()?.takeIf { it > 0f } ?: Place.DEFAULT_RADIUS_METERS)
                    if (shortcutName.matches(newShortcut)) {
                        repository.save(newShortcut, place)
                        onPicked("@$newShortcut")
                    } else {
                        onPicked(place.toToken())
                    }
                },
            ) { Text(text = stringResource(R.string.place_picker_use)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
        },
    )
}
