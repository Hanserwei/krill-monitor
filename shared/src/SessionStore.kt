package com.hanserwei.krill_monitor

interface SessionStore {
    val isSecure: Boolean

    // 没有记录返回 null；读取失败抛出不含凭据的异常，供界面提示。
    fun load(): String?

    fun save(token: String): Boolean

    fun clear(): Boolean

    // 仅删除旧版本中转 Key，不读取或转换为账号会话。
    fun clearLegacyKey(): Boolean
}

expect fun createSessionStore(): SessionStore
