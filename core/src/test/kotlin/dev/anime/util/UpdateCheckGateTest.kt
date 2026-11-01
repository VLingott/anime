package dev.anime.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UpdateCheckGateTest {
    @Test
    fun limitsChecksToOnePerMinute() {
        var now = 1_000L
        val gate = UpdateCheckGate(clock = { now })

        val first = assertIs<UpdateCheckGate.Result.Allowed>(gate.tryAcquire())
        assertEquals(1_000L, first.at)

        now = 60_999L
        val early = assertIs<UpdateCheckGate.Result.Denied>(gate.tryAcquire())
        assertEquals(61_000L, early.retryAt)
        assertEquals(false, early.github)

        now = 61_000L
        assertIs<UpdateCheckGate.Result.Allowed>(gate.tryAcquire())
    }

    @Test
    fun githubLimitBlocksEvenAfterLocalInterval() {
        var now = 1_000L
        val gate = UpdateCheckGate(clock = { now })
        gate.tryAcquire()
        gate.blockUntil(120_000L)

        now = 90_000L
        val limited = assertIs<UpdateCheckGate.Result.Denied>(gate.tryAcquire())
        assertEquals(120_000L, limited.retryAt)
        assertEquals(true, limited.github)

        now = 120_000L
        assertIs<UpdateCheckGate.Result.Allowed>(gate.tryAcquire())
    }

    @Test
    fun rateLimitUsesResetHeaderAndFallsBackToMinute() {
        assertNull(GitHubRateLimit.retryAt(remaining = 12, resetEpochSeconds = 2_000L, now = 1_000L))
        assertEquals(2_000_000L, GitHubRateLimit.retryAt(remaining = 0, resetEpochSeconds = 2_000L, now = 1_000L))
        assertEquals(2_000_000L, GitHubRateLimit.retryAt(remaining = 0, resetEpochSeconds = null, now = 1_940_000L))
        assertEquals(61_000L, GitHubRateLimit.retryAt(remaining = null, resetEpochSeconds = null, now = 1_000L))
    }
}
