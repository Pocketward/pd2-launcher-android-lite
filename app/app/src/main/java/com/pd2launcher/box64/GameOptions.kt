package com.pd2launcher.box64

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import org.json.JSONObject

/**
 * How a debug start of the game alone draws (`--es program game`, `--es renderer glide|ddraw`):
 * D2GL (Glide, on the GPU) or the game's own DirectDraw (cnc-ddraw). The player's starts go
 * through the official PD2 launcher, whose Options choose it.
 */
enum class Renderer(val argument: String) { GLIDE("-3dfx"), DDRAW("-ddraw") }

/**
 * The X screen of every session (Settings → Game → Game resolution): the official PD2 launcher
 * and the game it starts share it, and the game view scales it up to the display. Every choice
 * keeps the display's own landscape shape, so the picture fills it, and has room for the
 * launcher's window ([launcherFits]). The player's pick is a number of lines
 * ([AppSettings.gameResolution]); none is Automatic: the display's full size on an Adreno,
 * [MALI_LINES] on a Mali.
 */
object GameResolution {
    /**
     * The fewest lines offered: cnc-ddraw (DirectDraw, which the official launcher's Options may
     * choose) shows only part of PD2's 800 x 600 menus on a smaller X screen.
     */
    const val MIN_LINES = 600

    /**
     * Automatic's lines on a Mali (PanVK): a moderate size. Every frame is copied on the CPU
     * (Mesa's software WSI into Xvfb, Xvfb's frame buffer into the game view), which grows with the
     * X screen; 720 lines are under half the pixels of a 1080-line display's full size.
     */
    const val MALI_LINES = 720

    /** The sizes below the display's full size, in lines. */
    private val LINES = listOf(1080, 900, MALI_LINES, MIN_LINES)

    /**
     * The official PD2 launcher's window on the X screen, measured: 800 x 600 px with its own
     * frame (Xvfb has no window manager to add one), in the X screen's centre when the place it
     * saved (AppData/launcherSettings.json, often a PC's second monitor) lies outside. On a
     * smaller X screen part of it, its Play button too, is off the screen.
     */
    const val LAUNCHER_WIDTH = 800
    const val LAUNCHER_HEIGHT = 600

    /** Room around the launcher's window a size keeps at least, in pixels across and down. */
    private const val LAUNCHER_ROOM = 16

    /** Whether the launcher's window fits a [width] x [height] X screen with [LAUNCHER_ROOM] to spare. */
    fun launcherFits(width: Int, height: Int): Boolean =
        width >= LAUNCHER_WIDTH + LAUNCHER_ROOM && height >= LAUNCHER_HEIGHT + LAUNCHER_ROOM

    /** The default display's native size in landscape, width to height. */
    @Suppress("DEPRECATION")
    fun display(context: Context): Pair<Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        display.getRealSize(size)
        return maxOf(size.x, size.y) to minOf(size.x, size.y)
    }

    /**
     * The choices for a [width] x [height] landscape display: its full size first, then each of
     * [LINES] below it and no fewer than [MIN_LINES], at the display's shape ([at]), where the
     * launcher's window fits ([launcherFits]).
     */
    fun choices(width: Int, height: Int): List<Pair<Int, Int>> =
        listOf(width to height) + LINES.filter { it < height && it >= MIN_LINES }
            .map { at(width, height, it) }
            .filter { (w, h) -> launcherFits(w, h) }

    /**
     * The [width] x [height] display's shape at [lines] (an even width, rounded down); the full
     * size at as many lines as the display has or more. On a 1604 x 720 display 600 lines are
     * 1336 x 600.
     */
    fun at(width: Int, height: Int, lines: Int): Pair<Int, Int> {
        if (lines >= height) return width to height
        return width * lines / height / 2 * 2 to lines
    }

    /**
     * The X screen of a session: the [picked] lines at the display's shape when they are one of
     * the [choices]; else (null: Automatic, or lines not offered here) Automatic's size:
     * [MALI_LINES] on a [mali] GPU where they are one of the choices, else the full size.
     */
    fun forSession(width: Int, height: Int, picked: Int?, mali: Boolean): Pair<Int, Int> {
        val choices = choices(width, height)
        return choices.firstOrNull { picked != null && it.second == picked }
            ?: choices.firstOrNull { mali && it.second == MALI_LINES }
            ?: (width to height)
    }
}

