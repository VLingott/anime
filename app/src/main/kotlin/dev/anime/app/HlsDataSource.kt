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
    private var length: Long = 0L
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
                    engine.openLink(target.link)
                } else {
                    engine.handleFile(target.url.orEmpty())
                }
            }.getOrNull()
        } ?: throw IOException("Upstream fetch failed: ${target.link ?: target.url}")

        stream = when (result) {
            is HlsProxy.Result.Playlist -> ByteArrayInputStream(result.text.toByteArray(Charsets.UTF_8))
            is HlsProxy.Result.FileData -> FileInputStream(result.file)
            is HlsProxy.Result.Binary -> ByteArrayInputStream(result.bytes)
        }
        length = when (result) {
            is HlsProxy.Result.Playlist -> result.text.toByteArray(Charsets.UTF_8).size.toLong()
            is HlsProxy.Result.FileData -> result.file.length()
            is HlsProxy.Result.Binary -> result.bytes.size.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val current = stream ?: return C.RESULT_END_OF_INPUT
        val count = current.read(buffer, offset, length)
        if (count <= 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        runCatching { stream?.close() }
        stream = null
        length = 0L
    }
}

private object C {
    const val RESULT_END_OF_INPUT = -1
}
