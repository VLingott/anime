package dev.anime.search

import dev.anime.model.SearchMeta
import dev.anime.model.StreamHit
import dev.anime.filter.EngineRules
import dev.anime.filter.QualityEngine
import dev.anime.model.StreamResponse
import dev.anime.model.StreamResult
import dev.anime.registry.Registry
import dev.anime.source.SourceContext
import dev.anime.source.StreamSource
import dev.anime.store.Jsonx
import dev.anime.store.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue

private const val STREAM_CACHE_TTL = 30 * 60_000L
private const val STREAM_TIMEOUT_MS = 4_000L
private const val STREAM_LATE_MS = 2_800L
private const val FANOUT_STREAM = 8

class StreamAggregator(
    private val registry: Registry<StreamSource>,
    private val store: Store,
    private val ctx: SourceContext,
) {
    private val engine = QualityEngine()
    suspend fun search(raw: String, epsBound: Int? = null, seasonYear: Int? = null, names: List<String> = emptyList()): StreamResponse {
        val started = System.nanoTime()
        val q = normalizeQuery(raw)
        if (q.isEmpty()) {
            return StreamResponse(SearchMeta(raw, "", 0), emptyList())
        }

        return coroutineScope {
            val results = ConcurrentLinkedQueue<StreamResult>()
            val gate = Semaphore(FANOUT_STREAM)
            registry.candidates().filter { it.searchable }.map { source ->
                launch { results.add(gate.withPermit { fetch(source, q, epsBound, seasonYear, names) }) }
            }.joinAll()

            val list = results.sortedBy { registry.rankOf(it.sourceId) }
                .filter { it.error != null || it.hits.isNotEmpty() }
            val winner = list.firstOrNull { it.error == null && it.hits.any { h -> h.rejectReason == null } }
            if (winner != null) {
                runCatching { store.searchCachePut(q, Jsonx.encodeToString(winner)) }
            }
            StreamResponse(SearchMeta(raw, q, registry.sources.size), list)
        }
    }

    suspend fun cached(raw: String): StreamResponse {
        val q = normalizeQuery(raw)
        val hit = store.searchCacheGet(q)
            ?: return StreamResponse(SearchMeta(raw, q, registry.sources.size), emptyList())
        if (System.currentTimeMillis() - hit.second > STREAM_CACHE_TTL) {
            return StreamResponse(SearchMeta(raw, q, registry.sources.size), emptyList())
        }
        val cached = runCatching { Jsonx.decodeFromString<StreamResult>(hit.first) }.getOrNull()
            ?: return StreamResponse(SearchMeta(raw, q, registry.sources.size), emptyList())
        return StreamResponse(
            SearchMeta(raw, q, registry.sources.size, cached = true),
            listOf(cached.copy(cached = true, late = false, error = null, ms = 0)),
        )
    }

    private suspend fun fetch(
        source: StreamSource,
        q: String,
        epsBound: Int?,
        seasonYear: Int?,
        names: List<String>,
    ): StreamResult {
        val t0 = System.nanoTime()
        var error: String? = null
        val hits: List<StreamHit> = try {
            withTimeoutOrNull(STREAM_TIMEOUT_MS) { source.search(q, ctx) }
                ?: run { error = "timeout"; emptyList() }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            error = e.message?.take(140) ?: e::class.simpleName ?: "error"
            emptyList()
        }
        val ms = (System.nanoTime() - t0) / 1_000_000

        val rules = EngineRules(
            query = q,
            candidates = (names + candidateNames(q)).filter { it.isNotBlank() }.distinct(),
            epsBound = epsBound,
            seasonYear = seasonYear,
            strictTitles = source.strictTitles,
        )
        val seen = HashSet<String>()
        val annotated = hits.map { hit ->
            val norm = hit.title.lowercase().replace(Regex("\\s+"), " ")
            val reason = if (!seen.add(norm)) "duplicate" else engine.decide(hit, rules)
            hit.copy(rejectReason = reason)
        }
        val clean = annotated.count { it.rejectReason == null }

        return StreamResult(
            sourceId = source.id,
            sourceName = source.name,
            ms = ms,
            hits = annotated,
            error = error,
            late = ms >= STREAM_LATE_MS,
        )
    }

    private fun candidateNames(q: String): List<String> {
        val cleaned = q.replace(Regex("[:\",'_-]+"), " ").lowercase()
        return (listOf(q, cleaned) + cleaned.split(" ").filter { it.length >= 4 })
            .filter { it.isNotBlank() }
            .distinct()
    }
}
