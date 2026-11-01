package dev.anime.cache

import dev.anime.util.AppLog
import java.io.File
import java.security.MessageDigest

class DiskCache(private val dir: File, private val maxBytes: Long) {
    init {
        runCatching { dir.mkdirs() }
            .onFailure { AppLog.w("cache", "Could not create cache directory ${dir.name}", it) }
    }

    fun read(key: String): String? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }
            .onFailure { AppLog.w("cache", "Could not read cache entry ${file.name}", it) }
            .getOrNull() ?: return null
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return text
    }

    fun write(key: String, value: String) {
        runCatching {
            val file = fileFor(key)
            file.writeText(value)
            file.setLastModified(System.currentTimeMillis())
            trim()
        }.onFailure { AppLog.w("cache", "Could not write cache in ${dir.name}", it) }
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total > maxBytes) {
                total -= file.length()
                runCatching { file.delete() }
            }
        }
    }

    private fun fileFor(key: String): File = File(dir, sha1(key) + ".json")

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
