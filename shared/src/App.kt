package com.hanserwei.krill_monitor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hanserwei.krill_monitor.resources.Res
import com.hanserwei.krill_monitor.resources.krill_logo
import com.hanserwei.krill_monitor.resources.noto_sans_sc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jetbrains.compose.reload.DevelopmentEntryPoint
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource

private fun navIcon(name: String, draw: PathBuilder.() -> Unit) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
                pathBuilder = draw,
            )
        }
        .build()

private enum class Page(val label: String, val icon: ImageVector) {
    Overview(
        "概览",
        navIcon("Dashboard") {
            moveTo(3f, 3f)
            lineTo(11f, 3f)
            lineTo(11f, 11f)
            lineTo(3f, 11f)
            close()
            moveTo(13f, 3f)
            lineTo(21f, 3f)
            lineTo(21f, 8f)
            lineTo(13f, 8f)
            close()
            moveTo(13f, 10f)
            lineTo(21f, 10f)
            lineTo(21f, 21f)
            lineTo(13f, 21f)
            close()
            moveTo(3f, 13f)
            lineTo(11f, 13f)
            lineTo(11f, 21f)
            lineTo(3f, 21f)
            close()
        },
    ),
    Usage(
        "用量",
        navIcon("BarChart") {
            moveTo(3f, 13f)
            lineTo(7f, 13f)
            lineTo(7f, 21f)
            lineTo(3f, 21f)
            close()
            moveTo(10f, 3f)
            lineTo(14f, 3f)
            lineTo(14f, 21f)
            lineTo(10f, 21f)
            close()
            moveTo(17f, 8f)
            lineTo(21f, 8f)
            lineTo(21f, 21f)
            lineTo(17f, 21f)
            close()
        },
    ),
    Lines(
        "线路",
        navIcon("AccountTree") {
            moveTo(3f, 3f)
            lineTo(10f, 3f)
            lineTo(10f, 10f)
            lineTo(8f, 10f)
            lineTo(8f, 12f)
            lineTo(15f, 12f)
            lineTo(15f, 7f)
            lineTo(21f, 7f)
            lineTo(21f, 13f)
            lineTo(15f, 13f)
            lineTo(15f, 14f)
            lineTo(8f, 14f)
            lineTo(8f, 17f)
            lineTo(15f, 17f)
            lineTo(15f, 15f)
            lineTo(21f, 15f)
            lineTo(21f, 21f)
            lineTo(15f, 21f)
            lineTo(15f, 19f)
            lineTo(6f, 19f)
            lineTo(6f, 10f)
            lineTo(3f, 10f)
            close()
        },
    ),
    Settings(
        "设置",
        navIcon("Settings") {
            moveTo(10f, 2f)
            lineTo(14f, 2f)
            lineTo(14.6f, 4.5f)
            lineTo(16.3f, 5.2f)
            lineTo(18.5f, 3.8f)
            lineTo(21.2f, 6.5f)
            lineTo(19.8f, 8.7f)
            lineTo(20.5f, 10.4f)
            lineTo(23f, 11f)
            lineTo(23f, 14f)
            lineTo(20.5f, 14.6f)
            lineTo(19.8f, 16.3f)
            lineTo(21.2f, 18.5f)
            lineTo(18.5f, 21.2f)
            lineTo(16.3f, 19.8f)
            lineTo(14.6f, 20.5f)
            lineTo(14f, 23f)
            lineTo(10f, 23f)
            lineTo(9.4f, 20.5f)
            lineTo(7.7f, 19.8f)
            lineTo(5.5f, 21.2f)
            lineTo(2.8f, 18.5f)
            lineTo(4.2f, 16.3f)
            lineTo(3.5f, 14.6f)
            lineTo(1f, 14f)
            lineTo(1f, 11f)
            lineTo(3.5f, 10.4f)
            lineTo(4.2f, 8.7f)
            lineTo(2.8f, 6.5f)
            lineTo(5.5f, 3.8f)
            lineTo(7.7f, 5.2f)
            lineTo(9.4f, 4.5f)
            close()
            moveTo(12f, 8f)
            curveTo(14.5f, 8f, 16.5f, 10f, 16.5f, 12.5f)
            curveTo(16.5f, 15f, 14.5f, 17f, 12f, 17f)
            curveTo(9.5f, 17f, 7.5f, 15f, 7.5f, 12.5f)
            curveTo(7.5f, 10f, 9.5f, 8f, 12f, 8f)
            close()
        },
    ),
}

