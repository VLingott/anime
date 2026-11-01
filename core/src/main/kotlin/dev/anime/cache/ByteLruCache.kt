package dev.anime.cache

private class Entry<V>(val value: V, val bytes: Long)

class ByteLruCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val weigher: (V) -> Long,
) {
    private val map = LinkedHashMap<K, Entry<V>>(32, 0.75f, true)
    private var total = 0L

    @Synchronized
    fun get(key: K): V? = map[key]?.value

    @Synchronized
    fun put(key: K, value: V) {
        val bytes = weigher(value).coerceAtLeast(1L)
        map.remove(key)?.let { total -= it.bytes }
        map[key] = Entry(value, bytes)
        total += bytes
        val iterator = map.entries.iterator()
        while (total > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            total -= entry.value.bytes
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        total = 0L
    }
}
