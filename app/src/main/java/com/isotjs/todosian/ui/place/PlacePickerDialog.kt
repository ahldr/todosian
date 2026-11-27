package com.isotjs.todosian.ui.place

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.isotjs.todosian.R
import com.isotjs.todosian.data.places.AddressResult
import com.isotjs.todosian.data.places.PhotonGeocoder
import com.isotjs.todosian.data.places.Place
import com.isotjs.todosian.data.places.PlaceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val PICK_ZOOM = 16.0
private const val MIN_PICK_ZOOM = 12.0
private val shortcutName = Regex("""[\w-]+""")

@SuppressLint("MissingPermission")
@Composable
fun PlacePickerDialog(
    initialToken: String?,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { PlaceRepository(context) }
    val geocoder = remember { PhotonGeocoder(context.packageName) }
    var shortcuts by remember { mutableStateOf(repository.shortcuts()) }
    val initial = remember { initialToken?.let { Place.resolve(it, shortcuts) } }
    var radius by remember { mutableStateOf((initial?.radiusMeters ?: Place.DEFAULT_RADIUS_METERS).toInt().toString()) }
    var newShortcut by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var showResults by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<AddressResult>()) }
    val hasLocationPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri(if (dark) DARK_STYLE else LIGHT_STYLE),
        initialCameraPosition = initial
            ?.let { CameraPosition(center = Position(longitude = it.longitude, latitude = it.latitude), zoom = PICK_ZOOM) }
            ?: CameraPosition(zoom = 1.0),
    )

    fun moveTo(latitude: Double, longitude: Double) {
        scope.launch {
            mapState.animateCamera(
                CameraUpdate(center = Position(longitude = longitude, latitude = latitude), zoom = PICK_ZOOM),
            )
        }
    }

    LaunchedEffect(Unit) {
        if (initial == null && hasLocationPermission) {
            LocationServices.getFusedLocationProviderClient(context).lastLocation
                .addOnSuccessListener { location -> location?.let { moveTo(it.latitude, it.longitude) } }
        }
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (!showResults || trimmed.length < 3) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        val center = mapState.cameraPosition.center
        results = runCatching { geocoder.search(trimmed, center.latitude, center.longitude) }.getOrDefault(emptyList())
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                    Text(text = stringResource(R.string.place_picker_title), style = MaterialTheme.typography.titleLarge)
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        showResults = true
                    },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text(text = stringResource(R.string.place_picker_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp)),
                ) {
                    MaplibreMap(modifier = Modifier.fillMaxSize(), state = mapState)
                    Icon(
                        imageVector = Icons.Filled.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(40.dp)
                            .offset(y = (-20).dp),
                    )
                    if (hasLocationPermission) {
                        SmallFloatingActionButton(
                            onClick = {
                                LocationServices.getFusedLocationProviderClient(context).lastLocation
                                    .addOnSuccessListener { location -> location?.let { moveTo(it.latitude, it.longitude) } }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp),
                        ) {
                            Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.place_picker_my_location))
                        }
                    }
                    if (showResults && results.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 3.dp,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .padding(8.dp),
                        ) {
                            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                                items(results) { result ->
                                    ListItem(
                                        headlineContent = { Text(text = result.label, maxLines = 2) },
                                        modifier = Modifier.clickable {
                                            showResults = false
                                            query = result.label
                                            moveTo(result.latitude, result.longitude)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (shortcuts.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        shortcuts.forEach { (name, place) ->
                            InputChip(
                                selected = false,
                                onClick = {
                                    radius = place.radiusMeters.toInt().toString()
                                    moveTo(place.latitude, place.longitude)
                                },
                                label = { Text(text = "@$name") },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.place_picker_delete_shortcut),
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable {
                                                repository.delete(name)
                                                shortcuts = repository.shortcuts()
                                            },
                                    )
                                },
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = radius,
                        onValueChange = { radius = it.filter(Char::isDigit) },
                        singleLine = true,
                        label = { Text(text = stringResource(R.string.place_picker_radius)) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = newShortcut,
                        onValueChange = { newShortcut = it.trim().removePrefix("@") },
                        singleLine = true,
                        label = { Text(text = stringResource(R.string.place_picker_shortcut_name)) },
                        modifier = Modifier.weight(1f),
                    )
                }

                val canPick = mapState.cameraPosition.zoom >= MIN_PICK_ZOOM
                Button(
                    enabled = canPick,
                    onClick = {
                        val center = mapState.cameraPosition.center
                        val place = Place(
                            center.latitude,
                            center.longitude,
                            radius.toFloatOrNull()?.takeIf { it > 0f } ?: Place.DEFAULT_RADIUS_METERS,
                        )
                        if (shortcutName.matches(newShortcut)) {
                            repository.save(newShortcut, place)
                            onPicked("@$newShortcut")
                        } else {
                            onPicked(place.toToken())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(
                            if (canPick) R.string.place_picker_use else R.string.place_picker_zoom_in,
                        ),
                    )
                }
            }
        }
    }
}
