package dev.anime.model

import kotlinx.serialization.Serializable

@Serializable
data class DetailTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class DetailTag(
    val name: String,
    val rank: Int,
)

@Serializable
data class DetailCharacter(
    val name: String,
    val role: String? = null,
    val image: String? = null,
)

@Serializable
data class DetailResult(
    val id: Long,
    val title: DetailTitle? = null,
    val synonyms: List<String> = emptyList(),
    val description: String? = null,
    val coverImage: String? = null,
    val coverColor: String? = null,
    val bannerImage: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<DetailTag> = emptyList(),
    val format: String? = null,
    val status: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val source: String? = null,
    val averageScore: Double? = null,
    val popularity: Long? = null,
    val studios: List<String> = emptyList(),
    val startDate: String? = null,
    val endDate: String? = null,
    val siteUrl: String? = null,
    val trailer: String? = null,
    val characters: List<DetailCharacter> = emptyList(),
)
