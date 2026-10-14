// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/input/Controls.kt).
package com.pd2launcher.box64.input

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The player's [Controls] in the app's `controls` preferences, and as a flow the screens and the
 * game view follow ([current]): the bindings of both modes, the chord, the pointer speed, when the
 * on-screen controls show and their opacity.
 */
object ControlsStore {
    private const val PREFERENCES = "controls"
    private val _current = MutableStateFlow(Controls())
    private var loaded = false

    val current: StateFlow<Controls> = _current.asStateFlow()

    /** Reads the stored controls once per process; later calls return what is current. */
    fun load(context: Context): Controls {
        if (!loaded) {
            loaded = true
            _current.value = Controls.fromPreferences(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all)
        }
        return _current.value
    }

    fun save(context: Context, controls: Controls) {
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear()
        for ((key, value) in controls.toPreferences()) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
        loaded = true
        _current.value = controls
    }
}

/**
 * The on-screen controls' layouts in the app's `on_screen_layouts` preferences, one per screen
 * size and orientation ([ControlsLayout.key], dp) as JSON in normalised coordinates
 * ([LayoutJson]); a screen with none stored has the default for its size. [version] moves on
 * every save, so the game view reads the layout again.
 */
object LayoutStore {
    private const val PREFERENCES = "on_screen_layouts"
    private val _version = MutableStateFlow(0)

    val version: StateFlow<Int> = _version.asStateFlow()

    /** The layout for a [width] x [height] dp screen. */
    fun layout(context: Context, width: Int, height: Int): ControlsLayout = LayoutJson.decode(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(ControlsLayout.key(width, height), null),
        ControlsLayout.default(width.toFloat(), height.toFloat()),
    )

    fun save(context: Context, width: Int, height: Int, layout: ControlsLayout) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(ControlsLayout.key(width, height), LayoutJson.encode(layout)).apply()
        _version.value++
    }
}
