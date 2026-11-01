package dev.anime.source.play

import dev.anime.model.DetailResult
import dev.anime.model.PlaySource
import dev.anime.source.SourceContext

interface PlaybackProvider {
    val id: String
    val name: String
    val priority: Int get() = 0
    suspend fun resolve(
        detail: DetailResult,
        season: Int,
        episode: Int,
        ctx: SourceContext,
        absoluteEpisode: Int? = null,
        episodeTitle: String? = null,
    ): List<PlaySource>
}
