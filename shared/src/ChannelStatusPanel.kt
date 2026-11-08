package com.hanserwei.krill_monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        2 -> Color(0xFFD69A45)
        0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }

@Composable
internal fun ChannelStatusPanel(
    snapshot: ChannelStatusSnapshot?,
    loading: Boolean,
    error: String?,
) {
    Panel("官网渠道状态") {
        Text(
            "最近 24 小时 · 渠道与模型运行情况",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        (error ?: snapshot?.warning)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (snapshot == null) {
            Text(if (loading) "正在获取官网渠道状态…" else "渠道状态暂不可用，可点击顶部刷新。")
            return@Panel
        }
        Text(
            "${if (snapshot.stale) "上次结果" else "获取于"} ${beijingTimeText(snapshot.fetchedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color =
                if (snapshot.stale) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (snapshot.channels.isEmpty()) {
            Text("官网暂无渠道状态数据。")
            return@Panel
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 0, null).forEach { status ->
                Surface(
                    Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor(status).copy(alpha = 0.10f),
                ) {
                    Column(
                        Modifier.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            snapshot.channels.count { it.status == status }.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(channelStatusText(status), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        snapshot.channels
            .groupBy { it.channel }
            .forEach { (name, entries) ->
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("${entries.size} 个模型", style = MaterialTheme.typography.labelSmall)
                        }
                        entries.forEachIndexed { index, entry ->
                            if (index > 0)
                                HorizontalDivider(
                                    color =
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                )
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        Modifier.size(7.dp)
                                            .background(statusColor(entry.status), CircleShape)
                                    )
                                    Text(
                                        entry.model,
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        channelStatusText(entry.status),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColor(entry.status),
                                    )
                                }
                                val buckets =
                                    remember(entry.history, snapshot.fetchedAt) {
                                        channelHistoryBuckets(entry.history, snapshot.fetchedAt)
                                    }
                                Row(
                                    Modifier.fillMaxWidth().height(12.dp).semantics {
                                        contentDescription =
                                            "${entry.model} 近24小时：" +
                                                buckets.joinToString("、") { channelStatusText(it) }
                                    },
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    buckets.forEach { status ->
                                        Box(
                                            Modifier.weight(1f)
                                                .fillMaxHeight()
                                                .background(
                                                    statusColor(status),
                                                    RoundedCornerShape(2.dp),
                                                )
                                        )
                                    }
                                }
                                val metrics = buildList {
                                    entry.throughputTps?.let {
                                        add("速度 ${channelMetricText(it)} tokens/s")
                                    }
                                    entry.cacheRate?.let {
                                        add("缓存 ${channelMetricText(it * 100)}%")
                                    }
                                    entry.imageTimeMillis?.let {
                                        add("绘图 ${channelMetricText(it / 1000)} 秒")
                                    }
                                }
                                if (metrics.isNotEmpty())
                                    Text(
                                        metrics.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                entry.history.lastOrNull()?.let {
                                    Text(
                                        "最新采样 ${beijingTimeText(it.at)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        Text(
            "每格代表 1 小时，以该小时内最差的已知状态着色；灰色表示无有效样本。渠道状态由官网提供，线路耗时由本机探测。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
