package com.thelightphone.homeassistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A map card rendered on OpenStreetMap tiles, with the tracked entities on top. */
class MapViewModel(
    private val server: ServerConfig,
    private val entityIds: List<String>,
    private val tiles: MapTiles,
) : LightViewModel<Unit>() {

    data class Marker(
        val label: String,
        val zone: String,
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Double,
    ) {
        /**
         * The same label HA's own map markers use: the first letter of each
         * word, capped at three characters (see ha-map.ts).
         */
        val initial: String
            get() = label.trim()
                .split(" ")
                .mapNotNull { it.firstOrNull() }
                .joinToString("")
                .take(3)
                .ifBlank { "?" }
    }

    /** One tile, positioned by its absolute tile coordinates. */
    data class Tile(val image: ImageBitmap, val tileX: Int, val tileY: Int)

    data class MapView(val tiles: List<Tile>, val viewport: MapViewport)

    val markers = MutableStateFlow<List<Marker>>(emptyList())
    val mapView = MutableStateFlow<MapView?>(null)
    val viewport = MutableStateFlow<MapViewport?>(null)
    val error = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)
    val home = MutableStateFlow<Pair<Double, Double>?>(null)

    private var loadJob: kotlinx.coroutines.Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (markers.value.isEmpty()) refresh()
    }

    fun refresh() {
        if (loading.value) return
        loading.value = true
        viewModelScope.launch {
            val client = HaClient(server)
            try {
                val homeCoordinates = client.fetchHomeCoordinates().getOrElse {
                    error.value = it.message
                    return@launch
                }
                home.value = homeCoordinates
                client.fetchStates()
                    .onSuccess { (states, _) ->
                        val found = entityIds.mapNotNull { states[it]?.toMarker(homeCoordinates) }
                        markers.value = found
                        error.value = if (found.isEmpty()) "No entities with coordinates." else null
                        loadTiles(found, homeCoordinates)
                    }
                    .onFailure { error.value = it.message }
            } finally {
                client.close()
                loading.value = false
            }
        }
    }

    /** Frames every marker plus home, then loads the tiles for that viewport. */
    private suspend fun loadTiles(found: List<Marker>, homeCoordinates: Pair<Double, Double>) {
        val positions = found.map { it.latitude to it.longitude } + homeCoordinates
        val zoom = MapTiles.fitZoom(positions, viewportTiles = GRID.toDouble())
        viewport.value = MapViewport.centredOn(
            latitude = positions.map { it.first }.average(),
            longitude = positions.map { it.second }.average(),
            zoom = zoom,
        )
        loadViewport()
    }

    /** Pans and pinches funnel through here; tiles are cached, so this is cheap. */
    fun moveTo(next: MapViewport) {
        viewport.value = next
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadViewport() }
    }

    private suspend fun loadViewport() {
        val current = viewport.value ?: return
        // Enough tiles to cover the screen around the centre, plus a ring of
        // margin so a pan doesn't immediately hit empty space.
        val half = GRID / 2 + 1
        val centreTileX = MapTiles.floorInt(current.centreTileX)
        val centreTileY = MapTiles.floorInt(current.centreTileY)

        val loaded = mutableListOf<Tile>()
        for (dx in -half..half) {
            for (dy in -half..half) {
                val tileX = centreTileX + dx
                val tileY = centreTileY + dy
                tiles.tile(current.zoom, tileX, tileY)?.let {
                    loaded += Tile(it.asImageBitmap(), tileX, tileY)
                }
                // Publish as they arrive so the map fills in progressively.
                if (loaded.isNotEmpty()) {
                    mapView.value = MapView(loaded.toList(), current)
                }
            }
        }
        tiles.trimCache()
        if (loaded.isEmpty() && error.value == null) error.value = "Could not load map tiles."
    }

    private fun HaState.toMarker(homeCoordinates: Pair<Double, Double>): Marker? {
        val latitude = attributes.double("latitude") ?: return null
        val longitude = attributes.double("longitude") ?: return null
        return Marker(
            label = friendlyName,
            zone = state.replace('_', ' ').replaceFirstChar { it.uppercase() },
            latitude = latitude,
            longitude = longitude,
            distanceMeters = distanceMeters(
                homeCoordinates.first,
                homeCoordinates.second,
                latitude,
                longitude,
            ),
        )
    }

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private companion object {
        const val GRID = 3
        const val EARTH_RADIUS_METERS = 6_371_000.0

        /** Equirectangular approximation; plenty for city-scale distances. */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val meanLatitude = Math.toRadians((lat1 + lat2) / 2)
            val dx = Math.toRadians(lon2 - lon1) * cos(meanLatitude)
            val dy = Math.toRadians(lat2 - lat1)
            return sqrt(dx * dx + dy * dy) * EARTH_RADIUS_METERS
        }
    }
}

