package dev.anime.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val MAX_BYTES = 2_000_000L
    @Volatile private var file: File? = null

    fun init(target: File) {
        file = target
        runCatching { target.parentFile?.mkdirs() }
    }

    fun i(tag: String, message: String) = write("I", tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write("W", tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val target = file ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val detail = error?.let {
            "\n  " + it::class.simpleName + ": " + (it.message ?: "") +
                it.stackTrace.take(8).joinToString("") { frame -> "\n    at $frame" }
        }.orEmpty()
        synchronized(this) {
            runCatching {
                if (target.length() > MAX_BYTES) {
                    val rotated = File(target.parentFile, target.name + ".1")
                    rotated.delete()
                    target.renameTo(rotated)
                }
                target.appendText("$stamp $level/$tag $message$detail\n")
            }
        }
    }
}
