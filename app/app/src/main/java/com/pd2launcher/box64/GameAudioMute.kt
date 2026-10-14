// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/GameAudio.kt, GameAudioMute).
package com.pd2launcher.box64

/**
 * When the game's sound is silent: while the game view is not on screen (the player uses another
 * app; the session runs on, [SessionService]), unless the game was started with its sound in the
 * background (`-sndbkg`, the official launcher's Options), as the main app follows its Launcher's
 * "Sound in background". The track is muted, never paused, so the sound server's pipe keeps draining.
 */
object GameAudioMute {
    /** The game's argument for its sound in the background. */
    const val SOUND_IN_BACKGROUND = "-sndbkg"

    fun muted(inBackground: Boolean, soundInBackground: Boolean): Boolean = inBackground && !soundInBackground

    /** Whether the game's command line [arguments] keep its sound in the background; false while it does not run. */
    fun soundInBackground(arguments: List<String>?): Boolean =
        arguments?.any { it.equals(SOUND_IN_BACKGROUND, ignoreCase = true) } == true
}
