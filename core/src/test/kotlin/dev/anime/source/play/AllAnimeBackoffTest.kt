package dev.anime.source.play

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AllAnimeBackoffTest {
    private val provider = AllAnimeProvider()

    @Test
    fun `backoff grows exponentially and caps`() {
        assertEquals(30_000L, provider.backoffFor(1))
        assertEquals(60_000L, provider.backoffFor(2))
        assertEquals(240_000L, provider.backoffFor(4))
        assertEquals(600_000L, provider.backoffFor(10))
    }

    @Test
    fun `retry delay is parsed from the api message with margin`() {
        assertEquals(5_000L, provider.parseRetryDelay("Too many requests, please try again in 3 seconds."))
        assertEquals(12_000L, provider.parseRetryDelay("try again in 10 second"))
        assertNull(provider.parseRetryDelay("NEED_CAPTCHA"))
    }
}
