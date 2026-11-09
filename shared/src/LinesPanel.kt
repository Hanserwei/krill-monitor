package com.hanserwei.krill_monitor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LinesPanel(lines: List<LineHealth>, loading: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("线路可达", fontSize = 14.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${if (lines.isEmpty()) "—" else lines.count { it.reachable }} / ${RelayLine.entries.size}", Modifier.weight(1f), fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MonitorIcon("check_circle", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                        Text(if (loading) "探测中" else if (lines.isEmpty()) "尚无结果" else "刚刚更新", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text("匿名探测，不发送推理请求", fontSize = 12.sp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            RelayLine.entries.forEachIndexed { index, line ->
                val health = lines.firstOrNull { it.line == line }
                var expanded by remember(line) { mutableStateOf(false) }
                val interactionSource = remember(line) { MutableInteractionSource() }
                val symbol = when (line) {
                    RelayLine.Overseas -> "public"
                    RelayLine.China -> "language"
                    RelayLine.Cdn -> "hub"
                }
                Surface(Modifier.fillMaxWidth().clip(groupedShape(index, RelayLine.entries.size)), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec())) {
                        ListItem(
                            modifier = Modifier.pressScale(interactionSource).semantics { stateDescription = if (expanded) "已展开" else "已收起" }.clickable(interactionSource = interactionSource, indication = ripple()) { expanded = !expanded },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            headlineContent = { Text(line.label) },
                            supportingContent = {
                                Text(when {
                                    health == null -> "—"
                                    health.reachable && health.latencyMillis != null && health.latencyMillis >= 0 -> "${health.latencyMillis} ms · 可达"
                                    health.reachable -> "可达 · —"
                                    else -> health.latencyMillis?.takeIf { it >= 0 }?.let { "$it ms · 失败" } ?: "失败"
                                })
                            },
                            leadingContent = {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                                    Box(contentAlignment = Alignment.Center) { MonitorIcon(symbol, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
                                }
                            },
                            trailingContent = {
                                if (health != null) MonitorIcon(if (health.reachable) "check_circle" else "error_outline", contentDescription = if (health.reachable) "可达" else "失败", tint = if (health.reachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                else Text("—")
                            },
                        )
                        if (expanded) Column(Modifier.padding(start = 72.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("端点：${line.origin}/v1/models", style = MaterialTheme.typography.bodySmall)
                            Text("HTTP：${health?.status?.toString() ?: "—"}", style = MaterialTheme.typography.bodySmall)
                            Text("错误：${health?.message ?: "—"}", style = MaterialTheme.typography.bodySmall)
                            Text("匿名 HTTP 探测，200 或 401 表示接口可达；不发送推理请求。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Text("线路延迟不等于 TTFT（首 token 时间）。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
