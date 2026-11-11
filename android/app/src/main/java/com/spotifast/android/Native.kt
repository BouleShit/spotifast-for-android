package com.spotifast.android

/** The Rust core (android/core). Every call that touches the network blocks: call it off the main thread. */
object Native {
    init {
        System.loadLibrary("spotifast_android")
    }

    const val PCM_FLUSHED = -1
    const val PCM_PAUSED = -2

    @JvmStatic external fun init(dataDir: String, cacheDir: String, deviceName: String)
    /** Reconnects the built-in librespot player too when [connectBuiltIn]. */
    @JvmStatic external fun start(connectBuiltIn: Boolean)
    @JvmStatic external fun stopBuiltIn()

    /** A current Web API access token for Spotify's web player, or null. Blocks. */
    @JvmStatic external fun webToken(): String?
    @JvmStatic external fun log(line: String)
    @JvmStatic external fun status(): String
    @JvmStatic external fun setClientId(clientId: String)
    @JvmStatic external fun beginWebLogin(): String
    /** With [startPlayer], the built-in player starts; otherwise only lyrics and Home's session. */
    @JvmStatic external fun beginPlaybackLogin(startPlayer: Boolean): String

    /** Lyrics JSON for a track, or null. Blocks. */
    @JvmStatic external fun lyrics(trackUri: String): String?

    /** Made for you and Your top mixes JSON. Blocks. */
    @JvmStatic external fun homeShelves(timezone: String): String
    @JvmStatic external fun reconnect()
    @JvmStatic external fun signOut()

    /** Returns "<status>\n<body>". */
    @JvmStatic external fun api(method: String, path: String, body: String?): String
    @JvmStatic external fun command(json: String)
    @JvmStatic external fun state(): String

    /** The recent engine log, for the debug screen. */
    @JvmStatic external fun logs(): String
    @JvmStatic external fun nextEvent(timeoutMs: Int): String?
    @JvmStatic external fun readPcm(buffer: ShortArray, timeoutMs: Int): Int
}
