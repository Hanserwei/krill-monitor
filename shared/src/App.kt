package com.hanserwei.krill_monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hanserwei.krill_monitor.resources.Res
import com.hanserwei.krill_monitor.resources.noto_sans_sc
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.reload.DevelopmentEntryPoint
import org.jetbrains.compose.resources.Font

private data class Connection(val key: String, val line: RelayLine)

@Composable
@Preview
@DevelopmentEntryPoint
fun App() {
    val fontFamily = FontFamily(Font(Res.font.noto_sans_sc))
    val colors =
        if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF80D5C1))
        else
            lightColorScheme(
                primary = Color(0xFF006B5B),
                primaryContainer = Color(0xFFB8F2DE),
                background = Color(0xFFF4F7F6),
            )
    MaterialTheme(colorScheme = colors, typography = Typography(fontFamily = fontFamily)) {
        var key by remember { mutableStateOf("") }
        var selectedLine by remember { mutableStateOf(RelayLine.Overseas) }
        var connection by remember { mutableStateOf<Connection?>(null) }
        var days by remember { mutableIntStateOf(7) }
        var revision by remember { mutableIntStateOf(0) }
        // Key 只活在当前会话；换 Key 时销毁客户端及其缓存，避免串用账户数据。
        val client = remember(connection?.key) { connection?.let { MonitorClient() } }
        var snapshot by remember(connection, days) { mutableStateOf<DashboardSnapshot?>(null) }
        var loading by remember(connection, days) { mutableStateOf(false) }
        var error by remember(connection, days) { mutableStateOf<String?>(null) }

        DisposableEffect(client) { onDispose { client?.close() } }
        // 配置变化会取消旧查询，避免迟到的结果覆盖新 Key 或日期范围。
        LaunchedEffect(connection, days, revision) {
            val active = connection ?: return@LaunchedEffect
            val api = client ?: return@LaunchedEffect
            loading = true
            error = null
            try {
                snapshot = api.load(active.key, active.line, days)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MonitorException) {
                snapshot = null
                error = e.message ?: "查询失败，请稍后重试。"
            } catch (_: Exception) {
                snapshot = null
                error = "无法完成查询，请检查网络后重试。Web 端还需要中转站允许跨域请求。"
            } finally {
                loading = false
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                Modifier.fillMaxSize().safeContentPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 1120.dp).fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Krill Monitor",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("中转账户与线路监控", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        ConnectionPanel(
                            key = key,
                            onKeyChange = { key = it },
                            line = selectedLine,
                            onLineChange = { selectedLine = it },
                            connected = connection != null,
                            onConnect = {
                                val next = Connection(key.trim(), selectedLine)
                                if (connection == next) revision++ else connection = next
                            },
                            onClear = {
                                key = ""
                                connection = null
                                error = null
                            },
                        )
                    }
                    if (connection != null) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${connection!!.line.label} · Key 尾号 ${connection!!.key.takeLast(4)}",
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        snapshot?.let { "查询于 ${utcTimeText(it.updatedAt)}" }
                                            ?: "等候查询结果",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedButton(onClick = { revision++ }, enabled = !loading) {
                                    Text("刷新")
                                }
                            }
                        }
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "用量统计",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                listOf(7, 30).forEach { value ->
                                    FilterChip(
                                        selected = days == value,
                                        onClick = { days = value },
                                        label = { Text("${value}天") },
                                    )
                                }
                            }
                        }
                        if (loading)
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text(
                                        if (snapshot == null) "正在查询账户、用量与线路…"
                                        else "正在刷新，当前显示上次查询结果…",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                    }
                    error?.let { message -> item { Notice(message, error = true) } }
                    val data = snapshot
                    if (data != null) {
                        item {
                            Notice("统计归属由服务端决定，可能是账户汇总；当前不能确认是单个 Key 的独立用量。")
                        }
                        data.warnings.distinct().forEach { message ->
                            item { Notice(message, error = true) }
                        }
                        item { Overview(data, days) }
                        item { AccountPanel(data.account, data.updatedAt) }
                        val usage = data.usage
                        if (usage == null) item { Notice("用量数据暂不可用。接口不支持或查询失败时，不会按零用量展示。") }
                        else {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "${if (usage.stale) "过期缓存" else if (usage.fromCache) "缓存结果" else "最新结果"} · ${utcTimeText(usage.fetchedAt)} · 日期按 UTC",
                                        style = MaterialTheme.typography.bodySmall,
                                        color =
                                            if (usage.stale) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "用量缓存 30 分钟；刷新账户与线路时会复用缓存。",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    usage.warnings.distinct().forEach { Notice(it, error = true) }
                                }
                            }
                            item { DailyPanel(usage.daily) }
                            item { ModelPanel(usage.models) }
                        }
                        item { LinesPanel(data.lines) }
                    } else if (!loading && error == null)
                        item {
                            Panel("开始监控") {
                                Text("输入中转 Key，选择查询线路，然后开始监控。")
                                Text(
                                    "这里会展示账户额度、每日费用、模型用量和三条线路的状态。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Key 仅保留在当前会话，关闭或清除后需要重新输入。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    item {
                        Text(
                            "金额单位 USD · 未知数据以 — 表示 · 线路探测不发送 Key",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionPanel(
    key: String,
    onKeyChange: (String) -> Unit,
    line: RelayLine,
    onLineChange: (RelayLine) -> Unit,
    connected: Boolean,
    onConnect: () -> Unit,
    onClear: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val invalid = key.trim().any { it.isWhitespace() || it.code < 32 || it.code in 127..159 }
    val canConnect = key.isNotBlank() && !invalid
    Panel("连接配置") {
        OutlinedTextField(
            value = key,
            onValueChange = onKeyChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "中转 API Key" },
            label = { Text("中转 API Key") },
            singleLine = true,
            isError = invalid,
            supportingText = { Text(if (invalid) "Key 不能包含空白或控制字符。" else "仅保留在当前会话，不写入本地存储。") },
            visualTransformation =
                if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") }
            },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (canConnect) onConnect() }),
        )
        Text("查询线路", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RelayLine.entries.forEach { option ->
                FilterChip(
                    selected = line == option,
                    onClick = { onLineChange(option) },
                    label = { Text(option.label) },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    visible = false
                    onConnect()
                },
                enabled = canConnect,
            ) {
                Text(if (connected) "应用配置" else "开始监控")
            }
            if (key.isNotEmpty() || connected)
                TextButton(
                    onClick = {
                        visible = false
                        onClear()
                    }
                ) {
                    Text("清除 Key")
                }
        }
    }
}

