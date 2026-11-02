package dev.anime.util

import dev.anime.source.TvmazeEpisode

/** One episode position in a season list. */
data class EpisodePointer(val season: Int, val episode: Int, val absolute: Int)

fun absoluteEpisodeIndex(
    seasons: List<Pair<Int, List<TvmazeEpisode>>>,
    season: Int,
    episode: Int,
): Int? {
    var index = 0
    for ((seasonNumber, episodes) in seasons.sortedBy { it.first }) {
        for (entry in episodes.sortedBy { it.number ?: 0 }) {
            val number = entry.number ?: continue
            index++
            if (seasonNumber == season && number == episode) return index
        }
    }
    return null
}

/** The episode after (season, episode) in watching order, or null at the end. */
fun nextEpisode(
    seasons: List<Pair<Int, List<TvmazeEpisode>>>,
    season: Int,
    episode: Int,
): EpisodePointer? {
    val ordered = seasons.sortedBy { it.first }.flatMap { (seasonNumber, episodes) ->
        episodes.sortedBy { it.number ?: 0 }.mapNotNull { entry ->
            entry.number?.let { number -> seasonNumber to number }
        }
    }
    val index = ordered.indexOfFirst { it.first == season && it.second == episode }
    if (index < 0) return null
    val next = ordered.getOrNull(index + 1) ?: return null
    return EpisodePointer(season = next.first, episode = next.second, absolute = index + 2)
}
