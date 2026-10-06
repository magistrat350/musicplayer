package com.magistrat.musicplayer.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

data class SpotifyTrack(val title: String, val artists: String, val durationMs: Long) {
    /** Hauptinterpret (fuer die Suche und den Abgleich) */
    val mainArtist: String get() = artists.split(",", "&", " feat.", " ft.").first().trim()
}

data class SpotifyList(val name: String, val coverUrl: String?, val tracks: List<SpotifyTrack>)

/**
 * Liest oeffentliche Spotify-Playlists, -Alben und -Songs ueber die Embed-Seite
 * (kein Spotify-Konto / API-Schluessel noetig). Die Embed-Seite liefert bis zu ~100 Titel.
 */
object Spotify {
    private val LINK = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?(?:embed/)?(playlist|album|track)/([A-Za-z0-9]+)")

    fun isSpotifyUrl(url: String): Boolean = LINK.containsMatchIn(url) || url.startsWith("spotify:")

    /** Einheitliche Form, damit ein spaeterer Abgleich dieselbe Playlist wiederfindet. */
    fun canonical(url: String): String? {
        LINK.find(url)?.let { return "https://open.spotify.com/${it.groupValues[1]}/${it.groupValues[2]}" }
        val parts = url.split(':')
        if (parts.size == 3 && parts[0] == "spotify") return "https://open.spotify.com/${parts[1]}/${parts[2]}"
        return null
    }

    suspend fun fetch(url: String): SpotifyList = withContext(Dispatchers.IO) {
        val canon = canonical(url) ?: throw IllegalArgumentException("Kein Spotify-Link")
        val m = LINK.find(canon)!!
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        val html = get("https://open.spotify.com/embed/$type/$id")
        val json = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("Spotify-Seite konnte nicht gelesen werden")
        val root = JSONObject(json)
        val entity = findEntity(root) ?: throw IllegalStateException("Keine Titel in der Spotify-Seite gefunden")

        val name = entity.optString("name").ifBlank { entity.optString("title") }.ifBlank { "Spotify" }
        val tracks = mutableListOf<SpotifyTrack>()
        val list = entity.optJSONArray("trackList")
        if (list != null) {
            for (i in 0 until list.length()) {
                val t = list.optJSONObject(i) ?: continue
                val title = t.optString("title").ifBlank { t.optString("name") }
                if (title.isBlank()) continue
                tracks += SpotifyTrack(title, artistsOf(t), t.optLong("duration", t.optLong("duration_ms", 0)))
            }
        } else {
            // Einzelner Song
            val title = entity.optString("title").ifBlank { entity.optString("name") }
            if (title.isNotBlank()) tracks += SpotifyTrack(title, artistsOf(entity), entity.optLong("duration", 0))
        }
        if (tracks.isEmpty()) throw IllegalStateException("Keine Titel gefunden (ist die Playlist öffentlich?)")
        SpotifyList(name, coverOf(entity), tracks)
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
        conn.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
        return try {
            if (conn.responseCode != 200) throw IllegalStateException("Spotify antwortet mit HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Sucht das Objekt mit "trackList" (bzw. den Song-Datensatz) – tolerant gegenueber Strukturaenderungen. */
    private fun findEntity(root: JSONObject): JSONObject? {
        root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity")?.let { return it }
        return findWithKey(root, "trackList", 0) ?: findWithKey(root, "entity", 0)?.optJSONObject("entity")
    }

    private fun findWithKey(obj: Any?, key: String, depth: Int): JSONObject? {
        if (depth > 12) return null
        when (obj) {
            is JSONObject -> {
                if (obj.has(key)) return obj
                for (k in obj.keys()) findWithKey(obj.opt(k), key, depth + 1)?.let { return it }
            }
            is JSONArray -> for (i in 0 until obj.length()) findWithKey(obj.opt(i), key, depth + 1)?.let { return it }
        }
        return null
    }

    private fun artistsOf(o: JSONObject): String {
        o.optJSONArray("artists")?.let { arr ->
            val names = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return o.optString("subtitle").replace(" ", " ").trim()
    }

    private fun coverOf(o: JSONObject): String? {
        o.optJSONObject("coverArt")?.optJSONArray("sources")?.let { s ->
            // groesstes Bild
            var best: JSONObject? = null
            for (i in 0 until s.length()) {
                val c = s.optJSONObject(i) ?: continue
                if (best == null || c.optInt("width") > best.optInt("width")) best = c
            }
            best?.optString("url")?.takeIf { it.startsWith("http") }?.let { return it }
        }
        o.optJSONObject("visualIdentity")?.optJSONArray("image")?.let { s ->
            for (i in s.length() - 1 downTo 0) s.optJSONObject(i)?.optString("url")?.takeIf { it.startsWith("http") }?.let { return it }
        }
        return null
    }

    // ---------- YouTube-Treffer bewerten ----------

    data class Candidate(val id: String, val title: String, val channel: String, val durationSec: Double)

    private val BAD_WORDS = listOf(
        "live", "cover", "karaoke", "remix", "sped up", "speed up", "slowed", "reverb", "8d", "nightcore",
        "instrumental", "acoustic", "reaction", "tutorial", "lesson", "1 hour", "10 hours", "loop", "bass boosted", "mashup",
    )

    /** Hoeher = besser. Bewertet Dauer, Titel-/Interpret-Uebereinstimmung, offizielle Kanaele. */
    fun score(c: Candidate, want: SpotifyTrack): Double {
        val title = c.title.lowercase()
        val channel = c.channel.lowercase()
        val wantTitle = want.title.lowercase()
        var s = 0.0

        if (want.durationMs > 0 && c.durationSec > 0) {
            val diff = abs(c.durationSec - want.durationMs / 1000.0)
            s -= diff / 3.0
            if (diff > 30) s -= 25
        }
        val words = wantTitle.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
        if (words.isNotEmpty()) s += 12.0 * words.count { title.contains(it) } / words.size
        val artist = want.mainArtist.lowercase()
        if (artist.isNotBlank() && (title.contains(artist) || channel.contains(artist))) s += 8
        if (channel.endsWith(" - topic")) s += 8
        if (channel.contains("vevo")) s += 4
        if (title.contains("official audio") || title.contains("offizielles audio")) s += 4
        if (title.contains("official video") || title.contains("official music video")) s += 2
        for (w in BAD_WORDS) if (title.contains(w) && !wantTitle.contains(w)) s -= 15
        return s
    }
}
