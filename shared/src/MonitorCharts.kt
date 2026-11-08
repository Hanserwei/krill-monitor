package com.hanserwei.krill_monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun chartCost(value: Double?): Double? = value?.takeIf { it.isFinite() && it >= 0 }

internal fun spendChartDescription(daily: List<DailyUsage>): String =
    daily.joinToString("，") {
        "${it.date} 费用 ${usdText(chartCost(it.costUsd))}，请求 ${countText(it.requests)} 次"
    }

internal data class ChartShare(val label: String, val fraction: Double)

internal data class ChartShares(val entries: List<ChartShare>, val incomplete: Boolean)

internal fun modelShares(models: List<ModelUsage>): ChartShares {
    val valid = models.mapNotNull { model -> chartCost(model.costUsd)?.let { model.model to it } }
    val incomplete = valid.size != models.size
    val max = valid.maxOfOrNull { it.second } ?: return ChartShares(emptyList(), incomplete)
    if (max == 0.0) return ChartShares(emptyList(), incomplete)
    // Scale before summing so even several Double.MAX_VALUE costs remain representable.
    val sorted = valid.sortedByDescending { it.second }
    val scaled = sorted.map { it.first to it.second / max }
    val total = scaled.sumOf { it.second }
    val top = scaled.take(4).map { ChartShare(it.first, it.second / total) }
    val other = scaled.drop(4).sumOf { it.second } / total
    return ChartShares(if (other > 0) top + ChartShare("其他", other) else top, incomplete)
}

internal fun quotaRatio(amount: Double?, limit: Double?): Double? {
    val value = chartCost(amount) ?: return null
    val cap = chartCost(limit)?.takeIf { it > 0 } ?: return null
    return (value / cap).coerceAtMost(Double.MAX_VALUE)
}

private fun percentText(ratio: Double): String {
    val percent = ratio * 100
    return if (!percent.isFinite() || percent > Int.MAX_VALUE) ">100%"
    else "${percent.roundToInt()}%"
}

internal fun compactCount(value: Long?): String {
    if (value == null || value < 0) return "—"
    val units =
        listOf(1_000_000_000_000L to "T", 1_000_000_000L to "B", 1_000_000L to "M", 1_000L to "K")
    val unit = units.firstOrNull { value >= it.first } ?: return value.toString()
    val whole = value / unit.first
    val tenth = value % unit.first / (unit.first / 10)
    return if (tenth == 0L) "$whole${unit.second}" else "$whole.$tenth${unit.second}"
}

