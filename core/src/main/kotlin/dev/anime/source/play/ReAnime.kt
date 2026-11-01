package dev.anime.source.play

import dev.anime.model.DetailResult
import dev.anime.model.PlaySource
import dev.anime.source.SourceContext
import dev.anime.store.arr
import dev.anime.store.obj
import dev.anime.store.str
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class ReAnimeProvider : PlaybackProvider {
    override val id = "reanime"
    override val name = "Re:ANIME"

    private val slugCache = ConcurrentHashMap<Long, String>()

    override suspend fun resolve(
        detail: DetailResult,
        season: Int,
        episode: Int,
        ctx: SourceContext,
        absoluteEpisode: Int?,
        episodeTitle: String?,
    ): List<PlaySource> {
        if (episode < 1) return emptyList()
        val slug = slugFor(detail, ctx)
        val referer = slug?.let { "https://reanime.to/watch/$it?ep=$episode" }
            ?: "https://reanime.to/"

        val flixText = runCatching {
            ctx.http.get("https://reanime.to/api/flix/${detail.id}/$episode") {
                header(HttpHeaders.UserAgent, UA)
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.Referrer, referer)
            }.bodyAsText()
        }.getOrNull() ?: return emptyList()

        val servers = runCatching {
            Json.parseToJsonElement(flixText).obj()?.get("servers")?.arr().orEmpty().mapNotNull { el ->
                val o = el.obj() ?: return@mapNotNull null
                FlixServer(
                    dataType = o["dataType"]?.str() ?: "",
                    serverName = o["serverName"]?.str() ?: "",
                    dataLink = o["dataLink"]?.str() ?: return@mapNotNull null,
                )
            }
        }.getOrDefault(emptyList())

        val ordered = servers
            .filter { it.dataLink.isNotBlank() }
            .sortedWith(
                compareByDescending<FlixServer> { it.dataType == "sub" }
                    .thenByDescending { it.serverName == "HD-1" }
                    .thenByDescending { it.serverName == "HD-2" },
            )

        val sources = mutableListOf<PlaySource>()
        var resolvedOne = false
        for (server in ordered) {
            val label = buildString {
                append(if (server.dataType == "dub") "Dub" else "Sub")
                if (server.serverName.isNotBlank()) append(' ').append(server.serverName)
            }
            if (!resolvedOne) {
                val extracted = runCatching { FlixCloud.extract(server.dataLink, ctx) }.getOrNull() ?: continue
                val (streamUrl, streamCtx) = extracted
                FlixCloud.register(streamUrl, streamCtx.wPayload, streamCtx.mask)
                val decrypted = FlixCloud.DEC + "/parse-flixcloud?url=" +
                    URLEncoder.encode(streamUrl, Charsets.UTF_8) +
                    "&w_payload=" + URLEncoder.encode(streamCtx.wPayload, Charsets.UTF_8)
                sources += PlaySource(
                    id = id,
                    name = "$name $label",
                    kind = "hls",
                    url = "/api/hls/stream.m3u8?u=" + URLEncoder.encode(decrypted, Charsets.UTF_8),
                    languages = if (server.dataType == "dub") listOf("English") else listOf("Japanese", "English"),
                )
                resolvedOne = true
            } else {
                sources += PlaySource(
                    id = id,
                    name = "$name $label",
                    kind = "hls",
                    url = "/api/hls/stream.m3u8?link=" + URLEncoder.encode(server.dataLink, Charsets.UTF_8),
                    languages = if (server.dataType == "dub") listOf("English") else listOf("Japanese", "English"),
                )
            }
            if (sources.size >= 4) break
        }
        return sources
    }

    private suspend fun slugFor(detail: DetailResult, ctx: SourceContext): String? {
        slugCache[detail.id]?.let { return it }
        val title = detail.title?.romaji?.takeIf { it.isNotBlank() }
            ?: detail.title?.english?.takeIf { it.isNotBlank() }
            ?: return null
        val text = runCatching {
            ctx.http.get("https://reanime.to/api/v1/search?q=" + URLEncoder.encode(title.take(80), Charsets.UTF_8)) {
                header(HttpHeaders.UserAgent, UA)
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.Referrer, "https://reanime.to/search")
            }.bodyAsText()
        }.getOrNull() ?: return null
        val slug = runCatching {
            Json.parseToJsonElement(text).obj()?.get("results")?.arr().orEmpty()
                .firstOrNull { it.obj()?.get("anilist_id")?.str()?.toLongOrNull() == detail.id }
                ?.obj()?.get("anime_id")?.str()
        }.getOrNull() ?: return null
        slugCache[detail.id] = slug
        return slug
    }

    private data class FlixServer(val dataType: String, val serverName: String, val dataLink: String)

    private companion object {
        const val UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
