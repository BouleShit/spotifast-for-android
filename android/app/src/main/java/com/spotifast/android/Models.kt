package com.spotifast.android

import org.json.JSONArray
import org.json.JSONObject

data class Status(
    val loaded: Boolean = false,
    val clientId: String = "",
    val redirectUri: String = "http://127.0.0.1:8989/login",
    val web: Boolean = false,
    val accountId: String = "",
    val displayName: String = "",
    val product: String = "",
    val playbackSaved: Boolean = false,
    val playback: Boolean = false,
    /** The Web API grant carries what Spotify's web player needs. */
    val webPlayer: Boolean = false,
    val connecting: Boolean = false,
    /** What the playback connection is doing right now. */
    val connectingStep: String = "",
) {
    companion object {
        fun parse(json: JSONObject) = Status(
            loaded = true,
            clientId = json.optString("clientId"),
            redirectUri = json.optString("redirectUri", "http://127.0.0.1:8989/login"),
            web = json.optBoolean("web"),
            accountId = json.optString("accountId"),
            displayName = json.optString("displayName"),
            product = json.optString("product"),
            playbackSaved = json.optBoolean("playbackSaved"),
            playback = json.optBoolean("playback"),
            webPlayer = json.optBoolean("webPlayer"),
        )
    }
}

data class Artist(val name: String, val uri: String)

/** A song as lists show it. */
data class Track(
    val uri: String,
    val name: String,
    val artists: List<Artist>,
    val albumName: String,
    val albumUri: String,
    val image: String?,
    val durationMs: Long,
    val number: Int = 0,
) {
    val artistLine: String get() = artists.joinToString(", ") { it.name }

    companion object {
        /** A Web API track or episode object; null for local files and removed items. */
        fun parse(json: JSONObject?, fallbackImage: String? = null, albumOverride: JSONObject? = null): Track? {
            if (json == null) return null
            val uri = json.optString("uri")
            if (uri.isEmpty() || uri.startsWith("spotify:local:")) return null
            val album = albumOverride ?: json.optJSONObject("album")
            val artists = json.optJSONArray("artists")?.objects()?.map {
                Artist(it.optString("name"), it.optString("uri"))
            } ?: json.optJSONObject("show")?.let { listOf(Artist(it.optString("name"), it.optString("uri"))) }
            ?: emptyList()
            return Track(
                uri = uri,
                name = json.optString("name"),
                artists = artists,
                albumName = album?.optString("name") ?: "",
                albumUri = album?.optString("uri") ?: "",
                image = bestImage(album?.optJSONArray("images"), small = true)
                    ?: bestImage(json.optJSONArray("images"), small = true) ?: fallbackImage,
                durationMs = json.optLong("duration_ms"),
                number = json.optInt("track_number"),
            )
        }
    }
}

/** A playlist, album or artist card. */
data class Item(
    val kind: String,
    val id: String,
    val uri: String,
    val name: String,
    val subtitle: String,
    val image: String?,
) {
    companion object {
        fun parse(json: JSONObject?): Item? {
            if (json == null) return null
            val kind = json.optString("type")
            val id = json.optString("id")
            if (id.isEmpty()) return null
            val subtitle = when (kind) {
                "playlist" -> json.optJSONObject("owner")?.optString("display_name")?.let { "Playlist · $it" } ?: "Playlist"
                "album" -> {
                    val artists = json.optJSONArray("artists")?.objects()?.joinToString(", ") { it.optString("name") } ?: ""
                    val year = json.optString("release_date").take(4)
                    listOf(artists, year).filter { it.isNotEmpty() }.joinToString(" · ")
                }
                "artist" -> "Artist"
                else -> ""
            }
            return Item(kind, id, json.optString("uri"), json.optString("name"), subtitle, bestImage(json.optJSONArray("images")))
        }
    }
}

