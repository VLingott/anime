package dev.anime.model

import kotlinx.serialization.Serializable

@Serializable
data class SubtitleTrack(
    val url: String,
    val label: String? = null,
    val language: String? = null,
    val default: Boolean = false,
)

@Serializable
data class PlaySource(
    val id: String,
    val name: String,
    val kind: String = "hls",
    val url: String,
    val languages: List<String> = emptyList(),
    val episodeTitleGerman: String? = null,
    val episodeTitleEnglish: String? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
)

@Serializable
data class StreamResolution(
    val id: String,
    val label: String,
)

@Serializable
data class PlayResponse(
    val id: Long,
    val title: String? = null,
    val season: Int = 1,
    val episode: Int = 1,
    val sources: List<PlaySource> = emptyList(),
    val error: String? = null,
)