@Composable
internal fun SpendTrendChart(
    daily: List<DailyUsage>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val primary = MaterialTheme.colorScheme.primary

    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val values = daily.map { chartCost(it.costUsd) }
    val max = values.filterNotNull().maxOrNull()
    var selected by remember(daily) { mutableStateOf(values.indexOfLast { it != null }) }
    val emptyLabel =
        when {
            max == null -> "暂无可用费用"
            max == 0.0 -> if (values.any { it == null }) "已知费用均为零" else "费用均为零"
            else -> null
        }
    val summary =
        listOfNotNull(emptyLabel, spendChartDescription(daily).takeIf { it.isNotEmpty() })
            .joinToString("；")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact && max != null && max > 0)
            Text(
                "单日最高 ${usdText(max)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        if (max == null || max == 0.0) {
            val touch =
                if (compact || daily.isEmpty()) Modifier
                else
                    Modifier.pointerInput(daily) {
                        detectTapGestures { point ->
                            selected =
                                (point.x / size.width * daily.size)
                                    .toInt()
                                    .coerceIn(0, daily.lastIndex)
                        }
                    }
            Box(
                Modifier.fillMaxWidth()
                    .height(if (compact) 64.dp else 116.dp)
                    .then(touch)
                    .semantics { contentDescription = summary },
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    emptyLabel.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val touch =
                if (compact) Modifier
                else
                    Modifier.pointerInput(daily) {
                        detectTapGestures { point ->
                            if (daily.isNotEmpty())
                                selected =
                                    (point.x / size.width * daily.size)
                                        .toInt()
                                        .coerceIn(0, daily.lastIndex)
                        }
                    }
            Canvas(
                Modifier.fillMaxWidth()
                    .height(if (compact) 80.dp else 180.dp)
                    .then(touch)
                    .semantics { contentDescription = summary }
            ) {
                val bottom = size.height - 4.dp.toPx()
                val top = 4.dp.toPx()
                for (i in 0..3) {
                    val y = top + (bottom - top) * i / 3f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                val step = size.width / daily.size
                var previous: Offset? = null
                values.forEachIndexed { index, value ->
                    if (value == null) {
                        // Missing days break the line; they are not zero-cost observations.
                        previous = null
                    } else {
                        val point =
                            Offset(
                                step * (index + 0.5f),
                                bottom - (value / max).toFloat() * (bottom - top),
                            )
                        drawLine(
                            primary.copy(alpha = 0.15f),
                            Offset(point.x, bottom),
                            point,
                            strokeWidth = (step * 0.5f).coerceAtLeast(1.dp.toPx()),
                        )
                        previous?.let { drawLine(primary, it, point, strokeWidth = 2.dp.toPx()) }
                        if (!compact && index == selected) drawCircle(primary, 4.dp.toPx(), point)
                        previous = point
                    }
                }
            }
            if (!compact && daily.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        daily.first().date.takeLast(5),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(daily.last().date.takeLast(5), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (!compact && selected in daily.indices) {
            val day = daily[selected]
            Text(
                "${day.date}  ${usdText(chartCost(day.costUsd))}  ·  ${countText(day.requests)} 次请求",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun ModelShareChart(models: List<ModelUsage>, modifier: Modifier = Modifier) {
    val shares = remember(models) { modelShares(models) }
    val colors =
        listOf(
            MaterialTheme.colorScheme.primary,
            Color(0xFF4D91D5),
            Color(0xFFE29B58),
            Color(0xFF9A7BD1),
            MaterialTheme.colorScheme.outline,
        )
    Column(modifier) {
        if (shares.incomplete)
            Text(
                "仅统计已知费用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        if (shares.entries.isEmpty()) {
            Text(
                if (models.isNotEmpty() && models.any { chartCost(it.costUsd) == 0.0 }) "已知费用为零"
                else "暂无可用费用",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Canvas(
                    Modifier.size(112.dp).semantics {
                        contentDescription =
                            shares.entries.joinToString("，") {
                                "${it.label} ${percentText(it.fraction)}"
                            }
                    }
                ) {
                    val stroke = 16.dp.toPx()
                    var start = -90f
                    shares.entries.forEachIndexed { index, entry ->
                        val sweep = (entry.fraction * 360).toFloat()
                        drawArc(
                            colors[index],
                            start,
                            sweep,
                            false,
                            Offset(stroke / 2, stroke / 2),
                            Size(size.width - stroke, size.height - stroke),
                            style = Stroke(stroke),
                        )
                        start += sweep
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    shares.entries.forEachIndexed { index, entry ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Canvas(Modifier.size(8.dp)) { drawCircle(colors[index]) }
                            Text(
                                entry.label,
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                percentText(entry.fraction),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun QuotaRing(
    amount: Double?,
    limit: Double?,
    modifier: Modifier = Modifier,
    remaining: Boolean = true,
) {
    val ratio = quotaRatio(amount, limit)
    val caption = if (remaining) "剩余" else "已用"
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val color =
        if (ratio != null && (if (remaining) ratio <= 0.1 else ratio >= 1)) Color(0xFFE29B58)
        else MaterialTheme.colorScheme.primary
    val description = "$caption ${chartCost(amount) ?: "—"} / 限额 ${chartCost(limit) ?: "—"}"
    Box(
        modifier.semantics { contentDescription = description },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (size.minDimension * 0.1f).coerceAtLeast(1f)
            val diameter = size.minDimension - stroke
            val offset = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            drawArc(
                track,
                -90f,
                360f,
                false,
                offset,
                Size(diameter, diameter),
                style = Stroke(stroke),
            )
            if (ratio != null)
                drawArc(
                    color,
                    -90f,
                    (ratio.coerceAtMost(1.0) * 360).toFloat(),
                    false,
                    offset,
                    Size(diameter, diameter),
                    style = Stroke(stroke),
                )
        }
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text(
                ratio?.let(::percentText) ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun TokenVolumeChart(statistics: AccountStatistics, modifier: Modifier = Modifier) {
    val entries =
        listOf(
            "输入" to statistics.inputTokens,
            "输出" to statistics.outputTokens,
            "缓存读" to statistics.cachedInputTokens,
            "缓存写" to statistics.cacheWriteTokens,
            "推理" to statistics.reasoningTokens,
        )
    val max = entries.mapNotNull { it.second?.takeIf { value -> value >= 0 } }.maxOrNull() ?: 0L
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        // Cache and reasoning counts can overlap other categories, so they must not be summed.
        Text(
            "独立计数，不相加",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entries.forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(label, Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall)
                Canvas(
                    Modifier.weight(1f).height(8.dp).semantics {
                        contentDescription = "$label ${countText(value)}"
                    }
                ) {
                    drawLine(
                        track,
                        Offset(0f, size.height / 2),
                        Offset(size.width, size.height / 2),
                        strokeWidth = size.height,
                    )
                    if (value != null && value >= 0 && max > 0)
                        drawLine(
                            primary,
                            Offset(0f, size.height / 2),
                            Offset(
                                size.width * (value.toDouble() / max).toFloat(),
                                size.height / 2,
                            ),
                            strokeWidth = size.height,
                        )
                }
                Text(compactCount(value), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
