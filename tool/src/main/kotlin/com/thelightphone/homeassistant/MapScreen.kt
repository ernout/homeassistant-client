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
import androidx.compose.runtime.getValue
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
        /** First letter of the name, as HA's own map markers use. */
        val initial: String get() = label.trim().take(1).uppercase().ifBlank { "?" }
    }

    data class Tile(val image: ImageBitmap, val column: Int, val row: Int)

    /** Everything the canvas needs: tiles plus the projection they were drawn with. */
    data class MapView(
        val tiles: List<Tile>,
        val zoom: Int,
        val originTileX: Int,
        val originTileY: Int,
        val columns: Int,
        val rows: Int,
    )

    val markers = MutableStateFlow<List<Marker>>(emptyList())
    val mapView = MutableStateFlow<MapView?>(null)
    val error = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)
    val home = MutableStateFlow<Pair<Double, Double>?>(null)

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

    /** Picks a zoom that fits every marker, then fetches the surrounding tiles. */
    private suspend fun loadTiles(found: List<Marker>, homeCoordinates: Pair<Double, Double>) {
        val positions = found.map { it.latitude to it.longitude } + homeCoordinates
        val zoom = MapTiles.fitZoom(positions, viewportTiles = GRID.toDouble())
        val centreLatitude = positions.map { it.first }.average()
        val centreLongitude = positions.map { it.second }.average()

        val centreX = MapTiles.tileX(centreLongitude, zoom)
        val centreY = MapTiles.tileY(centreLatitude, zoom)
        val originTileX = MapTiles.floorInt(centreX - GRID / 2.0)
        val originTileY = MapTiles.floorInt(centreY - GRID / 2.0)

        val loaded = mutableListOf<Tile>()
        for (column in 0 until GRID) {
            for (row in 0 until GRID) {
                tiles.tile(zoom, originTileX + column, originTileY + row)?.let {
                    loaded += Tile(it.asImageBitmap(), column, row)
                }
            }
        }
        tiles.trimCache()

        mapView.value = MapView(
            tiles = loaded,
            zoom = zoom,
            originTileX = originTileX,
            originTileY = originTileY,
            columns = GRID,
            rows = GRID,
        )
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
        Canvas(modifier = modifier) {
            val gridPixels = view.columns * MapTiles.TILE_SIZE.toFloat()
            // Cover the canvas, cropping the overflow rather than letterboxing.
            val scale = maxOf(size.width / gridPixels, size.height / gridPixels)
            val drawnSize = gridPixels * scale
            val offsetX = (size.width - drawnSize) / 2
            val offsetY = (size.height - drawnSize) / 2
            val tilePixels = (MapTiles.TILE_SIZE * scale).roundToInt()

            view.tiles.forEach { tile ->
                drawImage(
                    image = tile.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(tile.image.width, tile.image.height),
                    dstOffset = IntOffset(
                        (offsetX + tile.column * MapTiles.TILE_SIZE * scale).roundToInt(),
                        (offsetY + tile.row * MapTiles.TILE_SIZE * scale).roundToInt(),
                    ),
                    dstSize = IntSize(tilePixels, tilePixels),
                )
            }

            fun project(latitude: Double, longitude: Double): Offset {
                val x = MapTiles.tileX(longitude, view.zoom) - view.originTileX
                val y = MapTiles.tileY(latitude, view.zoom) - view.originTileY
                return Offset(
                    x = offsetX + (x * MapTiles.TILE_SIZE * scale).toFloat(),
                    y = offsetY + (y * MapTiles.TILE_SIZE * scale).toFloat(),
                )
            }

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
                val position = project(marker.latitude, marker.longitude)
                drawPin(position, marker.initial, content, background)
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
        val radius = 30f
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
                textSize = radius * 1.1f
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
