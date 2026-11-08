package com.hanserwei.krill_monitor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
            Text(if (pending == null) "使用 Krill 官网邮箱登录。" else "输入身份验证器中的 6 位动态验证码。")
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
                    shape = RoundedCornerShape(14.dp),
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
                        shape = RoundedCornerShape(14.dp),
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedButton(
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
                        shape = RoundedCornerShape(14.dp),
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
                    shape = RoundedCornerShape(14.dp),
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
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
    }
}

@Composable
internal fun AccountOverview(data: AccountDashboard, days: Int) {
    Panel("钱包与统计") {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF102D42),
            contentColor = Color.White,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("钱包余额", style = MaterialTheme.typography.labelLarge, color = Color(0xFF9DDCD4))
                Text(
                    usdText(data.balanceUsd),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF205D60),
                    contentColor = Color.White,
                ) {
                    Text(
                        "赠金 ${usdText(data.welfareUsd)}",
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        val stats = data.statistics
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountStatTile("近${days}天费用", usdText(stats?.costUsd), Modifier.weight(1f))
                AccountStatTile("请求", countText(stats?.requests), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountStatTile("失败请求", countText(stats?.failedRequests), Modifier.weight(1f))
                AccountStatTile(
                    "总 Token",
                    compactCount(stats?.totalTokens),
                    Modifier.weight(1f).semantics {
                        contentDescription = "总 Token ${countText(stats?.totalTokens)}"
                    },
                )
            }
        }
        data.usage
            ?.takeIf { report -> report.daily.any { it.costUsd != null } }
            ?.let { report ->
                Text("近期费用趋势 · ${report.timezone}", style = MaterialTheme.typography.titleSmall)
                SpendTrendChart(report.daily, compact = true)
            }
        stats?.let {
            Text("Token 用量", style = MaterialTheme.typography.titleSmall)
            TokenVolumeChart(it)
        }
        Text(
            "官网账号统计 · 北京时间 UTC+8 · 钱包与套餐额度独立",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccountStatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                softWrap = true,
            )
        }
    }
}

@Composable
internal fun AccountSubscriptions(data: AccountDashboard) {
    Panel("我的套餐") {
        if (data.subscriptions.isEmpty())
            Text(if (data.warnings.any { it.contains("套餐") }) "套餐信息暂不可用" else "当前没有套餐")
        data.subscriptions.forEach { subscription ->
            val status =
                when (subscription.status) {
                    "active" -> "生效中"
                    "expired" -> "已到期"
                    "frozen" -> "已冻结"
                    else -> subscription.status
                }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            subscription.name,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                status,
                                Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Text(
                        "到期：${subscription.expiresAt?.let(::utcTimeText) ?: "未提供"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    subscription.quotas.forEach { quota ->
                        fun amount(value: Double?): String = quotaValueText(value, quota.unit)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            QuotaRing(quota.used, quota.limit, Modifier.size(76.dp))
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    "${quota.label} · ${quota.unit}",
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "剩余 ${amount(quota.remaining)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "已用 ${amount(quota.used)} / ${amount(quota.limit)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                quota.resetsAt?.let {
                                    Text(
                                        "重置：${utcTimeText(it)}",
                                        style = MaterialTheme.typography.bodySmall,
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
