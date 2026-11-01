package dev.anime.filter

import dev.anime.model.StreamHit

private val JUNK = listOf(
    "teasers?", "trailers?", "\\btr(1|2)\\b", "\\bop\\b", "\\bop\\s*[0-9]+\\b",
    "opening\\s*(song|theme)?", "ending\\s*(song|theme)?", "\\bamv\\b", "\\bpv\\b", "recap",
    "creditless", "\\bcredits?(\\s+roll)?\\b", "music\\s*video", "interview", "commercial",
    "previews?", "sidebar", "\\bmenu\\b", "\\bncop\\b", "\\bnced\\b", "storyboards?",
    "datamosh", "\\bnegs?\\b",
).map { Regex(it, RegexOption.IGNORE_CASE) }

class EngineRules(
    val query: String,
    val candidates: List<String>,
    val epsBound: Int?,
    val seasonYear: Int?,
    val strictTitles: Boolean,
)

class QualityEngine {
    fun decide(hit: StreamHit, rules: EngineRules): String? {
        val title = hit.title.trim()
        if (title.isEmpty()) return "empty title"
        for (pattern in JUNK) if (pattern.containsMatchIn(title)) return "junk: ${pattern.pattern}"

        if (!rules.strictTitles) {
            val lower = title.lowercase()
            val ok = rules.candidates.any { cand -> matches(lower, cand.lowercase()) }
            if (!ok) return "no title match"
        }

        rules.epsBound?.takeIf { it > 0 }?.let { bound ->
            hit.episode?.let { ep -> if (ep > bound + 2) return "ep $ep > bound $bound" }
        }

        // sanity year only: season-anchored bounds reject legitimate later seasons
        hit.year?.let { y ->
            val now = java.time.Year.now().value
            if (y !in 1945..now + 1) return "insane year $y"
        }
        return null
    }

    private fun matches(title: String, cand: String): Boolean {
        if (cand.length < 4) return false
        if (cand in title) return true
        if (title.length >= 5 && title in cand) return true
        val ratio = lev(title.take(90), cand).toDouble() / maxOf(title.length, cand.length, 1)
        return ratio <= 0.42
    }

    private fun lev(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}