@Composable
private fun Overview(data: DashboardSnapshot, days: Int) {
    val account = data.account
    val daily = data.usage?.daily.orEmpty()
    val metrics =
        listOf(
            Triple(
                "剩余额度",
                if (account?.unlimited == true) "不限额" else usdText(account?.remainingUsd),
                "额度减当月消费的计算值",
            ),
            Triple("当月消费", usdText(account?.usedUsd), "UTC 月初至今"),
            Triple(
                "近${days}天费用",
                usdText(sumAmounts(daily.map { it.costUsd })),
                "USD · 可能包含账户其他 Key",
            ),
            Triple("近${days}天请求", countText(sumCounts(daily.map { it.requests })), "服务端聚合请求数"),
        )
    BoxWithConstraints {
        val columns = if (maxWidth < 420.dp) 1 else if (maxWidth < 840.dp) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            metrics.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value, detail) ->
                        Surface(
                            Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    value,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountPanel(account: AccountSummary?, now: kotlin.time.Instant) {
    Panel("账户与额度") {
        if (account == null) Text("账户信息暂不可用。")
        else {
            Text(account.email ?: "服务端未提供账户邮箱", style = MaterialTheme.typography.titleMedium)
            Text("套餐：${account.plan ?: "未提供"}")
            Text("订阅额度：${if (account.unlimited) "不限额" else usdText(account.limitUsd)}")
            val expires = account.expiresAt
            Text(
                if (expires == null) "到期时间：未提供或未设置"
                else "到期时间：${utcTimeText(expires)}${if (expires <= now) " · 已到期" else ""}",
                color =
                    if (expires != null && expires <= now) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
            if (
                !account.unlimited &&
                    account.limitUsd != null &&
                    account.limitUsd > 0 &&
                    account.usedUsd != null
            ) {
                LinearProgressIndicator(
                    progress = { (account.usedUsd / account.limitUsd).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            account.warnings.distinct().forEach { Notice(it, error = true) }
        }
    }
}

@Composable
private fun DailyPanel(daily: List<DailyUsage>) {
    Panel("每日费用") {
        Text(
            "日期按 UTC · 费用 / 请求数",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val maximum = daily.mapNotNull { it.costUsd }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        if (daily.isEmpty()) Text("当前范围内没有每日统计。")
        daily.forEach { day ->
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    day.date.takeLast(5),
                    Modifier.width(48.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val cost = day.costUsd
                val barModifier = Modifier.weight(1f).height(8.dp)
                if (cost == null) {
                    // 未知值不设置进度语义，避免读屏将它当成零费用。
                    Box(
                        barModifier
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .semantics {
                                contentDescription = "${day.date} 费用未知"
                            }
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { (cost / maximum).toFloat().coerceIn(0f, 1f) },
                        modifier =
                            barModifier.semantics {
                                contentDescription = "${day.date} 费用 ${usdText(cost)}"
                            },
                    )
                }
                Column(Modifier.widthIn(min = 84.dp), horizontalAlignment = Alignment.End) {
                    Text(usdText(day.costUsd), fontWeight = FontWeight.Medium)
                    Text(
                        "${countText(day.requests)} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPanel(models: List<ModelUsage>) {
    Panel("模型用量排行") {
        Text(
            "按费用优先排序；费用缺失时按请求数排序。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (models.isEmpty()) Text("当前范围内没有模型记录，或服务端未提供模型统计。")
        models.forEachIndexed { index, model ->
            if (index > 0) HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${index + 1}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    model.model,
                    Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(usdText(model.costUsd), fontWeight = FontWeight.SemiBold)
            }
            Text(
                "请求 ${countText(model.requests)} · 输入 ${countText(model.inputTokens)} · 输出 ${countText(model.outputTokens)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "缓存输入 ${countText(model.cachedInputTokens)} · 缓存占比 ${cacheRatioText(model.cachedInputTokens, model.inputTokens)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LinesPanel(lines: List<LineHealth>) {
    Panel("线路状态") {
        Text(
            "不带 Key 的 HTTP 探测；401 表示线路可达，耗时不代表模型生成速度。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lines.forEach { health ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(8.dp)
                        .background(
                            if (health.reachable) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            CircleShape,
                        )
                )
                Column(Modifier.weight(1f)) {
                    Text(health.line.label, fontWeight = FontWeight.Medium)
                    Text(
                        health.message ?: health.status?.let { "HTTP $it" } ?: "无法连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(health.latencyMillis?.let { "$it ms" } ?: "—")
            }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun Notice(message: String, error: Boolean = false) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color =
            if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            message,
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (error) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