/**
 * D2GL's graphics profile (Settings → Game → Graphics profile): the D2GL values written into the
 * game's `d2gl.json` when the profile a Play uses is not the one applied last, so the player's own
 * changes in the game (Ctrl+O) stay until the profile changes.
 */
enum class GraphicsProfile(val shader: String, val effects: Boolean) {
    /** Project Diablo 2's own D2GL settings: the advanced-aa upscaling shader with sharpening and bloom. */
    DEFAULT("anti-aliasing\\advanced-aa.slangp", true),

    /** Tuned down for slower GPUs: the plain bilinear upscale, no sharpening, no bloom. */
    PERFORMANCE("bilinear.slangp", false),
}

object GraphicsProfiles {
    /**
     * Automatic's profile: Performance where [lowTier] (the older and entry-level Adrenos, and a
     * Mali, whose PanVK path is tuned for it: [HostGpu.lowTier]), else Default.
     */
    fun automatic(lowTier: Boolean): GraphicsProfile = if (lowTier) GraphicsProfile.PERFORMANCE else GraphicsProfile.DEFAULT

    /** The profile a Play uses: the [picked] one, else Automatic's. */
    fun forPlay(picked: GraphicsProfile?, lowTier: Boolean): GraphicsProfile = picked ?: automatic(lowTier)

    /** One of Settings' choices: [profile] (null: Automatic), its title and what it means (string ids). */
    data class Choice(val profile: GraphicsProfile?, val title: Int, val body: Int)

    /** What Settings' row shows: the [picked] profile, or Automatic with what it picks here. */
    fun shown(picked: GraphicsProfile?, lowTier: Boolean): Int = when (picked) {
        null -> automaticTitle(lowTier)
        GraphicsProfile.DEFAULT -> R.string.settings_graphics_default
        GraphicsProfile.PERFORMANCE -> R.string.settings_graphics_performance
    }

    /** Settings' choices, in order: Automatic (what it picks here), Default, Performance. */
    fun choices(lowTier: Boolean): List<Choice> = listOf(
        Choice(null, automaticTitle(lowTier), R.string.settings_graphics_automatic_body),
        Choice(GraphicsProfile.DEFAULT, R.string.settings_graphics_default, R.string.settings_graphics_default_body),
        Choice(GraphicsProfile.PERFORMANCE, R.string.settings_graphics_performance, R.string.settings_graphics_performance_body),
    )

    private fun automaticTitle(lowTier: Boolean): Int = when (automatic(lowTier)) {
        GraphicsProfile.DEFAULT -> R.string.settings_graphics_automatic_default
        GraphicsProfile.PERFORMANCE -> R.string.settings_graphics_automatic_performance
    }
}

/**
 * The values the game's `d2gl.json` needs on the X screen (the main app's engine writes the same):
 * D2GL full screen at the X screen's size, the cursor unlocked (a locked one puts Wine into
 * pointer clipping, where XTEST motion does not move it), no minimize on focus loss (no window
 * manager brings it back), the DLLs as PD2 ships them, and a graphics [profile] when one is given.
 * Only the named values change; the player's other choices stay.
 */
