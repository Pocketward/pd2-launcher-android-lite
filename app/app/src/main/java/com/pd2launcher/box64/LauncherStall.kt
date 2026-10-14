package com.pd2launcher.box64

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Whether the official PD2 launcher stopped responding while its game does not run, from one
 * [Look] a second. Under the x86 emulation the launcher (a .NET program) can stop for good, in one
 * of four ways ([Why]). The game view then asks the player whether to close it (the rail's
 * question), so that a stopped launcher never leaves the player before a screen that does nothing.
 *
 * - [Why.NO_WINDOW]: the X screen stayed black [NO_WINDOW_MS] after the launcher's process started
 *   (a normal start shows its window within about 20 s).
 * - [Why.BLOCKED]: after its window showed, the launcher's main thread (its window's) waited on a
 *   lock for [BLOCKED_MS] without a break: its window takes no input.
 * - [Why.FROZEN]: after its window showed, the X screen's picture stayed the same for [FROZEN_MS]
 *   while the launcher read less than [FROZEN_READ_BYTES] and used at least [FROZEN_SHARE] cores:
 *   a thread runs in a loop that asks nothing of Wine or the X server. A working launcher's window
 *   changes many times a second (its animation), and it reads Wine's and the X server's answers
 *   all the time; a launcher that only waits uses almost no CPU.
 * - [Why.BUSY]: from [BUSY_AFTER_MS] after its window showed, the launcher used at least
 *   [BUSY_SHARE] cores in every [STEP_MS] for [BUSY_MS] without a break (one alone uses less than
 *   half of one; its start uses more, but not for that long; the two .NET pool threads that stop
 *   it use one each), while it wrote less than [BUSY_WRITE_BYTES] to storage and read less than
 *   [BUSY_READ_BYTES] or only small answers (Wine's server's and the X server's, at most
 *   [SMALL_READ_BYTES] a read on average): no download, no file check, no file copy (an update it
 *   runs reads large blocks and writes files). Its drawing is not counted: the window's pictures
 *   go to the X screen through a socket, megabytes a second while it shows.
 *
 * A look at another process (a new pid) starts the rules over. Times in ms on one clock; CPU time
 * in clock ticks ([ticksPerSecond] a second).
 */
class LauncherStall(private val ticksPerSecond: Long = 100) {
    /**
     * One look at the launcher's process [pid]: whether the X screen is black, its CPU time so far,
     * the bytes it read (from files, sockets and pipes) in how many reads, and the bytes it wrote to
     * storage so far (null when unknown), whether its main thread waits on a lock, and a hash of the
     * X screen's picture ([picture], null when unknown).
     */
    data class Look(
        val now: Long,
        val pid: Int,
        val blackScreen: Boolean,
        val cpuTicks: Long,
        val readBytes: Long?,
        val readCalls: Long?,
        val storageWrites: Long?,
        val mainBlocked: Boolean,
        val picture: Long? = null,
    )

    enum class Why { NO_WINDOW, BLOCKED, FROZEN, BUSY }

    private var pid: Int? = null
    private var firstAt: Long? = null
    private var shownAt: Long? = null
    private var blockedSince: Long? = null
    /** The first look of the run of looks with the same picture; null while the picture is unknown. */
    private var stillFrom: Look? = null
    /** The look a run of busy steps started at; null while the last step was not busy. */
    private var busyFrom: Look? = null
    private val looks = ArrayDeque<Look>()

    /** What the last [Why.FROZEN] or [Why.BUSY] saw, for the log: its time, CPU and reads (and writes). */
    var facts: String? = null
        private set

    /** Starts over at [now] (the player chose to wait): each rule needs its whole time again. */
    fun reset(now: Long) {
        firstAt = now
        blockedSince = null
        stillFrom = null
        busyFrom = null
        looks.clear()
    }

    /** The next look ([look] null: no launcher process, or its game runs); a reason once it stopped responding. */
    fun sample(look: Look?): Why? {
        if (look == null) {
            pid = null
            firstAt = null
            blockedSince = null
            stillFrom = null
            busyFrom = null
            looks.clear()
            return null
        }
        val now = look.now
        if (look.pid != pid) {
            pid = look.pid
            firstAt = now
            blockedSince = null
            stillFrom = null
            busyFrom = null
            looks.clear()
        }
        val first = firstAt ?: now.also { firstAt = it }
        if (!look.blackScreen && shownAt == null) shownAt = now
        val shown = shownAt ?: return if (now - first >= NO_WINDOW_MS) Why.NO_WINDOW else null
        blockedSince = if (look.mainBlocked) blockedSince ?: now else null
        if (blockedSince?.let { now - it >= BLOCKED_MS } == true) return Why.BLOCKED
        if (frozen(look)) return Why.FROZEN
        if (now - shown < BUSY_AFTER_MS) return null
        looks.addLast(look)
        // The step: from the newest look at least STEP_MS old.
        while (looks.size > 1 && now - looks[1].now >= STEP_MS) looks.removeFirst()
        val step = looks.first()
        val span = now - step.now
        if (span < STEP_MS) return null
        val stepBusy = (look.cpuTicks - step.cpuTicks) * 1000 / ticksPerSecond >= span * BUSY_SHARE
        if (!stepBusy) {
            busyFrom = null
            return null
        }
        val from = busyFrom ?: step.also { busyFrom = it }
        val busyMs = now - from.now
        if (busyMs < BUSY_MS) return null
        val read = look.readBytes?.let { last -> from.readBytes?.let { last - it } } ?: return null
        val calls = look.readCalls?.let { last -> from.readCalls?.let { last - it } }
        val written = look.storageWrites?.let { last -> from.storageWrites?.let { last - it } } ?: return null
        val smallReads = calls != null && calls > 0 && read / calls <= SMALL_READ_BYTES
        if (written >= BUSY_WRITE_BYTES || (read >= BUSY_READ_BYTES && !smallReads)) return null
        val cpuMs = (look.cpuTicks - from.cpuTicks) * 1000 / ticksPerSecond
        facts = "${busyMs / 1000} s at ${cpuMs * 100 / busyMs}% of a core, ${read shr 10} KiB read in ${calls ?: "?"} reads, ${written shr 10} KiB written"
        return Why.BUSY
    }

