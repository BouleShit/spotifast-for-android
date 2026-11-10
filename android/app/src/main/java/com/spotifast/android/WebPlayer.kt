package com.spotifast.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Spotify's own web player (the Web Playback SDK) in a hidden WebView.
 *
 * For accounts whose audio keys Spotify refuses to librespot, this is the
 * phone's counterpart of Soloist on the desktop: Spotify's player plays,
 * Spotifast tells it what to play. Spotify gets the content key through
 * Widevine, as in any browser. Like Soloist, it appears as a Spotify
 * Connect device, and Spotifast starts music on it through the Web API.
 */
@SuppressLint("SetJavaScriptEnabled")
object WebPlayer {
    private const val TAG = "SpotifastWeb"

    enum class Phase { Off, Starting, Ready, Failed }

    private val main = Handler(Looper.getMainLooper())

    private val _view = MutableStateFlow<WebView?>(null)
    /** The WebView, so the activity can keep it attached while it is shown. */
    val view: StateFlow<WebView?> = _view

    private val _phase = MutableStateFlow(Phase.Off)
    val phase: StateFlow<Phase> = _phase

    private val _problem = MutableStateFlow("")
    val problem: StateFlow<String> = _problem

    @Volatile var deviceId: String? = null
        private set

    private var name = "Spotifast"

    fun start(context: Context, deviceName: String) {
        main.post {
            if (_view.value != null) return@post
            name = deviceName
            _phase.value = Phase.Starting
            _problem.value = ""
            try {
                _view.value = create(context.applicationContext)
            } catch (error: Throwable) {
                // No WebView on this phone (disabled or missing).
                fail("Android's WebView isn't available: ${error.message}")
            }
        }
    }

    fun stop() {
        main.post {
            val web = _view.value ?: return@post
            web.evaluateJavascript("if (window.player) player.disconnect();", null)
            _view.value = null
            deviceId = null
            _phase.value = Phase.Off
            // Give the disconnect a moment to reach Spotify.
            main.postDelayed({ web.destroy() }, 1500)
        }
    }

    fun restart(context: Context) {
        stop()
        main.postDelayed({ start(context, name) }, 1800)
    }

    private fun run(script: String) {
        main.post { _view.value?.evaluateJavascript(script, null) }
    }

    fun resume() = run("if (window.player) { activate(); player.resume(); }")
    fun pause() = run("if (window.player) player.pause();")
    fun next() = run("if (window.player) player.nextTrack();")
    fun previous() = run("if (window.player) player.previousTrack();")
    fun seek(positionMs: Long) = run("if (window.player) player.seek(${positionMs.coerceAtLeast(0)});")

    private fun fail(message: String) {
        _problem.value = message
        _phase.value = Phase.Failed
        log("failed: $message")
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        runCatching { Native.log(line) }
    }

    private fun create(context: Context): WebView {
        val web = WebView(context)
        web.setBackgroundColor(Color.TRANSPARENT)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Music starts from the Web API, not from a tap in the page.
            mediaPlaybackRequiresUserGesture = false
            // Present as Chrome for Android: some players refuse a page
            // that says it is a WebView. Widevine still does the protecting.
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Widevine needs the protected media identifier; nothing else is granted.
                val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                log("console ${message.messageLevel()}: ${message.message()}")
                return true
            }
        }
        web.addJavascriptInterface(Bridge, "SpotifastBridge")
        log("starting with user agent ${web.settings.userAgentString}")
        web.loadDataWithBaseURL("https://localhost/", page(name), "text/html", "utf-8", null)
        return web
    }

    private fun handle(kind: String, payload: JSONObject) {
        when (kind) {
            "ready" -> {
                deviceId = payload.optString("deviceId").ifEmpty { null }
                _phase.value = Phase.Ready
                _problem.value = ""
                log("ready as device $deviceId")
                Spotifast.webPlayerReady()
            }
            "not_ready" -> {
                deviceId = null
                _phase.value = Phase.Starting
                log("not ready")
            }
            "state" -> Spotifast.applyWebState(payload)
            "connected" -> if (!payload.optBoolean("ok")) fail("Spotify's player couldn't connect.")
            "error" -> {
                val type = payload.optString("kind")
                val message = payload.optString("message").ifEmpty { type }
                log("error $type: $message")
                when (type) {
                    "initialization_error" -> fail("This phone can't run Spotify's web player: $message")
                    "authentication_error" -> fail("Spotify refused the sign-in for its player: $message")
                    "account_error" -> fail("Spotify's player needs Premium: $message")
                    "playback_error" -> Spotifast.showMessage("Spotify couldn't play that: $message")
                    "autoplay_failed" -> Spotifast.showMessage("Android blocked playback. Tap play.")
                    else -> Spotifast.showMessage(message)
                }
            }
        }
    }

    private object Bridge {
        @JavascriptInterface
        fun token(): String = runCatching { Native.webToken() }.getOrNull().orEmpty()

        @JavascriptInterface
        fun event(kind: String, json: String) {
            val payload = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
            main.post { handle(kind, payload) }
        }
    }

    private fun page(deviceName: String): String {
        val quotedName = JSONObject.quote(deviceName)
        return """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"></head>
<body>
<script>
var player = null;
function report(kind, payload) {
  SpotifastBridge.event(kind, JSON.stringify(payload || {}));
}
function activate() {
  try { if (player && player.activateElement) player.activateElement(); } catch (e) {}
}
function biggest(images) {
  if (!images || !images.length) return "";
  var best = images[0];
  for (var i = 1; i < images.length; i++) {
    if ((images[i].width || 0) > (best.width || 0)) best = images[i];
  }
  return best.url || "";
}
function simplify(s) {
  if (!s) return { none: true };
  var t = s.track_window && s.track_window.current_track;
  return {
    paused: s.paused,
    loading: !!s.loading,
    position: s.position,
    duration: s.duration,
    shuffle: s.shuffle,
    repeat: s.repeat_mode,
    track: t ? {
      uri: t.uri,
      name: t.name,
      artists: (t.artists || []).map(function (a) { return { name: a.name, uri: a.uri }; }),
      album: t.album ? t.album.name : "",
      cover: t.album ? biggest(t.album.images) : "",
      duration: t.duration_ms
    } : null
  };
}
window.addEventListener("error", function (e) {
  report("error", { kind: "script", message: String(e.message) });
});
window.onSpotifyWebPlaybackSDKReady = function () {
  player = new Spotify.Player({
    name: $quotedName,
    getOAuthToken: function (cb) {
      var token = SpotifastBridge.token();
      if (token) cb(token);
      else report("error", { kind: "authentication_error", message: "not signed in" });
    },
    volume: 1.0
  });
  player.addListener("ready", function (e) { report("ready", { deviceId: e.device_id }); });
  player.addListener("not_ready", function (e) { report("not_ready", { deviceId: e.device_id }); });
  player.addListener("player_state_changed", function (s) { report("state", simplify(s)); });
  ["initialization_error", "authentication_error", "account_error", "playback_error"].forEach(function (k) {
    player.addListener(k, function (e) { report("error", { kind: k, message: e && e.message }); });
  });
  player.addListener("autoplay_failed", function () { report("error", { kind: "autoplay_failed" }); });
  player.connect().then(function (ok) { report("connected", { ok: ok }); });
};
</script>
<script src="https://sdk.scdn.co/spotify-player.js"></script>
</body></html>
"""
    }
}
