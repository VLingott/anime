package dev.anime.desktop

import dev.anime.ui.LastPlayed
import java.util.prefs.Preferences

object Progress {
    private val prefs: Preferences = Preferences.userRoot().node("dev/anime/desktop/progress")

    private fun key(id: Long, season: Int, episode: Int) = "$id:$season:$episode"

    fun resumeMs(id: Long, season: Int, episode: Int): Long =
        prefs.get(key(id, season, episode), null)?.substringBefore('|')?.toLongOrNull() ?: 0L

    fun save(id: Long, season: Int, episode: Int, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs <= 0) return
        val k = key(id, season, episode)
        if (positionMs < 3_000 || positionMs >= durationMs - 20_000) {
            prefs.remove(k)
            if (prefs.get("$id:last", null)?.startsWith("$season|$episode|") == true) prefs.remove("$id:last")
            return
        }
        prefs.put(k, "$positionMs|$durationMs")
        prefs.put("$id:last", "$season|$episode|$positionMs")
    }

    fun titleLanguage(): Int = prefs.getInt("ui:titleLanguage", 0)

    fun saveTitleLanguage(value: Int) = prefs.putInt("ui:titleLanguage", value)

    fun defaultAudioLanguage(): String = prefs.get("ui:defaultAudio", "Japanese") ?: "Japanese"

    fun saveDefaultAudioLanguage(value: String) = prefs.put("ui:defaultAudio", value)

    fun hideAdultContent(): Boolean = prefs.getBoolean("ui:hideAdult", true)

    fun saveHideAdultContent(value: Boolean) = prefs.putBoolean("ui:hideAdult", value)

    fun audioPreference(id: Long): String? = prefs.get("$id:audio", null)

    fun saveAudioPreference(id: Long, label: String?) {
        if (label.isNullOrBlank()) prefs.remove("$id:audio") else prefs.put("$id:audio", label)
    }

    fun last(id: Long): LastPlayed? {
        val raw = prefs.get("$id:last", null)
        if (raw != null) {
            val parts = raw.split('|')
            if (parts.size >= 3) {
                val season = parts[0].toIntOrNull()
                val episode = parts[1].toIntOrNull()
                if (season != null && episode != null) {
                    return LastPlayed(season, episode, parts[2].toLongOrNull() ?: 0L)
                }
            }
        }
        var best: LastPlayed? = null
        for (key in prefs.keys()) {
            if (!key.startsWith("$id:")) continue
            val parts = key.removePrefix("$id:").split(':')
            if (parts.size != 2) continue
            val season = parts[0].toIntOrNull() ?: continue
            val episode = parts[1].toIntOrNull() ?: continue
            val current = best
            if (current == null || season > current.season || (season == current.season && episode > current.episode)) {
                best = LastPlayed(season, episode, 0L)
            }
        }
        return best
    }

    fun removeSeries(id: Long) {
        val prefix = "$id:"
        runCatching {
            for (key in prefs.keys()) {
                if (key.startsWith(prefix)) prefs.remove(key)
            }
        }
        runCatching { prefs.flush() }
    }

    fun fraction(id: Long, season: Int, episode: Int): Float? {
        val raw = prefs.get(key(id, season, episode), null) ?: return null
        val pos = raw.substringBefore('|').toLongOrNull() ?: return null
        val dur = raw.substringAfter('|', "").toLongOrNull() ?: return null
        if (dur <= 0) return null
        return (pos.toFloat() / dur).coerceIn(0f, 1f).takeIf { it > 0f }
    }
}
