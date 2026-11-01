package dev.anime.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RecentSeries(
    val id: Long,
    val title: String,
    val cover: String? = null,
    val score: Double? = null,
    val episodeTitle: String? = null,
    val season: Int = 1,
    val episode: Int = 1,
    val absoluteEpisode: Int? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L,
)

interface RecentsStore {
    fun list(): List<RecentSeries>
    fun record(entry: RecentSeries)
}

class FileRecentsStore(private val file: File, private val limit: Int = 20) : RecentsStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun list(): List<RecentSeries> =
        load().sortedByDescending { it.updatedAt }

    @Synchronized
    override fun record(entry: RecentSeries) {
        val list = load().filterNot { it.id == entry.id }.toMutableList()
        list.add(0, entry.copy(updatedAt = entry.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()))
        while (list.size > limit) list.removeAt(list.lastIndex)
        save(list)
    }

    private fun load(): MutableList<RecentSeries> = runCatching {
        json.decodeFromString<List<RecentSeries>>(file.readText()).toMutableList()
    }.getOrDefault(mutableListOf())

    private fun save(list: List<RecentSeries>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(list))
        }
    }
}
