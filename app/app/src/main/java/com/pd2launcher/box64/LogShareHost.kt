// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/MainActivity.kt: shareLogs, saveLogs).
package com.pd2launcher.box64

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pd2launcher.box64.ui.LogShareUi
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant

/**
 * Share logs and Save logs for one activity (the start screens' and the game view's Settings): it
 * packs the zip off the main thread ([LogShare]), then hands it on once the activity is on screen
 * (a player who went elsewhere meanwhile finds it on return): Android's share sheet, or its "Save
 * as" screen, after which the zip is copied where the player chose. A second press while one is
 * prepared does nothing; a save stays WORKING until the "Save as" screen answers. Made with the
 * activity (it registers the "Save as" result before the activity starts). A game view that ends
 * while its "Save as" screen is open gets no answer: the document Android made there stays empty.
 */
class LogShareHost(private val activity: ComponentActivity) {
    val ui = LogShareUi(onShare = ::share, onSave = ::save)
    private val main = Handler(Looper.getMainLooper())
    private val saveTo = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), ::saveChosen)
    /** The zip waits for the activity to be on screen again; main thread only. */
    private var waiting: (() -> Unit)? = null

    init {
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> waiting?.let {
                        waiting = null
                        it()
                    }
                    Lifecycle.Event.ON_DESTROY -> waiting = null
                    else -> {}
                }
            },
        )
    }

    /** Share logs: [prepare], then Android's share sheet offers the zip. */
    private fun share() = prepare(LogShareUi.Action.SHARE) { zip ->
        activity.startActivity(
            LogShare.chooser(activity, zip, activity.getString(R.string.share_logs_subject), activity.getString(R.string.share_logs_chooser)),
        )
    }

    /** Save logs: [prepare], then Android's "Save as" screen asks where to put the zip; [saveChosen] copies it there. */
    private fun save() = prepare(LogShareUi.Action.SAVE) { zip -> saveTo.launch(zip.name) }

    private fun prepare(action: LogShareUi.Action, hand: (File) -> Unit) {
        if (ui.state == LogShareUi.State.WORKING) return
        ui.action = action
        ui.state = LogShareUi.State.WORKING
        val app = activity.applicationContext
        Thread({
            val zip = try {
                val about = aboutDevice()
                val appLog = LogShare.appLogFile(app.cacheDir)
                val failed = LogShare.captureAppLog(appLog)
                if (failed != null) Log.w(Native.TAG, "${action.name.lowercase()} logs: the app's log: $failed")
                try {
                    LogShare.write(app.cacheDir, Runtime(app).logs, about, appLog.takeIf { failed == null })
                } finally {
                    appLog.delete()
                }
            } catch (error: Exception) {
                Log.w(Native.TAG, "${action.name.lowercase()} logs: $error")
                null
            }
            main.post {
                if (zip == null) {
                    ui.state = LogShareUi.State.FAILED
                    return@post
                }
                val handOver = {
                    val handed = try {
                        hand(zip)
                        true
                    } catch (error: ActivityNotFoundException) {
                        Log.w(Native.TAG, "${action.name.lowercase()} logs: $error")
                        false
                    }
                    when {
                        !handed -> ui.state = LogShareUi.State.FAILED
                        action == LogShareUi.Action.SHARE -> ui.state = LogShareUi.State.IDLE
                    }
                }
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) handOver() else waiting = handOver
            }
        }, "log-share").start()
    }

    /**
     * The "Save as" screen's answer: null when the player closed it. Copies the zip the last
     * [prepare] left ([LogShare.latest]) to [target] ([LogShare.saveTo]), emptied first: a player
     * who picked an older file to overwrite gets no tail of it. A provider that cannot empty a
     * file ("wt") is written as "w".
     */
    private fun saveChosen(target: Uri?) {
        ui.action = LogShareUi.Action.SAVE
        if (target == null) {
            ui.state = LogShareUi.State.IDLE
            return
        }
        ui.state = LogShareUi.State.WORKING
        val resolver = activity.contentResolver
        val cacheDir = activity.cacheDir
        Thread({
            val saved = try {
                LogShare.saveTo(
                    LogShare.latest(cacheDir),
                    open = {
                        try {
                            resolver.openOutputStream(target, "wt")
                        } catch (_: IllegalArgumentException) {
                            resolver.openOutputStream(target, "w")
                        } catch (_: FileNotFoundException) {
                            resolver.openOutputStream(target, "w")
                        }
                    },
                    delete = { DocumentsContract.deleteDocument(resolver, target) },
                )
                true
            } catch (error: Exception) {
                Log.w(Native.TAG, "save logs: $error")
                false
            }
            main.post { ui.state = if (saved) LogShareUi.State.SAVED else LogShareUi.State.FAILED }
        }, "log-save").start()
    }

    /**
     * `device.txt`: the app, this device, the device check, the game support files, the copy of the
     * game, the session and the Android settings it depends on. Free space is measured now. No
     * folder the player picked and nothing of the game's files but which ones are missing. Off the
     * main thread; the device check runs here when this process has not run it yet (a game view
     * that Android restored).
     */
    private fun aboutDevice(): String {
        val rt = Runtime(activity)
        val device = Setup.device.value ?: DeviceCheck.run()
        val mali = device.mali
        val system = AndroidSystem.state(activity)
        val missing = Onboarding.missing(rt.game)
        return LogShare.about(
            listOf(
                "App" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.APPLICATION_ID}, ${BuildConfig.BUILD_TYPE})",
                "Created" to Instant.now().toString(),
                "Device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
                "Chip" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}",
                "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString(" ")}",
                "Kernel" to Os.uname().release,
                "GPU" to (device.gpu ?: "not found"),
                "Mali" to (mali?.let { "GPU id 0x%08x, kbase uAPI 1.%d, known to the bundled driver: %s".format(it.gpuId, it.uapiMinor, it.knownToDriver) } ?: "none"),
                "Page size" to device.pageSize.toString(),
                "Free space" to "${StatFs(rt.files.path).availableBytes shr 20} MB",
                "Device check" to (
                    DeviceCheck.problems(device).map { "problem: ${activity.getString(it.id, *it.args.toTypedArray())}" } +
                        DeviceCheck.warnings(device).map { "warning: ${activity.getString(it.id, *it.args.toTypedArray())}" }
                    ).joinToString("; ").ifEmpty { "passed" },
                "Data folder" to rt.data.path,
                "Game support files" to Setup.runtime.value.toString(),
                "Game copy" to if (missing.isEmpty()) "complete" else "missing ${missing.joinToString(", ")}",
                "Session" to if (LiteState.sessionActive) "running: ${ProgramWatch.running().joinToString(", ").ifEmpty { "the game view" }}" else "none",
                "Last start" to (LiteState.launcherEnd.value?.name?.lowercase() ?: "no problem"),
                "Settings" to AppSettingsStore.current.value.toString(),
                "Notifications" to if (system.notificationsOff) "off" else "on",
                "Battery optimisation" to if (system.batteryRestricted) "on" else "off",
            ),
        )
    }
}
