package dev.anime.cache

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CacheTest {
    @Test
    fun byteLruEvictsOldestByWeight() {
        val cache = ByteLruCache<String, String>(100) { it.length.toLong() }
        cache.put("a", "a".repeat(60))
        cache.put("b", "b".repeat(60))
        assertNull(cache.get("a"))
        assertEquals("b".repeat(60), cache.get("b"))
    }

    @Test
    fun byteLruRespectsAccessOrder() {
        val cache = ByteLruCache<String, String>(100) { it.length.toLong() }
        cache.put("a", "a".repeat(60))
        cache.put("b", "b".repeat(30))
        cache.get("a")
        cache.put("c", "c".repeat(30))
        assertEquals("a".repeat(60), cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("c".repeat(30), cache.get("c"))
    }

    @Test
    fun cacheManagerCountsNestedFiles() {
        val dir = Files.createTempDirectory("anime-cache-size").toFile()
        try {
            dir.resolve("search/nested").mkdirs()
            dir.resolve("search/a.bin").writeBytes(ByteArray(3))
            dir.resolve("search/nested/b.bin").writeBytes(ByteArray(7))

            assertEquals(10L, CacheManager.size(dir))
            CacheManager.clear(dir)
            assertEquals(0L, CacheManager.size(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun diskCacheTrimsToBudgetAndReads() {
        val dir = Files.createTempDirectory("anime-cache-test").toFile()
        try {
            val cache = DiskCache(dir, 6)
            cache.write("first", "12345")
            dir.listFiles()?.firstOrNull()?.setLastModified(0)
            cache.write("second", "67890")
            val files = dir.listFiles().orEmpty()
            assertEquals(1, files.size)
            assertNull(cache.read("first"))
            assertEquals("67890", cache.read("second"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
