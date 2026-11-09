package com.hanserwei.krill_monitor

import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal fun countText(value: Long?): String =
    value?.takeIf { it >= 0 }?.toString()?.reversed()?.chunked(3)?.joinToString(",")?.reversed()
        ?: "—"

internal fun usdText(value: Double?): String {
    if (value == null || !value.isFinite() || value < 0 || value >= Long.MAX_VALUE / 100.0)
        return "—"
    // 按十进制文本舍入到分，避免 1.015 * 100 的二进制误差导致少显示一分。
    val text = value.toString().lowercase()
    val mantissa = text.substringBefore('e')
    val digits = mantissa.replace(".", "")
    val point = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it }
    val cut = point + text.substringAfter('e', "0").toInt() + 2
    val whole = if (cut <= 0) 0L else digits.take(cut).padEnd(cut, '0').toLongOrNull() ?: return "—"
    val roundUp = cut >= 0 && (digits.getOrNull(cut) ?: '0') >= '5'
    if (roundUp && whole == Long.MAX_VALUE) return "—"
    val cents = whole + if (roundUp) 1 else 0
    return "$${countText(cents / 100)}.${(cents % 100).toString().padStart(2, '0')}"
}

internal fun sumAmounts(values: List<Double?>): Double? {
    if (values.isEmpty() || values.any { it == null }) return null
    return values.filterNotNull().sum().takeIf { it.isFinite() && it >= 0 }
}

internal fun sumCounts(values: List<Long?>): Long? {
    if (values.isEmpty()) return null
    var total = 0L
    for (value in values) {
        if (value == null || value < 0 || value > Long.MAX_VALUE - total) return null
        total += value
    }
    return total
}

internal fun cacheRatioText(cached: Long?, input: Long?): String {
    if (cached == null || input == null || input <= 0 || cached < 0 || cached > input) return "—"
    val tenths = (cached.toDouble() / input * 1000).roundToLong()
    return "${tenths / 10}.${tenths % 10}%"
}

internal fun accountCacheRatioText(statistics: AccountStatistics?): String {
    val stats = statistics ?: return "—"
    return if (stats.cacheRateReadTokens != null && stats.cacheRateEligibleTokens != null) {
        cacheRatioText(stats.cacheRateReadTokens, stats.cacheRateEligibleTokens)
    } else {
        cacheRatioText(stats.cachedInputTokens, sumCounts(listOf(stats.inputTokens, stats.cacheWriteTokens, stats.cachedInputTokens)))
    }
}

internal fun beijingTimeText(instant: Instant): String =
    Instant.fromEpochSeconds(instant.epochSeconds + 8 * 3600)
        .toString()
        .take(19)
        .replace('T', ' ') + " UTC+8"

internal fun quotaWindowEnd(reset: Instant?, expiry: Instant?): Instant? = reset?.let {
    // 官网显示包含最后一秒的窗口结束；最终窗口以套餐到期时刻为准。
    if (it == expiry) it else it - 1.seconds
}

internal fun quotaValueText(value: Double?, unit: String): String =
    when {
        unit == "USD" -> usdText(value)
        value == null || !value.isFinite() || value < 0 -> "—"
        value < Long.MAX_VALUE.toDouble() && value % 1.0 == 0.0 -> countText(value.toLong())
        else -> value.toString()
    }