    /** [Why.FROZEN]'s rule on the window's look [look]: the picture the same since [stillFrom]. */
    private fun frozen(look: Look): Boolean {
        val picture = look.picture
        val from = stillFrom?.takeIf { picture != null && it.picture == picture }
        if (from == null) {
            stillFrom = look.takeIf { picture != null }
            return false
        }
        val stillMs = look.now - from.now
        if (stillMs < FROZEN_MS) return false
        val read = look.readBytes?.let { last -> from.readBytes?.let { last - it } } ?: return false
        val cpuMs = (look.cpuTicks - from.cpuTicks) * 1000 / ticksPerSecond
        if (read >= FROZEN_READ_BYTES || cpuMs < stillMs * FROZEN_SHARE) return false
        facts = "${stillMs / 1000} s without a new picture at ${cpuMs * 100 / stillMs}% of a core, ${read shr 10} KiB read"
        return true
    }

    companion object {
        const val NO_WINDOW_MS = 60_000L
        const val BLOCKED_MS = 20_000L
        const val FROZEN_MS = 30_000L
        const val FROZEN_SHARE = 0.5
        /** A working launcher reads about 5 MB in 30 s (Wine's and the X server's answers). */
        const val FROZEN_READ_BYTES = 512L shl 10
        const val BUSY_MS = 45_000L
        const val BUSY_AFTER_MS = 30_000L
        const val STEP_MS = 5_000L
        const val BUSY_SHARE = 1.0
        /** A launcher alone reads about 8 MB in 45 s (Wine's and the X server's answers). */
        const val BUSY_READ_BYTES = 32L shl 20
        const val SMALL_READ_BYTES = 512L
        const val BUSY_WRITE_BYTES = 1L shl 20

        /**
         * A look at the launcher's process [pid] now: its CPU time (`stat`), what it read and wrote
         * to storage (`io`'s rchar, syscr and write_bytes, null when Android does not let the app
         * read it) and whether its main thread waits in the kernel's futex wait (Wine's wait for a
         * lock: `wchan`); [blackScreen] and [picture] are the X screen's.
         */
        fun look(now: Long, pid: Int, blackScreen: Boolean, picture: Long?): Look? {
            val stat = try { File("/proc/$pid/stat").readText() } catch (_: Exception) { return null }
            // After the name in parentheses: state is field 3, utime 14 and stime 15.
            val fields = stat.substringAfterLast(") ").split(' ')
            val cpu = (fields.getOrNull(11)?.toLongOrNull() ?: return null) + (fields.getOrNull(12)?.toLongOrNull() ?: return null)
            val io = try {
                File("/proc/$pid/io").readLines().associate { it.substringBefore(':') to it.substringAfter(':').trim().toLongOrNull() }
            } catch (_: Exception) {
                emptyMap()
            }
            val blocked = try {
                File("/proc/$pid/task/$pid/wchan").readText().contains("futex")
            } catch (_: Exception) {
                false
            }
            return Look(now, pid, blackScreen, cpu, io["rchar"], io["syscr"], io["write_bytes"], blocked, picture)
        }

        /** Clock ticks a second, for [Look.cpuTicks]. */
        fun ticksPerSecond(): Long = try { Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100 } catch (_: Exception) { 100 }

        /** Whether the X screen shows nothing yet: a grid of its pixels all black. */
        fun black(screen: ScreenPixels): Boolean {
            val h = screen.header
            for (gy in 0 until GRID) {
                for (gx in 0 until GRID) {
                    val x = (h.width - 1) * gx / (GRID - 1)
                    val y = (h.height - 1) * gy / (GRID - 1)
                    if (screen.pixel(x, y) and 0xFFFFFF != 0) return false
                }
            }
            return true
        }

        /**
         * A hash of the X screen's picture: every 4th line in full, as the frame copy's (pd2lite.c),
         * which sees the launcher's animation change the picture many times a second.
         */
        fun picture(screen: ScreenPixels): Long {
            val h = screen.header
            var hash = -3750763034362895579L // FNV-1a's offset basis
            for (y in 0 until h.height step 4) {
                val row = y * h.bytesPerLine
                var a = 0L
                for (x in 0 until h.width / 2) a = a * 31 + screen.pixels.getLong(row + x * 8)
                hash = (hash xor a) * 1099511628211L
            }
            return hash
        }

        private const val GRID = 16
    }
}