object D2glConfig {
    fun apply(json: JSONObject, width: Int, height: Int, profile: GraphicsProfile?): JSONObject {
        val screen = json.section("screen")
        screen.put("window_size_width", width)
        screen.put("window_size_height", height)
        screen.put("window_fullscreen", true)
        screen.put("unlock_cursor", true)
        screen.put("auto_minimize", false)
        if (profile != null) {
            val graphics = json.section("graphics")
            graphics.put("shader_preset", profile.shader)
            graphics.put("sharpen", profile.effects)
            graphics.put("bloom", profile.effects)
        }
        val other = json.section("other")
        other.put("load_dlls_early", "")
        other.put("load_dlls_late", "SGD2FreeRes.dll")
        return json
    }

    private fun JSONObject.section(name: String): JSONObject = optJSONObject(name) ?: JSONObject().also { put(name, it) }

    /**
     * A whole configuration for a game copy without one (D2GL never ran on the PC it came from),
     * as the main app writes it: D2GL's first-launch values (vsync on, 60 fps in front, 25 behind,
     * its corner mini map off), the [profile]'s graphics, and [apply]'s values for the [width] x
     * [height] X screen. Without it D2GL starts at its 800 x 600 default, a part of the X screen.
     */
    fun firstLaunch(width: Int, height: Int, profile: GraphicsProfile): JSONObject {
        val json = JSONObject()
        json.put("screen", JSONObject().apply {
            put("window_size_width", width)
            put("window_size_height", height)
            put("window_centered", true)
            put("window_position_x", 0)
            put("window_position_y", 0)
            put("unlock_cursor", true)
            put("window_fullscreen", true)
            put("window_vsync", true)
            put("foreground_fps_active", true)
            put("foreground_fps_value", 60)
            put("background_fps_active", true)
            put("background_fps_value", 25)
            put("auto_minimize", false)
            put("window_dark_mode", true)
        })
        json.put("graphics", JSONObject().apply {
            put("shader_preset", GraphicsProfile.DEFAULT.shader)
            put("sharpen", true)
            put("sharpen_strength", 1.2000000476837158)
            put("sharpen_clamp", 0.800000011920929)
            put("sharpen_radius", 0.4000000059604645)
            put("fxaa", false)
            put("fxaa_preset", 1)
            put("lut", 0)
            put("bloom", true)
            put("bloom_exposure", 1.0)
            put("bloom_gamma", 0.75)
            put("stretched_horizontal", false)
            put("stretched_vertical", false)
        })
        json.put("features", JSONObject().apply {
            put("hd_cursor", true)
            put("hd_text", true)
            put("hd_text_scale", 1.0)
            put("mini_map", false)
            put("mini_map_text_over", true)
            put("mini_map_width", 200)
            put("mini_map_height", 140)
            put("motion_prediction", true)
            put("skip_intro", false)
            put("no_pickup", false)
            put("show_item_quantity", true)
            put("show_fps", false)
        })
        json.put("other", JSONObject().apply {
            put("gl_ver_major", 4)
            put("gl_ver_minor", 6)
            put("use_compute_shader", false)
            put("frame_latency", 1)
            put("load_dlls_early", "")
            put("load_dlls_late", "SGD2FreeRes.dll")
        })
        return apply(json, width, height, profile)
    }

    /** D2GL's frame limit as the player set it: vsync, the limit on, its value (null: not in the file). */
    data class FpsValues(val vsync: Boolean?, val active: Boolean?, val value: Int?) {
        fun encode(): String = JSONObject().apply {
            vsync?.let { put("vsync", it) }
            active?.let { put("active", it) }
            value?.let { put("value", it) }
        }.toString()

        companion object {
            fun decode(text: String?): FpsValues? = try {
                text?.let { JSONObject(it) }?.let { o ->
                    FpsValues(
                        if (o.has("vsync")) o.getBoolean("vsync") else null,
                        if (o.has("active")) o.getBoolean("active") else null,
                        if (o.has("value")) o.getInt("value") else null,
                    )
                }
            } catch (_: org.json.JSONException) {
                null
            }
        }
    }