data class NowTrack(
    val uri: String,
    val name: String,
    val artists: List<Artist>,
    val album: String,
    val cover: String?,
    val durationMs: Long,
) {
    val artistLine: String get() = artists.joinToString(", ") { it.name }

    companion object {
        fun parse(json: JSONObject?): NowTrack? {
            if (json == null || json.optString("uri").isEmpty()) return null
            return NowTrack(
                uri = json.optString("uri"),
                name = json.optString("name"),
                artists = json.optJSONArray("artists")?.objects()?.map {
                    Artist(it.optString("name"), it.optString("uri"))
                } ?: emptyList(),
                album = json.optString("album"),
                cover = json.optString("cover").ifEmpty { null },
                durationMs = json.optLong("duration"),
            )
        }

        fun from(track: Track) = NowTrack(track.uri, track.name, track.artists, track.albumName, track.image, track.durationMs)

        /** A Web API track object, with its large cover. */
        fun fromApi(json: JSONObject?): NowTrack? {
            val track = Track.parse(json) ?: return null
            val big = bestImage(json?.optJSONObject("album")?.optJSONArray("images"))
            return from(track).copy(cover = big ?: track.image)
        }
    }
}

data class PlayerState(
    val connected: Boolean = false,
    val active: Boolean = false,
    val playing: Boolean = false,
    val loading: Boolean = false,
    /** Position at [at], in epoch milliseconds. */
    val position: Long = 0,
    val at: Long = 0,
    val track: NowTrack? = null,
    val shuffle: Boolean = false,
    val repeat: String = "off",
) {
    /** Where playback is now, moving on from the last report while playing. */
    fun positionNow(now: Long = System.currentTimeMillis()): Long {
        val moved = if (playing && !loading && at > 0) position + (now - at) else position
        val duration = track?.durationMs ?: 0
        return if (duration > 0) moved.coerceIn(0, duration) else moved.coerceAtLeast(0)
    }

    companion object {
        fun parse(json: JSONObject) = PlayerState(
            connected = json.optBoolean("connected"),
            active = json.optBoolean("active"),
            playing = json.optBoolean("playing"),
            loading = json.optBoolean("loading"),
            position = json.optLong("position"),
            at = json.optLong("at"),
            track = NowTrack.parse(json.optJSONObject("track")),
            shuffle = json.optBoolean("shuffle"),
            repeat = json.optString("repeat", "off").ifEmpty { "off" },
        )
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/** The smallest image at least 300 px wide, or the largest when [small] is false. */
fun bestImage(images: JSONArray?, small: Boolean = false): String? {
    val list = images?.objects()?.filter { it.optString("url").isNotEmpty() } ?: return null
    if (list.isEmpty()) return null
    val target = if (small) 160 else 300
    val sorted = list.sortedBy { it.optInt("width", 0) }
    return (sorted.firstOrNull { it.optInt("width", 0) >= target } ?: sorted.last()).optString("url")
}

fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** A Spotify Connect device from the Web API. */
data class Device(
    val id: String,
    val name: String,
    val type: String,
    val active: Boolean,
) {
    companion object {
        fun parse(json: JSONObject) = Device(
            id = json.optString("id"),
            name = json.optString("name"),
            type = json.optString("type"),
            active = json.optBoolean("is_active"),
        )
    }
}

/** A song's lyrics from Spotify; [synced] lines carry their start time. */
data class Lyrics(val synced: Boolean, val lines: List<LyricLine>) {
    /** The line being sung at [positionMs], or -1. */
    fun activeLine(positionMs: Long): Int {
        if (!synced) return -1
        var found = -1
        for ((index, line) in lines.withIndex()) {
            val at = line.atMs ?: continue
            if (at <= positionMs) found = index else break
        }
        return found
    }

    companion object {
        fun parse(json: JSONObject): Lyrics {
            val synced = json.optBoolean("synced")
            val lines = json.optJSONArray("lines")?.objects()?.map {
                LyricLine(if (it.isNull("ms")) null else it.optLong("ms"), it.optString("text"))
            } ?: emptyList()
            return Lyrics(synced, lines)
        }
    }
}

data class LyricLine(val atMs: Long?, val text: String)
