package com.thelightphone.homeassistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Fetches OpenStreetMap raster tiles, cached on disk.
 *
 * OSM's tile usage policy requires an identifying User-Agent and expects
 * caching; tiles are kept in the tool's files dir and only fetched once.
 */
class MapTiles(cacheRoot: File, private val http: OkHttpClient = OkHttpClient()) {

    private val cacheDir = File(cacheRoot, "osm-tiles").also { it.mkdirs() }

    suspend fun tile(zoom: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val wrapped = x.mod(1 shl zoom)
        if (y < 0 || y >= (1 shl zoom)) return@withContext null

        val cached = File(cacheDir, "$zoom-$wrapped-$y.png")
        if (cached.isFile) {
            BitmapFactory.decodeFile(cached.path)?.let { return@withContext it }
            cached.delete()
        }

        runCatching {
            val request = Request.Builder()
                .url("https://tile.openstreetmap.org/$zoom/$wrapped/$y.png")
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val bytes = response.body?.bytes() ?: return@runCatching null
                cached.writeBytes(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }

    /** Deletes cached tiles once the cache grows past a few megabytes. */
    fun trimCache(maxBytes: Long = 8L * 1024 * 1024) {
        val files = cacheDir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        const val TILE_SIZE = 256

        private const val USER_AGENT =
            "HomeAssistantLightPhoneTool/0.1 (+https://github.com/ernout/homeassistant-client)"

        /** Slippy-map projection: fractional tile coordinates for a position. */
        fun tileX(longitude: Double, zoom: Int): Double =
            (longitude + 180.0) / 360.0 * (1 shl zoom)

        fun tileY(latitude: Double, zoom: Int): Double {
            val radians = Math.toRadians(latitude)
            return (1.0 - asinh(tan(radians)) / PI) / 2.0 * (1 shl zoom)
        }

        fun longitudeOf(tileX: Double, zoom: Int): Double =
            tileX / (1 shl zoom) * 360.0 - 180.0

        fun latitudeOf(tileY: Double, zoom: Int): Double {
            val n = PI * (1 - 2 * tileY / (1 shl zoom))
            return Math.toDegrees(atan(sinh(n)))
        }

        /**
         * Highest zoom at which every position still fits inside a viewport of
         * [viewportTiles] tiles, so the map is as close-in as it can be.
         */
        fun fitZoom(
            positions: List<Pair<Double, Double>>,
            viewportTiles: Double,
            minZoom: Int = 3,
            maxZoom: Int = 17,
        ): Int {
            if (positions.size < 2) return 15
            for (zoom in maxZoom downTo minZoom) {
                val xs = positions.map { tileX(it.second, zoom) }
                val ys = positions.map { tileY(it.first, zoom) }
                val spanX = (xs.max() - xs.min())
                val spanY = (ys.max() - ys.min())
                if (spanX <= viewportTiles * 0.8 && spanY <= viewportTiles * 0.8) return zoom
            }
            return minZoom
        }

        fun floorInt(value: Double): Int = floor(value).toInt()

        /** Metres per pixel at a latitude and zoom, for the scale bar. */
        fun metersPerPixel(latitude: Double, zoom: Int): Double =
            156_543.03392 * kotlin.math.cos(Math.toRadians(latitude)) / (1 shl zoom)

        fun log2(value: Double): Double = ln(value) / ln(2.0)
    }
}
