package com.thelightphone.homeassistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A map card, rendered as a plot rather than a real map: HA serves no map
 * imagery, and tiles from an external service would mean another network
 * dependency and a grey mush on a monochrome screen. Dots positioned relative
 * to home, plus distance and compass bearing per entity, answer "where is
 * everyone" without any of that.
 */
class MapViewModel(
    private val server: ServerConfig,
    private val entityIds: List<String>,
) : LightViewModel<Unit>() {

    data class Marker(
        val label: String,
        val zone: String,
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Double,
        val bearingDegrees: Double,
    )

    val markers = MutableStateFlow<List<Marker>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    fun refresh() {
        if (loading.value) return
        loading.value = true
        viewModelScope.launch {
            val client = HaClient(server)
            try {
                val home = client.fetchHomeCoordinates().getOrElse {
                    error.value = it.message
                    return@launch
                }
                client.fetchStates()
                    .onSuccess { (states, _) ->
                        markers.value = entityIds.mapNotNull { id -> states[id]?.toMarker(home) }
                        error.value = if (markers.value.isEmpty()) {
                            "No entities with coordinates."
                        } else {
                            null
                        }
                    }
                    .onFailure { error.value = it.message }
            } finally {
                client.close()
                loading.value = false
            }
        }
    }

    private fun HaState.toMarker(home: Pair<Double, Double>): Marker? {
        val latitude = attributes.double("latitude") ?: return null
        val longitude = attributes.double("longitude") ?: return null
        return Marker(
            label = friendlyName,
            zone = state.replace('_', ' ').replaceFirstChar { it.uppercase() },
            latitude = latitude,
            longitude = longitude,
            distanceMeters = distanceMeters(home.first, home.second, latitude, longitude),
            bearingDegrees = bearingDegrees(home.first, home.second, latitude, longitude),
        )
    }

    private fun kotlinx.serialization.json.JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0

        /** Equirectangular approximation; plenty for city-scale distances. */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val meanLatitude = Math.toRadians((lat1 + lat2) / 2)
            val dx = Math.toRadians(lon2 - lon1) * cos(meanLatitude)
            val dy = Math.toRadians(lat2 - lat1)
            return sqrt(dx * dx + dy * dy) * EARTH_RADIUS_METERS
        }

        fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dx = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
            val dy = Math.toRadians(lat2 - lat1)
            val degrees = Math.toDegrees(atan2(dx, dy))
            return (degrees + 360) % 360
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

    override fun createViewModel() = MapViewModel(server, entityIds)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val markers by viewModel.markers.collectAsState()
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
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                if (markers.isNotEmpty()) {
                    Plot(
                        markers = markers,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                }

                error?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(
                            horizontal = 1f.gridUnitsAsDp(),
                            vertical = 8.dp,
                        ),
                    )
                }

                LazyColumn(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                    items(markers) { marker ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LightText(
                                text = marker.label,
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.weight(1f),
                            )
                            LightText(
                                text = "${marker.zone} · ${formatDistance(marker.distanceMeters)} " +
                                    compass(marker.bearingDegrees),
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
    private fun Plot(markers: List<MapViewModel.Marker>, modifier: Modifier) {
        val content = LightThemeTokens.colors.content
        Canvas(modifier = modifier) {
            val centre = Offset(size.width / 2, size.height / 2)
            val radius = min(size.width, size.height) / 2 * 0.85f
            val furthest = max(markers.maxOf { it.distanceMeters }, 1.0)

            // Home marker plus two range rings at half and full scale.
            drawCircle(color = content, radius = 5f, center = centre)
            listOf(0.5f, 1f).forEach { fraction ->
                drawCircle(
                    color = content,
                    radius = radius * fraction,
                    center = centre,
                    alpha = 0.25f,
                    style = Stroke(width = 2f),
                )
            }

            markers.forEach { marker ->
                val scaled = (marker.distanceMeters / furthest).toFloat() * radius
                val angle = Math.toRadians(marker.bearingDegrees)
                val position = Offset(
                    x = centre.x + (sin(angle) * scaled).toFloat(),
                    y = centre.y - (cos(angle) * scaled).toFloat(),
                )
                drawCircle(color = content, radius = 12f, center = position, style = Stroke(3f))
                drawCircle(color = content, radius = 4f, center = position)
            }
        }
    }

    private fun min(a: Float, b: Float) = if (a < b) a else b

    private fun formatDistance(meters: Double): String = when {
        meters < 1_000 -> "${meters.roundToInt()} m"
        meters < 100_000 -> "${(meters / 1_000).roundToInt()} km"
        else -> "${(meters / 1_000).roundToInt()} km"
    }

    private fun compass(degrees: Double): String {
        val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((degrees + 22.5) / 45).toInt() % points.size
        return if (abs(degrees) < 0.01) "" else points[index]
    }
}
