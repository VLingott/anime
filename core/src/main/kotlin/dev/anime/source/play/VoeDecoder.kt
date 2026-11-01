package dev.anime.source.play

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

internal object VoeDecoder {
    private val junk = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
    private val jsonScript = Regex(
        """<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val legacyPayload = Regex("""var a168c='([^']+)'""")
    private val m3u8Pattern = Regex("""https?://[^'"\s<>]+\.m3u8[^'"\s<>]*""")

    fun decodePage(html: String): String? {
        for (match in jsonScript.findAll(html)) {
            val element = runCatching { Json.parseToJsonElement(match.groupValues[1].trim()) }.getOrNull() ?: continue
            val encoded = when (element) {
                is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(element.contentOrNull)
                else -> emptyList()
            }
            for (candidate in encoded) {
                decode(candidate)?.let { return it }
            }
        }
        legacyPayload.find(html)?.groupValues?.get(1)?.let { encoded ->
            decode(encoded)?.let { return it }
        }
        return null
    }

    fun decode(encoded: String): String? = runCatching {
        var text = rotateLetters(encoded)
        for (part in junk) text = text.replace(part, "_")
        text = text.replace("_", "")
        val first = String(base64(text), Charsets.UTF_8)
        val shifted = buildString(first.length) {
            for (ch in first) append((ch.code - 3).toChar())
        }
        val second = String(base64(shifted.reversed()), Charsets.UTF_8)
        val obj = Json.parseToJsonElement(second).jsonObject
        obj["source"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
    }.getOrNull()

    fun findM3u8(html: String): String? = m3u8Pattern.find(html)?.value

    private fun base64(value: String): ByteArray {
        val padded = when (value.length % 4) {
            2 -> "$value=="
            3 -> "$value="
            else -> value
        }
        return Base64.getDecoder().decode(padded)
    }

    private fun rotateLetters(value: String): String = buildString(value.length) {
        for (ch in value) {
            val code = ch.code
            append(
                when {
                    code in 65..90 -> ((code - 65 + 13) % 26 + 65).toChar()
                    code in 97..122 -> ((code - 97 + 13) % 26 + 97).toChar()
                    else -> ch
                },
            )
        }
    }
}
