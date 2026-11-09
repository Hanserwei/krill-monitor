package com.hanserwei.krill_monitor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToLong
import kotlin.time.Instant

internal fun channelStatusText(status: Int?): String =
    when (status) {
        1 -> "正常"
        2 -> "波动"
        0 -> "异常"
        else -> "未知"
    }

internal fun channelHistoryBuckets(
    history: List<ChannelStatusPoint>,
    fetchedAt: Instant,
): List<Int?> {
    val end = fetchedAt.epochSeconds
    val start = end - 86400
    val result = MutableList<Int?>(24) { null }
    fun priority(status: Int?) =
        when (status) {
            0 -> 3
            2 -> 2
            1 -> 1
            else -> 0
        }
    history
        .filter { it.at.epochSeconds in start..end }
        .forEach { point ->
            val index = ((point.at.epochSeconds - start) / 3600).toInt().coerceAtMost(23)
            if (priority(point.status) > priority(result[index])) result[index] = point.status
        }
    return result
}

internal fun channelMetricText(value: Double?): String {
    if (value == null || !value.isFinite() || value < 0 || value >= Long.MAX_VALUE.toDouble() / 10)
        return "—"
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}

@Composable
private fun statusColor(status: Int?): Color =
    when (status) {
        1 -> MaterialTheme.colorScheme.primary
        2 -> MaterialTheme.colorScheme.tertiary
        0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ChannelStatusPanel(
    snapshot: ChannelStatusSnapshot?,
    loading: Boolean,
    error: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("官网渠道状态", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("官网公开的渠道与模型状态，最近 24 小时。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) Text(if (snapshot != null) "刷新失败，保留上次结果：$error" else error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        snapshot?.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (snapshot == null) {
            Text(if (loading) "正在获取官网渠道状态…" else "渠道状态暂不可用，可点击顶部刷新。")
        } else {
            Text("${if (snapshot.stale) "缓存的上次结果（可能已过期）" else "获取于"} ${beijingTimeText(snapshot.fetchedAt)}", style = MaterialTheme.typography.labelSmall, color = if (snapshot.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            val groups = snapshot.channels.groupBy { it.channel }.entries.toList()
            if (groups.isEmpty()) Text("官网暂无渠道状态数据。")
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                groups.forEachIndexed { index, (name, entries) ->
                    var expanded by remember(name) { mutableStateOf(false) }
                    val interactionSource = remember(name) { MutableInteractionSource() }
                    val status = when {
                        entries.any { it.status == 0 } -> 0
                        entries.any { it.status == 2 } -> 2
                        entries.any { it.status != 1 } -> null
                        else -> 1
                    }
                    Surface(Modifier.fillMaxWidth().clip(groupedShape(index, groups.size)), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec())) {
                            ListItem(
                                modifier = Modifier.pressScale(interactionSource).semantics { stateDescription = if (expanded) "已展开" else "已收起" }.clickable(interactionSource = interactionSource, indication = ripple()) { expanded = !expanded },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                headlineContent = { Text(name) },
                                supportingContent = { Text("${channelStatusText(status)} · ${entries.size} 个模型") },
                                leadingContent = {
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            MonitorIcon(if (status == 1) "check_circle" else "error_outline", contentDescription = channelStatusText(status), tint = if (status == null) MaterialTheme.colorScheme.onPrimaryContainer else statusColor(status), modifier = Modifier.size(22.dp))
                                        }
                                    }
                                },
                                trailingContent = { MonitorIcon(if (expanded) "expand_less" else "expand_more", contentDescription = if (expanded) "收起" else "展开") },
                            )
                            if (expanded) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                entries.forEach { entry ->
                                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(entry.model, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                            Text(channelStatusText(entry.status), style = MaterialTheme.typography.labelSmall, color = statusColor(entry.status))
                                        }
                                        val buckets = remember(entry.history, snapshot.fetchedAt) { channelHistoryBuckets(entry.history, snapshot.fetchedAt) }
                                        Row(Modifier.fillMaxWidth().height(12.dp).semantics {
                                            contentDescription = "${entry.model} 近24小时，从早到晚：" + buckets.joinToString("、") { channelStatusText(it) }
                                        }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                            buckets.forEach { bucket -> Box(Modifier.weight(1f).fillMaxHeight().background(statusColor(bucket), RoundedCornerShape(2.dp))) }
                                        }
                                        val cache = channelMetricText(entry.cacheRate?.times(100))
                                        Text("速度 ${channelMetricText(entry.throughputTps)} tokens/s · 缓存 ${if (cache == "—") cache else "$cache%"} · 绘图 ${channelMetricText(entry.imageTimeMillis?.div(1000))} 秒", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        entry.history.lastOrNull()?.let { Text("最新采样 ${beijingTimeText(it.at)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text("官网公开状态仅反映其提供的渠道与模型数据；缺失性能数据以 — 表示。每格代表 1 小时，以该小时内最差的已知状态着色；空缺表示无有效样本。线路耗时由本机探测。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
