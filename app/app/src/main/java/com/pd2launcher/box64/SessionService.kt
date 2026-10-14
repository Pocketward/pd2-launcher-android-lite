package com.pd2launcher.box64

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * The foreground service of a game session, as the main app's session service: while a game view
 * ([GameActivity]) runs the official PD2 launcher or the game, the app (and with it the session's
 * processes) stays in the state that Android does not freeze or stop to free memory, and the
 * player can use another app meanwhile. Its notification says what runs; a tap goes back to it,
 * and Stop ends the session as the in-game menu's Stop does ([stopSession]). The game view starts
 * the service when it opens and stops it when it closes ([start], [stop]), so nothing runs after
 * Stop or after the game ends. Removing the app from Recents ends the session too.
 *
 * The game view starts it while it is in front (a plain start: at targetSdk 28 a service that may
 * be stopped before it is in the foreground must not be started as a foreground service), and it
 * goes to the foreground at once.
 */
class SessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession("Stop in the notification")
            return START_NOT_STICKY
        }
        AndroidSystem.createChannel(this)
        startForeground(NOTIFICATION_ID, notification(this, intent?.getBooleanExtra(EXTRA_LAUNCHER, true) ?: true))
        if (!running) Log.i(Native.TAG, "session service: in the foreground with its notification")
        running = true
        return START_NOT_STICKY
    }

    /** The app was removed from Recents: the session ends with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSession("the app was removed from Recents")
    }

    override fun onDestroy() {
        running = false
        // The notification goes with the service, also one an update posted just before.
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        Log.i(Native.TAG, "session service stopped")
        super.onDestroy()
    }

    /**
     * Ends the session: the game view closes (its end stops the session's processes and this
     * service); without one, every other process of the app's user ends here.
     */
    private fun stopSession(why: String) {
        Log.i(Native.TAG, "session service: $why: stopping the session")
        val view = stopView
        if (view != null) {
            main.post(view)
        } else {
            Session.killAll()
        }
        stopSelf()
    }

    companion object {
        private const val ACTION_STOP = "com.pd2launcher.box64.action.STOP_SESSION"
        private const val EXTRA_LAUNCHER = "launcher"
        private const val NOTIFICATION_ID = 1

        /** Whether the service is in the foreground now; changed on the main thread only. */
        @Volatile private var running = false
        private val main = Handler(Looper.getMainLooper())

        /** The open game view's Stop (on the main thread); null while none is open. */
        @Volatile var stopView: Runnable? = null

        /**
         * Starts the service for the game view, which is in front: [launcher], the official
         * launcher runs and the game does not (yet).
         */
        fun start(context: Context, launcher: Boolean) {
            context.startService(Intent(context, SessionService::class.java).putExtra(EXTRA_LAUNCHER, launcher))
        }

        /** The game view closed (on the main thread): the notification goes with the service; no update shows it again. */
        fun stop(context: Context) {
            running = false
            context.stopService(Intent(context, SessionService::class.java))
        }

        /**
         * The notification says what runs now: the official launcher ([launcher]) or the game. On
         * the main thread, after the game view's [stop] or the service's end: then nothing.
         */
        fun update(context: Context, launcher: Boolean) {
            val app = context.applicationContext
            main.post {
                if (running) app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(app, launcher))
            }
        }

        /**
         * The session's notification: what runs, a tap back to the game view (singleTask: the
         * open one comes to the front), and Stop.
         */
        private fun notification(context: Context, launcher: Boolean): Notification {
            val back = PendingIntent.getActivity(
                context, 0,
                Intent(context, GameActivity::class.java).putExtra(GameActivity.EXTRA_RETURN, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                context, 1, Intent(context, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, AndroidSystem.CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_title))
                .setContentText(context.getString(if (launcher) R.string.notification_text_launcher else R.string.notification_text_game))
                .setContentIntent(back)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_notification), context.getString(R.string.notification_stop), stop,
                    ).build(),
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
