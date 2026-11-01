package dev.anime.search

import dev.anime.model.SearchHit
import dev.anime.model.SearchMeta
import dev.anime.model.SourceResult
import dev.anime.model.StreamEvent
import dev.anime.registry.Registry
import dev.anime.source.MetadataSource
import dev.anime.source.SourceAdapter
import dev.anime.source.SourceContext
import dev.anime.store.Jsonx
import dev.anime.store.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

private const val SOURCE_TIMEOUT_MS = 4_500L
private const val LATE_MS = 3_000L
private const val FANOUT = 16

fun normalizeQuery(raw: String): String =
    raw.trim().lowercase().replace(Regex("\\s+"), " ")

class Aggregator(
    private val registry: Registry<MetadataSource>,
    private val store: Store,
    private val ctx: SourceContext,
) {
    fun search(raw: String): Flow<StreamEvent> = channelFlow {
        val q = normalizeQuery(raw)
        if (q.isEmpty()) {
            send(StreamEvent.Meta(SearchMeta(query = raw, normalized = "", sourcesTotal = 0)))
            send(StreamEvent.Done(0))
            return@channelFlow
        }

        val started = System.nanoTime()
        val cachedRaw = store.searchCacheGet(q)
        send(
            StreamEvent.Meta(
                SearchMeta(
                    query = raw,
                    normalized = q,
                    sourcesTotal = registry.sources.size,
                    cached = cachedRaw != null,
                ),
            ),
        )

        val ranked = registry.ranked()
        val best = ConcurrentLinkedQueue<Pair<Int, SourceResult>>()

        if (cachedRaw != null) {
            val cached = runCatching { Jsonx.decodeFromString<SourceResult>(cachedRaw.first) }.getOrNull()
            if (cached != null) {
                send(StreamEvent.Result(cached.copy(ms = 0, cached = true, late = false, error = null)))
            }

            val job = launch {
                for ((index, source) in ranked.withIndex()) {
                    val result = fetch(source, q)
                    if (result.hits.isEmpty()) continue
                    best.add(index to result)
                    send(StreamEvent.Result(result))
                    break
                }
                persistBest(best, q)
            }
            job.join()
        } else {
            val gate = Semaphore(FANOUT)
            val jobs = ranked.mapIndexed { index, source ->
                launch {
                    val result = gate.withPermit { fetch(source, q) }
                    best.add(index to result)
                    send(StreamEvent.Result(result))
                }
            }
            jobs.joinAll()
            persistBest(best, q)
        }

        send(StreamEvent.Done((System.nanoTime() - started) / 1_000_000))
    }

    private suspend fun persistBest(best: ConcurrentLinkedQueue<Pair<Int, SourceResult>>, q: String) {
        val winner = best.filter { it.second.error == null && it.second.hits.isNotEmpty() }
            .minByOrNull { it.first }
            ?.second
            ?: return
        runCatching { store.searchCachePut(q, Jsonx.encodeToString(winner)) }
    }

    private suspend fun fetch(source: SourceAdapter, q: String): SourceResult {
        val t0 = System.nanoTime()
        var error: String? = null
        val hits: List<SearchHit> = try {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) { source.search(q, ctx) }
                ?: run { error = "timeout"; emptyList() }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            error = e.message?.take(140) ?: e::class.simpleName ?: "error"
            emptyList()
        }
        val ms = (System.nanoTime() - t0) / 1_000_000

        registry.record(source.id, error == null, ms, error)

        return SourceResult(
            sourceId = source.id,
            sourceName = source.name,
            ms = ms,
            hits = hits,
            error = error,
            late = ms >= LATE_MS,
        )
    }
}
