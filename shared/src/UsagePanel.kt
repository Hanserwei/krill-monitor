@file:OptIn(kotlin.time.ExperimentalTime::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.hanserwei.krill_monitor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first

internal fun sortedUsageModels(models: List<ModelUsage>, byTokens: Boolean): List<ModelUsage> =
    if (byTokens) models.sortedWith(compareByDescending { it.totalTokens?.takeIf { value -> value >= 0 } })
    else models.sortedWith(compareByDescending { chartCost(it.costUsd) })

@Composable
internal fun UsagePanel(data: AccountDashboard?, days: Int, onDaysChange: (Int) -> Unit) {
    val usage = data?.usage
    var selectedDate by remember(usage, days) { mutableStateOf<String?>(null) }
    var byTokens by rememberSaveable { mutableStateOf(false) }
    var expanded by remember(usage, days) { mutableStateOf<Set<Int>>(emptySet()) }
    val daily = remember(usage, days) { usage?.daily?.takeLast(days) ?: emptyList() }
    val selected = daily.firstOrNull { it.date == selectedDate }
    val models = remember(usage, byTokens) { sortedUsageModels(usage?.models ?: emptyList(), byTokens) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PrimaryTabRow(selectedTabIndex = if (days == 30) 1 else 0) {
            listOf(7, 30).forEach { period ->
                Tab(selected = days == period, onClick = { onDaysChange(period) }, text = { Text("${period}天") })
            }
        }
        if (data == null) {
            Text("暂无用量数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            data.warnings.distinct().forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (usage == null) {
                Text("用量数据暂不可用；接口不支持或查询失败时不会按零用量展示。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "${if (usage.stale) "过期缓存" else if (usage.fromCache) "缓存结果" else "最新结果"} · ${beijingTimeText(usage.fetchedAt)} · 日期按 ${usage.timezone}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (usage.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                usage.warnings.distinct().forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                usage.costNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (selected == null) "近${days}天费用 · USD" else "${selected.date} 费用 · USD", style = MaterialTheme.typography.labelLarge)
                        Text(usdText(if (selected == null) chartCost(data.statistics?.costUsd) ?: if (daily.size == days) sumAmounts(daily.map { chartCost(it.costUsd) }) else null else selected.costUsd), style = MaterialTheme.typography.displaySmall, fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (selected == null) "请求 ${countText(data.statistics?.requests?.takeIf { it >= 0 } ?: if (daily.size == days) sumCounts(daily.map { it.requests }) else null)} · Token ${compactCount(data.statistics?.totalTokens?.takeIf { it >= 0 } ?: if (daily.size == days) sumCounts(daily.map { sumCounts(listOf(it.inputTokens, it.outputTokens)) }) else null)}"
                            else "请求 ${countText(selected.requests)} · Token ${compactCount(sumCounts(listOf(selected.inputTokens, selected.outputTokens)))}",
                            style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selected != null) TextButton(onClick = { selectedDate = null }) { Text("返回总体") }
                        if (daily.isEmpty()) {
                            Text("当前范围内暂无每日用量记录。", style = MaterialTheme.typography.bodySmall)
                        } else {
                            val maximum = daily.mapNotNull { chartCost(it.costUsd) }.maxOrNull()
                            val scrollState = rememberScrollState()
                            LaunchedEffect(days, daily, scrollState) {
                                if (days == 30) scrollState.scrollTo(snapshotFlow { scrollState.maxValue }.first { it > 0 && it != Int.MAX_VALUE })
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(scrollState), verticalAlignment = Alignment.Bottom) {
                                daily.forEach { day ->
                                    val value = chartCost(day.costUsd)
                                    val height = if (value == null || maximum == null || maximum <= 0) 0.dp else (100 * value / maximum).toFloat().dp
                                    val interaction = remember(day.date) { MutableInteractionSource() }
                                    Column(
                                        Modifier.width(48.dp).clip(RoundedCornerShape(8.dp))
                                            .pressScale(interaction)
                                            .clickable(interactionSource = interaction, indication = ripple()) { selectedDate = day.date }
                                            .semantics { contentDescription = "${day.date}，费用 ${usdText(day.costUsd)}，请求 ${countText(day.requests)}" }
                                            .padding(vertical = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(Modifier.height(104.dp), contentAlignment = Alignment.BottomCenter) {
                                            if (value == null) Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            else Box(Modifier.width(28.dp).height(if (height < 3.dp) 3.dp else height).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(if (day.date == (selectedDate ?: daily.lastOrNull()?.date)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer))
                                        }
                                        Text(day.date.takeLast(5).replace('-', '.'), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                            Text("点击日期查看当天费用与请求数", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("模型用量排行", style = MaterialTheme.typography.headlineSmall, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(false to "费用", true to "Token").forEachIndexed { index, (tokens, label) ->
                        FilterChip(
                            selected = byTokens == tokens,
                            onClick = { byTokens = tokens; expanded = emptySet() },
                            label = { Text(label) },
                            leadingIcon = if (byTokens == tokens) ({ MonitorIcon("check", modifier = Modifier.size(18.dp)) }) else null,
                            shape = if (index == 0) RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 4.dp, bottomEnd = 4.dp) else RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 8.dp, bottomEnd = 8.dp),
                        )
                    }
                }
                if (models.isEmpty()) {
                    Text("当前范围内没有模型记录，或服务端未提供模型统计。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        models.forEachIndexed { index, model ->
                            val interaction = remember(index, model) { MutableInteractionSource() }
                            Column(Modifier.fillMaxWidth().clip(groupedShape(index, models.size)).background(MaterialTheme.colorScheme.surfaceContainerLow).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                                ListItem(
                                    modifier = Modifier.pressScale(interaction).clickable(interactionSource = interaction, indication = ripple()) { expanded = if (index in expanded) expanded - index else expanded + index },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    leadingContent = { Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { MonitorIcon("auto_awesome", tint = MaterialTheme.colorScheme.onPrimaryContainer) } },
                                    headlineContent = { Text(model.model) },
                                    supportingContent = { Text("${usdText(model.costUsd)} · 请求 ${countText(model.requests)} · Token ${compactCount(model.totalTokens)}") },
                                    trailingContent = { MonitorIcon(if (index in expanded) "expand_less" else "expand_more", contentDescription = if (index in expanded) "收起明细" else "展开明细") },
                                )
                                if (index in expanded) {
                                    Column(Modifier.padding(start = 72.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("输入 ${countText(model.inputTokens)} · 输出 ${countText(model.outputTokens)}", style = MaterialTheme.typography.bodySmall)
                                        Text("缓存命中 ${countText(model.cachedInputTokens)} · 占输入 ${cacheRatioText(model.cachedInputTokens, model.inputTokens)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val stats = data.statistics
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Token 缓存与推理", style = MaterialTheme.typography.titleMedium)
                    Text("缓存读取 ${countText(stats?.cachedInputTokens)} · 缓存写入 ${countText(stats?.cacheWriteTokens)}", style = MaterialTheme.typography.bodyMedium)
                    Text("推理 ${countText(stats?.reasoningTokens)} · 缓存占输入 ${cacheRatioText(stats?.cachedInputTokens, stats?.inputTokens)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
