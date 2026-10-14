// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/FrameCopy.kt).
package com.pd2launcher.box64

/**
 * Frames per second from a growing frame count sampled now and then: the frames counted over
 * the last full window of at least [windowNanos], so a rate is never from a shorter stretch.
 * A count that goes back (a new copy) starts over.
 */
class FrameRate(private val windowNanos: Long = 1_000_000_000L) {
    private var startNanos = 0L
    private var startFrames = -1L

    /** The rate of the last full window, or null before the first one. */
    var fps: Float? = null
        private set

    fun sample(nowNanos: Long, frames: Long): Float? {
        if (startFrames < 0 || frames < startFrames) {
            startNanos = nowNanos
            startFrames = frames
            fps = null
        } else if (nowNanos - startNanos >= windowNanos) {
            fps = (frames - startFrames) * 1e9f / (nowNanos - startNanos)
            startNanos = nowNanos
            startFrames = frames
        }
        return fps
    }
}

/**
 * The frame copy's counts since it started: the game's [presents] (null without the present
 * counter; a 32-bit count, so it wraps), the presents a display frame showed ([shownPresents]),
 * and the different pictures the copy found ([changed]). Lite's copy posts each different
 * picture once ([Native.copyCounts]), so its posted frames are both [shownPresents] and [changed].
 */
data class FrameCounts(val presents: Long?, val shownPresents: Long, val changed: Long) {
    companion object {
        /** From [Native.copyCounts]: [frames posted, the game's presents or -1]. */
        fun of(counts: LongArray): FrameCounts =
            FrameCounts(counts.getOrNull(1)?.takeIf { it >= 0 }, counts.getOrElse(0) { 0 }, counts.getOrElse(0) { 0 })
    }
}

/**
 * The fps counter's reading: [fps] the game's frames per second, and [onScreen] the ones the
 * screen showed, only when that is noticeably fewer (the display's refresh rate is below the
 * game's), else null.
 */
data class FpsReading(val fps: Float, val onScreen: Float?)

/**
 * The fps counter from the copy's counts, sampled now and then: the game's presents per second;
 * where nothing comes through the present counter (no counter, or a picture drawn outside Mesa's
 * presentation) the different pictures per second instead, which are then the game's frames.
 */
class GameFrameRate(windowNanos: Long = 1_000_000_000L) {
    private val presents = FrameRate(windowNanos)
    private val shown = FrameRate(windowNanos)
    private val changed = FrameRate(windowNanos)

    fun sample(nowNanos: Long, counts: FrameCounts?): FpsReading? {
        if (counts == null) return null
        val game = counts.presents?.let { presents.sample(nowNanos, it) }
        val screen = shown.sample(nowNanos, counts.shownPresents)
        val pictures = changed.sample(nowNanos, counts.changed)
        return when {
            game != null && game > 0f -> FpsReading(game, screen?.takeIf { game - it >= ON_SCREEN_GAP })
            pictures != null -> FpsReading(pictures, null)
            else -> null
        }
    }

    companion object {
        /** The screen shows "noticeably fewer" from this many frames a second below the game's. */
        const val ON_SCREEN_GAP = 3f
    }
}
