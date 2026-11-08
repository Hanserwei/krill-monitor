package com.hanserwei.krill_monitor

import kotlinx.browser.localStorage

private const val STORAGE_KEY = "krill_monitor_key"

private class WasmMonitorKeyStore : MonitorKeyStore {
    override val isSecure: Boolean = false

    // Wasm 将 DOMException 映射为 Throwable，不能只捕获 Kotlin Exception。
    override fun load(): String? =
        try {
            localStorage.getItem(STORAGE_KEY)
        } catch (_: Throwable) {
            throw IllegalStateException("无法读取浏览器保存的 Key")
        }

    override fun save(key: String): Boolean =
        try {
            localStorage.setItem(STORAGE_KEY, key)
            true
        } catch (_: Throwable) {
            false
        }

    override fun clear(): Boolean =
        try {
            localStorage.removeItem(STORAGE_KEY)
            true
        } catch (_: Throwable) {
            false
        }
}

actual fun createMonitorKeyStore(): MonitorKeyStore = WasmMonitorKeyStore()
