package dev.anime.util

class UpdateCheckGate(
    private val minIntervalMs: Long = 60_000L,
    private var lastCheckAt: Long = 0L,
    private var blockedUntil: Long = 0L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data class Allowed(val at: Long) : Result
        data class Denied(val retryAt: Long, val github: Boolean) : Result
    }

    @Synchronized
    fun tryAcquire(): Result {
        val now = clock()
        val blocked = blockedUntil
        if (blocked > now) return Result.Denied(blocked, github = true)
        val next = lastCheckAt + minIntervalMs
        if (lastCheckAt > 0L && now < next) return Result.Denied(next, github = false)
        lastCheckAt = now
        return Result.Allowed(now)
    }

    @Synchronized
    fun blockUntil(epochMs: Long) {
        if (epochMs > blockedUntil) blockedUntil = epochMs
    }
}
