package com.hanserwei.krill_monitor

import kotlinx.browser.localStorage

private class WasmSessionStore : SessionStore {
    private val storageKey = "krill_monitor_session"
    override val isSecure: Boolean = false

    // Wasm 将 DOMException 映射为 Throwable，不能只捕获 Kotlin Exception。
    override fun load(): String? =
        try {
            localStorage.getItem(storageKey)
        } catch (_: Throwable) {
            throw IllegalStateException("无法读取浏览器保存的账号会话")
        }

    override fun save(token: String): Boolean =
        try {
            localStorage.setItem(storageKey, token)
            true
        } catch (_: Throwable) {
            false
        }

    override fun clear(): Boolean =
        try {
            localStorage.removeItem(storageKey)
            true
        } catch (_: Throwable) {
            false
        }

    override fun clearLegacyKey(): Boolean =
        try {
            localStorage.removeItem("krill_monitor_key")
            true
        } catch (_: Throwable) {
            false
        }
}

actual fun createSessionStore(): SessionStore = WasmSessionStore()
