package dev.anime.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.DriverManager

val Jsonx: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class HealthRow(
    val id: String,
    val online: Boolean = false,
    val lastMs: Long? = null,
    val avgMs: Long? = null,
    val score: Double = 0.0,
    val failStreak: Int = 0,
    val lastChecked: Long = 0,
    val lastError: String? = null,
    val checks: Long = 0,
    val oks: Long = 0,
)

@Serializable
data class CacheStats(
    val resultBytes: Long = 0,
    val resultEntries: Int = 0,
    val resultCapBytes: Long = 0,
    val thumbBytes: Long = 0,
    val thumbEntries: Int = 0,
    val thumbCapBytes: Long = 0,
)

class Store(path: String = "data/anime.db", private val capBytes: Long = 32L * 1024 * 1024) {
    private val mutex = Mutex()
    private var resultBytes = 0L
    private val hot = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 512
        },
    )

    private val conn = run {
        File(path).parentFile?.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:$path").apply {
            createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("PRAGMA synchronous=NORMAL")
                it.execute("PRAGMA temp_store=MEMORY")
                it.execute("PRAGMA mmap_size=268435456")
                it.execute("PRAGMA cache_size=-16000")
                it.execute("""CREATE TABLE IF NOT EXISTS health(
                    id TEXT PRIMARY KEY, payload TEXT NOT NULL, ts INTEGER NOT NULL)""")
                it.execute("""CREATE TABLE IF NOT EXISTS search_cache(
                    q TEXT PRIMARY KEY, payload TEXT NOT NULL, ts INTEGER NOT NULL, bytes INTEGER NOT NULL)""")
            }
        }
    }

    init {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(SUM(bytes),0), COUNT(*) FROM search_cache").use { rs ->
                if (rs.next()) resultBytes = rs.getLong(1)
            }
        }
    }

    private suspend fun <T> locked(block: () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    suspend fun loadHealth(): Map<String, HealthRow> = locked {
        val out = HashMap<String, HealthRow>()
        conn.prepareStatement("SELECT id, payload FROM health").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    runCatching { Jsonx.decodeFromString<HealthRow>(rs.getString(2)) }
                        .onSuccess { out[rs.getString(1)] = it }
                }
            }
        }
        out
    }

    suspend fun saveHealth(rows: Collection<HealthRow>) = locked {
        if (rows.isEmpty()) return@locked
        conn.prepareStatement(
            "INSERT INTO health(id, payload, ts) VALUES(?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET payload=excluded.payload, ts=excluded.ts",
        ).use { ps ->
            val now = System.currentTimeMillis()
            rows.forEach { row ->
                ps.setString(1, row.id)
                ps.setString(2, Jsonx.encodeToString(row))
                ps.setLong(3, now)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    suspend fun searchCacheGet(q: String): Pair<String, Long>? {
        hot[q]?.let { return it to 0 }
        val loaded = locked {
            conn.prepareStatement("SELECT payload, ts FROM search_cache WHERE q=?").use { ps ->
                ps.setString(1, q)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getLong(2) else null }
            }
        } ?: return null
        hot[q] = loaded.first
        return loaded
    }

    suspend fun searchCachePut(q: String, payload: String) = locked {
        val bytes = payload.length.toLong() * 2
        conn.prepareStatement("SELECT bytes FROM search_cache WHERE q=?").use { ps ->
            ps.setString(1, q)
            ps.executeQuery().use { rs -> if (rs.next()) resultBytes -= rs.getLong(1) }
        }
        conn.prepareStatement(
            "INSERT INTO search_cache(q, payload, ts, bytes) VALUES(?,?,?,?) " +
                "ON CONFLICT(q) DO UPDATE SET payload=excluded.payload, ts=excluded.ts, bytes=excluded.bytes",
        ).use { ps ->
            ps.setString(1, q)
            ps.setString(2, payload)
            ps.setLong(3, System.currentTimeMillis())
            ps.setLong(4, bytes)
            ps.executeUpdate()
        }
        resultBytes += bytes
        hot[q] = payload
        if (resultBytes > capBytes) prune()
    }

    private fun prune() {
        val target = (capBytes * 0.85).toLong()
        var freed = 0L
        conn.prepareStatement(
            "DELETE FROM search_cache WHERE q IN " +
                "(SELECT q FROM search_cache ORDER BY ts ASC LIMIT 200) RETURNING bytes",
        ).use { ps ->
            ps.executeQuery().use { rs -> while (rs.next()) freed += rs.getLong(1) }
        }
        resultBytes -= freed
        if (resultBytes > target) prune()
    }

    suspend fun stats(): CacheStats = locked {
        val entries = conn.prepareStatement("SELECT COUNT(*) FROM search_cache").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        CacheStats(
            resultBytes = resultBytes,
            resultEntries = entries,
            resultCapBytes = capBytes,
        )
    }
}
