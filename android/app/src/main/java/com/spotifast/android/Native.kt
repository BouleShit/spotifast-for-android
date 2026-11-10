package com.spotifast.android

/** The Rust core (android/core). Every call that touches the network blocks: call it off the main thread. */
object Native {
    init {
        System.loadLibrary("spotifast_android")
    }

    const val PCM_FLUSHED = -1
    const val PCM_PAUSED = -2

    @JvmStatic external fun init(dataDir: String, cacheDir: String, deviceName: String)
    @JvmStatic external fun start()
    @JvmStatic external fun status(): String
    @JvmStatic external fun setClientId(clientId: String)
    @JvmStatic external fun beginWebLogin(): String
    @JvmStatic external fun beginPlaybackLogin(): String
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
