package com.spotifast.android

import android.content.Context
import android.content.SharedPreferences
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
import java.net.URLEncoder
import java.util.TimeZone
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

    private lateinit var prefs: SharedPreferences
    private var deviceName = "Spotifast"

    private val _webMode = MutableStateFlow(true)
    /** Spotify's web player plays (true), or the built-in librespot player. */
    val webMode: StateFlow<Boolean> = _webMode

    private val _liked = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** Whether songs are in Liked Songs, as far as known. */
    val liked: StateFlow<Map<String, Boolean>> = _liked

    private val _remoteDevice = MutableStateFlow<String?>(null)
    /** The device playing when it isn't this phone. */
    val remoteDevice: StateFlow<String?> = _remoteDevice

    private val lyricsCache = HashMap<String, Lyrics?>()
    @Volatile private var home: Pair<Long, HomeShelves>? = null

    private val cache = object : LinkedHashMap<String, Pair<Long, JSONObject>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JSONObject>>?) = size > 64
    }

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("spotifast", Context.MODE_PRIVATE)
        _webMode.value = prefs.getBoolean("web_player", true)
        val name = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        deviceName = "Spotifast on $name"
        Native.init(app.filesDir.absolutePath, app.cacheDir.absolutePath, deviceName)
        reported = PlayerState.parse(JSONObject(Native.state()))
        _player.value = reported
        thread(name = "spotifast-events", isDaemon = true) { eventLoop() }
        AudioOutput.start()
        refreshStatus()
        Native.start(!_webMode.value)
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
            // The built-in player's state; Spotify's web player reports its own.
            "state" -> if (!_webMode.value) {
                applyReported(PlayerState.parse(event.optJSONObject("state") ?: JSONObject()))
            }
            "playback_connecting" -> {
                _status.value = _status.value.copy(connecting = true, connectingStep = event.optString("step"))
            }
            "web_signed_in", "playback_ready", "reads_ready" -> {
                if (event.optString("type") == "playback_ready") {
                    _status.value = _status.value.copy(connecting = false, connectingStep = "")
                }
                refreshStatus()
                if (awaitingBrowser) {
                    awaitingBrowser = false
                    SignInService.stop(app)
                    bringToFront()
                }
            }
            "playback_lost" -> refreshStatus()
            "signed_out" -> {
                synchronized(cache) { cache.clear() }
                WebPlayer.stop()
                refreshStatus()
                event.optString("reason").takeIf { it.isNotEmpty() }?.let { _messages.tryEmit(it) }
            }
            "error" -> {
                val where = event.optString("where")
                if (where == "web_login" || where == "playback_login") {
                    awaitingBrowser = false
                    SignInService.stop(app)
                }
                if (where == "playback_login" || where == "playback" || where == "panic") {
                    _status.value = _status.value.copy(connecting = false, connectingStep = "")
                    refreshStatus()
                }
                _messages.tryEmit(event.optString("message"))
            }
        }
    }

    internal fun applyReported(state: PlayerState) {
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
                val fresh = Status.parse(JSONObject(Native.status()))
                val current = _status.value
                val connecting = current.connecting && !fresh.playback
                _status.value = fresh.copy(
                    connecting = connecting,
                    connectingStep = if (connecting) current.connectingStep else "",
                )
                if (_webMode.value && fresh.web && fresh.webPlayer && WebPlayer.phase.value == WebPlayer.Phase.Off) {
                    WebPlayer.start(app, deviceName)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "status", error)
            }
        }
    }

    /** Keeps the app awake until the browser hands the approval back. */
    private fun beganSignIn() {
        awaitingBrowser = true
        SignInService.start(app)
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
            Native.beginWebLogin().also { beganSignIn() }
        } catch (error: Throwable) {
            _messages.tryEmit(error.message ?: "Sign-in failed")
            null
        }
    }

    suspend fun beginPlaybackLogin(): String? = withContext(Dispatchers.IO) {
        try {
            Native.beginPlaybackLogin(!_webMode.value).also { beganSignIn() }
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
        runCatching { Native.log("play requested") }
        val current = _player.value
        hold(playing = true)
        _player.value = current.copy(playing = true, at = System.currentTimeMillis())
        PlaybackService.ensureStarted(app)
        if (playingElsewhere()) {
            webCall("/v1/me/player/play")
        } else if (!_webMode.value) {
            command(JSONObject().put("cmd", "play"))
        } else if (reported.active) {
            WebPlayer.resume()
        } else {
            playHere()
        }
    }

    fun pause() {
        runCatching { Native.log("pause requested") }
        val current = _player.value
        val position = current.positionNow()
        hold(playing = false)
        _player.value = current.copy(playing = false, position = position, at = System.currentTimeMillis())
        when {
            playingElsewhere() -> webCall("/v1/me/player/pause")
            _webMode.value -> WebPlayer.pause()
            else -> command(JSONObject().put("cmd", "pause").put("position", position))
        }
    }

    fun next() {
        when {
            playingElsewhere() -> webCall("/v1/me/player/next", "POST")
            _webMode.value -> WebPlayer.next()
            else -> command(JSONObject().put("cmd", "next"))
        }
    }

    fun previous() {
        when {
            playingElsewhere() -> webCall("/v1/me/player/previous", "POST")
            _webMode.value -> WebPlayer.previous()
            else -> command(JSONObject().put("cmd", "prev"))
        }
    }

    fun seek(positionMs: Long) {
        _player.value = _player.value.copy(position = positionMs, at = System.currentTimeMillis())
        when {
            playingElsewhere() -> webCall("/v1/me/player/seek?position_ms=$positionMs")
            _webMode.value -> WebPlayer.seek(positionMs)
            else -> command(JSONObject().put("cmd", "seek").put("position", positionMs))
        }
    }

    fun setShuffle(on: Boolean) {
        _player.value = _player.value.copy(shuffle = on)
        if (_webMode.value || playingElsewhere()) {
            webCall("/v1/me/player/shuffle?state=$on${deviceParam()}")
        } else {
            command(JSONObject().put("cmd", "shuffle").put("on", on))
        }
    }

    fun cycleRepeat() {
        val next = when (_player.value.repeat) {
            "off" -> "context"
            "context" -> "track"
            else -> "off"
        }
        _player.value = _player.value.copy(repeat = next)
        if (_webMode.value || playingElsewhere()) {
            webCall("/v1/me/player/repeat?state=$next${deviceParam()}")
        } else {
            command(JSONObject().put("cmd", "repeat").put("mode", next))
        }
    }

    /** Moves whatever plays in this account to this phone. */
    fun playHere() {
        if (!_webMode.value) {
            command(JSONObject().put("cmd", "transfer"))
            return
        }
        val device = WebPlayer.deviceId ?: return notReady()
        webCall("/v1/me/player", body = JSONObject().put("device_ids", JSONArray().put(device)).put("play", true))
    }

    /**
     * Plays [context] (a playlist, album, artist or Liked Songs), starting at
     * [track] when given. [fallback] is played as a plain list if Spotify's
     * Web API won't start the context itself (Liked Songs on some accounts).
     */
    fun playContext(context: String, track: Track? = null, shuffle: Boolean? = null, fallback: List<Track> = emptyList()) {
        if (!_webMode.value) {
            val json = JSONObject().put("cmd", "load").put("context", context)
            track?.let { json.put("track", it.uri) }
            shuffle?.let { json.put("shuffle", it) }
            if (!startOptimistically(track)) return
            command(json)
            return
        }
        val device = WebPlayer.deviceId ?: return notReady()
        if (!startOptimistically(track)) return
        val body = JSONObject().put("context_uri", context)
        track?.let { body.put("offset", JSONObject().put("uri", it.uri)) }
        scope.launch {
            try {
                shuffle?.let { api("/v1/me/player/shuffle?state=$it&device_id=$device", "PUT") }
                api("/v1/me/player/play?device_id=$device", "PUT", body.toString())
            } catch (error: Throwable) {
                if (fallback.isNotEmpty() && error is ApiException && error.status in 400..404) {
                    val index = track?.let { wanted -> fallback.indexOfFirst { it.uri == wanted.uri } }?.coerceAtLeast(0) ?: 0
                    playWebTracks(device, fallback, index)
                } else {
                    playFailed(error)
                }
            }
        }
    }

    /** Plays a plain list of songs, starting at [index]. */
    fun playTracks(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        if (!_webMode.value) {
            val uris = JSONArray()
            tracks.take(500).forEach { uris.put(it.uri) }
            if (!startOptimistically(tracks.getOrNull(index))) return
            command(JSONObject().put("cmd", "load").put("tracks", uris).put("index", index))
            return
        }
        val device = WebPlayer.deviceId ?: return notReady()
        if (!startOptimistically(tracks.getOrNull(index))) return
        scope.launch {
            try {
                playWebTracks(device, tracks, index)
            } catch (error: Throwable) {
                playFailed(error)
            }
        }
    }

    /** The Web API takes a limited list: send the chosen song and what follows. */
    private suspend fun playWebTracks(device: String, tracks: List<Track>, index: Int) {
        val start = index.coerceIn(0, tracks.lastIndex)
        val uris = JSONArray()
        tracks.subList(start, minOf(tracks.size, start + 100)).forEach { uris.put(it.uri) }
        api("/v1/me/player/play?device_id=$device", "PUT", JSONObject().put("uris", uris).toString())
    }

    private fun webCall(path: String, method: String = "PUT", body: JSONObject? = null) {
        scope.launch {
            try {
                apiText(path, method, body?.toString())
            } catch (error: Throwable) {
                playFailed(error)
            }
        }
    }

    /** Music plays on another of the account's devices, not this phone. */
    private fun playingElsewhere() = !reported.active && _remoteDevice.value != null

    /** Targets this phone's web player when it is the one playing. */
    private fun deviceParam(): String {
        val device = WebPlayer.deviceId
        return if (_webMode.value && reported.active && device != null) "&device_id=$device" else ""
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ------------------------------------------------------- likes and queue

    /** Asks Spotify whether [uri] is in Liked Songs. */
    fun refreshLiked(uri: String) {
        if (uri.isEmpty()) return
        scope.launch {
            try {
                val answer = try {
                    apiText("/v1/me/library/contains?uris=${enc(uri)}", "GET")
                } catch (error: ApiException) {
                    // The older endpoint, for tracks.
                    apiText("/v1/me/tracks/contains?ids=${enc(uri.substringAfterLast(':'))}", "GET")
                }
                _liked.value = _liked.value + (uri to JSONArray(answer).optBoolean(0))
            } catch (error: Throwable) {
                Log.w(TAG, "liked check", error)
            }
        }
    }

    fun toggleLike(uri: String) {
        if (uri.isEmpty()) return
        val was = _liked.value[uri] ?: false
        _liked.value = _liked.value + (uri to !was)
        val method = if (was) "DELETE" else "PUT"
        scope.launch {
            try {
                try {
                    apiText("/v1/me/library?uris=${enc(uri)}", method)
                } catch (error: ApiException) {
                    apiText("/v1/me/tracks?ids=${enc(uri.substringAfterLast(':'))}", method)
                }
                synchronized(cache) { cache.keys.removeAll { it.startsWith("/v1/me/tracks") } }
                _messages.tryEmit(if (was) "Removed from Liked Songs" else "Added to Liked Songs")
            } catch (error: Throwable) {
                _liked.value = _liked.value + (uri to was)
                _messages.tryEmit(error.message ?: "Couldn't change Liked Songs")
            }
        }
    }

    fun addToQueue(track: Track) {
        scope.launch {
            try {
                apiText("/v1/me/player/queue?uri=${enc(track.uri)}${deviceParam()}", "POST")
                _messages.tryEmit("Added to queue")
            } catch (error: Throwable) {
                _messages.tryEmit(
                    if (error is ApiException && error.status == 404) "Start playing something first."
                    else error.message ?: "Couldn't add to queue",
                )
            }
        }
    }

    /** What plays next, from Spotify. */
    suspend fun queue(): List<Track> =
        api("/v1/me/player/queue", fresh = true).optJSONArray("queue")?.objects()?.mapNotNull { Track.parse(it) }.orEmpty()

    // ---------------------------------------------------------------- devices

    suspend fun devices(): List<Device> =
        api("/v1/me/player/devices", fresh = true).optJSONArray("devices")?.objects()
            ?.map { Device.parse(it) }?.filter { it.id.isNotEmpty() }.orEmpty()

    /** Moves playback to [device]. */
    fun transferTo(device: Device) {
        val local = device.id == WebPlayer.deviceId
        if (!local) _remoteDevice.value = device.name
        webCall("/v1/me/player", body = JSONObject().put("device_ids", JSONArray().put(device.id)).put("play", true))
    }

    /**
     * Reads what another device plays, so this phone can show and control
     * it. Called every few seconds while the app is open.
     */
    suspend fun pollRemote() {
        if (reported.active) {
            _remoteDevice.value = null
            return
        }
        val text = try {
            apiText("/v1/me/player", "GET")
        } catch (error: Throwable) {
            return
        }
        val json = if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
        val device = json?.optJSONObject("device")
        val item = json?.optJSONObject("item")
        if (json == null || device == null || item == null) {
            _remoteDevice.value = null
            return
        }
        // This phone's own player reports itself.
        if (device.optString("id") == WebPlayer.deviceId || reported.active) return
        _remoteDevice.value = device.optString("name").ifEmpty { "another device" }
        applyReported(
            PlayerState(
                connected = true,
                active = false,
                playing = json.optBoolean("is_playing"),
                position = json.optLong("progress_ms"),
                at = System.currentTimeMillis(),
                track = NowTrack.fromApi(item),
                shuffle = json.optBoolean("shuffle_state"),
                repeat = json.optString("repeat_state", "off"),
            ),
        )
    }

    // ------------------------------------------------------- lyrics and Home

    /** Spotify's lyrics for a song, or null when it has none. Throws when they can't be read. */
    suspend fun lyrics(uri: String): Lyrics? = withContext(Dispatchers.IO) {
        val known = synchronized(lyricsCache) { lyricsCache.containsKey(uri) }
        if (known) return@withContext synchronized(lyricsCache) { lyricsCache[uri] }
        val found = Native.lyrics(uri)?.let { Lyrics.parse(JSONObject(it)) }
        synchronized(lyricsCache) { lyricsCache[uri] = found }
        found
    }

    data class HomeShelves(val madeForYou: List<Item>, val topMixes: List<Item>)

    /** Made for you and Your top mixes, kept ten minutes. */
    suspend fun homeShelves(fresh: Boolean = false): HomeShelves = withContext(Dispatchers.IO) {
        val kept = home
        if (!fresh && kept != null && SystemClock.elapsedRealtime() - kept.first < 10 * 60 * 1000L) {
            return@withContext kept.second
        }
        val json = JSONObject(Native.homeShelves(TimeZone.getDefault().id))
        val shelves = HomeShelves(
            madeForYou = json.optJSONArray("madeForYou")?.objects()?.mapNotNull { Item.parse(it) }.orEmpty(),
            topMixes = json.optJSONArray("topMixes")?.objects()?.mapNotNull { Item.parse(it) }.orEmpty(),
        )
        home = SystemClock.elapsedRealtime() to shelves
        shelves
    }

    private fun playFailed(error: Throwable) {
        holdUntil = 0
        applyReported(reported)
        _messages.tryEmit(error.message ?: "Playback failed")
    }

    private fun notReady() {
        _messages.tryEmit(
            if (WebPlayer.phase.value == WebPlayer.Phase.Failed) WebPlayer.problem.value
            else "Spotify's player is still starting.",
        )
    }

    /** Spotify's web player reported what it plays. */
    internal fun applyWebState(json: JSONObject) {
        if (json.optBoolean("none")) {
            // Playback moved to another device.
            applyReported(reported.copy(active = false, playing = false))
            return
        }
        val repeat = when (json.optInt("repeat")) {
            1 -> "context"
            2 -> "track"
            else -> "off"
        }
        applyReported(
            PlayerState(
                connected = true,
                active = true,
                playing = !json.optBoolean("paused", true),
                loading = json.optBoolean("loading"),
                position = json.optLong("position"),
                at = System.currentTimeMillis(),
                track = NowTrack.parse(json.optJSONObject("track")),
                shuffle = json.optBoolean("shuffle"),
                repeat = repeat,
            ),
        )
    }

    internal fun webPlayerReady() {
        _messages.tryEmit("Spotify's player is ready on this phone")
    }

    /** Switches between Spotify's web player and the built-in librespot player. */
    fun setWebMode(on: Boolean) {
        if (_webMode.value == on) return
        prefs.edit().putBoolean("web_player", on).apply()
        _webMode.value = on
        reported = PlayerState()
        _player.value = reported
        if (on) {
            scope.launch(Dispatchers.IO) { runCatching { Native.stopBuiltIn() } }
            refreshStatus()
        } else {
            WebPlayer.stop()
            if (_status.value.playbackSaved) reconnect()
        }
    }

    private fun startOptimistically(track: Track?): Boolean {
        val ready = if (_webMode.value) WebPlayer.deviceId != null else _status.value.playback
        if (!ready) {
            _messages.tryEmit("Playback isn't connected yet.")
            return false
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
        return true
    }

    // ----------------------------------------------------------------- Web API

    /** A Web API request; GET answers are kept for a few minutes so going back is instant. */
    /** A Web API request's raw body; throws [ApiException] unless Spotify answered 2xx. */
    suspend fun apiText(path: String, method: String = "GET", body: String? = null): String =
        withContext(Dispatchers.IO) {
            val raw = Native.api(method, path, body)
            val split = raw.indexOf('\n')
            val code = raw.substring(0, split).toInt()
            val text = raw.substring(split + 1)
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                throw ApiException(code, message?.takeIf { it.isNotEmpty() } ?: "Spotify answered $code")
            }
            text
        }

    suspend fun api(path: String, method: String = "GET", body: String? = null, fresh: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            if (method == "GET" && !fresh) {
                val cached = synchronized(cache) { cache[path] }
                if (cached != null && SystemClock.elapsedRealtime() - cached.first < CACHE_MS) {
                    return@withContext cached.second
                }
            }
            val text = apiText(path, method, body)
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (method == "GET") synchronized(cache) { cache[path] = SystemClock.elapsedRealtime() to json }
            json
        }
}
