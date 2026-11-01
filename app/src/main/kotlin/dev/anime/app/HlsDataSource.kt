package dev.anime.app

import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.anime.web.HlsProxy
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * In-process HLS data source: resolves the app's custom scheme, fetches upstream
 * playlists/segments, decrypts/rewrites them, and hands bytes to Media3.
 * No local HTTP server involved.
 */
class HlsDataSourceFactory(private val engine: HlsProxy) : DataSource.Factory {
    override fun createDataSource(): DataSource = HlsDataSource(engine)
}

private class HlsDataSource(private val engine: HlsProxy) : BaseDataSource(false) {
    private var bytes: ByteArray = ByteArray(0)
    private var position = 0
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
                if (target.link != null) engine.openLink(target.link) else engine.handle(target.url.orEmpty())
            }.getOrNull()
        } ?: throw IOException("Upstream fetch failed: ${target.link ?: target.url}")

        bytes = when (result) {
            is HlsProxy.Result.Playlist -> result.text.toByteArray(Charsets.UTF_8)
            is HlsProxy.Result.Binary -> result.bytes
        }
        position = 0
        opened = true
        transferStarted(dataSpec)
        return bytes.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= bytes.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, bytes.size - position)
        System.arraycopy(bytes, position, buffer, offset, count)
        position += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        bytes = ByteArray(0)
        position = 0
    }
}

private object C {
    const val RESULT_END_OF_INPUT = -1
}
