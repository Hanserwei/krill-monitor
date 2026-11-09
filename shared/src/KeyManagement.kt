package com.hanserwei.krill_monitor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class KeyManagementState(
    private val client: AccountClient,
    private val session: AccountSession,
    private val vault: LocalKeyVault,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
    private val onExpired: () -> Unit,
) {
    var busy by mutableStateOf(false)
        private set

    var data by mutableStateOf<ManagedKeyList?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var revealed by mutableStateOf<ApiKeySecret?>(null)
        private set

    var pendingSave by mutableStateOf<ApiKeySecret?>(null)
        private set

    var channelItem by mutableStateOf<ManagedApiKey?>(null)
        private set

    var channels by mutableStateOf<List<ApiKeyChannel>?>(null)
        private set

    var channelSelection by mutableStateOf<Set<Long>>(emptySet())
        private set

    var channelError by mutableStateOf<String?>(null)
        private set

    var newKeyConfiguration by mutableStateOf(false)
        private set

    private var channelNew = false
    private var channelGeneration = 0
    private var pendingCreateGeneration: Int? = null

    private var pendingOwner: Long? = null
    var editing by mutableStateOf<ManagedApiKey?>(null)
    var action by mutableStateOf<Pair<String, ManagedApiKey>?>(null)
    private var owner: Long? = null
    private var identityMismatch = false
    private var disposed = false
    private var authGeneration = 0
    private var authJob: Job? = null
    private val jobs = mutableSetOf<Job>()
    val enabled
        get() = vault.enabled

    val unavailableReason
        get() = vault.unavailableReason

    private fun run(block: suspend () -> Unit) {
        if (busy || disposed) return
        busy = true
        error = null
        val job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountSessionExpired) {
                leavePage()
                pendingSave = null
                pendingOwner = null
                data = null
                owner = null
                onExpired()
            } catch (e: AccountAccessException) {
                error = e.message ?: "操作失败"
            } catch (e: KeyVaultException) {
                error = e.message ?: "本机密钥操作失败"
            } finally {
                busy = false
            }
        }
        jobs.add(job)
        job.invokeOnCompletion { jobs.remove(job) }
    }

    fun load(force: Boolean = false) {
        if (identityMismatch) {
            error = "账号身份不一致，请重新登录"
            return
        }
        if (!force && data != null) return
        run {
            val result = client.listApiKeys(session)
            if (result.accountId <= 0 || (owner != null && owner != result.accountId)) {
                identityMismatch = true
                leavePage()
                owner = null
                data = null
                error = "账号身份不一致，请重新登录"
                return@run
            }
            owner = result.accountId
            data = result
        }
    }

    private fun update(item: ManagedApiKey) {
        val current = data ?: return
        data = current.copy(items = current.items.filterNot { it.id == item.id } + item)
    }

    private fun savePending(): ManagedApiKey? {
        val secret = pendingSave ?: return null
        val accountId = pendingOwner ?: return null
        try {
            vault.save(accountId, secret.item.id, secret.secret)
            pendingSave = null
            pendingOwner = null
            error = if (identityMismatch) "账号身份不一致，请重新登录" else null
            toast("Key 已加密保存在本机")
            return secret.item
        } catch (e: KeyVaultException) {
            error = "官网已生成但本机未保存，请重试保存，勿重复创建或轮换"
            return null
        }
    }

    fun retrySave() {
        if (busy || disposed) return
        val generation = pendingCreateGeneration
        val item = savePending() ?: return
        pendingCreateGeneration = null
        if (generation != null && generation == authGeneration) openChannels(item, true)
    }

    private fun generate(created: Boolean, request: suspend () -> ApiKeySecret) {
        if (pendingSave != null) {
            error = "请先重试保存已生成的 Key"
            return
        }
        val accountId = owner
        if (accountId == null || data?.accountId != accountId) {
            error = "请先加载并验证账号"
            return
        }
        val reason = vault.unavailableReason
        if (!vault.enabled || reason != null) {
            error = reason ?: "本机加密保存不可用"
            return
        }
        val generation = authGeneration
        run {
            val result = request()
            if (revealed?.item?.id == result.item.id) revealed = null
            update(result.item)
            pendingSave = result
            pendingOwner = accountId
            pendingCreateGeneration = if (created) generation else null
            val saved = savePending()
            if (saved != null) {
                pendingCreateGeneration = null
                if (created && generation == authGeneration && !disposed) {
                    channelItem = saved
                    channels = null
                    channelSelection = emptySet()
                    channelError = null
                    channelNew = true
                    newKeyConfiguration = true
                    loadChannelsInternal(saved, true, ++channelGeneration, generation)
                }
            }
        }
    }

    fun create(name: String) = generate(true) { client.createApiKey(session, name.trim()) }

    fun rotate(item: ManagedApiKey) = generate(false) { client.rotateApiKey(session, item.id) }

    fun openChannels(item: ManagedApiKey, created: Boolean = false) {
        if (busy || disposed || item.revoked) return
        channelItem = item
        channels = null
        channelSelection = emptySet()
        channelError = null
        channelNew = created
        newKeyConfiguration = created
        reloadChannels()
    }

    fun reloadChannels() {
        val item = channelItem ?: return
        val generation = ++channelGeneration
        val page = authGeneration
        run { loadChannelsInternal(item, channelNew, generation, page) }
    }

    private suspend fun loadChannelsInternal(
        item: ManagedApiKey,
        created: Boolean,
        generation: Int,
        page: Int,
    ) {
        try {
            val catalog = client.listApiKeyChannels(session)
            if (generation != channelGeneration || page != authGeneration || disposed) return
            val selected = if (created) emptySet() else client.getApiKeyChannels(session, item.id)
            if (generation != channelGeneration || page != authGeneration || disposed) return
            channels = catalog
            channelSelection = selected.ifEmpty {
                catalog.filter { it.defaultRouted }.map { it.id }.toSet()
            }
            channelError = null
        } catch (e: AccountAccessException) {
            if (generation == channelGeneration && page == authGeneration && !disposed) {
                channelError =
                    if (created) "Key已创建并加密保存，渠道配置未确认；可稍后从管理进入。${e.message}"
                    else e.message ?: "加载渠道失败"
            }
        }
    }

    private val channelGroups =
        listOf(setOf("国产模型官渠", "阿里云直连"), setOf("Max 官渠", "Kiro 渠道", "Max 官渠高可用", "Anthropic 官key"))

    fun toggleChannel(channel: ApiKeyChannel, checked: Boolean) {
        if (busy) return
        if (checked) {
            val group = channelGroups.firstOrNull { channel.name in it }
            val conflict =
                channels.orEmpty().firstOrNull {
                    it.id in channelSelection && it.id != channel.id && it.name in group.orEmpty()
                }
            if (conflict != null) {
                channelError = "${channel.name} 与 ${conflict.name} 不能同时选择"
                return
            }
        }
        channelSelection =
            if (checked) channelSelection + channel.id else channelSelection - channel.id
        channelError = null
    }

    fun defaultChannels() {
        if (busy) return
        val catalog = channels ?: return
        val defaults = catalog.filter { it.defaultRouted }
        for (channel in defaults.filterNot { it.id in channelSelection }) {
            val group = channelGroups.firstOrNull { channel.name in it }
            val conflict = defaults.firstOrNull {
                it.id != channel.id && it.name in group.orEmpty()
            }
            if (conflict != null) {
                channelError = "${channel.name} 与 ${conflict.name} 不能同时选择"
                return
            }
        }
        val known = catalog.map { it.id }.toSet()
        channelSelection =
            channelSelection.filterNot { it in known }.toSet() + defaults.map { it.id }
        channelError = null
    }

    fun saveChannels() {
        val item = channelItem ?: return
        if (channels == null) return
        val selection = channelSelection
        val generation = channelGeneration
        val page = authGeneration
        val showNewKey = newKeyConfiguration
        run {
            channelNew = false
            try {
                client.setApiKeyChannels(session, item.id, selection)
                if (generation == channelGeneration && page == authGeneration && !disposed) {
                    closeChannels()
                    toast("渠道配置已保存")
                    if (showNewKey) revealStoredKey(item, page)
                }
            } catch (e: AccountAccessException) {
                if (generation == channelGeneration && page == authGeneration && !disposed) {
                    channelError = "保存渠道失败：${e.message}；可重试或重新读取"
                }
            }
        }
    }

    fun closeChannels() {
        channelGeneration++
        channelItem = null
        channels = null
        channelSelection = emptySet()
        channelError = null
        channelNew = false
        newKeyConfiguration = false
    }

    fun finishChannels() {
        if (busy || disposed) return
        val created = channelItem?.takeIf { newKeyConfiguration }
        closeChannels()
        created?.let(::reveal)
    }

    fun rename(item: ManagedApiKey, name: String) = run {
        update(client.renameApiKey(session, item.id, name.trim()))
        toast("名称已更新")
    }

    fun revoke(item: ManagedApiKey) = run {
        client.revokeApiKey(session, item.id)
        update(item.copy(revoked = true))
        toast("Key 已撤销")
    }

    fun delete(item: ManagedApiKey) {
        if (pendingSave?.item?.id == item.id) {
            error = "请先重试保存已生成的 Key"
            return
        }
        run {
            client.destroyApiKey(session, item.id)
            data = data?.copy(items = data?.items.orEmpty().filterNot { it.id == item.id })
            try {
                owner?.let { vault.remove(it, item.id) }
                toast("Key 已永久删除")
            } catch (e: KeyVaultException) {
                error = "官网已永久删除，但本机副本清理失败"
            }
        }
    }

    fun hasKey(item: ManagedApiKey): Boolean? =
        try {
            owner?.let { vault.hasKey(it, item.id) } ?: false
        } catch (e: KeyVaultException) {
            null
        }

    fun reveal(item: ManagedApiKey) {
        if (
            owner == null ||
                busy ||
                disposed ||
                pendingSave?.item?.id == item.id ||
                hasKey(item) != true
        )
            return
        val generation = authGeneration
        run { revealStoredKey(item, generation) }
    }

    private suspend fun revealStoredKey(item: ManagedApiKey, generation: Int) {
        val accountId = owner ?: return
        if (
            generation != authGeneration ||
                disposed ||
                pendingSave?.item?.id == item.id ||
                hasKey(item) != true
        )
            return
        authJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        try {
            val secret = vault.reveal(accountId, item.id)
            if (generation == authGeneration && !disposed && secret != null)
                revealed = ApiKeySecret(item, secret)
        } finally {
            authJob = null
        }
    }

    fun copy(item: ManagedApiKey) {
        val accountId = owner ?: return
        if (busy || disposed || pendingSave?.item?.id == item.id || hasKey(item) != true) return
        val generation = authGeneration
        run {
            if (generation != authGeneration || disposed) return@run
            authJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            val copied = vault.copy(accountId, item.id)
            if (generation == authGeneration && !disposed && copied) toast("已复制 Key")
            authJob = null
        }
    }

    fun closeSecret() {
        revealed = null
    }

    fun leavePage() {
        authGeneration++
        closeChannels()
        pendingCreateGeneration = null
        editing = null
        action = null
        revealed = null
        vault.cancelAuthentication()
        authJob?.cancel()
        authJob = null
    }

    fun dispose() {
        disposed = true
        leavePage()
        jobs.toList().forEach { it.cancel() }
        jobs.clear()
        data = null
        owner = null
        pendingSave = null
        pendingOwner = null
        error = null
        busy = false
    }
}

