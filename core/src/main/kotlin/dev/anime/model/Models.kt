package dev.anime.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchHit(
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val titleEn: String? = null,
    val titleNative: String? = null,
    val year: Int? = null,
    val episodes: Int? = null,
    val format: String? = null,
    val score: Double? = null,
    val url: String,
    val image: String? = null,
    val externalId: String? = null,
    val synonyms: List<String> = emptyList(),
    val snippet: String? = null,
    val season: String? = null,
    val studio: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class StreamHit(
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val episode: Int? = null,
    val year: Int? = null,
    val kind: String? = null,
    val url: String? = null,
    val language: String? = null,
    val durationSec: Long? = null,
    val rejectReason: String? = null,
)

@Serializable
data class StreamResult(
    val sourceId: String,
    val sourceName: String,
    val ms: Long,
    val hits: List<StreamHit>,
    val cached: Boolean = false,
    val error: String? = null,
    val late: Boolean = false,
)

@Serializable
data class StreamResponse(
    val meta: SearchMeta,
    val results: List<StreamResult>,
)

@Serializable
data class SourceInfo(
    val id: String,
    val name: String,
    val baseUrl: String,
    val kind: String,
    val online: Boolean,
    val lastMs: Long? = null,
    val avgMs: Long? = null,
    val score: Double = 0.0,
    val failStreak: Int = 0,
    val lastChecked: Long? = null,
    val lastError: String? = null,
    val checks: Long = 0,
    val oks: Long = 0,
    val availability: Double = 0.0,
    val rank: Int = 0,
)

@Serializable
data class SourceResult(
    val sourceId: String,
    val sourceName: String,
    val ms: Long,
    val hits: List<SearchHit>,
    val cached: Boolean = false,
    val error: String? = null,
    val late: Boolean = false,
)

@Serializable
data class SearchMeta(
    val query: String,
    val normalized: String,
    val sourcesTotal: Int,
    val aliases: List<String> = emptyList(),
    val anilistMatch: String? = null,
    val cached: Boolean = false,
)

@Serializable
data class EpisodeAltTitle(
    val source: String,
    val title: String,
)

@Serializable
data class EpisodeEntry(
    val season: Int,
    val number: Int,
    val title: String? = null,
    val altTitles: List<EpisodeAltTitle> = emptyList(),
    val thumb: String? = null,
    val url: String? = null,
    val sources: List<String> = emptyList(),
    val airdate: String? = null,
)

@Serializable
data class SeasonInfo(
    val season: Int,
    val episodes: List<EpisodeEntry>,
)

@Serializable
data class SeasonList(
    val id: Long,
    val title: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val episodesCount: Int? = null,
    val seasons: List<SeasonInfo> = emptyList(),
    val totalEpisodes: Int = 0,
    val providerCount: Int = 0,
)

@Serializable
sealed class StreamEvent {
    @Serializable
    @kotlinx.serialization.SerialName("meta")
    data class Meta(val meta: SearchMeta) : StreamEvent()

    @Serializable
    @kotlinx.serialization.SerialName("result")
    data class Result(val result: SourceResult) : StreamEvent()

    @Serializable
    @kotlinx.serialization.SerialName("done")
    data class Done(val elapsedMs: Long) : StreamEvent()
}
