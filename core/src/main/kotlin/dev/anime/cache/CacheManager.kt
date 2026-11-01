package dev.anime.cache

import java.io.File

object CacheManager {
    private const val SCHEMA = 2
    private val directories = listOf(
        "search", "tags-v1", "series-v5", "play-v6", "resolutions", "capabilities-v1", "aniworld-structure", "play", "series",
        "series-v2", "series-v3", "series-v4", "play-v2", "play-v3", "play-v4", "play-v5",
    )

    fun ensure(root: File) {
        val marker = File(root, "schema")
        val current = runCatching { marker.readText().trim().toIntOrNull() }.getOrNull()
        if (current != SCHEMA) {
            clear(root)
            runCatching {
                root.mkdirs()
                marker.writeText(SCHEMA.toString())
            }
        }
    }

    fun size(root: File): Long = runCatching {
        directories.sumOf { name ->
            File(root, name).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }.getOrDefault(0L)

    fun clear(root: File) {
        runCatching {
            root.mkdirs()
            for (name in directories) {
                File(root, name).deleteRecursively()
            }
            File(root, "schema").writeText(SCHEMA.toString())
        }
    }
}
