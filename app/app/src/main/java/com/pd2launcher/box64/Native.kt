package com.pd2launcher.box64

import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap

/** libpd2lite.so: process spawning, the framebuffer memfd and the frame copy (pd2lite.c). */
object Native {
    init {
        System.loadLibrary("pd2lite")
    }

    /** Child pid, or -errno. [fd3] becomes the child's descriptor 3 (or -1 for none). */
    @JvmStatic external fun spawn(
        path: String, argv: Array<String>, envp: Array<String>, stdinFd: Int, outputFd: Int, fd3: Int,
    ): Int

    /** [app end, child end] of a connected socket pair, or null. */
    @JvmStatic external fun socketPair(): IntArray?

    /** A growable memfd for Xvfb's framebuffer, or -errno. */
    @JvmStatic external fun memfd(name: String): Int

    @JvmStatic external fun killGroup(pgid: Int, sig: Int): Int

    /** Starts copying the framebuffer in [fd] into [surface]; 0 on failure. */
    @JvmStatic external fun startCopy(surface: Surface, fd: Int, counterPath: String?): Long

    /** [pictures posted, the game's presents or -1, display frames that showed a new present]. */
    @JvmStatic external fun copyCounts(handle: Long): LongArray

    @JvmStatic external fun stopCopy(handle: Long)

    /** Processes started and not yet seen to end: pid to name. */
    val running = ConcurrentHashMap<Int, String>()

    @Volatile var exitListener: ((pid: Int, name: String, exited: Boolean, value: Int) -> Unit)? = null

    /** Called by the reaper thread of each child. */
    @JvmStatic
    fun onExit(pid: Int, exited: Boolean, value: Int) {
        val name = running.remove(pid) ?: "?"
        Log.i(TAG, "$name ($pid) ${if (exited) "exited $value" else "killed by signal $value"}")
        exitListener?.invoke(pid, name, exited, value)
    }

    const val TAG = "pd2-lite"
}
