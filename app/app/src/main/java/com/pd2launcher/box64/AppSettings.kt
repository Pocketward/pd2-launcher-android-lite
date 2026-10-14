package com.pd2launcher.box64

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's settings, in the app's `app_settings` preferences: the game's (applied at the next
 * Start launcher) and the app's own (at once). The official PD2 launcher's Options set the
 * renderer, Skip to Battle.net and the game's sound in the background.
 */
data class AppSettings(
    /** The X screen's lines the player picked; null: Automatic, the display's full size ([GameResolution]). */
    val gameResolution: Int? = null,
    /** D2GL's graphics profile the player picked; null: Automatic ([GraphicsProfiles]). */
    val graphicsProfile: GraphicsProfile? = null,
    /** On a screen faster than 60 Hz the game view is limited to 60 frames a second ([FrameRateVote]). */
    val limitFrameRate: Boolean = false,
    /** The screen stays on while the game shows. */
    val keepScreenOn: Boolean = true,
    /** A small counter of the game's frames per second shows over the game. */
    val showFps: Boolean = false,
) {
    /** The preference values, by key (null: none); [fromPreferences] reads them back. */
    fun toPreferences(): Map<String, Any?> = mapOf(
        GAME_RESOLUTION to gameResolution,
        GRAPHICS_PROFILE to graphicsProfile?.name,
        LIMIT_FRAME_RATE to limitFrameRate,
        KEEP_SCREEN_ON to keepScreenOn,
        SHOW_FPS to showFps,
    )

    companion object {
        const val GAME_RESOLUTION = "game_resolution"
        const val GRAPHICS_PROFILE = "graphics_profile"
        const val LIMIT_FRAME_RATE = "limit_frame_rate"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val SHOW_FPS = "show_fps"

        /** A missing or mistyped value is the default, one by one. */
        fun fromPreferences(values: Map<String, *>): AppSettings {
            val defaults = AppSettings()
            return AppSettings(
                gameResolution = (values[GAME_RESOLUTION] as? Int)?.takeIf { it >= GameResolution.MIN_LINES },
                graphicsProfile = GraphicsProfile.entries.firstOrNull { it.name == values[GRAPHICS_PROFILE] },
                limitFrameRate = values[LIMIT_FRAME_RATE] as? Boolean ?: defaults.limitFrameRate,
                keepScreenOn = values[KEEP_SCREEN_ON] as? Boolean ?: defaults.keepScreenOn,
                showFps = values[SHOW_FPS] as? Boolean ?: defaults.showFps,
            )
        }
    }
}

/** [AppSettings] in the app's preferences, and as a flow the screens and the game view follow ([current]). */
object AppSettingsStore {
    private const val PREFERENCES = "app_settings"
    private val _current = MutableStateFlow(AppSettings())
    private var loaded = false

    val current: StateFlow<AppSettings> = _current.asStateFlow()

    /** Reads the stored settings once per process; later calls return what is current. */
    fun load(context: Context): AppSettings {
        if (!loaded) {
            loaded = true
            _current.value = AppSettings.fromPreferences(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all)
        }
        return _current.value
    }

    fun save(context: Context, settings: AppSettings) {
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
        for ((key, value) in settings.toPreferences()) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                null -> editor.remove(key)
            }
        }
        editor.apply()
        loaded = true
        _current.value = settings
    }
}

/**
 * The player's own D2GL frame limit values while Limit to 60 fps holds them in the game copy's
 * `d2gl.json` ([D2glConfig.limitFrames]); they go back when the limit is switched off. A new
 * import forgets them.
 */
object SavedFrameLimit {
    private const val PREFERENCES = "game_state"
    private const val KEY = "d2gl_fps"

    fun get(context: Context): D2glConfig.FpsValues? =
        D2glConfig.FpsValues.decode(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null))

    fun set(context: Context, values: D2glConfig.FpsValues?) {
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
        if (values == null) editor.remove(KEY) else editor.putString(KEY, values.encode())
        editor.commit()
    }
}

/**
 * The graphics profile last written into the game copy's `d2gl.json` (in the app's `game_state`
 * preferences): a Play writes the profile only when it is another one; a new import forgets it.
 */
object AppliedProfile {
    private const val PREFERENCES = "game_state"
    private const val KEY = "d2gl_profile"

    fun get(context: Context): GraphicsProfile? =
        GraphicsProfile.entries.firstOrNull { it.name == context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null) }

    fun set(context: Context, profile: GraphicsProfile?) {
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
        if (profile == null) editor.remove(KEY) else editor.putString(KEY, profile.name)
        editor.commit()
    }
}

/**
 * What the app knows of its copy's origin (in `game_state`): the folder it was imported from (its
 * path on shared storage, for Storage and the one-time notice), whether a game session has been
 * played since, and whether that notice was answered. A new import starts it over.
 */
object ImportRecord {
    private const val PREFERENCES = "game_state"
    private const val FOLDER = "import_folder"
    private const val PLAYED = "game_played"
    private const val ANSWERED = "notice_answered"

    /** A game session counts as played when it ran this long (one that ends at once, a crash at start, does not). */
    const val PLAYED_MS = 30_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun imported(context: Context, folder: String?) {
        prefs(context).edit().apply { if (folder == null) remove(FOLDER) else putString(FOLDER, folder) }
            .putBoolean(PLAYED, false).putBoolean(ANSWERED, false).commit()
    }

    fun folder(context: Context): String? = prefs(context).getString(FOLDER, null)

    /** A session of [ranMs] ended: the first one long enough counts. */
    fun sessionEnded(context: Context, ranMs: Long) {
        if (ranMs >= PLAYED_MS && !prefs(context).getBoolean(PLAYED, false)) prefs(context).edit().putBoolean(PLAYED, true).apply()
    }

    /** The one-time notice is due: a session was played and the notice not answered yet. */
    fun noticeDue(context: Context): Boolean = prefs(context).getBoolean(PLAYED, false) && !prefs(context).getBoolean(ANSWERED, false)

    fun answered(context: Context) {
        prefs(context).edit().putBoolean(ANSWERED, true).apply()
    }
}
