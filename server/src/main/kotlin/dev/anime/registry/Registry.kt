package dev.anime.registry

import dev.anime.model.SourceInfo
import dev.anime.source.SourceAdapter
import dev.anime.source.SourceContext
import dev.anime.store.HealthRow
import dev.anime.store.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

class Registry<T : dev.anime.source.SourceBase>(
    val sources: List<T>,
    private val store: Store,
    private val ctx: SourceContext,
    private val scope: CoroutineScope,
) {
    private val health = ConcurrentHashMap<String, HealthRow>()
    private val probing = AtomicBoolean(false)
    private val dirty = ConcurrentHashMap.newKeySet<String>()

    suspend fun init() {
        health.putAll(store.loadHealth())
    }

    fun start() {
        scope.launch {
            probeAll()
            while (true) {
                delay(15 * 60_000)
                probeAll()
            }
        }
        scope.launch {
            while (true) {
                delay(5_000)
                flushHealth()
            }
        }
    }

    private suspend fun flushHealth() {
        if (dirty.isEmpty()) return
        val rows = dirty.mapNotNull { health[it] }
        dirty.clear()
        runCatching { store.saveHealth(rows) }
    }

    private fun score(ok: Boolean, avgMs: Long?, checks: Long, oks: Long, streak: Int): Double {
        val availability = (oks + 1.0) / (checks + 2.0)
        val speed = 1.0 / (1.0 + (avgMs ?: 1_500L) / 1_200.0)
        val penalty = 0.55.pow(min(streak, 3))
        return ((0.7 * availability + 0.3 * speed) * penalty * 1000).toInt() / 1000.0
    }

    fun rankOf(id: String): Int = ranked().indexOfFirst { it.id == id } + 1

    /** Sources eligible for a query now: not probed yet, or last seen online. */
    fun candidates(): List<T> = ranked().filter { health[it.id]?.online != false }

    fun ranked(): List<T> = sources.sortedWith(
        compareByDescending<T> { health[it.id]?.score ?: 0.3 }
            .thenBy { health[it.id]?.avgMs ?: 2_000L },
    )

    fun infos(): List<SourceInfo> = ranked().mapIndexed { index, s ->
        val h = health[s.id]
        val checks = h?.checks ?: 0
        val oks = h?.oks ?: 0
        SourceInfo(
            id = s.id,
            name = s.name,
            baseUrl = s.baseUrl,
            kind = s.kind,
            online = h?.online ?: false,
            lastMs = h?.lastMs,
            avgMs = h?.avgMs,
            score = h?.score ?: 0.0,
            failStreak = h?.failStreak ?: 0,
            lastChecked = h?.lastChecked ?: 0,
            lastError = h?.lastError,
            checks = checks,
            oks = oks,
            availability = if (checks == 0L) 0.0 else (oks.toDouble() / checks),
            rank = index + 1,
        )
    }

    suspend fun probeAll() {
        if (!probing.compareAndSet(false, true)) return
        try {
            val gate = Semaphore(12)
            sources.map { s -> scope.async { gate.withPermit { probeOne(s) } } }.awaitAll()
            flushHealth()
        } finally {
            probing.set(false)
        }
    }

    suspend fun probeOne(s: dev.anime.source.SourceBase) {
        val t0 = System.nanoTime()
        val ok = try {
            withTimeoutOrNull(4_000) { s.probe(ctx) } ?: false
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            false
        }
        record(s.id, ok, (System.nanoTime() - t0) / 1_000_000, if (ok) null else "probe failed")
    }

    fun record(id: String, ok: Boolean, ms: Long, error: String?) {
        val prev = health[id]
        val prevAvg = prev?.avgMs
        val avg = when {
            !ok -> prevAvg ?: ms
            prevAvg == null -> ms
            else -> (prevAvg * 0.65 + ms * 0.35).toLong()
        }
        val streak = if (ok) 0 else min((prev?.failStreak ?: 0) + 1, 99)
        val checks = (prev?.checks ?: 0) + 1
        val oks = (prev?.oks ?: 0) + if (ok) 1 else 0
        val row = HealthRow(
            id = id,
            online = ok,
            lastMs = ms,
            avgMs = avg,
            score = score(ok, avg, checks, oks, streak),
            failStreak = streak,
            lastChecked = System.currentTimeMillis(),
            lastError = error,
            checks = checks,
            oks = oks,
        )
        health[id] = row
        dirty.add(id)
    }
}
