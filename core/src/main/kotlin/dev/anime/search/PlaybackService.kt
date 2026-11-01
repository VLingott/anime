package dev.anime.search

import dev.anime.cache.DiskCache
import dev.anime.model.PlayResponse
import dev.anime.model.StreamResolution
import dev.anime.source.SourceContext
import dev.anime.source.play.AniWorldProvider
import dev.anime.source.play.PlaybackProvider
import dev.anime.source.play.ReAnimeProvider
import dev.anime.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val PLAY_CACHE_BYTES = 32L * 1024 * 1024

@Serializable
private data class CachedPlay(
    val savedAt: Long,
    val response: PlayResponse,
)

class PlaybackService(
    private val ctx: SourceContext,
    private val details: Details,
    private val urlTransform: (String) -> String = { it },
    cacheDir: File? = null,
    private val providers: List<PlaybackProvider> = listOf(AniWorldProvider(cacheDir), ReAnimeProvider()),
    val health: ProviderHealth = ProviderHealth(),
) {
    private val hot = ConcurrentHashMap<String, PlayResponse>()
    private val disk = cacheDir?.let { DiskCache(File(it, "play-v5"), PLAY_CACHE_BYTES) }
    private val resolutionDisk = cacheDir?.let { DiskCache(File(it, "resolutions"), 8L * 1024 * 1024) }
    private val cacheJson = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastResolvedAt = ConcurrentHashMap<String, Long>()

    suspend fun play(
        id: String,
        season: Int,
        episode: Int,
        forceRefresh: Boolean = false,
        absoluteEpisode: Int? = null,
        episodeTitle: String? = null,
    ): PlayResponse {
        val animeId = id.trim().toLongOrNull()
            ?: return PlayResponse(0, null, season, episode, error = "invalid id")
        if (episode < 1 || episode > 9999) {
            return PlayResponse(animeId, null, season, episode, error = "invalid episode")
        }

        val key = "$animeId|${season.coerceAtLeast(1)}|$episode"
        if (!forceRefresh) {
            val cached = hot[key] ?: readDisk(key)?.also { hot[key] = it }
            if (cached != null) {
                refreshInBackground(key, animeId, season, episode, absoluteEpisode, episodeTitle)
                return present(cached)
            }
        }

        return locks.getOrPut(key) { Mutex() }.withLock {
            if (!forceRefresh) {
                val cached = hot[key] ?: readDisk(key)?.also { hot[key] = it }
                if (cached != null) return@withLock present(cached)
            } else if (System.currentTimeMillis() - (lastResolvedAt[key] ?: 0L) < 5_000) {
                val recent = hot[key] ?: readDisk(key)?.also { hot[key] = it }
                if (recent != null) return@withLock present(recent)
            }
            val resolved = resolveAndStore(key, animeId, season, episode, absoluteEpisode, episodeTitle)
            if (resolved.sources.isNotEmpty()) lastResolvedAt[key] = System.currentTimeMillis()
            present(resolved)
        }
    }

    private fun refreshInBackground(
        key: String,
        animeId: Long,
        season: Int,
        episode: Int,
        absoluteEpisode: Int?,
        episodeTitle: String?,
    ) {
        if (absoluteEpisode == null && episodeTitle == null) return
        if (!refreshing.add(key)) return
        scope.launch {
            try {
                play(
                    id = animeId.toString(),
                    season = season,
                    episode = episode,
                    forceRefresh = true,
                    absoluteEpisode = absoluteEpisode,
                    episodeTitle = episodeTitle,
                )
            } finally {
                refreshing.remove(key)
            }
        }
    }

    private suspend fun resolveAndStore(
        key: String,
        animeId: Long,
        season: Int,
        episode: Int,
        absoluteEpisode: Int? = null,
        episodeTitle: String? = null,
    ): PlayResponse {
        val detail = details.byId(animeId.toString())
            ?: return PlayResponse(animeId, null, season, episode, error = "title not found")

        val ordered = health.ordered(providers)
        var lastError: String? = null
        val resolved = coroutineScope {
            ordered.map { provider ->
                async(Dispatchers.IO) {
                    val t0 = System.nanoTime()
                    val result = runCatching {
                        provider.resolve(detail, season, episode, ctx, absoluteEpisode, episodeTitle)
                    }
                    val sources = result.getOrDefault(emptyList())
                    val error = result.exceptionOrNull()?.message?.take(120)
                        ?: result.exceptionOrNull()?.let { it::class.simpleName }
                    health.record(provider.id, sources.isNotEmpty(), (System.nanoTime() - t0) / 1_000_000, error)
                    if (sources.isEmpty() && error != null) AppLog.w("provider", provider.id + ": " + error)
                    if (sources.isEmpty() && lastError == null && error != null) lastError = error
                    sources
                }
            }.awaitAll()
        }

        val merged = resolved.flatten().distinctBy { it.url }
        if (merged.isEmpty()) {
            val errorResponse = PlayResponse(
                id = animeId,
                title = detail.title?.english ?: detail.title?.romaji,
                season = season,
                episode = episode,
                error = lastError ?: "no playable source",
            )
            return errorResponse
        }

        val response = PlayResponse(
            id = animeId,
            title = detail.title?.english ?: detail.title?.romaji,
            season = season,
            episode = episode,
            sources = merged,
        )
        hot[key] = response
        runCatching {
            disk?.write(key, cacheJson.encodeToString(CachedPlay(System.currentTimeMillis(), response)))
        }
        return response
    }

    fun cachedResolutions(url: String): List<StreamResolution> {
        val text = resolutionDisk?.read(url) ?: return emptyList()
        return runCatching { cacheJson.decodeFromString<List<StreamResolution>>(text) }.getOrDefault(emptyList())
    }

    fun storeResolutions(url: String, resolutions: List<StreamResolution>) {
        if (resolutions.isEmpty()) return
        runCatching { resolutionDisk?.write(url, cacheJson.encodeToString(resolutions)) }
    }

    private fun present(response: PlayResponse): PlayResponse {
        if (response.sources.isEmpty()) return response
        return response.copy(
            sources = response.sources.map { source ->
                if (source.url.startsWith("/api/hls")) source.copy(url = urlTransform(source.url)) else source
            },
        )
    }

    private fun readDisk(key: String): PlayResponse? {
        val text = disk?.read(key) ?: return null
        return runCatching { cacheJson.decodeFromString<CachedPlay>(text).response }.getOrNull()
    }

    /** Resolves a real episode per provider (not just a homepage probe) and records the outcome. */
    suspend fun probe(animeId: Long = 154587L, season: Int = 1, episode: Int = 1) {
        val detail = details.byId(animeId.toString()) ?: return
        coroutineScope {
            providers.map { provider ->
                async(Dispatchers.IO) {
                    val t0 = System.nanoTime()
                    val sources = runCatching { provider.resolve(detail, season, episode, ctx, null, null) }
                    health.record(
                        provider.id,
                        sources.isSuccess && sources.getOrDefault(emptyList()).isNotEmpty(),
                        (System.nanoTime() - t0) / 1_000_000,
                        sources.exceptionOrNull()?.message,
                    )
                }
            }.awaitAll()
        }
    }
}
