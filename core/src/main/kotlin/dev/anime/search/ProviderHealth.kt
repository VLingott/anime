package dev.anime.search

import dev.anime.source.play.PlaybackProvider
import dev.anime.store.Jsonx
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ProviderStat(
    val id: String,
    var attempts: Int = 0,
    var successes: Int = 0,
    var avgMs: Long = 0,
    var lastError: String? = null,
    var lastAt: Long = 0,
)

/** Persisted per-provider playback health; providers are ordered by success rate then speed. */
class ProviderHealth(private val file: File? = null) {
    private val stats = ConcurrentHashMap<String, ProviderStat>()

    init {
        file?.takeIf { it.isFile }?.let { f ->
            runCatching {
                Jsonx.decodeFromString<List<ProviderStat>>(f.readText()).forEach { stats[it.id] = it }
            }
        }
    }

    fun score(id: String): Double {
        val s = stats[id] ?: return 0.5
        if (s.attempts == 0) return 0.5
        val rate = s.successes.toDouble() / s.attempts
        val speed = 1.0 / (1.0 + s.avgMs / 3_000.0)
        return rate * 0.75 + speed * 0.25
    }

    fun stat(id: String): ProviderStat? = stats[id]

    fun ordered(providers: List<PlaybackProvider>): List<PlaybackProvider> =
        providers.sortedWith(
            compareByDescending<PlaybackProvider> { it.priority }
                .thenByDescending { score(it.id) }
                .thenBy { stat(it.id)?.avgMs ?: 4_000 },
        )

    fun record(id: String, ok: Boolean, ms: Long, error: String?) {
        val s = stats.getOrPut(id) { ProviderStat(id) }
        s.attempts += 1
        if (ok) s.successes += 1
        s.avgMs = if (s.avgMs == 0L) ms else (s.avgMs * 0.7 + ms * 0.3).toLong()
        s.lastError = error
        s.lastAt = System.currentTimeMillis()
        save()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(Jsonx.encodeToString(stats.values.toList()))
        }
    }
}
