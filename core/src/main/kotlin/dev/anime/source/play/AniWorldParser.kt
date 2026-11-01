package dev.anime.source.play

internal object AniWorldParser {
    data class EpisodeTitles(val german: String?, val english: String?)

    private val germanTitlePattern = Regex("""class="episodeGermanTitle">([^<]*)<""")
    private val englishTitlePattern = Regex("""class="episodeEnglishTitle">([^<]*)<""")
    private val listItem = Regex("""<li[^>]*data-lang-key="(\d+)"[^>]*>""")
    private val linkTarget = Regex("""data-link-target="([^"]+)"""")
    private val seasonPattern = Regex("""staffel-(\d+)""")
    private val episodePattern = Regex("""staffel-(\d+)/episode-(\d+)""")

    fun parseLanguages(html: String): LinkedHashMap<Int, List<String>> {
        val byLanguage = LinkedHashMap<Int, MutableList<String>>()
        for (match in listItem.findAll(html)) {
            val tag = match.value
            val language = match.groupValues[1].toIntOrNull() ?: continue
            val target = linkTarget.find(tag)?.groupValues?.get(1) ?: continue
            byLanguage.getOrPut(language) { mutableListOf() }.add(target)
        }
        return byLanguage.mapValuesTo(LinkedHashMap()) { (_, targets) -> targets.distinct() }
    }

    fun episodeTitles(html: String): EpisodeTitles = EpisodeTitles(
        german = germanTitlePattern.find(html)?.groupValues?.get(1)
            ?.let(::decodeEntities)?.let(::cleanEpisodeTitle),
        english = englishTitlePattern.find(html)?.groupValues?.get(1)
            ?.let(::decodeEntities)?.let(::cleanEpisodeTitle),
    )

    fun seasonNumbers(html: String): List<Int> = seasonPattern.findAll(html)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .distinct()
        .sorted()
        .toList()

    fun episodeNumbers(html: String, season: Int): List<Int> = episodePattern.findAll(html)
        .filter { it.groupValues[1].toIntOrNull() == season }
        .mapNotNull { it.groupValues[2].toIntOrNull() }
        .distinct()
        .sorted()
        .toList()

    fun titleSeason(title: String): Int {
        Regex("(?i)(\\d+)(st|nd|rd|th)\\s+season").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("(?i)season\\s+(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("(?i)part\\s+(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return 1
    }

    fun cleanEpisodeTitle(value: String): String? = value
        .replace(Regex("""\s*\[Episode\s+\d+]\s*$"""), "")
        .trim()
        .takeIf { it.isNotEmpty() }

    fun decodeEntities(value: String): String = value
        .replace("&#039;", "'")
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace(Regex("""&#(\d+);""")) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
}
