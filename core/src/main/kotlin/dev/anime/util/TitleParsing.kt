package dev.anime.util

private val seasonMarker = Regex(
    "(?i)(\\b(\\d+(st|nd|rd|th)\\s+season|season\\s+\\d+|part\\s+\\d+|cour\\s+\\d+)\\b|\\s(ii|iii|iv|v|vi|vii|viii|ix|x)$)",
)

fun hasSeasonMarker(title: String): Boolean = seasonMarker.containsMatchIn(title)

fun stripSeasonMarkers(title: String): String {
    var current = title
    while (true) {
        val next = current.replace(seasonMarker, "").trim()
        if (next == current) return current
        current = next
    }
}
