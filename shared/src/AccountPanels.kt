package com.hanserwei.krill_monitor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun AccountLoginPanel(
    client: AccountClient,
    connected: Boolean,
    email: String?,
    secureStorage: Boolean,
    onLogin: (AccountSession, Boolean) -> Unit,
    onLogout: () -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }
    var totpCode by remember { mutableStateOf("") }
    var useEmailCode by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<String?>(null) }
    var rememberLogin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val toast = rememberAppToast()
    var resendDeadline by remember { mutableStateOf(0L) }
    var remaining by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(resendDeadline) {
        do {
            remaining = (resendDeadline - Clock.System.now().epochSeconds).coerceAtLeast(0)
            if (remaining > 0) delay(1000)
        } while (remaining > 0)
    }

    fun clearCredentials() {
        password = ""
        emailCode = ""
        totpCode = ""
        pending = null
    }

    Panel("Krill 账号") {
        if (connected) {
            Text(email ?: "已登录 Krill 账号", fontWeight = FontWeight.Medium)
            Text("使用账号授权查询钱包、全部套餐及请求日志统计。")
            OutlinedButton(
                onClick = {
                    clearCredentials()
                    onLogout()
                }
            ) {
                Text("退出登录")
            }
        } else {
            Text(if (pending == null) "尚未登录，使用 Krill 官网邮箱登录以查看账户信息。" else "输入身份验证器中的 6 位动态验证码。")
            if (pending == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        shape =
                            RoundedCornerShape(
                                topStart = 14.dp,
                                bottomStart = 14.dp,
                                topEnd = 4.dp,
                                bottomEnd = 4.dp,
                            ),
                        selected = useEmailCode,
                        onClick = {
                            useEmailCode = true
                            password = ""
                            emailCode = ""
                        },
                        enabled = !busy,
                        label = { Text("邮箱验证码") },
                        leadingIcon = { if (useEmailCode) MonitorIcon("check") else MonitorIcon("mail") },
                    )
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        shape =
                            RoundedCornerShape(
                                topStart = 4.dp,
                                bottomStart = 4.dp,
                                topEnd = 14.dp,
                                bottomEnd = 14.dp,
                            ),
                        selected = !useEmailCode,
                        onClick = {
                            useEmailCode = false
                            password = ""
                            emailCode = ""
                        },
                        enabled = !busy,
                        label = { Text("密码") },
                        leadingIcon = { if (!useEmailCode) MonitorIcon("check") else MonitorIcon("lock") },
                    )
                }
                OutlinedTextField(
                    address,
                    {
                        if (address.trim().lowercase() != it.trim().lowercase()) {
                            emailCode = ""
                        }
                        address = it
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "账号邮箱" },
                    label = { Text("邮箱") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                if (useEmailCode) {
                    OutlinedTextField(
                        emailCode,
                        { emailCode = it.take(6) },
                        modifier =
                            Modifier.fillMaxWidth().semantics { contentDescription = "邮箱验证码" },
                        label = { Text("邮箱验证码") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        enabled =
                            !busy &&
                                address.isNotBlank() &&
                                remaining == 0L &&
                                !client.isCodeResendUnknown(address),
                        onClick = {
                            val target = address.trim()
                            busy = true
                            scope.launch {
                                try {
                                    val dispatch = client.sendLoginCode(target)
                                    val seconds = dispatch.resendAfterSeconds
                                    if (seconds != null) {
                                        val now = Clock.System.now().epochSeconds
                                        resendDeadline =
                                            if (seconds > Long.MAX_VALUE - now) Long.MAX_VALUE
                                            else now + seconds
                                        remaining = seconds
                                    }
                                    toast("验证码请求已提交，请检查邮箱和垃圾邮件")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: AccountAccessException) {
                                    toast(e.message ?: "发送验证码失败，请重试")
                                } catch (_: Exception) {
                                    toast("发送验证码失败，请重试")
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) {
                        Text(
                            when {
                                remaining > 0 -> "${remaining}秒后可重发"
                                client.isCodeResendUnknown(address) -> "已申请验证码"
                                else -> "获取验证码"
                            }
                        )
                    }
                    if (client.isCodeResendUnknown(address)) {
                        Text(
                            "此邮箱已申请验证码，官网未提供重发时间；请先检查邮箱或到官网重新申请。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    OutlinedTextField(
                        password,
                        { password = it },
                        modifier =
                            Modifier.fillMaxWidth().semantics { contentDescription = "账号密码" },
                        label = { Text("密码") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            } else {
                OutlinedTextField(
                    totpCode,
                    { totpCode = it.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "动态验证码" },
                    label = { Text("动态验证码") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = rememberLogin,
                    onCheckedChange = { rememberLogin = it },
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "记住登录" },
                )
                Text(
                    if (secureStorage) "记住登录（设备加密保存）" else "记住登录（浏览器本地保存，同源脚本可读取）",
                    Modifier.weight(1f),
                )
            }
            Text("仅保存会话令牌，不保存密码或验证码；会话失效后需重新登录。", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    enabled =
                        !busy &&
                            if (pending != null) totpCode.length == 6
                            else
                                address.isNotBlank() &&
                                    if (useEmailCode) emailCode.trim().length in 1..6
                                    else password.isNotEmpty(),
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val token = pending
                                if (token != null) {
                                    val session = client.verifyTotp(token, totpCode)
                                    pending = null
                                    toast("登录成功")
                                    onLogin(session, rememberLogin)
                                } else {
                                    val result =
                                        if (useEmailCode)
                                            client.loginWithCode(address.trim(), emailCode.trim())
                                        else client.login(address, password)
                                    when (result) {
                                        is AccountLoginResult.SignedIn -> {
                                            toast("登录成功")
                                            onLogin(result.session, rememberLogin)
                                        }
                                        is AccountLoginResult.TotpRequired ->
                                            pending = result.pendingToken
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: AccountAccessException) {
                                toast(e.message ?: "登录失败，请重试")
                            } catch (_: Exception) {
                                toast("登录失败，请重试")
                            } finally {
                                password = ""
                                emailCode = ""
                                totpCode = ""
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(if (pending == null) "登录" else "验证并登录")
                }
                if (pending != null)
                    TextButton(enabled = !busy, onClick = { clearCredentials() }) { Text("重新登录") }
            }
            TextButton(
                onClick = {
                    clearCredentials()
                    onLogout()
                },
                enabled = !busy,
            ) {
                Text("清除本地登录")
            }
        }
        TextButton(onClick = { uriHandler.openUri("https://www.krill-code.com/login") }) {
            Text("打开 Krill 官网")
        }
        Text("第三方非官方客户端，与 Krill 官方无隶属关系。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AccountOverview(data: AccountDashboard, days: Int, onUsage: () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("钱包余额 · USD", style = MaterialTheme.typography.bodyMedium)
                Text(usdText(data.balanceUsd), style = MaterialTheme.typography.displayLarge.copy(fontSize = 48.sp), fontWeight = FontWeight.SemiBold)
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Row(Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MonitorIcon("redeem", modifier = Modifier.size(18.dp))
                        Text("赠金 ${usdText(data.welfareUsd)}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        val stats = data.statistics
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountStatTile("近${days}天费用", usdText(stats?.costUsd), Modifier.weight(1f), onUsage)
            AccountStatTile("请求", countText(stats?.requests), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountStatTile("失败请求", countText(stats?.failedRequests), Modifier.weight(1f))
            AccountStatTile("总 Token", compactCount(stats?.totalTokens), Modifier.weight(1f).semantics { contentDescription = "总 Token ${countText(stats?.totalTokens)}" })
        }
        Text("官网账号统计 · 北京时间 UTC+8 · 钱包与套餐额度独立", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccountStatTile(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val click = if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier
    Surface(modifier.heightIn(min = 88.dp), shape = RoundedCornerShape(20.dp), color = if (onClick != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, modifier = click, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun AccountSubscriptions(data: AccountDashboard) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("我的套餐", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp), fontWeight = FontWeight.SemiBold)
        if (data.subscriptions.isEmpty()) {
            Text(if (data.warnings.any { it.contains("套餐") }) "套餐信息暂不可用" else "当前没有套餐", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        data.subscriptions.forEach { subscription ->
            val status = when (subscription.status) {
                "active" -> "生效中"
                "expired" -> "已到期"
                "frozen" -> "已冻结"
                else -> subscription.status.ifBlank { "—" }
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(subscription.name.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), fontWeight = FontWeight.SemiBold)
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(status, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text("到期：${subscription.expiresAt?.let(::beijingTimeText) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    if (subscription.quotas.isEmpty()) Text("周期额度：—", style = MaterialTheme.typography.bodyMedium)
                    subscription.quotas.forEach { quota ->
                        fun amount(value: Double?): String = quotaValueText(value, quota.unit)
                        val remaining = quota.unit != "次"
                        val value = if (remaining) quota.remaining else quota.used
                        Text("${quota.label} · ${quota.unit.ifBlank { "—" }}", fontWeight = FontWeight.Medium)
                        Text("${if (remaining) "剩余" else "已用"} ${amount(value)} / ${amount(quota.limit)}", style = MaterialTheme.typography.headlineSmall)
                        val limit = quota.limit
                        if (value != null && value.isFinite() && value >= 0 && limit != null && limit.isFinite() && limit > 0) {
                            LinearProgressIndicator(progress = { (value / limit).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)), trackColor = MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            Text("进度：—", style = MaterialTheme.typography.bodySmall)
                        }
                        if (remaining && quota.used != null) Text("累计消耗 ${amount(quota.used)}", style = MaterialTheme.typography.bodySmall)
                        Text("周期开始：${quota.startsAt?.let(::beijingTimeText) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                        Text("周期结束：${quotaWindowEnd(quota.resetsAt, subscription.expiresAt)?.let(::beijingTimeText) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("钱包与套餐额度独立", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AccountSettingsPanel(email: String?, secureStorage: Boolean, onLogout: () -> Unit) {
    var confirmLogout by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前账号", style = MaterialTheme.typography.labelLarge)
                Text(email?.takeIf { it.isNotBlank() } ?: "—", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp), fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonitorIcon("verified_user", modifier = Modifier.size(18.dp))
                    Text("已登录", style = MaterialTheme.typography.labelMedium)
                }
                Text(if (secureStorage) "选择记住登录时，会话令牌在设备上加密保存。" else "选择记住登录时，会话令牌保存在浏览器本地，同源脚本可读取。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("隐私与数据", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp), fontWeight = FontWeight.SemiBold)
        val details = listOf(
            Triple("lock", "会话存储", if (secureStorage) "可在设备上加密保存会话令牌，不保存密码或验证码" else "可在浏览器本地保存会话令牌，同源脚本可读取；不保存密码或验证码"),
            Triple("schedule", "统计时间与币种", "USD · 北京时间 UTC+8"),
            Triple("account_balance_wallet", "账户额度", "钱包与套餐额度独立"),
            Triple("shield", "匿名线路隐私", "匿名探测线路不携带账号凭据"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            details.forEachIndexed { index, (icon, title, detail) ->
                ListItem(
                    modifier = Modifier.clip(groupedShape(index, details.size)),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    headlineContent = { Text(title) },
                    supportingContent = { Text(detail) },
                    leadingContent = {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) { MonitorIcon(icon, modifier = Modifier.size(22.dp)) }
                        }
                    },
                )
            }
        }
        OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            MonitorIcon("logout", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("退出登录")
        }
        Text("第三方非官方客户端，与 Krill 官方无隶属关系。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录？") },
            text = { Text("仅清除本应用的会话。") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("退出登录") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
            shape = RoundedCornerShape(28.dp),
        )
    }
}
