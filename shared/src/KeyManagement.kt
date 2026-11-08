package com.hanserwei.krill_monitor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    private fun savePending() {
        val secret = pendingSave ?: return
        val accountId = pendingOwner ?: return
        try {
            vault.save(accountId, secret.item.id, secret.secret)
            pendingSave = null
            pendingOwner = null
            error = if (identityMismatch) "账号身份不一致，请重新登录" else null
            toast("Key 已加密保存在本机")
        } catch (e: KeyVaultException) {
            error = "官网已生成但本机未保存，请重试保存，勿重复创建或轮换"
        }
    }

    fun retrySave() {
        if (!busy && !disposed) savePending()
    }

    private fun generate(request: suspend () -> ApiKeySecret) {
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
        run {
            val result = request()
            if (revealed?.item?.id == result.item.id) revealed = null
            update(result.item)
            pendingSave = result
            pendingOwner = accountId
            savePending()
        }
    }

    fun create(name: String) = generate { client.createApiKey(session, name.trim()) }

    fun rotate(item: ManagedApiKey) = generate { client.rotateApiKey(session, item.id) }

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
        val accountId = owner ?: return
        if (busy || disposed || pendingSave?.item?.id == item.id || hasKey(item) != true) return
        val generation = authGeneration
        run {
            authJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            val secret = vault.reveal(accountId, item.id)
            if (generation == authGeneration && !disposed && secret != null)
                revealed = ApiKeySecret(item, secret)
            authJob = null
        }
    }

    fun copy(item: ManagedApiKey) {
        val accountId = owner ?: return
        if (busy || disposed || pendingSave?.item?.id == item.id || hasKey(item) != true) return
        val generation = authGeneration
        run {
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
    var name by remember { mutableStateOf("") }
    Panel("账号 Key 管理") {
        Text("默认配置可能使用账号余额；已有 Key 若本机没有副本则无法找回完整值。", style = MaterialTheme.typography.bodySmall)
        state.unavailableReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.pendingSave != null) {
            Button(onClick = state::retrySave, enabled = !state.busy) { Text("重试保存") }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称（可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                state.create(name)
                name = ""
            },
            enabled =
                !state.busy &&
                    state.pendingSave == null &&
                    state.data != null &&
                    state.enabled &&
                    state.unavailableReason == null,
        ) {
            Text("创建 Key")
        }
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.data?.items?.forEach { item ->
            var expanded by remember(item.id) { mutableStateOf(false) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name?.takeIf { it.isNotBlank() } ?: "未命名",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("前缀：${item.prefix ?: "未提供"} · ${if (item.revoked) "已撤销" else "有效"}")
                    Text(
                        "上次使用：${item.lastUsedAt?.let(::beijingTimeText) ?: "未使用"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        when {
                            state.pendingSave?.item?.id == item.id -> "新Key尚未保存，请重试保存"
                            else ->
                                when (state.hasKey(item)) {
                                    true -> "本机有加密副本"
                                    false -> "本机无副本，完整 Key 无法找回"
                                    null -> "本机副本状态读取失败，请稍后重试"
                                }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box {
                    TextButton(onClick = { expanded = true }, enabled = !state.busy) { Text("管理") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        if (state.pendingSave?.item?.id != item.id && state.hasKey(item) == true) {
                            DropdownMenuItem(
                                text = { Text("查看") },
                                onClick = {
                                    expanded = false
                                    state.reveal(item)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("复制") },
                                onClick = {
                                    expanded = false
                                    state.copy(item)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = {
                                expanded = false
                                state.editing = item
                            },
                        )
                        if (!item.revoked) {
                            DropdownMenuItem(
                                text = { Text("轮换") },
                                enabled = state.pendingSave == null,
                                onClick = {
                                    expanded = false
                                    state.action = "轮换" to item
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("撤销") },
                                onClick = {
                                    expanded = false
                                    state.action = "撤销" to item
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("永久删除") },
                            onClick = {
                                expanded = false
                                state.action = "永久删除" to item
                            },
                        )
                    }
                }
            }
        }
        if (state.data?.items?.isEmpty() == true) Text("暂无 Key")
    }
}

@Composable
internal fun KeyManagementDialogs(state: KeyManagementState) {
    state.editing?.let { item ->
        var value by remember(item.id) { mutableStateOf(item.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { state.editing = null },
            title = { Text("重命名 Key") },
            text = {
                OutlinedTextField(
                    value,
                    { value = it },
                    label = { Text("名称（可选）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
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
            title = { Text("确认$kind") },
            text = { Text("${item.name ?: "未命名"}（${item.prefix ?: "无前缀"}）\n$description") },
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
                    Text(kind)
                }
            },
            dismissButton = { TextButton(onClick = { state.action = null }) { Text("取消") } },
        )
    }
    state.revealed?.let { secret ->
        AlertDialog(
            onDismissRequest = state::closeSecret,
            title = { Text("Key") },
            text = {
                Text(secret.secret, modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = { state.copy(secret.item) }, enabled = !state.busy) {
                    Text("认证并复制")
                }
            },
            dismissButton = { TextButton(onClick = state::closeSecret) { Text("关闭") } },
        )
    }
}
