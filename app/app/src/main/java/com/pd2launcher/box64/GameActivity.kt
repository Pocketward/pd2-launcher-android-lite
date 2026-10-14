package com.pd2launcher.box64

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pd2launcher.box64.files.GameFilesProvider
import com.pd2launcher.box64.input.DdrawPointer
import com.pd2launcher.box64.ui.AboutScreen
import com.pd2launcher.box64.ui.FormFactor
import com.pd2launcher.box64.ui.GameSettings
import com.pd2launcher.box64.ui.GameView
import com.pd2launcher.box64.ui.LauncherTheme
import com.pd2launcher.box64.ui.LocalFormFactor
import com.pd2launcher.box64.ui.SettingsNav
import com.pd2launcher.box64.ui.SettingsScreen
import java.io.File
import java.io.IOException

/**
 * The game view, full screen: the main app's Game view (ui/GameView.kt: the X screen at its own
 * shape, the menu rail, the Controls panel, the on-screen controls and their editor, Settings over
 * the game) over Lite's own session ([Session]: Xvfb, x-input-relay, the gamepad file, sound and
 * Wine through Box64).
 *
 * It opens the official PD2 launcher ([PROGRAM_LAUNCHER]); the launcher's own Play starts the
 * game in the same session, on the same X screen. A [ProgramWatch] follows what runs: the rail
 * shows the launcher's rows until the game runs, and once neither runs any more the session ends
 * and Play shows again (with a problem line when the launcher did not do its work). Extras (adb,
 * debug builds only): "program" game starts the game alone (Glide, or "renderer" ddraw;
 * -skiptobnet), for tests; "env" space-separated NAME=value for Wine.
 */
class GameActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var dns: Dns? = null
    /** The session once it runs (its X screen is up and its program started): the game view shows it. */
    private val running = mutableStateOf<Session?>(null)
    /** Started with the official PD2 launcher, and the game it starts does not run (yet, or any more). */
    private val launcher = mutableStateOf(false)
    /** The game runs with DirectDraw (the launcher's Options): touch moves past cnc-ddraw's bars. */
    private val ddraw = mutableStateOf(false)
    /** The official launcher stopped responding ([LauncherStall]): the rail asks whether to close it. */
    private val stalled = mutableStateOf(false)
    /** The player chose to wait: the watch starts its rules over. */
    @Volatile private var stallWait = false
    /** Started with the official PD2 launcher; false only for a debug build's game alone. */
    private var launcherMode = false
    /** The game view started (it does not while an import runs). */
    private var started = false
    /** The official launcher is ended once its game shows ([LauncherExit]). */
    private val launcherExit = LauncherExit()
    /** The session's watch runs ([ProgramWatch]); the program the session started has ended. */
    @Volatile private var watching = false
    @Volatile private var startEnded = false
    /** Settings over the game: where in it, and About from its App tab. */
    private val settingsNav = SettingsNav()
    private val aboutOpen = mutableStateOf(false)
    private lateinit var formFactor: FormFactor
    private var display = 1 to 1
    private var highestRate = 0f
    private var lowTier = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not while an import replaces the game copy.
        if (LiteState.importing) {
            Toast.makeText(this, R.string.status_import_busy, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Android restores only this activity from Recents after the process died (an app update
        // too): the start screen, which unpacks the game support files, never ran. Without ready
        // files and a complete copy the session goes back there.
        val rt = Runtime(this)
        if (!rt.installed() || rt.missingFiles().isNotEmpty() || Onboarding.missing(rt.game).isNotEmpty()) {
            Log.w(Native.TAG, "game view without ready game support files or a complete copy: back to the start screen")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
            return
        }
        // From here until the session ends the game's files are not changed through Files.
        LiteState.sessionStarted()
        GameFilesProvider.stateChanged(this)
        started = true
        LiteState.launcherEnd.value = null
        // The whole display, the cutout's edge too: no system bars (a swipe shows them for a
        // moment, over the game), and Android's keyboard covers the bottom of the game instead of
        // resizing it (the on-screen controls' layout is per screen size).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val app = AppSettingsStore.load(this)
        formFactor = FormFactor.of(this)
        // The official PD2 launcher; debug builds only: the game alone, for tests.
        launcherMode = !(BuildConfig.DEBUG && intent.getStringExtra(EXTRA_PROGRAM) == PROGRAM_GAME)
        launcher.value = launcherMode
        val renderer = if (intent.getStringExtra("renderer") == "ddraw") Renderer.DDRAW else Renderer.GLIDE
        // One X screen at the display's shape (Settings → Game → Game resolution): the launcher
        // and the game it starts share it.
        lowTier = HostGpu.lowTier
        display = GameResolution.display(this)
        highestRate = FrameRateVote.highest(this)
        val (xWidth, xHeight) = GameResolution.forSession(display.first, display.second, app.gameResolution, HostGpu.panvk)
        Log.i(Native.TAG, "X screen ${xWidth}x$xHeight (display ${display.first}x${display.second})")
        val profile = GraphicsProfiles.forPlay(app.graphicsProfile, lowTier)
        val s = Session(rt, xWidth, xHeight, renderer, if (launcherMode) Session.Program.LAUNCHER else Session.Program.GAME)
        // Test knobs without a rebuild: files/env.txt (written with run-as), one NAME=value a line.
        val envFile = File(rt.files, "env.txt")
        s.extraEnv = (intent.getStringExtra("env")?.split(' ') ?: emptyList()) +
            (if (envFile.isFile) envFile.readLines() else emptyList())
        s.extraEnv = s.extraEnv.map { it.trim() }.filter { '=' in it && !it.startsWith("#") }
        // A web link the official launcher opens (its news, Donate): in Android's browser.
        s.onWebLink = { link -> main.post { openWebLink(link) } }
        session = s
        // Android's DNS servers for the userland's resolver, before any program starts.
        dns = Dns(this, File(rt.userland, "etc/resolv.conf")).also { it.start() }
        // The program the session started ended (Native logs every end); close-launcher.exe's answer.
        Native.exitListener = { _, name, exited, value ->
            if (name == "game") startEnded = true
            if (name == Session.CLOSER_NAME) launcherExit.asked(if (exited) value else -1)
        }
        setContent {
            LauncherTheme {
                CompositionLocalProvider(LocalFormFactor provides formFactor) { Game() }
            }
        }
        val startedAt = SystemClock.elapsedRealtime()
        Thread {
            try {
                // The copy's settings files for this X screen; the graphics profile only when it is
                // not the one written last (the player's own changes in the game stay until then).
                val limit = app.limitFrameRate && FrameRateVote.limitApplies(highestRate)
                val applied = GameCopy.applySettings(
                    rt.game, xWidth, xHeight, profile, AppliedProfile.get(this), limit, SavedFrameLimit.get(this),
                )
                SavedFrameLimit.set(this, applied.keep)
                applied.profile?.let { AppliedProfile.set(this, it) }
                when (applied.d2gl) {
                    GameCopy.D2glFile.CREATED -> Log.i(Native.TAG, "d2gl.json written with the first-launch values for ${xWidth}x$xHeight")
                    GameCopy.D2glFile.UNREADABLE -> Log.w(Native.TAG, "d2gl.json is not JSON: left to D2GL")
                    else -> Log.i(Native.TAG, "d2gl.json: ${applied.d2gl.name.lowercase()}")
                }
                s.start()
                Log.i(Native.TAG, "session started in ${(System.nanoTime() - s.startedAt) / 1_000_000} ms")
                main.post { if (!isFinishing) running.value = s }
                watch(ProgramWatch(startedAt))
            } catch (e: Exception) {
                Log.e(Native.TAG, "session start failed", e)
                main.post { end(LiteState.LauncherEnd.FAILED) }
            }
        }.start()
    }

    /**
     * Once a second, what the session runs ([ProgramWatch]): the rail's rows follow it, and once
     * it is over the game view closes. On the session's start thread, until the view closes.
     */
    private fun watch(watch: ProgramWatch) {
        watching = true
        var keeperStopped = false
        val stall = LauncherStall(LauncherStall.ticksPerSecond())
        var screen: ScreenPixels? = null
        var asked = false
        while (watching) {
            val now = SystemClock.elapsedRealtime()
            val names = ProgramWatch.running()
            val sample = watch.sample(now, names, startEnded)
            played.sample(now, sample.gameRuns)
            val launcherRuns = if (launcherMode) ProgramWatch.pids(ProgramWatch.LAUNCHER).firstOrNull() else null
            // The official launcher alone: whether it stopped responding (asked once until answered).
            val launcherPid = launcherRuns.takeIf { !sample.gameRuns }
            if (stallWait) {
                stallWait = false
                asked = false
                stall.reset(now)
            }
            val look = launcherPid?.let { pid ->
                screen = screen ?: session?.let { ScreenPixels.ofMemfd(it.memfd) }
                val pixels = screen
                LauncherStall.look(now, pid, pixels?.let(LauncherStall::black) ?: false, pixels?.let(LauncherStall::picture))
            }
            val why = stall.sample(look)
            if (why != null && !asked) {
                asked = true
                val counted = why == LauncherStall.Why.FROZEN || why == LauncherStall.Why.BUSY
                val facts = stall.facts?.takeIf { counted }?.let { " ($it)" }.orEmpty()
                Log.w(Native.TAG, "the official launcher stopped responding: ${why.name.lowercase().replace('_', ' ')}$facts")
                main.post { stalled.value = true }
            }
            if (look == null && stalled.value) main.post { stalled.value = false }
            // The launcher's window is on the screen by now and the game covers it: its keeper's
            // look at every window, four times a second, would cost the game CPU time. The
            // session ends soon after the game, so the keeper does not start again.
            if (sample.gameRuns && !keeperStopped) {
                keeperStopped = true
                Session.stopKeeper()
            }
            if (launcherMode && !launcherExit.done) endLauncher(now, names, sample.gameRuns, launcherRuns)
            val directDraw = sample.gameRuns && ProgramWatch.gameArguments()?.let(DdrawPointer::directDraw) == true
            main.post {
                launcher.value = launcherMode && !sample.gameRuns
                if (directDraw && !ddraw.value) Log.i(Native.TAG, "the game draws with DirectDraw")
                ddraw.value = directDraw
                if (sample.ended) end(LiteState.LauncherEnd.FAILED.takeIf { sample.launcherFailed })
            }
            if (sample.ended) return
            Thread.sleep(1000)
        }
    }

    /** The game's presents when the watch first saw it run; null before. */
    private var presentsAtGame: Long? = null

    /**
     * Ends the official launcher once its game shows ([LauncherExit]): close-launcher.exe asks it
     * to close; its process is ended only when it is still there a little later.
     */
    private fun endLauncher(now: Long, names: List<String>, gameRuns: Boolean, launcherPid: Int?) {
        val s = session ?: return
        val presents = LauncherExit.presents(s.counter)
        if (gameRuns && presentsAtGame == null) presentsAtGame = presents ?: 0
        if (!gameRuns) presentsAtGame = null
        val look = LauncherExit.Look(
            now = now,
            gameRuns = gameRuns,
            gameDraws = presents?.let { p -> presentsAtGame?.let { p > it } },
            launcherPid = launcherPid,
            updaterRuns = names.any { it.equals(ProgramWatch.comm(ProgramWatch.UPDATER), ignoreCase = true) },
            storageWrites = launcherPid?.let(LauncherExit::storageWrites),
        )
        val closedBefore = launcherExit.closedIn
        when (launcherExit.sample(look)) {
            LauncherExit.Step.ASK -> {
                Log.i(Native.TAG, "the game shows: asking the official launcher to close")
                try {
                    s.closeLauncher()
                } catch (e: IOException) {
                    Log.w(Native.TAG, "close-launcher.exe: $e")
                    launcherExit.asked(-1)
                }
            }
            LauncherExit.Step.KILL -> launcherPid?.let { pid ->
                if (Session.killLauncher(pid)) Log.w(Native.TAG, "the official launcher ($pid) did not close: ended")
            }
            null -> {}
        }
        val closed = launcherExit.closedIn
        if (closedBefore == null && closed != null) Log.i(Native.TAG, "the official launcher closed ${closed} ms after the question")
    }

    /** The session is over (or did not start): back to Play, which says so when it ended with a [problem]. */
    private fun end(problem: LiteState.LauncherEnd?) {
        if (isFinishing) return
        Log.i(Native.TAG, "session over" + when (problem) {
            LiteState.LauncherEnd.FAILED -> ": the launcher did not open"
            LiteState.LauncherEnd.STALLED -> ": the launcher stopped responding"
            null -> ""
        })
        LiteState.launcherEnd.value = problem
        finish()
    }

    @Composable
    private fun Game() {
        val app by AppSettingsStore.current.collectAsState()
        // Settings → Keep the screen on, at once.
        DisposableEffect(app.keepScreenOn) {
            if (app.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        GameView(
            session = running.value,
            launcher = launcher.value,
            ddraw = ddraw.value,
            stalled = stalled.value,
            onStallWait = {
                stalled.value = false
                stallWait = true
            },
            onStop = { end(LiteState.LauncherEnd.STALLED.takeIf { stalled.value }) },
            settings = GameSettings(
                open = { tab ->
                    settingsNav.open(tab)
                    aboutOpen.value = false
                },
                content = { onEditOnScreen, onClose -> SettingsLayer(onEditOnScreen, onClose) },
            ),
        )
    }

    /** Settings over the game: the main app's screen (About from its App tab over it). */
    @Composable
    private fun SettingsLayer(onEditOnScreen: () -> Unit, onClose: () -> Unit) {
        val app by AppSettingsStore.current.collectAsState()
        if (aboutOpen.value) {
            AboutScreen(onBack = { aboutOpen.value = false }, runtime = Runtime(this).version())
        } else {
            SettingsScreen(
                app = app,
                display = display,
                highestFrameRate = highestRate,
                lowTier = lowTier,
                nav = settingsNav,
                fromGame = true,
                onAppChange = { AppSettingsStore.save(this, it) },
                onEditOnScreen = onEditOnScreen,
                onAbout = { aboutOpen.value = true },
                onBack = onClose,
            )
        }
    }

    /** [link] (http or https, [WebLinks.allowed]) in the browser Android picks; the game keeps running. */
    private fun openWebLink(link: String) {
        if (isFinishing || !WebLinks.allowed(link)) return
        Log.i(Native.TAG, "opening a web link in Android's browser")
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, link, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Controllers on the overlays and Settings, as on the native screens: A selects and B goes
     * back. The game view sees every key first while nothing covers the game (it wraps the
     * window's callback), so this applies only to what it passes on.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mapped = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> return super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(
            KeyEvent(
                event.downTime, event.eventTime, event.action, mapped, event.repeatCount,
                event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
            ),
        )
    }

    /** How long the game itself ran (not the launcher alone), as the main app counts it. */
    private val played = PlayTime()

    /**
     * A session whose game ran long enough counts as played: the one-time notice on Play waits
     * for it. Recorded as the game view closes (before Play comes back) and at its end.
     */
    private fun recordPlayed() {
        if (session == null) return
        ImportRecord.sessionEnded(this, played.total(SystemClock.elapsedRealtime()))
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) recordPlayed()
    }

    override fun onDestroy() {
        if (!started) {
            super.onDestroy()
            return
        }
        watching = false
        main.removeCallbacksAndMessages(null)
        Native.exitListener = null
        session?.let { s ->
            recordPlayed()
            s.stop()
        }
        session = null
        dns?.stop()
        dns = null
        LiteState.sessionEnded()
        GameFilesProvider.stateChanged(this)
        super.onDestroy()
    }

    companion object {
        /** What the game view runs: [PROGRAM_LAUNCHER] (the player's start), or [PROGRAM_GAME] (debug builds only). */
        const val EXTRA_PROGRAM = "program"
        const val PROGRAM_LAUNCHER = "launcher"
        const val PROGRAM_GAME = "game"
    }
}