@Composable
@Preview
@DevelopmentEntryPoint
fun App() {
    val fontFamily = FontFamily(Font(Res.font.noto_sans_sc))
    val colors = monitorColors(isSystemInDarkTheme())
    MaterialTheme(colorScheme = colors, typography = monitorTypography(fontFamily)) {
        val toast = rememberAppToast()
        val sessionStore = remember { createSessionStore() }
        var storageMessage by remember { mutableStateOf<String?>(null) }
        var storageLoaded by remember { mutableStateOf(false) }
        var session by remember { mutableStateOf<AccountSession?>(null) }
        var days by remember { mutableIntStateOf(7) }
        var revision by remember { mutableIntStateOf(0) }
        var page by remember { mutableStateOf(Page.Settings) }
        // 滚动状态和查询状态都放在页面外，切换页面不会重置位置或触发请求。
        val scrollStates =
            listOf(
                rememberLazyListState(),
                rememberLazyListState(),
                rememberLazyListState(),
                rememberLazyListState(),
            )
        val accountApi = remember(session?.token) { AccountClient(baseUrl = accountApiBaseUrl()) }
        DisposableEffect(accountApi) { onDispose { accountApi.close() } }
        val lineProber = remember { LineProber() }
        DisposableEffect(lineProber) { onDispose { lineProber.close() } }
        var accountData by remember(session, days) { mutableStateOf<AccountDashboard?>(null) }
        var lines by remember(session) { mutableStateOf<List<LineHealth>>(emptyList()) }
        var loading by remember(session, days) { mutableStateOf(false) }
        var error by remember(session, days) { mutableStateOf<String?>(null) }

        LaunchedEffect(sessionStore) {
            try {
                if (!sessionStore.clearLegacyKey()) storageMessage = "清除旧登录数据失败，请检查本地存储。"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                storageMessage = "清除旧登录数据失败，请检查本地存储。"
            }
            try {
                sessionStore
                    .load()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { saved ->
                        session = AccountSession(saved, null)
                        page = Page.Overview
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                storageMessage = listOfNotNull(storageMessage, "读取账号会话失败，请重新登录。").joinToString("\n")
            } finally {
                storageLoaded = true
            }
        }
        // 配置变化会取消旧查询，避免迟到的结果覆盖新账号或日期范围。
        LaunchedEffect(storageLoaded, session, days, revision) {
            if (!storageLoaded) return@LaunchedEffect
            val active = session ?: return@LaunchedEffect
            loading = true
            error = null
            try {
                coroutineScope {
                    val account = async { accountApi.load(active, days) }
                    val probes = async { lineProber.probeLines() }
                    accountData = account.await()
                    lines = probes.await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: AccountSessionExpired) {
                val cleared = runCatching { sessionStore.clear() }.getOrDefault(false)
                session = null
                page = Page.Settings
                storageMessage = if (cleared) "登录已过期，请重新登录。" else "登录已过期；本地会话删除失败，请重试退出。"
                toast(storageMessage ?: "登录已过期，请重新登录。")
            } catch (e: AccountAccessException) {
                accountData = null
                error = e.message
            } catch (_: Exception) {
                accountData = null
                error = "无法完成查询，请检查网络后重试。Web 端还需要服务端允许跨域请求。"
            } finally {
                loading = false
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            Scaffold(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!wide) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp,
                        ) {
                            Page.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = page == destination,
                                    onClick = { page = destination },
                                    icon = { Icon(destination.icon, contentDescription = null) },
                                    label = { Text(destination.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (wide) {
                        NavigationRail(
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            containerColor = MaterialTheme.colorScheme.background,
                        ) {
                            Page.entries.forEach { destination ->
                                NavigationRailItem(
                                    selected = page == destination,
                                    onClick = { page = destination },
                                    icon = { Icon(destination.icon, contentDescription = null) },
                                    label = { Text(destination.label) },
                                )
                            }
                        }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            Modifier.widthIn(max = 1120.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Image(
                                painterResource(Res.drawable.krill_logo),
                                "Krill Monitor Logo",
                                Modifier.size(42.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "KRILL MONITOR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(page.label, style = MaterialTheme.typography.titleLarge)
                                session?.let { active ->
                                    Text(
                                        "Krill 账号 · ${accountData?.email ?: active.email ?: "已授权"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (session != null) {
                                FilledTonalButton(
                                    onClick = {
                                        revision++
                                        toast("正在刷新监控数据")
                                    },
                                    enabled = !loading,
                                    contentPadding =
                                        PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Text("刷新")
                                }
                            }
                        }
                        LazyColumn(
                            state = scrollStates[page.ordinal],
                            modifier = Modifier.widthIn(max = 1120.dp).fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (page == Page.Settings) {
                                item {
                                    AccountLoginPanel(
                                        client = accountApi,
                                        connected = session != null,
                                        email = accountData?.email ?: session?.email,
                                        secureStorage = sessionStore.isSecure,
                                        onLogin = { signedIn, persist ->
                                            val stored = runCatching {
                                                if (persist) sessionStore.save(signedIn.token)
                                                else sessionStore.clear()
                                            }
                                                .getOrDefault(false)
                                            storageMessage =
                                                if (stored) null else "账号会话保存失败，仅本次登录有效。"
                                            session = signedIn
                                            page = Page.Overview
                                            if (!stored) toast("会话未能保存，下次需要重新登录")
                                        },
                                        onLogout = {
                                            val cleared = runCatching {
                                                sessionStore.clear()
                                            }
                                                .getOrDefault(false)
                                            session = null
                                            storageMessage = if (cleared) null else "账号会话清除失败，请重试。"
                                            toast(if (cleared) "已退出本应用账号" else "本地登录清除失败，请重试")
                                        },
                                    )
                                }
                                storageMessage?.let { message ->
                                    item { Notice(message, error = true) }
                                }
                                item {
                                    Panel("隐私与统计口径") {
                                        Text(
                                            if (sessionStore.isSecure)
                                                "可选择在设备上加密保存账号会话；不保存密码或验证码。线路探测不发送凭据。"
                                            else "可选择在浏览器本地保存账号会话，同源脚本可读取；不保存密码或验证码。线路探测不发送凭据。"
                                        )
                                        Text("查询个人账户；钱包余额与套餐额度独立。")
                                        Text("金额单位 USD，未知数据以 — 表示；日期按北京时间 UTC+8。")
                                    }
                                }
                            } else {
                                storageMessage?.let { message ->
                                    item { Notice(message, error = true) }
                                }
                                if (page == Page.Usage) {
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
                                }
                                if (session == null) {
                                    item {
                                        Panel("开始监控") {
                                            Text("登录 Krill 账号后即可查看${page.label}。")
                                            Button(onClick = { page = Page.Settings }) {
                                                Text("前往登录")
                                            }
                                        }
                                    }
                                } else {
                                    if (loading) {
                                        item {
                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                                Text(
                                                    if (accountData == null) "正在查询账户、用量与线路…"
                                                    else "正在刷新，当前显示上次查询结果…",
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    }
                                    error?.let { message -> item { Notice(message, error = true) } }
                                    when (page) {
                                        Page.Overview ->
                                            accountData?.let { data ->
                                                item { AccountOverview(data, days) }
                                                item { AccountSubscriptions(data) }
                                                data.warnings.forEach { message ->
                                                    item { Notice(message, error = true) }
                                                }
                                                if (
                                                    data.usage == null ||
                                                        data.warnings.isNotEmpty() ||
                                                        data.usage.warnings.isNotEmpty()
                                                ) {
                                                    item {
                                                        Notice("用量数据可能不完整，请在用量页查看详情。", error = true)
                                                    }
                                                }
                                                item {
                                                    Text(
                                                        "查询于 ${utcTimeText(data.fetchedAt)}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                }
                                            }
                                        Page.Usage ->
                                            accountData?.let { data ->
                                                data.warnings.distinct().forEach { message ->
                                                    item { Notice(message, error = true) }
                                                }
                                                val usage = data.usage
                                                if (usage == null) {
                                                    item { Notice("用量数据暂不可用；接口不支持或查询失败时不会按零用量展示。") }
                                                } else {
                                                    item {
                                                        Column(
                                                            verticalArrangement =
                                                                Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            Text(
                                                                "${if (usage.stale) "过期缓存" else if (usage.fromCache) "缓存结果" else "最新结果"} · ${utcTimeText(usage.fetchedAt)} · 日期按 ${usage.timezone}",
                                                                style =
                                                                    MaterialTheme.typography
                                                                        .bodySmall,
                                                                color =
                                                                    if (usage.stale)
                                                                        MaterialTheme.colorScheme
                                                                            .error
                                                                    else
                                                                        MaterialTheme.colorScheme
                                                                            .onSurfaceVariant,
                                                            )
                                                            usage.warnings.distinct().forEach {
                                                                Notice(it, error = true)
                                                            }
                                                        }
                                                    }
                                                    item { DailyPanel(usage.daily, usage.timezone) }
                                                    item { ModelPanel(usage.models) }
                                                    item {
                                                        Panel("统计说明") {
                                                            Text(
                                                                "个人账户请求日志 · 北京时间 UTC+8",
                                                                style =
                                                                    MaterialTheme.typography
                                                                        .bodySmall,
                                                            )
                                                            Text(
                                                                "账号统计缓存 5 分钟，刷新时校验登录状态。",
                                                                style =
                                                                    MaterialTheme.typography
                                                                        .bodySmall,
                                                            )
                                                            usage.costNote?.let {
                                                                Text(
                                                                    it,
                                                                    style =
                                                                        MaterialTheme.typography
                                                                            .bodySmall,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        Page.Lines -> {
                                            if (lines.isNotEmpty()) item { LinesPanel(lines) }
                                            else if (!loading && error == null)
                                                item { Notice("线路数据暂不可用。") }
                                        }
                                        Page.Settings -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyPanel(daily: List<DailyUsage>, timezone: String) {
    Panel("费用趋势") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${daily.size} 天累计",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    usdText(sumAmounts(daily.map { it.costUsd })),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                timezone,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        SpendTrendChart(daily)
        Text(
            "点击图表查看当天费用与请求数",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelPanel(models: List<ModelUsage>) {
    Panel("模型用量排行") {
        ModelShareChart(models)
        Text(
            "按费用优先排序；费用缺失时按请求数排序。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (models.isEmpty()) Text("当前范围内没有模型记录，或服务端未提供模型统计。")
        val maximum = models.mapNotNull { chartCost(it.costUsd) }.maxOrNull()
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
            if (chartCost(model.costUsd) != null && maximum != null && maximum > 0) {
                LinearProgressIndicator(
                    progress = { (model.costUsd!! / maximum).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
            Text(
                if (model.totalTokens != null)
                    "请求 ${countText(model.requests)} · 总 Token ${countText(model.totalTokens)}"
                else
                    "请求 ${countText(model.requests)} · 输入 ${countText(model.inputTokens)} · 输出 ${countText(model.outputTokens)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (model.totalTokens == null)
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
    Panel("线路健康") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${lines.count { it.reachable }} / ${lines.size}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text("条线路接口可达", style = MaterialTheme.typography.bodyMedium)
        }
        val maximum =
            lines
                .filter { it.reachable }
                .mapNotNull { it.latencyMillis?.takeIf { ms -> ms >= 0 } }
                .maxOrNull()
                ?.coerceAtLeast(1) ?: 1L
        lines.forEach { health ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(8.dp)
                                .background(
                                    if (health.reachable) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                    CircleShape,
                                )
                        )
                        Text(
                            health.line.label,
                            Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            health.latencyMillis?.let { "$it ms" } ?: "—",
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    val latency = health.latencyMillis
                    if (health.reachable && latency != null && latency >= 0) {
                        LinearProgressIndicator(
                            progress = {
                                (latency.toDouble() / maximum).toFloat().coerceIn(0f, 1f)
                            },
                            modifier =
                                Modifier.fillMaxWidth().height(7.dp).semantics {
                                    contentDescription = "${health.line.label} 探测耗时 $latency 毫秒"
                                },
                        )
                    }
                    Text(
                        health.message ?: health.status?.let { "HTTP $it · 接口可达" } ?: "无法连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "匿名 HTTP 探测 · 耗时越短越好 · 不代表模型生成速度",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
