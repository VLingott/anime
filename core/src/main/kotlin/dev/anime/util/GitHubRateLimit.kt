package dev.anime.util

import java.net.HttpURLConnection

object GitHubRateLimit {
    fun retryAt(remaining: Int?, resetEpochSeconds: Long?, now: Long): Long? {
        if (remaining != null && remaining > 0) return null
        val reset = resetEpochSeconds?.times(1000L)
        return reset?.takeIf { it > now } ?: (now + 60_000L)
    }

    fun retryAt(connection: HttpURLConnection, now: Long): Long? = retryAt(
        remaining = connection.getHeaderField("X-RateLimit-Remaining")?.trim()?.toIntOrNull(),
        resetEpochSeconds = connection.getHeaderField("X-RateLimit-Reset")?.trim()?.toLongOrNull(),
        now = now,
    )
}
