@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.hanserwei.krill_monitor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.hanserwei.krill_monitor.resources.Res
import com.hanserwei.krill_monitor.resources.krill_logo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.reload.DevelopmentEntryPoint

private enum class Page(val label: String, val symbol: String, val title: String = label) {
    Overview("概览", "dashboard"),
    Usage("用量", "bar_chart"),
    Lines("线路", "hub"),
    Keys("密钥", "key", "API 密钥"),
    Settings("设置", "settings"),
}

@Composable
@Preview
@DevelopmentEntryPoint
fun App(fontFamily: FontFamily = FontFamily.Default, web: Boolean = false) {
    MaterialExpressiveTheme(
        colorScheme = monitorColors(isSystemInDarkTheme()),
        motionScheme = MotionScheme.expressive(),
        typography = monitorTypography(fontFamily),
        shapes = Shapes(large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)),
    ) {
        val toast = rememberAppToast()
        val sessionStore = remember { createSessionStore() }
        var storageMessage by remember { mutableStateOf<String?>(null) }
        var storageLoaded by remember { mutableStateOf(false) }
        var session by remember { mutableStateOf<AccountSession?>(null) }
        val initialPage = remember { initialMonitorRoute()?.let { route -> Page.entries.find { it.name.equals(route, ignoreCase = true) } } }
        var history by rememberSaveable { mutableStateOf(listOf((initialPage ?: Page.Settings).name)) }
        val page = Page.valueOf(history.last())
        var backwards by remember { mutableStateOf(false) }
        var days by rememberSaveable { mutableIntStateOf(7) }
        val queryDays = if (page == Page.Usage) days else 7
        var revision by remember { mutableIntStateOf(0) }
        var forceRefresh by remember(session, queryDays) { mutableStateOf(false) }
        val scrollStates = List(Page.entries.size) { rememberLazyListState() }
        val dashboards = remember(session) { mutableStateMapOf<Int, AccountDashboard>() }
        val accountData = dashboards[queryDays]
        val accountApi = remember(session?.token) { AccountClient(baseUrl = accountApiBaseUrl()) }
        DisposableEffect(accountApi) { onDispose { accountApi.close() } }

        fun navigate(destination: Page, reverse: Boolean = false) {
            if (page == destination) return
            backwards = reverse
            val index = history.indexOf(destination.name)
            history = if (index >= 0) history.take(index + 1) else history + destination.name
        }
        fun expireSession(active: AccountSession) {
            if (session !== active) return
            val cleared = runCatching { sessionStore.clear() }.getOrDefault(false)
            session = null
            history = listOf(Page.Settings.name)
            storageMessage = if (cleared) "登录已过期，请重新登录。" else "登录已过期；本地会话删除失败，请重试清除。"
            toast(storageMessage ?: "登录已过期，请重新登录。")
        }
        MonitorBackHandler(enabled = history.size > 1) {
            backwards = true
            history = history.dropLast(1)
        }
        if (web && storageLoaded) MonitorBrowserNavigation(page.name.lowercase(), page.title) { route ->
            Page.entries.find { it.name.equals(route, ignoreCase = true) }?.let { destination ->
                val index = history.indexOf(destination.name)
                navigate(destination, reverse = index in 0 until history.lastIndex)
            }
        }

        val keyVault = rememberLocalKeyVault()
        val keyScope = rememberCoroutineScope()
        val keyManager = remember(accountApi, session, keyVault) {
            session?.takeIf { keyVault.enabled }?.let { active ->
                KeyManagementState(accountApi, active, keyVault, keyScope, toast) { expireSession(active) }
            }
        }
        val destinations = Page.entries.filter { web || it != Page.Keys || keyVault.enabled }
        DisposableEffect(keyManager) { onDispose { keyManager?.dispose() } }
        LaunchedEffect(page, keyManager) {
            if (page == Page.Keys) keyManager?.load() else keyManager?.leavePage()
        }
        val lineProber = remember {
            if (web) LineProber(probeUrl = { line -> accountApiBaseUrl().removeSuffix("/krill-account") + "/krill-lines/" + line.name.lowercase() })
            else LineProber()
        }
        val channelStatusClient = remember { ChannelStatusClient() }
        DisposableEffect(lineProber) { onDispose { lineProber.close() } }
        DisposableEffect(channelStatusClient) { onDispose { channelStatusClient.close() } }
        var lines by remember { mutableStateOf<List<LineHealth>>(emptyList()) }
        var lineRevision by remember { mutableIntStateOf(-1) }
        var probing by remember { mutableStateOf(false) }
        var lineError by remember { mutableStateOf<String?>(null) }
        var channelLoading by remember { mutableStateOf(false) }
        var channelStatus by remember { mutableStateOf<ChannelStatusSnapshot?>(null) }
        var channelError by remember { mutableStateOf<String?>(null) }
        var loading by remember(session, queryDays) { mutableStateOf(false) }
        var error by remember(session, queryDays) { mutableStateOf<String?>(null) }

        LaunchedEffect(page) {
            if (page == Page.Lines && lineRevision < 0) lineRevision = 0
        }
        LaunchedEffect(lineRevision) {
            if (lineRevision < 0) return@LaunchedEffect
            coroutineScope {
                launch {
                    probing = true
                    lineError = null
                    try {
                        lines = lineProber.probeLines()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        lineError = "线路探测失败，保留上次成功结果。请稍后刷新。"
                    } finally {
                        probing = false
                    }
                }
                launch {
                    channelLoading = true
                    channelError = null
                    try {
                        channelStatus = channelStatusClient.load(refresh = lineRevision > 0)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AccountAccessException) {
                        channelError = e.message
                    } catch (_: Exception) {
                        channelError = "无法获取官网渠道状态，请稍后刷新。"
                    } finally {
                        channelLoading = false
                    }
                }
            }
        }
        LaunchedEffect(sessionStore) {
            try {
                if (!sessionStore.clearLegacyKey()) storageMessage = "清除旧登录数据失败，请检查本地存储。"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                storageMessage = "清除旧登录数据失败，请检查本地存储。"
            }
            try {
                sessionStore.load()?.takeIf { it.isNotBlank() }?.let { saved ->
                    session = AccountSession(saved, null)
                    if (initialPage == null && history == listOf(Page.Settings.name)) history = listOf(Page.Overview.name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                storageMessage = listOfNotNull(storageMessage, "读取账号会话失败，请重新登录。").joinToString("\n")
            } finally {
                storageLoaded = true
            }
        }
        // 每个范围单独保留结果；取消旧查询，避免迟到的响应覆盖新账号或范围。
        LaunchedEffect(storageLoaded, session, queryDays, revision) {
            if (!storageLoaded) return@LaunchedEffect
            val active = session ?: return@LaunchedEffect
            loading = true
            error = null
            val fresh = forceRefresh
            forceRefresh = false
            try {
                dashboards[queryDays] = accountApi.load(active, queryDays, refresh = fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (_: AccountSessionExpired) {
                expireSession(active)
            } catch (e: AccountAccessException) {
                error = e.message ?: "查询失败，请稍后重试。"
            } catch (_: Exception) {
                error = "无法完成查询，请检查网络后重试。"
            } finally {
                loading = false
            }
        }

        fun logout() {
            if (keyManager?.pendingSave != null) {
                navigate(Page.Keys)
                toast("新 Key 尚未保存，请先重试加密保存后退出")
                return
            }
            if (keyManager?.busy == true || keyManager?.channelItem != null) {
                navigate(Page.Keys)
                toast("密钥操作尚未完成，请先完成或取消后退出")
                return
            }
            val cleared = runCatching { sessionStore.clear() }.getOrDefault(false)
            if (cleared) {
                session = null
                history = listOf(Page.Settings.name)
                storageMessage = null
                toast("已退出本应用账号")
            } else {
                storageMessage = "账号会话清除失败，请重试。"
                toast("本地登录清除失败，请重试")
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val desktopWeb = web && wide
            val columns = web && maxWidth >= 1200.dp
            val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
            Row(Modifier.fillMaxSize()) {
                if (desktopWeb) WebSidebar(
                    page = page,
                    email = dashboards[7]?.email ?: accountData?.email ?: session?.email,
                    connected = session != null,
                    onNavigate = { navigate(it) },
                )
                Scaffold(
                    modifier = Modifier.weight(1f).fillMaxHeight().imePadding(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(page.title, style = if (desktopWeb) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
                                    if (desktopWeb) Text(
                                        when (page) {
                                            Page.Overview -> "钱包、近期统计与独立套餐额度"
                                            Page.Usage -> "按日期与模型了解每一笔用量"
                                            Page.Lines -> "匿名线路探测与官网渠道状态"
                                            Page.Keys -> "安全管理你的 API 访问凭据"
                                            Page.Settings -> "账户会话与隐私说明"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            scrollBehavior = scrollBehavior,
                            actions = {
                                if (page != Page.Settings && !(web && page == Page.Keys)) {
                                    val refreshing = when (page) {
                                        Page.Keys -> keyManager?.busy == true
                                        Page.Lines -> probing || channelLoading
                                        else -> loading
                                    }
                                    val interaction = remember { MutableInteractionSource() }
                                    val refresh = {
                                        when (page) {
                                            Page.Keys -> keyManager?.load(force = true)
                                            Page.Lines -> lineRevision++
                                            else -> { forceRefresh = true; revision++ }
                                        }
                                        Unit
                                    }
                                    if (desktopWeb) FilledTonalButton(
                                        onClick = refresh,
                                        interactionSource = interaction,
                                        enabled = !refreshing && (session != null || page == Page.Lines),
                                        modifier = Modifier.padding(end = 16.dp).height(48.dp).pressScale(interaction),
                                    ) {
                                        if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        else MonitorIcon("refresh")
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (refreshing) "正在刷新" else "刷新")
                                    } else IconButton(
                                        modifier = Modifier.size(48.dp).pressScale(interaction),
                                        interactionSource = interaction,
                                        enabled = !refreshing && (session != null || page == Page.Lines),
                                        onClick = refresh,
                                    ) {
                                        if (refreshing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                        else MonitorIcon("refresh", "刷新${page.label}")
                                    }
                                }
                            },
                        )
                    },
                    bottomBar = {
                        if (!wide) NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 0.dp,
                        ) {
                            destinations.forEach { destination ->
                                val interaction = remember(destination) { MutableInteractionSource() }
                                NavigationBarItem(
                                    modifier = Modifier.pressScale(interaction),
                                    interactionSource = interaction,
                                    selected = page == destination,
                                    onClick = { navigate(destination) },
                                    icon = { MonitorIcon(destination.symbol, filled = page == destination) },
                                    label = { Text(destination.label, style = MaterialTheme.typography.labelMedium) },
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
                                )
                            }
                        }
                    },
                ) { padding ->
                    Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        if (wide && !web) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            destinations.forEach { destination ->
                                NavigationRailItem(
                                    selected = page == destination,
                                    onClick = { navigate(destination) },
                                    icon = { MonitorIcon(destination.symbol, filled = page == destination) },
                                    label = { Text(destination.label) },
                                )
                            }
                        }
                        val motion = MaterialTheme.motionScheme
                        AnimatedContent(
                            targetState = page,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            transitionSpec = {
                                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(
                                    motion.defaultSpatialSpec(), initialScale = if (backwards) 1.02f else 0.98f,
                                )) togetherWith (fadeOut(motion.fastEffectsSpec()) + scaleOut(
                                    motion.defaultSpatialSpec(), targetScale = if (backwards) 0.98f else 1.02f,
                                ))
                            },
                            label = "main pages",
                        ) { destination ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                LazyColumn(
                                    state = scrollStates[destination.ordinal],
                                    modifier = Modifier.widthIn(max = if (desktopWeb) 1180.dp else 640.dp).fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                                    contentPadding = PaddingValues(if (desktopWeb) 24.dp else 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    if (!storageLoaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                                    storageMessage?.let { message -> item { Notice(message, error = true) } }
                                    when {
                                        destination == Page.Settings -> item {
                                            if (session != null) {
                                                AccountSettingsPanel(
                                                    email = dashboards[7]?.email ?: accountData?.email ?: session?.email,
                                                    secureStorage = sessionStore.isSecure,
                                                    onLogout = ::logout,
                                                    wide = columns,
                                                    web = web,
                                                )
                                            } else {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                                                    if (columns) Column(Modifier.weight(1f)) { WebWelcomePanel() }
                                                    Column(Modifier.weight(1f)) {
                                                        AccountLoginPanel(
                                                            client = accountApi,
                                                            connected = false,
                                                            email = null,
                                                            secureStorage = sessionStore.isSecure,
                                                            onLogin = { signedIn, persist ->
                                                                val stored = runCatching {
                                                                    if (persist) sessionStore.save(signedIn.token) else sessionStore.clear()
                                                                }.getOrDefault(false)
                                                                storageMessage = if (stored) null else "账号会话保存失败，仅本次登录有效。"
                                                                session = signedIn
                                                                backwards = false
                                                                history = listOf(Page.Overview.name)
                                                                if (!stored) toast("会话未能保存，下次需要重新登录")
                                                            },
                                                            onLogout = ::logout,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        destination == Page.Lines -> {
                                            lineError?.let { message -> item { Notice(message, error = true) } }
                                            if (columns) item {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                                                    Column(Modifier.weight(0.9f)) { LinesPanel(lines, probing, web = true) }
                                                    Column(Modifier.weight(1.1f)) { ChannelStatusPanel(channelStatus, channelLoading, channelError) }
                                                }
                                            } else {
                                                item { LinesPanel(lines, probing, web = web) }
                                                item { ChannelStatusPanel(channelStatus, channelLoading, channelError) }
                                            }
                                        }
                                        destination == Page.Keys && web -> item { WebKeyInfoPanel() }
                                        session == null -> item {
                                            Panel("连接你的 Krill 账号") {
                                                Text("登录后查看${destination.label}，安全管理你的账户。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                val interaction = remember { MutableInteractionSource() }
                                                Button(
                                                    onClick = { navigate(Page.Settings) },
                                                    interactionSource = interaction,
                                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(interaction),
                                                ) {
                                                    MonitorIcon("login")
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("前往登录")
                                                }
                                            }
                                        }
                                        destination == Page.Keys -> item { keyManager?.let { KeyManagementPanel(it) } }
                                        else -> {
                                            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                                            error?.let { message -> item {
                                                Notice(if (accountData != null) "$message\n当前保留上次成功数据，请稍后刷新。" else message, error = true)
                                            } }
                                            if (destination == Page.Overview) {
                                                val overview = dashboards[7]
                                                item {
                                                    Text(
                                                        "KRILL MONITOR · ${overview?.let { "更新于 ${beijingTimeText(it.fetchedAt)}" } ?: "等待更新"}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                if (overview != null) {
                                                    item { AccountOverview(overview, 7, wide = columns) { navigate(Page.Usage) } }
                                                    item { AccountSubscriptions(overview, wide = columns) }
                                                    overview.warnings.distinct().forEach { message -> item { Notice(message, error = true) } }
                                                } else if (!loading) item { Notice("暂时没有账户数据，请点击顶部刷新重试。") }
                                            } else if (destination == Page.Usage) {
                                                item {
                                                    key(session) {
                                                        val active = session
                                                        val selectedDays = days
                                                        val usageData = dashboards[selectedDays]
                                                        UsagePanel(
                                                            usageData,
                                                            selectedDays,
                                                            onDaysChange = { days = it },
                                                            wide = columns,
                                                            loadModelDetails = { model, refresh ->
                                                                val signedIn = active ?: throw AccountSessionExpired()
                                                                val rangeAt = usageData?.usage?.fetchedAt
                                                                    ?: throw AccountAccessException("用量范围暂不可用，请先刷新用量")
                                                                try {
                                                                    accountApi.loadModelStatistics(signedIn, model, selectedDays, rangeAt, refresh)
                                                                } catch (e: AccountSessionExpired) {
                                                                    expireSession(signedIn)
                                                                    throw e
                                                                }
                                                            },
                                                        )
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
            }
        }
        if (page == Page.Keys) keyManager?.let {
            ProtectKeyDisplay(onBackground = it::leavePage)
            KeyManagementDialogs(it)
        }
    }
}

@Composable
private fun WebSidebar(page: Page, email: String?, connected: Boolean, onNavigate: (Page) -> Unit) {
    Surface(
        modifier = Modifier.width(248.dp).fillMaxHeight().padding(16.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource(Res.drawable.krill_logo), null, Modifier.size(36.dp))
                    Column {
                        Text("Krill Monitor", style = MaterialTheme.typography.titleMedium)
                        Text("用量监控工作台", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("工作台", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Page.entries.forEach { destination ->
                    val interaction = remember(destination) { MutableInteractionSource() }
                    NavigationDrawerItem(
                        selected = page == destination,
                        onClick = { onNavigate(destination) },
                        label = { Text(destination.title) },
                        icon = { MonitorIcon(destination.symbol, filled = page == destination) },
                        interactionSource = interaction,
                        modifier = Modifier.fillMaxWidth().pressScale(interaction),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                }
            }
            Surface(
                onClick = { onNavigate(Page.Settings) },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonitorIcon(if (connected) "verified_user" else "login", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (connected) "已连接账户" else "连接账户", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(email ?: if (connected) "登录会话有效" else "登录后查看余额与用量", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("USD · 北京时间 UTC+8", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("独立第三方客户端", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun Notice(message: String, error: Boolean = false) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
