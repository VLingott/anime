package dev.anime.search

import dev.anime.model.DetailResult
import dev.anime.model.PlaySource
import dev.anime.source.SourceContext
import dev.anime.source.play.PlaybackProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderFilterTest {
    private class FakeProvider(
        override val id: String,
        override val name: String,
    ) : PlaybackProvider {
        override suspend fun resolve(
            detail: DetailResult,
            season: Int,
            episode: Int,
            ctx: SourceContext,
            absoluteEpisode: Int?,
            episodeTitle: String?,
        ): List<PlaySource> = emptyList()
    }

    private fun service(vararg providers: PlaybackProvider) = PlaybackService(
        ctx = SourceContext(dev.anime.source.buildHttpClient("test")),
        details = Details(SourceContext(dev.anime.source.buildHttpClient("test"))),
        providers = providers.toList(),
    )

    @Test
    fun `disabled providers are skipped and exposed for settings`() {
        val provider = FakeProvider("alpha", "Alpha")
        val other = FakeProvider("beta", "Beta")
        val service = service(provider, other)

        assertEquals(listOf("alpha" to "Alpha", "beta" to "Beta"), service.availableProviders())
        assertEquals(listOf("alpha", "beta"), service.activeProviders().map { it.id })

        service.setDisabledProviders(setOf("alpha"))
        assertEquals(listOf("beta"), service.activeProviders().map { it.id })

        service.setDisabledProviders(emptySet())
        assertEquals(2, service.activeProviders().size)
    }

    @Test
    fun `disabling every provider leaves nothing active`() {
        val service = service(FakeProvider("alpha", "Alpha"))
        service.setDisabledProviders(setOf("alpha"))
        assertTrue(service.activeProviders().isEmpty())
    }
}
