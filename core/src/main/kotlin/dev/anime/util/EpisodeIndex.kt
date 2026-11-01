package dev.anime.util

import dev.anime.source.TvmazeEpisode

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
