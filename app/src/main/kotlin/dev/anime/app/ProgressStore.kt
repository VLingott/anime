package dev.anime.app

import dev.anime.ui.LastPlayed

import android.content.Context
import android.content.SharedPreferences

/** Per-episode playback position, stored as "positionMs|durationMs" keyed by id:season:episode. */
object ProgressStore : dev.anime.ui.ProgressStore {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback-progress", Context.MODE_PRIVATE)
    }

    private fun key(id: Long, season: Int, episode: Int) = "$id:$season:$episode"

    override fun resumeMs(id: Long, season: Int, episode: Int): Long {
        val raw = prefs.getString(key(id, season, episode), null) ?: return 0L
        return raw.substringBefore('|').toLongOrNull() ?: 0L
    }

    override fun save(id: Long, season: Int, episode: Int, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs <= 0) return
        val key = key(id, season, episode)
        if (positionMs < 3_000 || positionMs >= durationMs - 20_000) {
            val marker = "$id:last"
            val edit = prefs.edit().remove(key)
            if (prefs.getString(marker, null)?.startsWith("$season|$episode|") == true) edit.remove(marker)
            edit.apply()
            return
        }
        prefs.edit()
            .putString(key, "$positionMs|$durationMs")
            .putString("$id:last", "$season|$episode|$positionMs")
            .apply()
    }

    override fun titleLanguage(): Int = prefs.getInt("ui:titleLanguage", 0)

    override fun saveTitleLanguage(value: Int) {
        prefs.edit().putInt("ui:titleLanguage", value).apply()
    }

    override fun audioPreference(id: Long): String? = prefs.getString("$id:audio", null)

    override fun saveAudioPreference(id: Long, label: String?) {
        if (label.isNullOrBlank()) prefs.edit().remove("$id:audio").apply()
        else prefs.edit().putString("$id:audio", label).apply()
    }

    override fun last(id: Long): LastPlayed? {
        val raw = prefs.getString("$id:last", null)
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
        for ((key, value) in prefs.all) {
            if (!key.startsWith("$id:") || value !is String) continue
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

    /** episode ("season-episode") -> progress fraction 0..1 for the series. */
    override fun fraction(id: Long, season: Int, episode: Int): Float? = fractions(id)["$season-$episode"]

    fun fractions(id: Long): Map<String, Float> {
        val prefix = "$id:"
        val out = HashMap<String, Float>()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(prefix) || value !is String) continue
            val parts = key.removePrefix(prefix).split(':')
            if (parts.size != 2) continue
            val pos = value.substringBefore('|').toLongOrNull() ?: continue
            val dur = value.substringAfter('|', "").toLongOrNull() ?: continue
            if (dur <= 0) continue
            out["${parts[0]}-${parts[1]}"] = (pos.toFloat() / dur).coerceIn(0f, 1f)
        }
        return out
    }
}
