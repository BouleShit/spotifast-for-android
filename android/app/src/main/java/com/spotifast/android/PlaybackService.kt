package com.spotifast.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.launch
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * Keeps playback alive in the background: a foreground service with a
 * media notification, a media session for the lock screen, headsets and
 * watches, audio focus, and a pause when headphones are unplugged.
 */
class PlaybackService : LifecycleService() {
    companion object {
        private const val TAG = "SpotifastService"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE = "com.spotifast.android.TOGGLE"
        private const val ACTION_NEXT = "com.spotifast.android.NEXT"
        private const val ACTION_PREVIOUS = "com.spotifast.android.PREVIOUS"

        @Volatile private var running = false

        fun ensureStarted(context: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            } catch (error: Throwable) {
                // Android refuses foreground services started from the background.
                Log.w(TAG, "cannot start the playback service now", error)
            }
        }
    }

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var resumeOnFocusGain = false
    private var foreground = false
    private var coverUrl: String? = null
    private var cover: Bitmap? = null
    private var shownKey: String? = null

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && Spotifast.player.value.playing) {
                Spotifast.pause()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        runCatching { Native.log("audio focus change $change (web player: ${Spotifast.webMode.value})") }
        // Spotify's web player manages focus itself, and taking it is what
        // makes this app lose it: never pause it from here.
        if (Spotifast.webMode.value) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                hasFocus = false
                if (Spotifast.player.value.playing) Spotifast.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (Spotifast.player.value.playing) {
                    resumeOnFocusGain = true
                    Spotifast.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    Spotifast.play()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
        session = MediaSessionCompat(this, "Spotifast").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = Spotifast.play()
                override fun onPause() = Spotifast.pause()
                override fun onStop() = Spotifast.pause()
                override fun onSkipToNext() = Spotifast.next()
                override fun onSkipToPrevious() = Spotifast.previous()
                override fun onSeekTo(pos: Long) = Spotifast.seek(pos)
            })
            setSessionActivity(
                PendingIntent.getActivity(
                    this@PlaybackService,
                    0,
                    Intent(this@PlaybackService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            isActive = true
        }
        // Android requires the notification within seconds of the start.
        goForeground(build(Spotifast.player.value))
        ContextCompat.registerReceiver(
            this,
            noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleScope.launch {
            Spotifast.player.collect { update(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_TOGGLE -> Spotifast.togglePlay()
            ACTION_NEXT -> Spotifast.next()
            ACTION_PREVIOUS -> Spotifast.previous()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(noisy) }
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun update(state: PlayerState) {
        if (Spotifast.webMode.value) {
            // The WebView asks Android for audio focus when it plays; holding
            // it here too would make Android take it from one of them.
            abandonFocus()
        } else if (state.playing) {
            requestFocus()
        }
        val track = state.track
        if (track?.cover != coverUrl) {
            coverUrl = track?.cover
            cover = null
            track?.cover?.let { loadCover(it) }
        }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.name ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artistLine ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track?.durationMs ?: 0)
                .apply { cover?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    if (state.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    state.positionNow(),
                    if (state.playing && !state.loading) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        // The notification only changes with the song, the cover or play/pause.
        val key = "${track?.uri}|${state.playing}|${cover != null}"
        if (key == shownKey) return
        shownKey = key
        val notification = build(state)
        if (state.playing) {
            goForeground(notification)
        } else {
            if (foreground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                foreground = false
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun goForeground(notification: Notification) {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            foreground = true
        } catch (error: Throwable) {
            Log.w(TAG, "cannot go foreground", error)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun loadCover(url: String) {
        lifecycleScope.launch {
            val result = imageLoader.execute(
                ImageRequest.Builder(this@PlaybackService).data(url).size(512).allowHardware(false).build(),
            )
            val bitmap = (result as? SuccessResult)?.drawable?.toBitmap()
            if (bitmap != null && url == coverUrl) {
                cover = bitmap
                update(Spotifast.player.value)
            }
        }
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        hasFocus = false
        resumeOnFocusGain = false
    }

    private fun requestFocus() {
        if (hasFocus) return
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
            description = "What's playing, with controls"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun action(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this,
        code,
        Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(state: PlayerState): Notification {
        val track = state.track
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_spotifast)
            .setContentTitle(track?.name ?: "Spotifast")
            .setContentText(track?.artistLine ?: "Ready to play")
            .setLargeIcon(cover)
            .setContentIntent(session.controller.sessionActivity)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(state.playing)
            .addAction(R.drawable.ic_skip_previous, "Previous", action(ACTION_PREVIOUS, 1))
            .addAction(
                if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (state.playing) "Pause" else "Play",
                action(ACTION_TOGGLE, 2),
            )
            .addAction(R.drawable.ic_skip_next, "Next", action(ACTION_NEXT, 3))
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }
}
