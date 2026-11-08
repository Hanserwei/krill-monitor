package com.hanserwei.krill_monitor

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