@Composable
internal fun KeyManagementPanel(state: KeyManagementState) {
    var name by rememberSaveable { mutableStateOf("") }
    val createInteraction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("每次查看，都先验证身份", fontSize = 20.sp, style = MaterialTheme.typography.titleLarge)
                Text("查看或复制需通过系统认证", fontSize = 14.sp, style = MaterialTheme.typography.bodyMedium)
                Text("本机保存的密钥副本经过加密", fontSize = 12.sp, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.unavailableReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.pendingSave != null) {
            Text("Key 尚未保存在本机，请重试保存，勿重复创建或轮换。", color = MaterialTheme.colorScheme.error)
            Button(onClick = state::retrySave, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("重试保存") }
        }
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("我的密钥", fontSize = 22.sp, style = MaterialTheme.typography.titleLarge)
            Text(state.data?.items?.size?.let { "${it}个" } ?: "—", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val items = state.data?.items
        if (items == null && state.busy) Text("正在加载 Key…", style = MaterialTheme.typography.bodyMedium)
        if (items == null && !state.busy && state.error != null) {
            TextButton(onClick = { state.load(force = true) }) { Text("重新加载") }
        }
        if (items?.isEmpty() == true) Text("暂无 Key", style = MaterialTheme.typography.bodyMedium)
        if (!items.isNullOrEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items.forEachIndexed { index, item ->
                    var expanded by remember(item.id) { mutableStateOf(false) }
                    LaunchedEffect(state.busy) { if (state.busy) expanded = false }
                    val local = state.hasKey(item)
                    ListItem(
                        modifier = Modifier.clip(groupedShape(index, items.size)),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        leadingContent = {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) {
                                Box(contentAlignment = Alignment.Center) { MonitorIcon(if (local == false) "key_off" else "key") }
                            }
                        },
                        headlineContent = { Text(item.name?.takeIf { it.isNotBlank() } ?: "未命名") },
                        supportingContent = {
                            Column {
                                Text("${item.prefix?.takeIf { it.isNotBlank() }?.let { it.take(minOf(8, it.length - 1)) + "••••" } ?: "—"} · ${if (item.revoked) "已撤销" else "有效"}")
                                Text(when {
                                    state.pendingSave?.item?.id == item.id -> "新 Key 尚未保存，请重试保存"
                                    local == false -> "本机未保存，完整 Key 无法找回"
                                    local == null -> "本机副本状态读取失败，请稍后重试"
                                    else -> "上次使用：${item.lastUsedAt?.let(::beijingTimeText) ?: "—"}"
                                })
                            }
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { expanded = true }, enabled = !state.busy) { MonitorIcon("more_vert", contentDescription = "管理 Key") }
                                DropdownMenu(expanded = expanded && !state.busy, onDismissRequest = { expanded = false }) {
                                    if (state.pendingSave?.item?.id != item.id && local == true) {
                                        DropdownMenuItem(text = { Text("查看") }, onClick = { expanded = false; state.reveal(item) })
                                        DropdownMenuItem(text = { Text("复制") }, onClick = { expanded = false; state.copy(item) })
                                    }
                                    if (!item.revoked) DropdownMenuItem(text = { Text("渠道配置") }, onClick = { expanded = false; state.openChannels(item) })
                                    DropdownMenuItem(text = { Text("重命名") }, onClick = { expanded = false; state.editing = item })
                                    if (!item.revoked) {
                                        DropdownMenuItem(text = { Text("轮换") }, enabled = state.pendingSave == null, onClick = { expanded = false; state.action = "轮换" to item })
                                        DropdownMenuItem(text = { Text("撤销") }, onClick = { expanded = false; state.action = "撤销" to item })
                                    }
                                    DropdownMenuItem(text = { Text("永久删除", color = MaterialTheme.colorScheme.error) }, onClick = { expanded = false; state.action = "永久删除" to item })
                                }
                            }
                        },
                    )
                }
            }
        }
        Text("新建密钥", fontSize = 22.sp, style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称（可选）") },
            leadingIcon = { MonitorIcon("edit") },
            enabled = !state.busy,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { state.create(name); name = "" },
            enabled = !state.busy && state.pendingSave == null && state.data != null && state.enabled && state.unavailableReason == null,
            interactionSource = createInteraction,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(createInteraction),
        ) {
            MonitorIcon("add")
            Spacer(Modifier.width(8.dp))
            Text("创建 Key")
        }
        Text("未保存在本机的完整密钥无法找回", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun KeyManagementDialogs(state: KeyManagementState) {
    state.editing?.let { item ->
        var value by remember(item.id) { mutableStateOf(item.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { state.editing = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("重命名 Key") },
            text = {
                OutlinedTextField(
                    value,
                    { value = it },
                    enabled = !state.busy,
                    label = { Text("名称（可选）") },
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.busy,
                    onClick = {
                        state.rename(item, value)
                        state.editing = null
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { state.editing = null }) { Text("取消") } },
        )
    }
    state.action?.let { (kind, item) ->
        val description =
            when (kind) {
                "轮换" -> "旧值将立即失效。"
                "撤销" -> "撤销后不可恢复。"
                else -> "永久删除将移除本机副本。"
            }
        AlertDialog(
            onDismissRequest = { state.action = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("确认$kind") },
            text = { Text("${item.name ?: "未命名"}（${item.prefix?.takeIf { it.isNotBlank() }?.let { it.take(minOf(8, it.length - 1)) + "••••" } ?: "—"}）\n$description") },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (kind) {
                            "轮换" -> state.rotate(item)
                            "撤销" -> state.revoke(item)
                            else -> state.delete(item)
                        }
                        state.action = null
                    }
                ) {
                    Text(kind, color = if (kind == "轮换") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { state.action = null }) { Text("取消") } },
        )
    }
    if (state.channelItem != null) KeyChannelEditor(state)
    state.revealed?.let { secret ->
        KeySecretDialog(
            secret = secret,
            busy = state.busy,
            onCopy = { state.copy(secret.item) },
            onClose = state::closeSecret,
        )
    }
}
