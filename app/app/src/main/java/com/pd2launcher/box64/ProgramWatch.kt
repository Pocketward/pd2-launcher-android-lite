package com.pd2launcher.box64

import android.system.Os
import java.io.File

/**
 * What a session runs, followed once a second ([sample]) from the names of the app's processes:
 * the official PD2 launcher ([LAUNCHER], and [UPDATER], its self-update) and the game ([GAME]),
 * which the launcher's own Play starts in the same session. The game view shows the launcher's
 * rows until the game runs ([Sample.gameRuns]). The session is over ([Sample.ended]):
 * - once the game has run: when it has not run for [GRACE_MS]. The launcher stays open behind its
 *   game (the game view ends it once the game shows: [LauncherExit]); if it is still there when
 *   the game ends, Play shows instead;
 * - before that: when none of them has run for [GRACE_MS] after one was seen (the launcher closed
 *   without playing); when none was ever seen, once the program the session started has ended
 *   ([sample]'s startEnded) or [START_MS] passed.
 * It failed ([Sample.launcherFailed]: Play says so) when the game never ran and the last of them
 * was seen within [EARLY_MS] of the start, or none was seen.
 */
class ProgramWatch(private val startedAt: Long) {
    /** One look: whether the game runs now, whether the session is over, and if so whether it failed. */
    data class Sample(val gameRuns: Boolean, val ended: Boolean, val launcherFailed: Boolean)

    private var seenAt: Long? = null
    private var gameAt: Long? = null

    /** [names]: the app's process names now ([running]); times in ms on one clock. */
    fun sample(now: Long, names: Collection<String>, startEnded: Boolean): Sample {
        val gameRuns = names.any { it.equals(comm(GAME), ignoreCase = true) }
        if (names.any { it.lowercase() in WATCHED }) seenAt = now
        if (gameRuns) gameAt = now
        val last = seenAt
        val game = gameAt
        val ended = when {
            game != null -> now - game >= GRACE_MS
            last != null -> now - last >= GRACE_MS
            else -> startEnded || now - startedAt >= START_MS
        }
        // When it ended: the last look that saw one of them.
        val failed = ended && game == null && (last == null || last - startedAt < EARLY_MS)
        return Sample(gameRuns, ended, failed)
    }

    companion object {
        const val LAUNCHER = "PD2Launcher.exe"
        const val GAME = "Game.exe"
        const val UPDATER = "UpdateUtility.exe"

        /** Nothing of the launcher or the game for this long: the session is over. */
        const val GRACE_MS = 5_000L

        /** A session that ends this soon without the game ran into a problem (the main app's EarlyGameEnd.WINDOW_MS). */
        const val EARLY_MS = 10_000L

        /** None of them started this long after the session started: it is over. */
        const val START_MS = 60_000L

        /** Linux keeps a process name to 15 bytes (Wine sets it to the program's file name). */
        fun comm(name: String): String = name.take(15)

        /** In lower case: Wine names a process after its file, whose case the copy keeps. */
        private val WATCHED = setOf(comm(LAUNCHER), comm(GAME), comm(UPDATER)).map { it.lowercase() }.toSet()

        /** The names of the app user's processes other than the app's own (another app's are never counted). */
        fun running(): List<String> = processes().map { it.second }

        /** The pids of the app user's processes named [name] (a Windows program's file name). */
        fun pids(name: String): List<Int> =
            processes().filter { it.second.equals(comm(name), ignoreCase = true) }.mapNotNull { it.first.name.toIntOrNull() }

        /**
         * The game's command line ([GAME], the first of the app user's), its words split at NULs and
         * spaces (Wine writes the Windows command line there); null while it does not run.
         */
        fun gameArguments(): List<String>? {
            val game = processes().firstOrNull { it.second.equals(comm(GAME), ignoreCase = true) }?.first ?: return null
            return try {
                File(game, "cmdline").readText().split('\u0000', ' ').filter { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

        /** The app user's processes other than the app's own: each one's /proc folder and name. */
        private fun processes(): List<Pair<File, String>> {
            val me = Os.getpid()
            val uid = Os.getuid()
            return File("/proc").listFiles().orEmpty().mapNotNull { p ->
                val pid = p.name.toIntOrNull() ?: return@mapNotNull null
                if (pid == me) return@mapNotNull null
                try {
                    if (Os.stat(p.path).st_uid != uid) return@mapNotNull null
                    p to File(p, "comm").readText().trim()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}

/**
 * How long the game ran in a session: the times between the first and the last look that saw it,
 * each run counted on its own (the launcher can start it more than once). Times in ms on one clock.
 */
class PlayTime {
    private var since: Long? = null
    private var done = 0L

    @Synchronized fun sample(now: Long, gameRuns: Boolean) {
        val start = since
        if (gameRuns && start == null) since = now
        if (!gameRuns && start != null) {
            done += now - start
            since = null
        }
    }

    @Synchronized fun total(now: Long): Long = done + (since?.let { now - it } ?: 0L)
}