class MapScreen(
    sealedActivity: SealedLightActivity,
    private val server: ServerConfig,
    private val entityIds: List<String>,
    private val title: String,
) : LightScreen<Unit, MapViewModel>(sealedActivity) {

    override val viewModelClass: Class<MapViewModel>
        get() = MapViewModel::class.java

    override fun createViewModel() = MapViewModel(
        server = server,
        entityIds = entityIds,
        tiles = MapTiles(lightContext.filesDir),
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val markers by viewModel.markers.collectAsState()
        val mapView by viewModel.mapView.collectAsState()
        val home by viewModel.home.collectAsState()
        val error by viewModel.error.collectAsState()
        val loading by viewModel.loading.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack(null) },
                    ),
                    center = LightTopBarCenter.Text(title),
                    rightButton = LightBarButton.Text(
                        text = if (loading) "…" else "↻",
                        onClick = { viewModel.refresh() },
                    ),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    val view = mapView
                    if (view != null && view.tiles.isNotEmpty()) {
                        MapCanvas(view, markers, home, Modifier.fillMaxSize())
                    } else {
                        LightText(
                            text = error ?: "Loading map…",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    }
                }

                LightText(
                    text = "© OpenStreetMap contributors",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(
                        horizontal = 1f.gridUnitsAsDp(),
                        vertical = 4.dp,
                    ),
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    items(markers) { marker ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LightText(
                                text = marker.label,
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.weight(1f),
                            )
                            LightText(
                                text = "${marker.zone} · ${formatDistance(marker.distanceMeters)}",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MapCanvas(
        view: MapViewModel.MapView,
        markers: List<MapViewModel.Marker>,
        home: Pair<Double, Double>?,
        modifier: Modifier,
    ) {
        val content = LightThemeTokens.colors.content
        val background = LightThemeTokens.colors.background
        val viewport = view.viewport
        // Accumulate gestures locally and hand whole tiles to the loader, so a
        // drag feels immediate without refetching on every frame.
        var pendingPan by remember(viewport.zoom) { mutableStateOf(Offset.Zero) }
        var pendingScale by remember(viewport.zoom) { mutableFloatStateOf(1f) }

        Canvas(
            modifier = modifier
                .pointerInput(viewport.zoom) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        pendingPan += pan
                        pendingScale *= gestureZoom
                        // A pinch past a full doubling/halving steps the tile zoom.
                        when {
                            pendingScale >= 2f -> {
                                viewModel.moveTo(viewport.zoomedTo(viewport.zoom + 1))
                                pendingScale = 1f
                                pendingPan = Offset.Zero
                            }
                            pendingScale <= 0.5f -> {
                                viewModel.moveTo(viewport.zoomedTo(viewport.zoom - 1))
                                pendingScale = 1f
                                pendingPan = Offset.Zero
                            }
                            // Committing per tile keeps tile fetches sane.
                            pendingPan.getDistance() > MapTiles.TILE_SIZE / 2f -> {
                                viewModel.moveTo(viewport.panBy(pendingPan.x, pendingPan.y, 1f))
                                pendingPan = Offset.Zero
                            }
                        }
                    }
                },
        ) {
            val scale = pendingScale
            val centre = Offset(size.width / 2, size.height / 2)
            val tilePixels = MapTiles.TILE_SIZE * scale

            fun screenOf(tileX: Double, tileY: Double) = Offset(
                x = centre.x + ((tileX - viewport.centreTileX) * tilePixels).toFloat() + pendingPan.x,
                y = centre.y + ((tileY - viewport.centreTileY) * tilePixels).toFloat() + pendingPan.y,
            )

            view.tiles.forEach { tile ->
                val topLeft = screenOf(tile.tileX.toDouble(), tile.tileY.toDouble())
                drawImage(
                    image = tile.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(tile.image.width, tile.image.height),
                    dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                    dstSize = IntSize(tilePixels.roundToInt(), tilePixels.roundToInt()),
                )
            }

            fun project(latitude: Double, longitude: Double) = screenOf(
                MapTiles.tileX(longitude, viewport.zoom),
                MapTiles.tileY(latitude, viewport.zoom),
            )

            home?.let { (latitude, longitude) ->
                val position = project(latitude, longitude)
                // A hollow square for home, so it reads differently from people.
                drawRect(
                    color = background,
                    topLeft = Offset(position.x - 11, position.y - 11),
                    size = Size(22f, 22f),
                )
                drawRect(
                    color = content,
                    topLeft = Offset(position.x - 11, position.y - 11),
                    size = Size(22f, 22f),
                    style = Stroke(width = 4f),
                )
            }

            markers.forEach { marker ->
                drawPin(project(marker.latitude, marker.longitude), marker.initial, content, background)
            }
        }
    }

    /**
     * A disc carrying the entity's initial, like HA's own map markers, ringed
     * in the background colour so it stays legible on any tile.
     */
    private fun DrawScope.drawPin(
        position: Offset,
        initial: String,
        content: androidx.compose.ui.graphics.Color,
        background: androidx.compose.ui.graphics.Color,
    ) {
        val radius = if (initial.length > 1) 36f else 30f
        drawCircle(color = content, radius = radius, center = position)
        drawCircle(
            color = background,
            radius = radius,
            center = position,
            style = Stroke(width = 5f),
        )
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = background.toArgb()
                textSize = if (initial.length > 2) radius * 0.8f else radius * 1.05f
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val baseline = position.y - (paint.descent() + paint.ascent()) / 2
            canvas.nativeCanvas.drawText(initial, position.x, baseline, paint)
        }
    }

    private fun formatDistance(meters: Double): String = when {
        meters < 1_000 -> "${meters.roundToInt()} m"
        else -> "${(meters / 1_000).roundToInt()} km"
    }
}
