package dev.anime.app

import android.content.Context
import dev.anime.cache.CacheManager
import dev.anime.search.Details
import dev.anime.search.PlaybackService
import dev.anime.source.AniListSource
import dev.anime.source.SourceContext
import dev.anime.source.TvmazeEpisodesSource
import dev.anime.source.buildHttpClient
import dev.anime.web.HlsProxy
import java.io.File
import java.net.URLEncoder

private const val UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

object AppCore {
    private const val BASE = "animehls://hls?"

    val ctx: SourceContext by lazy { SourceContext(buildHttpClient(UA)) }
    @Volatile private var cacheDir: File? = null
    val aniList by lazy { AniListSource(cacheDir) }
    val tvmaze by lazy { TvmazeEpisodesSource() }
    val details by lazy { Details(ctx) }

    @Volatile private var hlsEngine: HlsProxy? = null

    val hls: HlsProxy
        get() = hlsEngine ?: error("core not initialized")

    val playback: PlaybackService
        get() = playbackService ?: error("core not initialized")

    @Volatile private var playbackService: PlaybackService? = null

    @Synchronized
    fun init(context: Context) {
        if (hlsEngine != null) return
        cacheDir = File(context.filesDir, "anime-cache").apply { mkdirs() }
        CacheManager.ensure(cacheDir!!)
        val dir = File(context.cacheDir, "hls-cache").apply { mkdirs() }
        val engine = HlsProxy(
            ctx = ctx,
            dir = dir.absolutePath,
            urlFor = { abs -> BASE + "u=" + URLEncoder.encode(abs, Charsets.UTF_8) },
        )
        hlsEngine = engine
        playbackService = PlaybackService(
            ctx = ctx,
            details = details,
            urlTransform = { url -> BASE + url.substringAfter('?', "") },
            health = dev.anime.search.ProviderHealth(File(context.filesDir, "provider-health.json")),
            cacheDir = File(context.filesDir, "anime-cache"),
        )
    }

    data class Target(val url: String? = null, val link: String? = null)

    fun target(uri: String): Target? {
        if (!uri.startsWith(BASE)) return null
        val query = uri.substringAfter('?', "")
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (value.isEmpty()) continue
            val decoded = runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8) }.getOrNull() ?: continue
            when (key) {
                "u" -> return Target(url = decoded)
                "link" -> return Target(link = decoded)
            }
        }
        return null
    }
}
