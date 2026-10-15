package com.fibrocoir.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** One line on a graph. values line up with the graph's x labels; null = no reading there. */
data class Series(val name: String, val color: Color, val values: List<Double?>, val join: Boolean = true)

/** A dashed horizontal line, e.g. an EC limit. */
data class Limit(val name: String, val value: Double, val color: Color)

/**
 * A line graph: x labels along the bottom (a second line under each, e.g. the sample no),
 * dashed limit lines, tap to see the values at that point.
 */
@Composable
fun LineChart(
    labels: List<String>,
    series: List<Series>,
    limits: List<Limit> = emptyList(),
    subLabels: List<String> = emptyList(),
    height: Dp = 220.dp,
    yMinZero: Boolean = true,
    format: (Double) -> String = { num(it) },
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    var picked by remember(labels, series) { mutableStateOf(-1) }
    val n = labels.size
    val all = series.flatMap { s -> s.values.filterNotNull() } + limits.map { it.value }
    val lo0 = if (yMinZero) 0.0 else (all.minOrNull() ?: 0.0)
    val hi0 = all.maxOrNull() ?: 1.0
    val ticks = niceTicks(lo0, if (hi0 <= lo0) lo0 + 1 else hi0)
    val lo = ticks.first(); val hi = ticks.last()

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(height)) {
            Canvas(
                Modifier.fillMaxWidth().height(height).pointerInput(labels, series) {
                    detectTapGestures { pos ->
                        if (n == 0) return@detectTapGestures
                        val left = 40.dp.toPx(); val right = size.width - 8.dp.toPx()
                        val step = if (n > 1) (right - left) / (n - 1) else 0f
                        val i = if (n == 1) 0 else ((pos.x - left) / step).let { Math.round(it) }.coerceIn(0, n - 1)
                        picked = if (picked == i) -1 else i
                    }
                }
            ) {
                val left = 40.dp.toPx(); val right = size.width - 8.dp.toPx()
                val top = 10.dp.toPx(); val bottom = size.height - (if (subLabels.isNotEmpty()) 34.dp else 22.dp).toPx()
                fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
                val step = if (n > 1) (right - left) / (n - 1) else 0f
                fun x(i: Int) = if (n == 1) (left + right) / 2 else left + i * step
                val axis = TextStyle(color = Fcp.faint, fontSize = 10.sp)

                // grid + y labels
                ticks.forEach { t ->
                    val yy = y(t)
                    drawLine(Fcp.husk4, Offset(left, yy), Offset(right, yy), 1f)
                    val lay = tm.measure(format(t), axis)
                    drawText(lay, topLeft = Offset(left - lay.size.width - 6.dp.toPx(), yy - lay.size.height / 2))
                }
                // limit lines
                limits.forEach { l ->
                    if (l.value < lo || l.value > hi) return@forEach
                    val yy = y(l.value)
                    drawLine(l.color, Offset(left, yy), Offset(right, yy), 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    val lay = tm.measure("${l.name} ${format(l.value)}", TextStyle(color = l.color, fontSize = 10.sp))
                    drawText(lay, topLeft = Offset(right - lay.size.width, yy - lay.size.height - 2))
                }
                // x labels: as many as fit
                if (n > 0) {
                    val every = max(1, ceil(n / max(1f, (right - left) / 46.dp.toPx())).toInt())
                    for (i in 0 until n) {
                        if (i % every != 0 && i != n - 1) continue
                        val lay = tm.measure(labels[i], axis)
                        drawText(lay, topLeft = Offset(x(i) - lay.size.width / 2, bottom + 5.dp.toPx()))
                        if (i < subLabels.size && subLabels[i].isNotEmpty()) {
                            val sl = tm.measure(subLabels[i], TextStyle(color = Fcp.faint.copy(alpha = 0.75f), fontSize = 9.sp))
                            drawText(sl, topLeft = Offset(x(i) - sl.size.width / 2, bottom + 5.dp.toPx() + lay.size.height))
                        }
                    }
                }
                // series
                series.forEach { s ->
                    val path = Path(); var open = false
                    s.values.forEachIndexed { i, v ->
                        if (v == null) { if (!s.join) open = false; return@forEachIndexed }
                        val px = x(i); val py = y(v)
                        if (!open) { path.moveTo(px, py); open = true } else path.lineTo(px, py)
                    }
                    drawPath(path, s.color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
                    s.values.forEachIndexed { i, v ->
                        if (v != null) drawCircle(s.color, (if (i == picked) 5.5 else 3.2).dp.toPx(), Offset(x(i), y(v)))
                    }
                }
                if (picked in 0 until n) {
                    drawLine(Fcp.muted.copy(alpha = 0.5f), Offset(x(picked), top), Offset(x(picked), bottom), 1f)
                }
            }
            // tooltip
            if (picked in 0 until n) {
                val lines = buildList {
                    add(labels[picked] + (subLabels.getOrNull(picked)?.takeIf { it.isNotEmpty() }?.let { "  $it" } ?: ""))
                    series.forEach { s -> s.values.getOrNull(picked)?.let { add("${s.name}: ${format(it)}") } }
                }
                Column(
                    Modifier.align(Alignment.TopCenter).clip(RoundedCornerShape(10.dp)).background(Fcp.husk4.copy(alpha = 0.96f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    lines.forEachIndexed { i, t ->
                        Text(t, style = if (i == 0) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                            color = if (i == 0) Fcp.cream else Fcp.muted)
                    }
                }
            }
        }
        Legend(series.map { it.name to it.color } + limits.map { it.name to it.color }, dashedFrom = series.size)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Legend(items: List<Pair<String, Color>>, dashedFrom: Int = Int.MAX_VALUE) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        items.forEachIndexed { i, (name, color) ->
            Row(Modifier.padding(end = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 16.dp, height = 8.dp)) {
                    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(),
                        pathEffect = if (i >= dashedFrom) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null)
                }
                Spacer(Modifier.width(5.dp))
                Text(name, style = MaterialTheme.typography.labelSmall, color = Fcp.muted)
            }
        }
    }
}

