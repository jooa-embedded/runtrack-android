package com.runtrack.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.runtrack.ui.components.ColorDot

/** One labelled data series. xs are shared via [LineChart]'s x-range. */
data class ChartSeries(
    val label: String,
    val color: Color,
    val points: List<Float>          // y-values; index maps to xFractions
)

/** A vertical marker drawn over the chart (e.g. a form-break event). */
data class ChartMarker(
    val xFraction: Float,            // 0..1 position along the x-axis
    val color: Color,
    val label: String
)

/**
 * Lightweight multi-series line chart drawn with Compose Canvas.
 * Deliberately dependency-free so the project has no fragile chart library.
 *
 * All series must share the same point count; the x-axis is implied by index.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Int = 180,
    markers: List<ChartMarker> = emptyList(),
    yUnit: String = "",
    minYOverride: Float? = null,
    maxYOverride: Float? = null
) {
    val nonEmpty = series.filter { it.points.isNotEmpty() }
    if (nonEmpty.isEmpty()) {
        Box(
            modifier
                .fillMaxWidth()
                .height(height.dp)
        ) {
            Text(
                "데이터 없음",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        }
        return
    }

    val allY = nonEmpty.flatMap { it.points }
    val rawMin = minYOverride ?: allY.min()
    val rawMax = maxYOverride ?: allY.max()
    val pad = ((rawMax - rawMin) * 0.12f).takeIf { it > 0f } ?: 1f
    val minY = rawMin - pad
    val maxY = rawMax + pad
    val span = (maxY - minY).takeIf { it != 0f } ?: 1f

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height.dp)
        ) {
            Canvas(Modifier.fillMaxWidth().height(height.dp)) {
                val w = size.width
                val h = size.height

                // horizontal grid lines (4 divisions)
                for (i in 0..4) {
                    val y = h * i / 4f
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f
                    )
                }

                // event markers (vertical dashed)
                markers.forEach { m ->
                    val x = (w * m.xFraction).coerceIn(0f, w)
                    drawLine(
                        color = m.color,
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = 2.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                    )
                    drawCircle(color = m.color, radius = 5f, center = Offset(x, 6f))
                }

                // series lines
                nonEmpty.forEach { s ->
                    if (s.points.size < 2) return@forEach
                    val path = Path()
                    s.points.forEachIndexed { i, v ->
                        val x = w * i / (s.points.size - 1).toFloat()
                        val y = h - (v - minY) / span * h
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = s.color,
                        style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                    )
                }
            }

            // y-axis min/max labels
            Column(
                Modifier.fillMaxWidth().height(height.dp).padding(start = 4.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "%.1f%s".format(maxY, if (yUnit.isEmpty()) "" else " $yUnit"),
                    style = MaterialTheme.typography.labelSmall,
                    color = axisLabelColor
                )
                Text(
                    "%.1f".format(minY),
                    style = MaterialTheme.typography.labelSmall,
                    color = axisLabelColor
                )
            }
        }

        // legend
        if (nonEmpty.size > 1 || markers.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                nonEmpty.forEach { s ->
                    LegendItem(s.color, s.label)
                }
                markers.map { it.color to it.label }.distinct().forEach { (c, l) ->
                    LegendItem(c, l)
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ColorDot(color = color, size = 8)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
