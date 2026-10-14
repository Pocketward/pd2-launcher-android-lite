// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/MainActivity.kt).
package com.pd2launcher.box64

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pd2launcher.box64.input.ControlsStore
import com.pd2launcher.box64.ui.AboutScreen
import com.pd2launcher.box64.ui.CheckScreen
import com.pd2launcher.box64.ui.CheckingDeviceScreen
import com.pd2launcher.box64.ui.CompleteScreen
import com.pd2launcher.box64.ui.ControlsEditor
import com.pd2launcher.box64.ui.CopyFacts
import com.pd2launcher.box64.ui.FormFactor
import com.pd2launcher.box64.ui.GameSettings
import com.pd2launcher.box64.ui.GameView
import com.pd2launcher.box64.ui.ImportNotice
import com.pd2launcher.box64.ui.ImportScreen
import com.pd2launcher.box64.ui.LauncherTheme
import com.pd2launcher.box64.ui.LocalFormFactor
import com.pd2launcher.box64.ui.NotSupportedScreen
import com.pd2launcher.box64.ui.Play
import com.pd2launcher.box64.ui.PlayDestinations
import com.pd2launcher.box64.ui.PlayScreen
import com.pd2launcher.box64.ui.RepairScreen
import com.pd2launcher.box64.ui.SettingsNav
import com.pd2launcher.box64.ui.SettingsScreen
import com.pd2launcher.box64.ui.importContinuePicks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The native screens, as the main app's: the device check, then Check, Import and Complete
 * (the first run), Play (Start launcher opens the official PD2 launcher in [GameActivity]), Repair,
 * Settings, About and the on-screen controls editor over a sample screen. Where it opens is read
 * from the app's copy of the game ([Onboarding.opening]). Laid out for the display's shape
 * (ui/FormFactor.kt), edge to edge. Touch and controller (d-pad focus, A selects, B goes back).
 */
