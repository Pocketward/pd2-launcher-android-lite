package com.pd2launcher.box64

import java.io.File
import java.io.RandomAccessFile

/**
 * When the official PD2 launcher is ended once its game shows, from one [Look] a second. The
 * launcher only starts Game.exe at its Play; then it stays open behind the game until the player
 * closes it (it does not exit, hide or go to a tray, and its Options have no choice for that),
 * with its window and about half a gigabyte of memory. Lite ends it, as the player would:
 *
 * 1. [Step.ASK]: once the game has run for [GAME_MS] and draws (its first present), the launcher
 *    wrote nothing to storage for [QUIET_MS] (no download, no file copy, no settings write) and
 *    its updater does not run, the game view runs close-launcher.exe ([Session.closeLauncher]).
 *    That asks the launcher to close (WM_CLOSE to its window, as Windows ends a program) only when
 *    a window of Game.exe shows; its exit code comes back through [asked].
 * 2. [Step.KILL]: when the launcher's process is still there [CLOSE_MS] after the question (or
 *    [CLOSE_MAX_MS] while it still writes), the game view ends it.
 *
 * Before the game's window shows the helper does nothing and the question comes again after
 * [RETRY_MS]; after [MAX_TRIES] helpers that did not ask, Lite gives up and the launcher stays.
 * The session's end does not change: it follows the game. Times in ms on one clock.
 */
class LauncherExit {
    /**
     * One look: whether the game runs and has drawn ([gameDraws], null when unknown), the
     * launcher's pid (null: none), whether its updater runs, and the bytes the launcher wrote to
     * storage so far (null when unknown).
     */
    data class Look(
        val now: Long,
        val gameRuns: Boolean,
        val gameDraws: Boolean?,
        val launcherPid: Int?,
        val updaterRuns: Boolean,
        val storageWrites: Long?,
    )

    enum class Step { ASK, KILL }

    private enum class State { WAIT, ASKING, CLOSING, DONE }

    private var state = State.WAIT
    private var gameSince: Long? = null
    private var writes: Long? = null
    private var wroteAt: Long? = null
    private var retryAt = 0L
    private var askedAt = 0L
    private var tries = 0
    private var result: Int? = null

    /** The launcher was asked and closed by itself, or it was ended, or Lite gave up: nothing more to do. */
    val done get() = state == State.DONE

    /** How long the launcher took to close by itself after the question (null: it did not, yet). */
    var closedIn: Long? = null
        private set

    /** The helper's exit code (0 asked, 2 no game window yet, 3 no launcher window, else it failed). */
    @Synchronized fun asked(exitCode: Int) {
        result = exitCode
    }

    /** The next look; what to do now, or null. */
    @Synchronized fun sample(look: Look): Step? {
        val now = look.now
        gameSince = if (look.gameRuns) gameSince ?: now else null
        if (look.storageWrites != writes) {
            writes = look.storageWrites
            wroteAt = now
        }
        val pid = look.launcherPid
        when (state) {
            State.DONE -> return null
            State.WAIT -> {
                if (pid == null || look.updaterRuns || look.gameDraws == false || now < retryAt) return null
                val since = gameSince ?: return null
                if (now - since < GAME_MS || now - (wroteAt ?: since) < QUIET_MS) return null
                state = State.ASKING
                askedAt = now
                result = null
                tries++
                return Step.ASK
            }
            State.ASKING -> {
                val code = result ?: run {
                    // A helper that never ends counts as one that failed.
                    if (now - askedAt < ASK_MS) return null
                    -1
                }
                result = null
                when (code) {
                    ASKED, NO_LAUNCHER_WINDOW -> {
                        state = State.CLOSING
                        askedAt = now
                    }
                    else -> {
                        state = if (tries >= MAX_TRIES) State.DONE else State.WAIT
                        retryAt = now + RETRY_MS
                    }
                }
                return null
            }
            State.CLOSING -> {
                if (pid == null) {
                    state = State.DONE
                    closedIn = now - askedAt
                    return null
                }
                val waited = now - askedAt
                val writing = now - (wroteAt ?: askedAt) < QUIET_MS
                if (waited < CLOSE_MS || (writing && waited < CLOSE_MAX_MS)) return null
                state = State.DONE
                return Step.KILL
            }
        }
    }

    companion object {
        /** The game ran this long before the launcher is asked. */
        const val GAME_MS = 2_000L

        /** The launcher wrote nothing to storage for this long. */
        const val QUIET_MS = 3_000L

        /** A question again this long after the game's window did not show yet (or the helper failed). */
        const val RETRY_MS = 3_000L

        /** The helper ended within this time, or it counts as failed. */
        const val ASK_MS = 15_000L

        /** Helpers that did not ask (no game window yet, or the helper failed) before Lite gives up. */
        const val MAX_TRIES = 10

        /** The launcher's process is ended when it is still there this long after the question... */
        const val CLOSE_MS = 10_000L

        /** ...or this long while it still writes (its settings, as it closes). */
        const val CLOSE_MAX_MS = 20_000L

        /** The bytes process [pid] wrote to storage so far (its /proc io's write_bytes), or null. */
        fun storageWrites(pid: Int): Long? = try {
            File("/proc/$pid/io").readLines().firstOrNull { it.startsWith("write_bytes:") }
                ?.substringAfter(':')?.trim()?.toLongOrNull()
        } catch (_: Exception) {
            null
        }

        /**
         * The game's presents so far: Mesa's present counter (userland Mesa patch 0002, a 32-bit
         * count at the file's start), or null. The official launcher draws without Mesa (WPF in
         * software), so only the game counts.
         */
        fun presents(counter: File): Long? = try {
            RandomAccessFile(counter, "r").use { f ->
                val b = ByteArray(4)
                f.readFully(b)
                (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                    ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
            }
        } catch (_: Exception) {
            null
        }

        /** close-launcher.exe's exit codes (helpers/close-launcher.c). */
        const val ASKED = 0
        const val NO_GAME_WINDOW = 2
        const val NO_LAUNCHER_WINDOW = 3
    }
}
