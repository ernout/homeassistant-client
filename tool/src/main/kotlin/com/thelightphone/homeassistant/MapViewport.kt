package com.thelightphone.homeassistant

/**
 * Where the map is looking: a centre in fractional tile coordinates plus a
 * zoom. Panning and pinching change this; the loader then fetches whatever
 * tiles the new viewport needs.
 */
data class MapViewport(
    val zoom: Int,
    val centreTileX: Double,
    val centreTileY: Double,
) {
    /** Shifts the centre by a screen-pixel delta at the current scale. */
    fun panBy(deltaXPixels: Float, deltaYPixels: Float, scale: Float): MapViewport = copy(
        centreTileX = centreTileX - deltaXPixels / (MapTiles.TILE_SIZE * scale),
        centreTileY = centreTileY - deltaYPixels / (MapTiles.TILE_SIZE * scale),
    )

    /** Zooming keeps the same ground position centred: tile coords halve/double. */
    fun zoomedTo(newZoom: Int): MapViewport {
        val clamped = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = Math.pow(2.0, (clamped - zoom).toDouble())
        return MapViewport(
            zoom = clamped,
            centreTileX = centreTileX * factor,
            centreTileY = centreTileY * factor,
        )
    }

    companion object {
        const val MIN_ZOOM = 3
        const val MAX_ZOOM = 18

        fun centredOn(latitude: Double, longitude: Double, zoom: Int) = MapViewport(
            zoom = zoom,
            centreTileX = MapTiles.tileX(longitude, zoom),
            centreTileY = MapTiles.tileY(latitude, zoom),
        )
    }
}