class MainActivity : ComponentActivity() {
    /** Debug builds only: the [DebugPreview] screen asked for with `--es preview <name>`. */
    private val preview = mutableStateOf<String?>(null)
    /** Where the player went on to from Check, and the way back. */
    private val nav = mutableStateOf(FlowNav())
    /** Play, Repair or the first run, from the copy; the first run keeps it until Complete's Continue. */
    private val opening = mutableStateOf(Opening.FLOW)
    /** Settings, from Play, and where in it. */
    private val settingsOpen = mutableStateOf(false)
    private val settingsNav = SettingsNav()
    /** The on-screen controls editor, from Settings, over a sample screen. */
    private val editorOpen = mutableStateOf(false)
    /** Repair, from Play (it also shows by itself when the copy lacks Project Diablo 2). */
    private val repairOpen = mutableStateOf(false)
    /** About, from Play or Settings; over both. */
    private val aboutOpen = mutableStateOf(false)
    /** The one-time notice after the first game session ([ImportRecord.noticeDue]). */
    private val noticeDue = mutableStateOf(false)
    /** Which layout the launcher screens use, from the display's shape (ui/FormFactor.kt). */
    private lateinit var formFactor: FormFactor
    private lateinit var rt: Runtime
    private val debuggable get() = BuildConfig.DEBUG

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        formFactor = FormFactor.of(this)
        requestedOrientation = formFactor.launcherOrientation
        rt = Runtime(this)
        AppSettingsStore.load(this)
        ControlsStore.load(this)
        opening.value = Onboarding.opening(rt.game)
        // The device check comes before anything is unpacked.
        Setup.start(this)
        readScreenExtras(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            LauncherTheme {
                CompositionLocalProvider(LocalFormFactor provides formFactor) { Root() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the game (or Files): the copy may have changed; the first run keeps its page.
        if (opening.value != Opening.FLOW) opening.value = Onboarding.opening(rt.game)
        noticeDue.value = ImportRecord.noticeDue(this)
    }

    @Composable
    private fun Root() {
        val device by Setup.device.collectAsState()
        val runtime by Setup.runtime.collectAsState()
        val folder by Setup.folder.collectAsState()
        val copy by Setup.copy.collectAsState()
        val app by AppSettingsStore.current.collectAsState()
        val display = remember { GameResolution.display(this) }

        val previewName = preview.value
        BackHandler(enabled = previewName != null) { preview.value = null }
        // Debug builds: the game view's overlay over an empty screen ([DebugPreview.gameLayer]).
        val gamePreview = DebugPreview.gameLayer(previewName)
        // The controls editor lays them out on the game's landscape screen, without system bars.
        val gameScreen = editorOpen.value || gamePreview != null
        DisposableEffect(gameScreen) {
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            if (gameScreen) {
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                bars.hide(WindowInsetsCompat.Type.systemBars())
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            onDispose {
                if (gameScreen) {
                    bars.show(WindowInsetsCompat.Type.systemBars())
                    requestedOrientation = formFactor.launcherOrientation
                }
            }
        }
        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) Setup.checkFolder(this, tree)
        }
        val back = { nav.value = nav.value.back() }
        val openSettings: (Int) -> Unit = { tab ->
            settingsNav.open(tab)
            settingsOpen.value = true
        }
        val leavePreview = { preview.value = null }
        val runtimeBuild = (runtime as? RuntimeState.Ready)?.version
        val lowTier = remember { HostGpu.lowTier }

        if (aboutOpen.value) {
            AboutScreen(onBack = { aboutOpen.value = false }, runtime = runtimeBuild)
        } else if (editorOpen.value) {
            ControlsEditor(sample = true, onClose = { editorOpen.value = false })
        } else if (settingsOpen.value) {
            SettingsScreen(
                app = app,
                display = display,
                highestFrameRate = remember { FrameRateVote.highest(this) },
                lowTier = lowTier,
                nav = settingsNav,
                fromGame = false,
                onAppChange = { AppSettingsStore.save(this, it) },
                onEditOnScreen = { editorOpen.value = true },
                onAbout = { aboutOpen.value = true },
                onBack = { settingsOpen.value = false },
            )
        } else if (gamePreview != null) {
            GameView(
                session = null, launcher = false, onStop = leavePreview, preview = gamePreview,
                previewLauncher = DebugPreview.gameLauncher(previewName),
                stalled = DebugPreview.gameStalled(previewName),
                // Settings over the preview, as over the game; it changes the real settings.
                settings = GameSettings(open = settingsNav::open) { onEditOnScreen, onClose ->
                    SettingsScreen(
                        app = app, display = display, highestFrameRate = remember { FrameRateVote.highest(this) },
                        lowTier = lowTier, nav = settingsNav, fromGame = true,
                        onAppChange = { AppSettingsStore.save(this, it) }, onEditOnScreen = onEditOnScreen,
                        onAbout = { aboutOpen.value = true }, onBack = onClose,
                    )
                },
            )
        } else if (previewName != null && DebugPreview.screen(previewName, device, runtime) != null) {
            when (val screen = DebugPreview.screen(previewName, device, runtime)!!) {
                Screen.CheckingDevice -> CheckingDeviceScreen()
                is Screen.NotSupported -> NotSupportedScreen(screen, onClose = leavePreview)
                is Screen.Check -> CheckScreen(screen, onContinue = {})
                else -> leavePreview()
            }
        } else if (previewName == DebugPreview.IMPORT) {
            ImportScreen(folder, onPickFolder = { pickFolder.launch(null) }, onContinue = {}, onBack = leavePreview)
        } else if (previewName != null && DebugPreview.copy(previewName) != null) {
            CompleteScreen(DebugPreview.copy(previewName)!!, onStop = {}, onTryAgain = {}, onContinue = {}, onBack = leavePreview)
        } else if (previewName == DebugPreview.REPAIR) {
            RepairPage(runtime, onBack = leavePreview, preview = true)
        } else if (previewName == DebugPreview.ABOUT) {
            AboutScreen(onBack = leavePreview, runtime = runtimeBuild)
        } else if (previewName == DebugPreview.PLAY_FAILED) {
            PlayPage(device, RuntimeState.Failed("preview"), app, display, notice = false, preview = true, openSettings = openSettings)
        } else if (previewName == DebugPreview.PLAY || previewName == DebugPreview.NOTICE_IMPORT) {
            PlayPage(device, runtime, app, display, notice = previewName == DebugPreview.NOTICE_IMPORT, preview = true, openSettings = openSettings)
        } else if (opening.value != Opening.FLOW && device?.let { DeviceCheck.problems(it) }?.isNotEmpty() == true) {
            // An installed app on a device the check now refuses (as the main app's Installed.Refused):
            // the game support files are not unpacked there, so Play and Repair could only wait.
            NotSupportedScreen(Screen.NotSupported(DeviceCheck.problems(device!!)), onClose = ::finish)
        } else if (repairOpen.value || opening.value == Opening.REPAIR) {
            // Back to Play; with the copy found incomplete there is none: Back leaves the app.
            RepairPage(runtime, onBack = { if (opening.value == Opening.REPAIR) moveTaskToBack(true) else repairOpen.value = false })
        } else if (opening.value == Opening.PLAY) {
            PlayPage(device, runtime, app, display, notice = noticeDue.value, preview = false, openSettings = openSettings)
        } else when (val screen = Onboarding.screen(device, runtime, nav.value.page)) {
            Screen.CheckingDevice -> CheckingDeviceScreen()
            is Screen.NotSupported -> NotSupportedScreen(screen, onClose = ::finish)
            is Screen.Check -> CheckScreen(
                screen,
                onContinue = { nav.value = nav.value.open(FlowPage.IMPORT) },
                onTryAgain = { Setup.unpack(this, again = true) },
            )
            Screen.Import -> ImportScreen(
                folder,
                onPickFolder = { pickFolder.launch(null) },
                onContinue = {
                    if (importContinuePicks(folder)) {
                        pickFolder.launch(null)
                    } else {
                        // No page asks first: the copy starts as Complete shows.
                        nav.value = nav.value.open(FlowPage.COMPLETE)
                        Setup.startCopy(this)
                    }
                },
                onBack = back,
            )
            Screen.Complete -> {
                // Never back to a step while the copy runs: Back leaves the app (the copy goes on).
                BackHandler(enabled = copy is CopyState.Copying) { moveTaskToBack(true) }
                CompleteScreen(
                    copy,
                    onStop = Setup::stopCopy,
                    onTryAgain = { Setup.startCopy(this) },
                    onContinue = {
                        Setup.finished()
                        nav.value = FlowNav()
                        opening.value = Onboarding.opening(rt.game)
                    },
                    onBack = back,
                )
            }
        }
    }

    /**
     * Play: Start launcher, the facts (the GPU and the next start's screen size), the
     * destinations, and once the one-time [notice]. Start launcher waits while the game support
     * files unpack, an import runs or the last session's processes still end.
     */
    @Composable
    private fun PlayPage(
        device: DeviceFacts?,
        runtime: RuntimeState,
        app: AppSettings,
        display: Pair<Int, Int>,
        notice: Boolean,
        preview: Boolean,
        openSettings: (Int) -> Unit,
    ) = Box(Modifier.fillMaxSize()) {
        // Followed once a second: the processes of the last session end a moment after it.
        val busy by produceState(false) {
            while (true) {
                value = withContext(Dispatchers.IO) { LiteState.importing || LiteState.sessionActive }
                delay(1_000)
            }
        }
        val ready = runtime is RuntimeState.Ready && runtime.missing.isEmpty()
        // The last Start launcher ended before the official PD2 launcher did its work.
        val launcherEnd by LiteState.launcherEnd.collectAsState()
        PlayScreen(
            facts = Play.facts(device?.gpu, GameResolution.forSession(display.first, display.second, app.gameResolution, HostGpu.panvk)),
            problem = when (runtime) {
                is RuntimeState.Failed -> getString(R.string.userland_failed, runtime.reason)
                is RuntimeState.Ready -> if (runtime.missing.isEmpty()) null else getString(R.string.host_missing_files, runtime.missing.joinToString(", "))
                else -> null
            } ?: when (launcherEnd.takeIf { !preview }) {
                LiteState.LauncherEnd.FAILED -> getString(R.string.play_launcher_failed)
                LiteState.LauncherEnd.STALLED -> getString(R.string.play_launcher_stalled)
                null -> null
            },
            // The game support files failed: the main app's "Can't start right now" and Try again
            // under the problem; else "Getting ready…" while they unpack or the last session ends.
            waiting = when {
                Onboarding.failed(runtime) -> getString(R.string.play_not_ready)
                !ready || busy -> getString(R.string.play_getting_ready)
                else -> null
            },
            onTryAgain = { if (!preview) Setup.unpack(this@MainActivity, again = true) }.takeIf { Onboarding.failed(runtime) },
            onStartLauncher = { if (!preview) startLauncher() },
            destinations = PlayDestinations(
                onSettings = { openSettings(SettingsNav.GAME) },
                onControls = { openSettings(SettingsNav.CONTROLLER) },
                onRepair = { if (!preview) repairOpen.value = true },
                onAbout = { aboutOpen.value = true },
            ),
        )
        if (notice) {
            ImportNotice(remember { ImportRecord.folder(this@MainActivity) }) {
                if (preview) {
                    this@MainActivity.preview.value = null
                } else {
                    ImportRecord.answered(this@MainActivity)
                    noticeDue.value = false
                }
            }
        }
    }

    /** Repair: the copy's facts (read again on Check again), the game support files, Start over. */
    @Composable
    private fun RepairPage(runtime: RuntimeState, onBack: () -> Unit, preview: Boolean = false) {
        var checks by remember { mutableIntStateOf(0) }
        var resetting by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        val copy by produceState<CopyFacts?>(null, checks) {
            value = null
            value = withContext(Dispatchers.IO) {
                CopyFacts(ImportRecord.folder(this@MainActivity), folderBytes(rt.game), Onboarding.missing(rt.game))
            }
        }
        RepairScreen(
            copy, runtime, resetting, problem,
            onCheckAgain = {
                if (!preview) {
                    problem = null
                    checks++
                    Setup.unpack(this, again = false)
                }
            },
            onStartOver = {
                if (!preview) {
                    resetting = true
                    problem = null
                    Setup.startOver(this) { why ->
                        runOnUiThread {
                            resetting = false
                            if (why == null) {
                                repairOpen.value = false
                                nav.value = FlowNav()
                                opening.value = Opening.FLOW
                            } else {
                                problem = getString(why.id, *why.args.toTypedArray())
                            }
                        }
                    }
                }
            },
            onBack = onBack,
        )
    }

    /** Start launcher: the official PD2 launcher in the game view; its own Play starts the game there. */
    private fun startLauncher() {
        Log.i(Native.TAG, "start launcher tapped")
        LiteState.launcherEnd.value = null
        startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_PROGRAM, GameActivity.PROGRAM_LAUNCHER))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readScreenExtras(intent)
    }

    /**
     * Controllers: A selects and B goes back on every native screen. Android does not treat
     * the gamepad's A as a confirm key, so it is handed on as the d-pad's center, and B as Back.
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

    /** Debug builds only: `--es preview <name>` ([DebugPreview]; `--ei tab <n>` with `settings`). */
    private fun readScreenExtras(intent: Intent) {
        if (!debuggable) return
        preview.value = intent.getStringExtra("preview")
        if (preview.value == DebugPreview.SETTINGS) {
            preview.value = null
            settingsNav.open(intent.getIntExtra("tab", SettingsNav.GAME))
            settingsOpen.value = true
        } else if (preview.value != null) {
            settingsOpen.value = false
            aboutOpen.value = false
        }
    }

    private companion object {
        /** The size of [folder] and everything below it, never following a link (the prefix's drives). */
        fun folderBytes(folder: File): Long {
            val st = try { android.system.Os.lstat(folder.path) } catch (_: Exception) { return 0 }
            if (!android.system.OsConstants.S_ISDIR(st.st_mode)) return if (android.system.OsConstants.S_ISREG(st.st_mode)) st.st_size else 0
            return folder.list()?.sumOf { folderBytes(File(folder, it)) } ?: 0
        }
    }
}
