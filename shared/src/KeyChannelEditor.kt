package com.hanserwei.krill_monitor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun KeyChannelEditor(state: KeyManagementState) {
    val item = state.channelItem ?: return
    val catalog = state.channels
    val unknown = state.channelSelection - catalog.orEmpty().map { it.id }.toSet()
    AlertDialog(
        onDismissRequest = state::closeChannels,
        modifier = Modifier.widthIn(max = 520.dp),
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = {
            Column {
                Text(if (state.newKeyConfiguration) "创建完成 · 配置渠道" else "渠道配置")
                Text(
                    item.name?.takeIf { it.isNotBlank() } ?: "未命名 Key",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.channelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("点击卡片选择渠道，可多选。", style = MaterialTheme.typography.bodySmall)
                if (state.newKeyConfiguration)
                    Text("保存或跳过后，验证身份即可查看新 Key。", style = MaterialTheme.typography.bodySmall)
                if (catalog != null) {
                    Text("空配置重新打开时会显示默认渠道", style = MaterialTheme.typography.bodySmall)
                    if (unknown.isNotEmpty())
                        Text(
                            "有${unknown.size}个已配置渠道当前不可选，保存会保留",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    catalog.forEach { channel ->
                        val selected = channel.id in state.channelSelection
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color =
                                if (selected)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.surface,
                            border =
                                BorderStroke(
                                    1.dp,
                                    if (selected)
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        selected,
                                        enabled = !state.busy,
                                        role = Role.Checkbox,
                                    ) {
                                        state.toggleChannel(channel, it)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(channel.name, style = MaterialTheme.typography.bodyMedium)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (channel.defaultRouted) {
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                            ) {
                                                Text(
                                                    "默认",
                                                    Modifier.padding(
                                                        horizontal = 7.dp,
                                                        vertical = 2.dp,
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        }
                                        Text(
                                            "计费倍率 ×${channel.billingMultiplier?.toString() ?: "—"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color =
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor =
                                        if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                ) {
                                    Text(
                                        if (selected) "已选" else "选择",
                                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    TextButton(onClick = state::defaultChannels, enabled = !state.busy) {
                        Text("恢复默认")
                    }
                }
                if (state.channelError != null) {
                    TextButton(onClick = state::reloadChannels, enabled = !state.busy) {
                        Text("重新读取")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = state::saveChannels, enabled = !state.busy && catalog != null) {
                Text(if (state.newKeyConfiguration) "保存并查看 Key" else "保存渠道")
            }
        },
        dismissButton = {
            TextButton(onClick = state::finishChannels, enabled = !state.busy) {
                Text(if (state.newKeyConfiguration) "跳过并查看 Key" else "取消")
            }
        },
    )
}
