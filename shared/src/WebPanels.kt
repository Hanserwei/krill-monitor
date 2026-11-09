package com.hanserwei.krill_monitor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler

import androidx.compose.ui.unit.dp

@Composable
internal fun WebWelcomePanel() {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("KRILL MONITOR", style = MaterialTheme.typography.labelLarge)
            Text("每一笔用量，\n心中有数。", style = MaterialTheme.typography.displaySmall)
            Text("连接你的 Krill 账户，在一个工作台查看钱包、套餐、模型用量与线路状态。", style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f))
            listOf(
                Triple("account_balance_wallet", "额度清晰", "钱包与套餐独立展示"),
                Triple("bar_chart", "用量可追溯", "按日期和模型查看费用与 Token"),
                Triple("shield", "线路匿名探测", "不携带账户凭据或发送推理请求"),
            ).forEach { (symbol, title, detail) ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonitorIcon(symbol, modifier = Modifier.size(28.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(detail, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text("USD · 北京时间 UTC+8", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun WebKeyInfoPanel() {
    val uriHandler = LocalUriHandler.current
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MonitorIcon("lock", modifier = Modifier.size(40.dp))
                Text("密钥留在可信设备", style = MaterialTheme.typography.headlineMedium)
                Text("完整密钥的本机加密保存与每次系统认证，请使用 Android 客户端。", style = MaterialTheme.typography.bodyLarge)
                Text("网页版不保存、查看或复制完整 API 密钥；需要管理官网密钥时，可直接前往 Krill 官网。", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { uriHandler.openUri("https://www.krill-code.com") },
                    interactionSource = interaction,
                    modifier = Modifier.heightIn(min = 56.dp).pressScale(interaction),
                ) {
                    MonitorIcon("key")
                    Spacer(Modifier.width(8.dp))
                    Text("打开 Krill 官网")
                }
            }
        }
        listOf(
            Triple("verified_user", "每次访问都先认证", "Android 查看和复制密钥时，均需通过设备的系统认证。"),
            Triple("shield", "浏览器不留完整副本", "网页不会把完整密钥写入浏览器本地存储。"),
            Triple("key_off", "没有副本无法找回", "官网已有密钥如果没有本机副本，无法恢复完整值；请妥善保管已创建的密钥。"),
        ).let { details ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                details.forEachIndexed { index, (symbol, title, detail) ->
                    Surface(shape = groupedShape(index, details.size), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        ListItem(
                            headlineContent = { Text(title) },
                            supportingContent = { Text(detail) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            leadingContent = {
                                Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                    Box(contentAlignment = Alignment.Center) { MonitorIcon(symbol) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
