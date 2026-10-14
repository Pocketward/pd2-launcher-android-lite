package com.pd2launcher.box64

import android.os.SystemClock
import android.system.Os
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the app is doing now, for the game files' provider ([com.pd2launcher.box64.files.GameFilesProvider]),
 * which runs in the same process: whether a game view is open or the game's processes still run
 * (the game or the official launcher, or the app preparing them), and whether an import copies a
 * Diablo II folder (one at a time).
 */
object LiteState {
    private val sessions = AtomicInteger()
    private val importer = AtomicBoolean()
    @Volatile private var aliveAt = 0L
    @Volatile private var alive = false

    /** A game view opened (from its creation, before the app writes the game's settings files). */
    fun sessionStarted() {
        sessions.incrementAndGet()
    }

    /** A game view closed (its processes were sent the kill). */
    fun sessionEnded() {
        sessions.decrementAndGet()
    }

    /**
     * A game view is open, or another process of the app's user still runs: the game's processes
     * outlive a stop that was just sent, or an app process that died without its view closing.
     */
    val sessionActive: Boolean get() = sessions.get() > 0 || childrenAlive()

    /** Claims the import; false while another one runs. */
    fun startImport(): Boolean = importer.compareAndSet(false, true)

    fun endImport() {
        importer.set(false)
    }

    val importing: Boolean get() = importer.get()

    /** How the last Start launcher ended, when Play says so until the next one. */
    enum class LauncherEnd {
        /** Before the official PD2 launcher did its work ([ProgramWatch.Sample.launcherFailed], or the start failed). */
        FAILED,

        /** The player closed it after it stopped responding ([LauncherStall]). */
        STALLED,
    }

    /** null: the last one ended as it should (or none ran). */
    val launcherEnd = MutableStateFlow<LauncherEnd?>(null)

    /** Whether a process of the app's user other than this one runs; looked at most once a second. */
    private fun childrenAlive(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - aliveAt < 1000) return alive
        val me = Os.getpid()
        val uid = Os.getuid()
        alive = File("/proc").listFiles()?.any { p ->
            val pid = p.name.toIntOrNull() ?: return@any false
            pid != me && try {
                Os.stat(p.path).st_uid == uid
            } catch (_: Exception) {
                false
            }
        } == true
        aliveAt = now
        return alive
    }
}
