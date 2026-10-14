package com.pd2launcher.box64

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.pd2launcher.box64.ui.SystemState

/**
 * The Android settings the session depends on while the app is in the background, as the main app
 * has them: the session's notification ([SessionService]) and battery optimisation; how they
 * stand ([state]) and the system screens that change them.
 */
object AndroidSystem {
    /** The session notification's channel. */
    const val CHANNEL = "session"

    /** Whether Android shows the app's notifications and optimises its battery use. */
    fun state(context: Context): SystemState = SystemState(
        batteryRestricted = !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        notificationsOff = !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
    )

    /**
     * The session notification's channel, made once. On Android 13 the first channel of an app
     * that targets SDK 28 brings Android's own notification prompt over the app's screen: the app
     * makes it once the device check passed, so the prompt comes over Check (or Play).
     */
    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
            Log.i(Native.TAG, "notification channel created")
        }
    }

    /**
     * The app's notification settings: at targetSdk 28 Android asks by itself only when the first
     * channel is made; after that only its settings page turns notifications on.
     */
    fun openNotificationSettings(activity: Activity) {
        open(activity, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
    }

    /** Android's own "Let app always run in background?" for the app, else its battery optimisation list. */
    @SuppressLint("BatteryLife")
    fun requestBatteryExemption(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")))
        } catch (error: ActivityNotFoundException) {
            Log.w(Native.TAG, "no battery optimisation prompt: $error")
            openBatterySettings(activity)
        }
    }

    /** Android's battery optimisation list (Settings → App → Android). */
    fun openBatterySettings(activity: Activity) {
        open(activity, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** A system settings page; some handhelds' firmware lacks one, which changes nothing. */
    private fun open(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Log.w(Native.TAG, "no settings page for ${intent.action}: $error")
        }
    }
}
