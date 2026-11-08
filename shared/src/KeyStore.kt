package com.hanserwei.krill_monitor

interface MonitorKeyStore {
    val isSecure: Boolean

    // 没有记录返回 null；读取失败抛出不含凭据的异常，供界面提示。
    fun load(): String?

    fun save(key: String): Boolean

    fun clear(): Boolean
}

expect fun createMonitorKeyStore(): MonitorKeyStore