/** Simple vertical bars, one per label, with the value on top. */
@Composable
fun BarChart(
    labels: List<String>,
    values: List<Double>,
    color: Color = Fcp.fibre,
    height: Dp = 160.dp,
    highlightLast: Boolean = false,
    format: (Double) -> String = { count(it) },
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height)) {
        val n = values.size
        if (n == 0) return@Canvas
        val hi = max(1.0, values.maxOrNull() ?: 1.0)
        val bottom = size.height - 20.dp.toPx(); val top = 16.dp.toPx()
        val slot = size.width / n; val w = (slot * 0.62f).coerceAtMost(36.dp.toPx())
        val axis = TextStyle(color = Fcp.faint, fontSize = 10.sp)
        values.forEachIndexed { i, v ->
            val h = ((v / hi) * (bottom - top)).toFloat()
            val cx = slot * i + slot / 2
            val c = if (highlightLast && i == n - 1) Fcp.fibreHi else color.copy(alpha = if (highlightLast) 0.7f else 1f)
            drawRoundRect(c, Offset(cx - w / 2, bottom - h), Size(w, h), CornerRadius(5.dp.toPx()))
            if (v > 0) {
                val lay = tm.measure(format(v), TextStyle(color = Fcp.cream, fontSize = 10.sp))
                drawText(lay, topLeft = Offset(cx - lay.size.width / 2, bottom - h - lay.size.height - 2))
            }
            val every = max(1, ceil(n / (size.width / 34.dp.toPx())).toInt())
            if (i % every == 0 || i == n - 1) {
                val lay = tm.measure(labels[i], axis)
                drawText(lay, topLeft = Offset(cx - lay.size.width / 2, bottom + 4.dp.toPx()))
            }
        }
    }
}

/** Round axis ticks covering lo..hi. */
fun niceTicks(lo: Double, hi: Double, count: Int = 5): List<Double> {
    val span = hi - lo
    val raw = span / (count - 1)
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw - 1e-12 }
    val start = floor(lo / step) * step
    val end = ceil(hi / step - 1e-9) * step
    val out = ArrayList<Double>()
    var t = start
    while (t <= end + step / 2) { out.add(if (abs(t) < 1e-12) 0.0 else t); t += step }
    return out
}
