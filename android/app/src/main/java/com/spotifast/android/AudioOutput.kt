package com.spotifast.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread

/**
 * Plays what the Rust core decodes: 16-bit stereo PCM at 44.1 kHz, pulled
 * from librespot's sink on one high-priority thread.
 *
 * A pause stops the AudioTrack at once and keeps what it holds, so resuming
 * is seamless; a flush (seek, new song) drops it.
 */
object AudioOutput {
    private const val TAG = "SpotifastAudio"
    private const val SAMPLE_RATE = 44100
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        thread(name = "spotifast-audio", isDaemon = true) { run() }
    }

    private fun createTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum * 2, 32 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var track = createTrack()
        val buffer = ShortArray(4096)
        while (true) {
            val count = try {
                Native.readPcm(buffer, 250)
            } catch (error: Throwable) {
                Log.w(TAG, "read", error)
                SystemClock.sleep(250)
                continue
            }
            try {
                when {
                    count > 0 -> {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        var offset = 0
                        while (offset < count) {
                            val written = track.write(buffer, offset, count - offset)
                            if (written <= 0) break
                            offset += written
                        }
                    }
                    count == Native.PCM_FLUSHED -> {
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                        track.flush()
                    }
                    count == Native.PCM_PAUSED -> {
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                    }
                }
            } catch (error: IllegalStateException) {
                // The output went away (a device change); start a new one.
                Log.w(TAG, "audio track failed, recreating", error)
                runCatching { track.release() }
                track = createTrack()
            }
        }
    }
}
