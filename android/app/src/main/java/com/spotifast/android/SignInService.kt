package com.spotifast.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app awake while a Spotify sign-in is open in the browser.
 *
 * The browser hands the approval back to a listener on 127.0.0.1 inside
 * this app. Android freezes background apps within seconds, and a frozen
 * app never answers, so the browser would spin forever. A short foreground
 * service keeps the process running until the sign-in ends.
 */
class SignInService : Service() {
    companion object {
        private const val TAG = "SpotifastSignIn"
        private const val CHANNEL = "sign_in"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, SignInService::class.java))
            } catch (error: Throwable) {
                Log.w(TAG, "cannot keep the app awake for sign-in", error)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SignInService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sign-in", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Spotify sign-in is open in the browser"
                setShowBadge(false)
            },
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (error: Throwable) {
            Log.w(TAG, "cannot go foreground", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** A short service gets three minutes; sign-in that takes longer can be retried. */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_spotifast)
        .setContentTitle("Signing in to Spotify")
        .setContentText("Approve in your browser, then tap here to come back.")
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()
}
