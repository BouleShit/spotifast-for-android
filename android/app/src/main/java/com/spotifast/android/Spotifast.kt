package com.spotifast.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class ApiException(val status: Int, message: String) : Exception(message)

/**
 * The app's one connection to the Rust core: account status, the player
 * state, playback commands and Web API reads.
 *
 * Like Spotifast on the desktop, the interface is optimistic: a command
 * shows its result at once, and late reports that still describe the
 * state from before it are held back for a moment instead of flickering.
 */
object Spotifast {
    private const val TAG = "Spotifast"
    private const val HOLD_MS = 2500L
    private const val CACHE_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: Context

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val _player = MutableStateFlow(PlayerState())
    val player: StateFlow<PlayerState> = _player

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** The last state the core reported, before any optimistic hold. */
    @Volatile private var reported = PlayerState()
    @Volatile private var holdPlaying: Boolean? = null
    @Volatile private var holdTrack: NowTrack? = null
    @Volatile private var holdUntil = 0L
    /** A sign-in page is open in the browser; come back when it finishes. */
    @Volatile private var awaitingBrowser = false

    private val cache = object : LinkedHashMap<String, Pair<Long, JSONObject>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JSONObject>>?) = size > 64
    }

    fun init(context: Context) {
        app = context.applicationContext
        val name = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        Native.init(app.filesDir.absolutePath, app.cacheDir.absolutePath, "Spotifast on $name")
        reported = PlayerState.parse(JSONObject(Native.state()))
        _player.value = reported
        thread(name = "spotifast-events", isDaemon = true) { eventLoop() }
        AudioOutput.start()
        refreshStatus()
        Native.start()
    }

    // ------------------------------------------------------------------ events

    private fun eventLoop() {
        while (true) {
            val text = try {
                Native.nextEvent(1000)
            } catch (error: Throwable) {
                Log.w(TAG, "event loop", error)
                SystemClock.sleep(500)
                null
            } ?: continue
            try {
                handle(JSONObject(text))
            } catch (error: Throwable) {
                Log.w(TAG, "bad event $text", error)
            }
        }
    }

    private fun handle(event: JSONObject) {
        when (event.optString("type")) {
            "state" -> applyReported(PlayerState.parse(event.optJSONObject("state") ?: JSONObject()))
            "playback_connecting" -> _status.value = _status.value.copy(connecting = true)
            "web_signed_in", "playback_ready" -> {
                refreshStatus()
                if (awaitingBrowser) {
                    awaitingBrowser = false
                    bringToFront()
                }
            }
            "playback_lost" -> refreshStatus()
            "signed_out" -> {
                synchronized(cache) { cache.clear() }
                refreshStatus()
                event.optString("reason").takeIf { it.isNotEmpty() }?.let { _messages.tryEmit(it) }
            }
            "error" -> {
                val where = event.optString("where")
                if (where == "playback_login" || where == "playback") refreshStatus()
                _messages.tryEmit(event.optString("message"))
            }
        }
    }

    private fun applyReported(state: PlayerState) {
        reported = state
        var shown = state
        if (SystemClock.elapsedRealtime() < holdUntil) {
            val wantedPlaying = holdPlaying
            if (wantedPlaying != null) {
                if (state.playing != wantedPlaying) {
                    shown = shown.copy(playing = wantedPlaying)
                } else {
                    holdPlaying = null
                }
            }
            val wantedTrack = holdTrack
            if (wantedTrack != null) {
                if (state.track?.uri != wantedTrack.uri) {
                    shown = shown.copy(track = wantedTrack, position = 0, at = System.currentTimeMillis())
                } else {
                    holdTrack = null
                }
            }
        } else {
            holdPlaying = null
            holdTrack = null
        }
        _player.value = shown
        if (shown.playing) PlaybackService.ensureStarted(app)
    }

    fun refreshStatus() {
        scope.launch(Dispatchers.IO) {
            try {
                val connecting = _status.value.connecting
                val fresh = Status.parse(JSONObject(Native.status()))
                _status.value = fresh.copy(connecting = connecting && !fresh.playback && fresh.playbackSaved)
            } catch (error: Throwable) {
                Log.w(TAG, "status", error)
            }
        }
    }

    /** Closes the sign-in tab by bringing the app back over it. */
    private fun bringToFront() {
        try {
            app.startActivity(
                Intent(app, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        } catch (error: Throwable) {
            Log.w(TAG, "cannot return from the browser", error)
        }
    }

    fun showMessage(message: String) {
        _messages.tryEmit(message)
    }

    // ----------------------------------------------------------------- sign-in

    /** Returns the URL to open, or null after showing why not. */
    suspend fun beginWebLogin(clientId: String): String? = withContext(Dispatchers.IO) {
        try {
            Native.setClientId(clientId)
            Native.beginWebLogin().also { awaitingBrowser = true }
        } catch (error: Throwable) {
            _messages.tryEmit(error.message ?: "Sign-in failed")
            null
        }
    }

    suspend fun beginPlaybackLogin(): String? = withContext(Dispatchers.IO) {
        try {
            Native.beginPlaybackLogin().also { awaitingBrowser = true }
        } catch (error: Throwable) {
            _messages.tryEmit(error.message ?: "Playback setup failed")
            null
        }
    }

    fun reconnect() {
        scope.launch(Dispatchers.IO) {
            try {
                _status.value = _status.value.copy(connecting = true)
                Native.reconnect()
            } catch (error: Throwable) {
                _status.value = _status.value.copy(connecting = false)
                _messages.tryEmit(error.message ?: "Couldn't reconnect")
            }
        }
    }

    fun signOut() {
        scope.launch(Dispatchers.IO) {
            try {
                Native.signOut()
            } catch (error: Throwable) {
                Log.w(TAG, "sign out", error)
            }
            app.stopService(Intent(app, PlaybackService::class.java))
            refreshStatus()
        }
    }

    // ---------------------------------------------------------------- playback

    private fun command(json: JSONObject) {
        scope.launch(Dispatchers.IO) {
            try {
                Native.command(json.toString())
            } catch (error: Throwable) {
                // The optimistic state was wrong: show what is really true.
                holdUntil = 0
                applyReported(reported)
                _messages.tryEmit(error.message ?: "Playback failed")
            }
        }
    }

    private fun hold(playing: Boolean? = holdPlaying, track: NowTrack? = holdTrack) {
        holdPlaying = playing
        holdTrack = track
        holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
    }

    fun togglePlay() {
        val current = _player.value
        if (current.playing) pause() else play()
    }

    fun play() {
        val current = _player.value
        hold(playing = true)
        _player.value = current.copy(playing = true, at = System.currentTimeMillis())
        PlaybackService.ensureStarted(app)
        command(JSONObject().put("cmd", "play"))
    }

    fun pause() {
        val current = _player.value
        val position = current.positionNow()
        hold(playing = false)
        _player.value = current.copy(playing = false, position = position, at = System.currentTimeMillis())
        command(JSONObject().put("cmd", "pause").put("position", position))
    }

    fun next() = command(JSONObject().put("cmd", "next"))

    fun previous() = command(JSONObject().put("cmd", "prev"))

    fun seek(positionMs: Long) {
        _player.value = _player.value.copy(position = positionMs, at = System.currentTimeMillis())
        command(JSONObject().put("cmd", "seek").put("position", positionMs))
    }

    fun setShuffle(on: Boolean) {
        _player.value = _player.value.copy(shuffle = on)
        command(JSONObject().put("cmd", "shuffle").put("on", on))
    }

    fun cycleRepeat() {
        val next = when (_player.value.repeat) {
            "off" -> "context"
            "context" -> "track"
            else -> "off"
        }
        _player.value = _player.value.copy(repeat = next)
        command(JSONObject().put("cmd", "repeat").put("mode", next))
    }

    fun playHere() = command(JSONObject().put("cmd", "transfer"))

    /** Plays [context] (a playlist, album, artist or Liked Songs), starting at [track] when given. */
    fun playContext(context: String, track: Track? = null, shuffle: Boolean? = null) {
        val json = JSONObject().put("cmd", "load").put("context", context)
        track?.let { json.put("track", it.uri) }
        shuffle?.let { json.put("shuffle", it) }
        startOptimistically(track)
        command(json)
    }

    /** Plays a plain list of songs, starting at [index]. */
    fun playTracks(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        val uris = JSONArray()
        tracks.take(500).forEach { uris.put(it.uri) }
        startOptimistically(tracks.getOrNull(index))
        command(JSONObject().put("cmd", "load").put("tracks", uris).put("index", index))
    }

    private fun startOptimistically(track: Track?) {
        if (!_status.value.playback) {
            _messages.tryEmit("Playback isn't connected yet.")
            return
        }
        val shownTrack = track?.let { NowTrack.from(it) }
        hold(playing = true, track = shownTrack)
        _player.value = _player.value.copy(
            playing = true,
            loading = true,
            track = shownTrack ?: _player.value.track,
            position = 0,
            at = System.currentTimeMillis(),
        )
        PlaybackService.ensureStarted(app)
    }

    // ----------------------------------------------------------------- Web API

    /** A Web API request; GET answers are kept for a few minutes so going back is instant. */
    suspend fun api(path: String, method: String = "GET", body: String? = null, fresh: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            if (method == "GET" && !fresh) {
                val cached = synchronized(cache) { cache[path] }
                if (cached != null && SystemClock.elapsedRealtime() - cached.first < CACHE_MS) {
                    return@withContext cached.second
                }
            }
            val raw = Native.api(method, path, body)
            val split = raw.indexOf('\n')
            val code = raw.substring(0, split).toInt()
            val text = raw.substring(split + 1)
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                throw ApiException(code, message?.takeIf { it.isNotEmpty() } ?: "Spotify answered $code")
            }
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (method == "GET") synchronized(cache) { cache[path] = SystemClock.elapsedRealtime() to json }
            json
        }
}
