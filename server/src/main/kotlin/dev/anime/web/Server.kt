package dev.anime.web

import dev.anime.model.SearchMeta
import dev.anime.model.SourceResult
import dev.anime.model.StreamEvent
import dev.anime.registry.Registry
import dev.anime.search.Aggregator
import dev.anime.model.DetailResult
import dev.anime.model.SeasonList
import dev.anime.search.EpisodeIndex
import dev.anime.search.PlaybackService
import dev.anime.store.Jsonx
import dev.anime.model.StreamResponse
import dev.anime.search.Details
import dev.anime.search.StreamAggregator
import dev.anime.source.MetadataSource
import dev.anime.source.StreamSource
import dev.anime.store.Store
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponse(val meta: SearchMeta, val results: List<SourceResult>)

@OptIn(DelicateCoroutinesApi::class)
fun Application.apiModule(
    registry: Registry<MetadataSource>,
    aggregator: Aggregator,
    store: Store,
    thumbs: ThumbService,
    details: Details,
    episodeIndex: EpisodeIndex,
    streamRegistry: Registry<StreamSource>,
    streamAgg: StreamAggregator,
    playback: PlaybackService,
    hlsProxy: HlsProxy,
) {
    install(ContentNegotiation) { json(Jsonx) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(
                """{"error":${Jsonx.encodeToString(cause.message ?: "error")}}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }

        get("/api/sources") { call.respond(registry.infos()) }

        get("/api/stream-sources") { call.respond(streamRegistry.infos()) }

        get("/apk") {
            val apk = java.io.File(System.getenv("APK_PATH") ?: "app/build/outputs/apk/debug/app-debug.apk")
            if (!apk.isFile) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.response.header("Content-Disposition", "attachment; filename=anime-debug.apk")
            call.respondBytes(apk.readBytes(), ContentType.parse("application/vnd.android.package-archive"))
        }

        get("/api/play") {
            val id = call.request.queryParameters["id"].orEmpty()
            val season = call.request.queryParameters["s"]?.toIntOrNull()?.coerceIn(0, 999) ?: 1
            val episode = call.request.queryParameters["e"]?.toIntOrNull() ?: 1
            val resp = playback.play(id, season, episode)
            if (resp.sources.isEmpty()) call.respond(HttpStatusCode.NotFound, resp) else call.respond(resp)
        }

        get("/api/hls/{file}") {
            val link = call.request.queryParameters["link"].orEmpty()
            val url = call.request.queryParameters["u"].orEmpty()
            when (val result = if (link.isNotEmpty()) hlsProxy.openLink(link) else hlsProxy.handle(url)) {
                null -> call.respond(HttpStatusCode.NotFound)
                is HlsProxy.Result.Playlist -> {
                    call.response.header("Cache-Control", "no-cache")
                    call.respondText(result.text, ContentType.parse("application/vnd.apple.mpegurl"))
                }
                is HlsProxy.Result.Binary -> {
                    call.response.header("Cache-Control", "public, max-age=31536000, immutable")
                    call.respondBytes(result.bytes, ContentType.parse(result.contentType))
                }
            }
        }

        get("/api/episodes") {
            val id = call.request.queryParameters["id"].orEmpty()
            val d: DetailResult? = if (id.isEmpty()) null else runCatching { details.byId(id) }.getOrNull()
            val list: SeasonList? = d?.let { runCatching { episodeIndex.byDetail(it) }.getOrNull() }
            if (list == null) call.respond(HttpStatusCode.NotFound) else call.respond(list)
        }

        get("/api/stream") {
            val ref = call.request.queryParameters["title"].orEmpty()
            val eps = call.request.queryParameters["eps"]?.toIntOrNull()?.takeIf { it in 1..9999 }
            val yr = call.request.queryParameters["yr"]?.toIntOrNull()?.takeIf { it in 1900..2100 }
            val names = call.request.queryParameters.getAll("c").orEmpty()
                .map { it.trim() }.filter { it.length >= 4 }
            val d: StreamResponse? = if (ref.isEmpty()) {
                null
            } else {
                runCatching { streamAgg.search(ref, eps, yr, names) }.getOrNull()
            }
            if (d == null) call.respond(HttpStatusCode.NotFound) else call.respond(d)
        }

        get("/api/cache") {
            val stats = store.stats()
            val (thumbBytes, thumbEntries) = thumbs.stats()
            call.respond(
                stats.copy(thumbBytes = thumbBytes, thumbEntries = thumbEntries, thumbCapBytes = thumbs.cap()),
            )
        }

        get("/api/thumb") {
            val url = call.request.queryParameters["u"].orEmpty()
            if (url.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val w = call.request.queryParameters["w"]?.toIntOrNull() ?: 128
            val h = call.request.queryParameters["h"]?.toIntOrNull() ?: 192
            val bytes = thumbs.get(url, w, h)
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.response.header("Cache-Control", "public, max-age=2592000, immutable")
                call.respondBytes(bytes, ContentType.Image.JPEG)
            }
        }

        get("/api/details") {
            val id = call.request.queryParameters["id"].orEmpty()
            val d: DetailResult? = if (id.isEmpty()) null else runCatching { details.byId(id) }.getOrNull()
            if (d == null) call.respond(HttpStatusCode.NotFound) else call.respond(d)
        }

        get("/{id}") {
            if (call.parameters["id"]?.toIntOrNull() == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(
                javaClass.classLoader.getResource("web/index.html")?.readBytes()?.decodeToString() ?: "",
                ContentType.Text.Html,
            )
        }

        post("/api/probe") {
            GlobalScope.launch { registry.probeAll() }
            call.respond(HttpStatusCode.Accepted)
        }

        get("/api/search") {
            val q = call.request.queryParameters["q"].orEmpty()
            var meta: SearchMeta? = null
            val results = ArrayList<SourceResult>()
            aggregator.search(q).toList().forEach { ev ->
                when (ev) {
                    is StreamEvent.Meta -> meta = ev.meta
                    is StreamEvent.Result -> results.add(ev.result)
                    is StreamEvent.Done -> Unit
                }
            }
            call.respond(SearchResponse(meta ?: SearchMeta(q, q, 0), results))
        }

        get("/api/search/stream") {
            val q = call.request.queryParameters["q"].orEmpty()
            call.response.header("Cache-Control", "no-cache")
            call.response.header("X-Accel-Buffering", "no")
            call.respondTextWriter(ContentType.Text.EventStream) {
                aggregator.search(q).collect { ev ->
                    write("data: " + Jsonx.encodeToString(StreamEvent.serializer(), ev) + "\n\n")
                    flush()
                }
            }
        }

        staticResources("/static", "web")

        get("/") {
            call.respondText(
                javaClass.classLoader.getResource("web/index.html")?.readBytes()?.decodeToString() ?: "",
                ContentType.Text.Html,
            )
        }
    }
}
