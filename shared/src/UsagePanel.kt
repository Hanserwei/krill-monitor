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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Instant

internal fun sortedUsageModels(models: List<ModelUsage>, byTokens: Boolean): List<ModelUsage> =
    if (byTokens) models.sortedWith(compareByDescending { it.totalTokens?.takeIf { value -> value >= 0 } })
    else models.sortedWith(compareByDescending { chartCost(it.costUsd) })

@Composable
internal fun UsagePanel(data: AccountDashboard?, days: Int, onDaysChange: (Int) -> Unit, wide: Boolean = false, loadModelDetails: suspend (model: String, refresh: Boolean) -> ModelStatisticsReport) {
    val usage = data?.usage
    var selectedDate by remember(usage, days) { mutableStateOf<String?>(null) }
    var byTokens by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    val daily = remember(usage, days) { usage?.daily?.takeLast(days) ?: emptyList() }
    val selected = daily.firstOrNull { it.date == selectedDate }
    val models = remember(usage, byTokens) { sortedUsageModels(usage?.models ?: emptyList(), byTokens) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PrimaryTabRow(selectedTabIndex = if (days == 30) 1 else 0, modifier = if (wide) Modifier.width(280.dp) else Modifier.fillMaxWidth()) {
            listOf(7, 30).forEach { period ->
                Tab(selected = days == period, onClick = { onDaysChange(period) }, text = { Text("${period}天") })
            }
        }
        if (data == null) {
            Text("暂无用量数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            data.warnings.distinct().forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val tokenDetails: @Composable () -> Unit = {
                val stats = data.statistics
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Token 缓存与推理", style = MaterialTheme.typography.titleMedium)
                        Text("缓存读取 ${countText(stats?.cachedInputTokens)} · 缓存写入 ${countText(stats?.cacheWriteTokens)}", style = MaterialTheme.typography.bodyMedium)
                        Text("推理 ${countText(stats?.reasoningTokens)} · 缓存占比 ${accountCacheRatioText(stats)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (usage == null) {
                Text("用量数据暂不可用；接口不支持或查询失败时不会按零用量展示。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                tokenDetails()
            } else {
                Text(
                    "${if (usage.stale) "过期缓存" else if (usage.fromCache) "缓存结果" else "最新结果"} · ${beijingTimeText(usage.fetchedAt)} · 日期按 ${usage.timezone}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (usage.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                usage.warnings.distinct().forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                usage.costNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val trendCard: @Composable () -> Unit = {
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
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val scrollChart = days == 30 || maxWidth < 336.dp
                                    Row(if (scrollChart) Modifier.fillMaxWidth().horizontalScroll(scrollState) else Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                                        daily.forEach { day ->
                                            val value = chartCost(day.costUsd)
                                            val height = if (value == null || maximum == null || maximum <= 0) 0.dp else ((if (wide) 136 else 100) * value / maximum).toFloat().dp
                                            val interaction = remember(day.date) { MutableInteractionSource() }
                                            Column(
                                                (if (scrollChart) Modifier.width(48.dp) else Modifier.weight(1f).widthIn(min = 48.dp)).clip(RoundedCornerShape(8.dp))
                                                    .pressScale(interaction)
                                                    .clickable(interactionSource = interaction, indication = ripple()) { selectedDate = day.date }
                                                    .semantics { contentDescription = "${day.date}，费用 ${usdText(day.costUsd)}，请求 ${countText(day.requests)}" }
                                                    .padding(vertical = 4.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                Box(Modifier.height(if (wide) 140.dp else 104.dp), contentAlignment = Alignment.BottomCenter) {
                                                    if (value == null) Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    else Box(Modifier.width(28.dp).height(if (height < 3.dp) 3.dp else height).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(if (day.date == (selectedDate ?: daily.lastOrNull()?.date)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer))
                                                }
                                                Text(day.date.takeLast(5).replace('-', '.'), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                                Text("点击日期查看当天费用与请求数", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                val modelRanking: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                    key(model.model) {
                                        val interaction = remember(model.model) { MutableInteractionSource() }
                                        Column(Modifier.fillMaxWidth().clip(groupedShape(index, models.size)).background(MaterialTheme.colorScheme.surfaceContainerLow).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                                            ListItem(
                                                modifier = Modifier.pressScale(interaction).semantics { stateDescription = if (model.model in expanded) "已展开" else "已收起" }.clickable(interactionSource = interaction, indication = ripple()) { expanded = if (model.model in expanded) expanded - model.model else expanded + model.model },
                                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                                leadingContent = { Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { MonitorIcon("auto_awesome", tint = MaterialTheme.colorScheme.onPrimaryContainer) } },
                                                headlineContent = { Text(model.model) },
                                                supportingContent = { Text("${usdText(model.costUsd)} · 请求 ${countText(model.requests)} · Token ${compactCount(model.totalTokens)}") },
                                                trailingContent = { MonitorIcon(if (model.model in expanded) "expand_less" else "expand_more") },
                                            )
                                            if (model.model in expanded) {
                                                ModelUsageDetails(model.model, days, usage.fetchedAt, loadModelDetails)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(0.6f).widthIn(min = 400.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            trendCard()
                            tokenDetails()
                        }
                        Column(Modifier.weight(0.4f)) { modelRanking() }
                    }
                } else {
                    trendCard()
                    modelRanking()
                    tokenDetails()
                }
            }

        }
    }
}

@Composable
private fun ModelUsageDetails(
    model: String,
    days: Int,
    fetchedAt: Instant,
    loader: suspend (String, Boolean) -> ModelStatisticsReport,
) {
    var report by remember(model, days, fetchedAt) { mutableStateOf<ModelStatisticsReport?>(null) }
    var error by remember(model, days, fetchedAt) { mutableStateOf<String?>(null) }
    var loading by remember(model, days, fetchedAt) { mutableStateOf(true) }
    var revision by remember(model, days, fetchedAt) { mutableIntStateOf(0) }
    val currentLoader by rememberUpdatedState(loader)

    LaunchedEffect(model, days, fetchedAt, revision) {
        loading = true
        error = null
        try {
            val result = currentLoader(model, revision > 0)
            currentCoroutineContext().ensureActive()
            report = result
        } catch (e: CancellationException) {
            throw e
        } catch (_: AccountSessionExpired) {
            currentCoroutineContext().ensureActive()
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            error = "模型明细查询失败，请稍后重试"
        }
        loading = false
    }

    Column(Modifier.padding(start = 72.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        report?.let { result ->
            val stats = result.statistics
            Text("输入 ${countText(stats.inputTokens)} · 输出 ${countText(stats.outputTokens)}", style = MaterialTheme.typography.bodySmall)
            Text("缓存命中 ${countText(stats.cachedInputTokens)} · 缓存占比 ${accountCacheRatioText(stats)}", style = MaterialTheme.typography.bodySmall)
            Text("缓存写入 ${countText(stats.cacheWriteTokens)} · 推理 ${countText(stats.reasoningTokens)}", style = MaterialTheme.typography.bodySmall)
            if (listOf(stats.inputTokens, stats.outputTokens, stats.cachedInputTokens, stats.cacheWriteTokens, stats.reasoningTokens).any { it == null || it < 0 }) {
                Text("部分明细未提供", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${if (result.stale) "过期缓存" else "明细时间"} · ${beijingTimeText(result.fetchedAt)}", style = MaterialTheme.typography.bodySmall, color = if (result.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            result.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (loading) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("正在查询该模型明细", style = MaterialTheme.typography.bodySmall)
            }
        } else if (report != null || error != null) {
            TextButton(onClick = { revision++ }) { Text(if (report == null) "重试" else "刷新明细") }
        }
    }
}
