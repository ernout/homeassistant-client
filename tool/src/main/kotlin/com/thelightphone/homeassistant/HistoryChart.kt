package com.thelightphone.homeassistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How a series is drawn.
 *
 * Home Assistant lets a card ask for either, and the two say different things:
 * a line reads as something that was always there and was sampled, bars as
 * quantities belonging to separate spans. Following the card's choice matters
 * more than picking a house style — an energy graph drawn as a line invites
 * reading between the bars, where nothing was measured.
 */
enum class ChartStyle { LINE, BAR }

/**
 * A numeric series over time, ready to draw.
 *
 * Non-numeric history — "on", "unavailable", a preset name — is dropped on the
 * way in rather than coerced: there is no meaningful height for "home", and a
 * chart that invents one is worse than a chart that admits it has nothing.
 */
data class ChartSeries(
    val points: List<Pair<Long, Double>>,
    val unit: String? = null,
) {
    val minimum: Double get() = points.minOf { it.second }
    val maximum: Double get() = points.maxOf { it.second }

    val isDrawable: Boolean get() = points.size >= 2

    companion object {
        fun from(history: List<HistoryPoint>, unit: String? = null) = ChartSeries(
            points = history.mapNotNull { point ->
                point.value?.let { point.atMillis to it }
            },
            unit = unit,
        )
    }
}

/**
 * The chart itself: axes-free, two inks, no grid. A Light Phone screen has room
 * for the shape of the data or for its scaffolding, not both, so the numbers
 * that would have been axis labels sit underneath as text instead.
 */
@Composable
fun HistoryChart(
    series: ChartSeries,
    style: ChartStyle,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    showScale: Boolean = true,
) {
    if (!series.isDrawable) {
        LightText(
            text = "No history recorded.",
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = modifier.padding(vertical = 8.dp),
        )
        return
    }

    val content = LightThemeTokens.colors.content

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            when (style) {
                ChartStyle.LINE -> drawLine(series, content)
                ChartStyle.BAR -> drawBars(series, content)
            }
        }

        if (showScale) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                LightText(
                    text = formatReading(series.minimum, series.unit),
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.weight(1f),
                )
                LightText(
                    text = formatReading(series.maximum, series.unit),
                    variant = LightTextVariant.Fine,
                    lighten = true,
                )
            }
        }
    }
}

/** The same series with no labels, sized to sit on a row beside a value. */
@Composable
fun Sparkline(
    series: ChartSeries,
    style: ChartStyle,
    modifier: Modifier = Modifier,
) {
    if (!series.isDrawable) return
    val content = LightThemeTokens.colors.content
    Canvas(modifier = modifier.height(SPARKLINE_HEIGHT)) {
        when (style) {
            ChartStyle.LINE -> drawLine(series, content, strokeWidth = 2.5f)
            ChartStyle.BAR -> drawBars(series, content, minimumGap = 1f)
        }
    }
}

private val SPARKLINE_HEIGHT = 26.dp

/**
 * Maps a series onto the canvas. Time runs left to right across the full width;
 * the value range is padded a little so a flat stretch does not end up drawn
 * along the very edge, where it reads as a border rather than as data.
 */
private class Plot(size: Size, series: ChartSeries) {
    private val firstMillis = series.points.first().first
    private val lastMillis = series.points.last().first
    private val spanMillis = (lastMillis - firstMillis).coerceAtLeast(1L)

    private val low: Double
    private val high: Double

    init {
        val minimum = series.minimum
        val maximum = series.maximum
        // A perfectly flat series has no range to scale against; give it one so
        // it lands mid-canvas instead of dividing by zero.
        val padding = ((maximum - minimum) * 0.1).takeIf { it > 0.0 }
            ?: (abs(maximum).coerceAtLeast(1.0) * 0.1)
        low = minimum - padding
        high = maximum + padding
    }

    private val width = size.width
    private val height = size.height

    fun x(atMillis: Long): Float =
        ((atMillis - firstMillis).toDouble() / spanMillis * width).toFloat()

    fun y(value: Double): Float =
        (height - (value - low) / (high - low) * height).toFloat()

    /** Where zero sits, clamped into view for series that never reach it. */
    fun baseline(): Float = y(0.0).coerceIn(0f, height)
}

private fun DrawScope.drawLine(
    series: ChartSeries,
    color: Color,
    strokeWidth: Float = 4f,
) {
    val plot = Plot(size, series)
    val path = Path()
    series.points.forEachIndexed { index, (atMillis, value) ->
        val point = Offset(plot.x(atMillis), plot.y(value))
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}

/**
 * Bars over equal spans of time rather than one per reading: a sensor reports
 * when it feels like it, and bars whose widths follow that would encode the
 * recorder's mood as much as the data. Each bucket averages what fell in it.
 */
private fun DrawScope.drawBars(
    series: ChartSeries,
    color: Color,
    minimumGap: Float = 2f,
) {
    val plot = Plot(size, series)
    val bucketCount = (size.width / BAR_TARGET_WIDTH).roundToInt().coerceIn(1, series.points.size)
    val firstMillis = series.points.first().first
    val lastMillis = series.points.last().first
    val span = (lastMillis - firstMillis).coerceAtLeast(1L)

    val buckets = Array(bucketCount) { mutableListOf<Double>() }
    series.points.forEach { (atMillis, value) ->
        val index = ((atMillis - firstMillis).toDouble() / span * bucketCount)
            .toInt()
            .coerceIn(0, bucketCount - 1)
        buckets[index] += value
    }

    val slot = size.width / bucketCount
    val barWidth = (slot - minimumGap).coerceAtLeast(1f)
    val baseline = plot.baseline()

    buckets.forEachIndexed { index, values ->
        if (values.isEmpty()) return@forEachIndexed
        val top = plot.y(values.average())
        drawRect(
            color = color,
            topLeft = Offset(index * slot, minOf(top, baseline)),
            size = Size(barWidth, abs(baseline - top).coerceAtLeast(1f)),
        )
    }
}

private const val BAR_TARGET_WIDTH = 14f

/** Readings get at most one decimal; a phone screen has no room for noise. */
fun formatReading(value: Double, unit: String?): String {
    val rounded = if (abs(value) >= 100 || value == value.roundToInt().toDouble()) {
        value.roundToInt().toString()
    } else {
        String.format("%.1f", value)
    }
    return if (unit.isNullOrBlank()) rounded else "$rounded $unit"
}
