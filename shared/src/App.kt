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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun App(fontFamily: FontFamily = FontFamily.Default) {
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
        var history by rememberSaveable { mutableStateOf(listOf(Page.Settings.name)) }
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

        fun navigate(destination: Page) {
            if (page == destination) return
            backwards = false
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

        val keyVault = rememberLocalKeyVault()
        val keyScope = rememberCoroutineScope()
        val keyManager = remember(accountApi, session, keyVault) {
            session?.let { active ->
                KeyManagementState(accountApi, active, keyVault, keyScope, toast) { expireSession(active) }
            }
        }
        val destinations = Page.entries.filter { it != Page.Keys || keyVault.enabled }
        DisposableEffect(keyManager) { onDispose { keyManager?.dispose() } }
        LaunchedEffect(page, keyManager) {
            if (page == Page.Keys) keyManager?.load() else keyManager?.leavePage()
        }
        val lineProber = remember { LineProber() }
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
                    history = listOf(Page.Overview.name)
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
            val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
            Scaffold(
                modifier = Modifier.fillMaxSize().imePadding(),
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    TopAppBar(
                        title = { Text(page.title, style = MaterialTheme.typography.titleLarge) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        scrollBehavior = scrollBehavior,
                        actions = {
                            if (page != Page.Settings) {
                                val refreshing = when (page) {
                                    Page.Keys -> keyManager?.busy == true
                                    Page.Lines -> probing || channelLoading
                                    else -> loading
                                }
                                val interaction = remember { MutableInteractionSource() }
                                IconButton(
                                    modifier = Modifier.size(48.dp).pressScale(interaction),
                                    interactionSource = interaction,
                                    enabled = !refreshing && (session != null || page == Page.Lines),
                                    onClick = {
                                        when (page) {
                                            Page.Keys -> keyManager?.load(force = true)
                                            Page.Lines -> lineRevision++
                                            else -> { forceRefresh = true; revision++ }
                                        }
                                    },
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
                    if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
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
                                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                                contentPadding = PaddingValues(16.dp),
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
                                            )
                                        } else {
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
                                            Text(
                                                "独立第三方客户端 · 非 Krill 官方产品",
                                                Modifier.padding(top = 16.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    destination == Page.Lines -> {
                                        lineError?.let { message -> item { Notice(message, error = true) } }
                                        item { LinesPanel(lines, probing) }
                                        item { ChannelStatusPanel(channelStatus, channelLoading, channelError) }
                                    }
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
                                                item { AccountOverview(overview, 7) { navigate(Page.Usage) } }
                                                item { AccountSubscriptions(overview) }
                                                overview.warnings.distinct().forEach { message -> item { Notice(message, error = true) } }
                                            } else if (!loading) item { Notice("暂时没有账户数据，请点击顶部刷新重试。") }
                                        } else if (destination == Page.Usage) {
                                            item { UsagePanel(dashboards[days], days) { days = it } }
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
