package dev.anime.app

import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.anime.web.HlsProxy
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * In-process HLS data source: resolves the app's custom scheme, fetches upstream
 * playlists/segments, decrypts/rewrites them, and hands bytes to Media3.
 * No local HTTP server involved.
 */
class HlsDataSourceFactory(private val engine: HlsProxy) : DataSource.Factory {
    override fun createDataSource(): DataSource = HlsDataSource(engine)
}

private class HlsDataSource(private val engine: HlsProxy) : BaseDataSource(false) {
    private var stream: InputStream? = null
    private var remaining: Long = 0L
    private var opened = false
    private var currentUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        currentUri = uri
        val target = AppCore.target(uri.toString())
            ?: throw IOException("Bad HLS uri: $uri")

        val result = runBlocking {
            runCatching {
                if (target.link != null) {
                    engine.openLinkFile(target.link)
                } else {
                    engine.handleFile(target.url.orEmpty())
                }
            }.getOrNull()
        } ?: throw IOException("Upstream fetch failed: ${target.link ?: target.url}")

        val available: Long
        stream = when (result) {
            is HlsProxy.Result.Playlist -> {
                val bytes = result.text.toByteArray(Charsets.UTF_8)
                available = bytes.size.toLong()
                ByteArrayInputStream(bytes)
            }
            is HlsProxy.Result.FileData -> {
                available = result.file.length()
                FileInputStream(result.file)
            }
        }
        val position = dataSpec.position.coerceAtLeast(0L)
        skipFully(stream!!, position)
        val afterSkip = (available - position).coerceAtLeast(0L)
        remaining = if (dataSpec.length >= 0L) minOf(afterSkip, dataSpec.length) else afterSkip
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val current = stream ?: return C.RESULT_END_OF_INPUT
        val limit = minOf(length.toLong(), remaining).toInt()
        val count = current.read(buffer, offset, limit)
        if (count <= 0) return C.RESULT_END_OF_INPUT
        remaining -= count
        bytesTransferred(count)
        return count
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            val buffer = ByteArray(minOf(left, 8192L).toInt())
            val read = input.read(buffer)
            if (read <= 0) break
            left -= read
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        runCatching { stream?.close() }
        stream = null
        remaining = 0L
    }
}

private object C {
    const val RESULT_END_OF_INPUT = -1
}
