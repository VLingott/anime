package dev.anime.source

import dev.anime.model.StreamHit
import dev.anime.store.arr
import dev.anime.store.obj
import dev.anime.store.str
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * Adapter for one site entry from a shared extension index
 * (kohi-den/extensions, yuzono/anime-extensions). It registers the site,
 * probes/ranks it, but does not scrape: real lookup would need per-site code.
 */
class ExtensionSiteSource(
    override val id: String,
    override val name: String,
    override val baseUrl: String,
) : StreamSource {
    override val kind = "stream"
    override val searchable = false

    override suspend fun search(query: String, ctx: SourceContext): List<StreamHit> = emptyList()

    override suspend fun probe(ctx: SourceContext): Boolean =
        runCatching { ctx.http.get(baseUrl).status.value in 200..399 }.getOrDefault(false)
}

/** Pulls every [StreamSource] site listed in the configured extension indexes. */
object ExtRepos {

    private const val INDEX_TIMEOUT_MS = 15_000L

    private fun kohiDenUrl(): String =
        System.getenv("KOHIDEN_INDEX_URL")
            ?: "https://kohiden.xyz/Kohi-den/extensions/raw/branch/main/index.min.json"

    private fun yuzonoUrl(): String =
        System.getenv("YUZONO_INDEX_URL")
            ?: "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"

    /** Loads both lists concurrently; a failing list is skipped, not fatal. */
    suspend fun load(ctx: SourceContext): List<StreamSource> = coroutineScope {
        listOf(
            async { fetchIndex(ctx, "koh", kohiDenUrl()) },
            async { fetchIndex(ctx, "yuz", yuzonoUrl()) },
        ).awaitAll().flatten().distinctBy { siteKey(it.name, it.baseUrl) }
    }

    private fun siteKey(name: String, baseUrl: String): String {
        val normName = name.lowercase().replace(Regex("[^a-z0-9]"), "")
        val host = runCatching { URI(baseUrl).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: baseUrl
        return "$normName@$host"
    }

    private suspend fun fetchIndex(
        ctx: SourceContext,
        prefix: String,
        url: String,
    ): List<ExtensionSiteSource> = withTimeoutOrNull(INDEX_TIMEOUT_MS) {
        runCatching {
            val el = Json.parseToJsonElement(ctx.http.get(url).bodyAsText())
            el.arr().orEmpty().flatMap { entry ->
                entry.obj()?.get("sources")?.arr().orEmpty().mapNotNull { s ->
                    val o = s.obj() ?: return@mapNotNull null
                    val site = o["baseUrl"]?.str()?.trim().orEmpty()
                    if (!site.startsWith("http")) return@mapNotNull null
                    val name = o["name"]?.str()?.trim().orEmpty()
                    val key = o["id"]?.str()?.trim().orEmpty().ifBlank {
                        runCatching { URI(site).host ?: site }.getOrDefault(site)
                    }
                    ExtensionSiteSource("$prefix-$key", name.ifBlank { key }, site)
                }
            }
        }.getOrDefault(emptyList())
    } ?: emptyList()
}