    /**
     * Settings → Game → Limit to 60 fps in the game itself: the game view's display rate does not
     * pace the game (its frames reach the X screen without vsync), so D2GL's own frame limit does,
     * which works only with its vsync off. [limit] on: vsync off and the limit at [cap]; the
     * player's values ([saved], or the file's now) are returned to keep. Off: the [saved] values
     * come back; null is returned (nothing to keep).
     */
    fun limitFrames(json: JSONObject, limit: Boolean, saved: FpsValues?, cap: Int = FrameRateVote.CAP.toInt()): FpsValues? {
        val screen = json.section("screen")
        if (limit) {
            val own = saved ?: FpsValues(
                if (screen.has("window_vsync")) screen.optBoolean("window_vsync") else null,
                if (screen.has("foreground_fps_active")) screen.optBoolean("foreground_fps_active") else null,
                if (screen.has("foreground_fps_value")) screen.optInt("foreground_fps_value") else null,
            )
            screen.put("window_vsync", false)
            screen.put("foreground_fps_active", true)
            screen.put("foreground_fps_value", cap)
            return own
        }
        if (saved != null) {
            fun restore(key: String, value: Any?) = if (value == null) screen.remove(key) else screen.put(key, value)
            restore("window_vsync", saved.vsync)
            restore("foreground_fps_active", saved.active)
            restore("foreground_fps_value", saved.value)
        }
        return null
    }
}

/**
 * The values the game's `ddraw.ini` needs on the X screen when the official launcher's Options pick
 * DirectDraw (cnc-ddraw): its renderer, its window at the X screen's size (its borderless window
 * takes that size, and a larger one would show only part of the game), the picture's own shape
 * kept as it is scaled up (`maintas`: PD2's 4:3 menus and 16:9 world with black bars, as D2GL
 * draws them, not stretched to the X screen's shape), and the game's mouse scaled with the
 * picture. Only these lines of the [ddraw] section change (game sections may set their own);
 * `maintas` is added there when the file has no line for it.
 */
object DdrawIni {
    fun apply(text: String, width: Int, height: Int, renderer: String): String {
        val lines = text.lines().dropLastWhile { it.isEmpty() }.map { it.trimEnd('\r') }
        var section = ""
        var aspect = false
        val out = lines.map { line ->
            if (line.startsWith("[")) section = line.trim().lowercase()
            when {
                section != "[ddraw]" -> line
                line.startsWith("renderer=") -> "renderer=$renderer"
                line.startsWith("width=") -> "width=$width"
                line.startsWith("height=") -> "height=$height"
                line.startsWith("maintas=") -> "maintas=true".also { aspect = true }
                line.startsWith("adjmouse=") -> "adjmouse=true"
                else -> line
            }
        }.toMutableList()
        val header = out.indexOfFirst { it.trim().lowercase() == "[ddraw]" }
        if (!aspect && header >= 0) out.add(header + 1, "maintas=true")
        return out.joinToString("\r\n") + "\r\n"
    }
}

/**
 * The display rate the game view asks for (Settings → Game → Limit to 60 fps): on a screen faster
 * than 60 Hz the game view asks for its highest rate, or for 60 when limited.
 */
object FrameRateVote {
    const val CAP = 60f

    /** The highest refresh rate [display] offers at its current resolution; 0 without a display. */
    fun highest(display: Display?): Float {
        display ?: return 0f
        val mode = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: display.refreshRate
    }

    /** The built-in display's highest refresh rate ([highest]). */
    fun highest(context: Context): Float =
        highest(context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY))

    /** Whether the limit means anything on a screen whose highest rate is [highest]: one faster than [CAP]. */
    fun limitApplies(highest: Float): Boolean = highest > CAP + 1f

    /** The rate to ask for on a screen whose highest rate is [highest], [limited] to [CAP] or not. */
    fun vote(highest: Float, limited: Boolean): Float = if (limited && limitApplies(highest)) CAP else highest
}
