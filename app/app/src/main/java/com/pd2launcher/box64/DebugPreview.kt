// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/DebugPreview.kt).
package com.pd2launcher.box64

import com.pd2launcher.box64.ui.GameLayer
import com.pd2launcher.box64.ui.SystemState

/**
 * Debug builds only: `am start -n com.pd2launcher.lite.dev/com.pd2launcher.box64.MainActivity --es preview <name>`
 * shows one native screen with injected facts, for screens a set-up device does not reach on its
 * own; the testers compare them with the main app's previews of the same name. It changes
 * nothing: its buttons do nothing, B or Back leaves the preview. `settings` takes `--ei tab <n>`
 * (0 Game, 1 App, 2 Controller).
 */
object DebugPreview {
    /** Check from the real device check and game support files, also once imported. */
    const val CHECK = "check"
    /** Check with every row passed, folded. */
    const val CHECK_PASSED = "check-passed"
    /** Check on a Mali of a supported generation that the bundled PanVK does not know: a warning, not a refusal. */
    const val CHECK_UNKNOWN_MALI = "check-unknown-mali"
    const val NOT_SUPPORTED = "not-supported"
    /** Import over the real folder state (a pick there is checked, Continue goes nowhere). */
    const val IMPORT = "import"
    /** Complete while it copies (1,234 of 3,068 MB). */
    const val COMPLETE = "complete"
    /** Complete once done: "Setup complete". */
    const val COMPLETE_DONE = "complete-done"
    /** Complete once done, but the characters a Start over kept did not all go back. */
    const val COMPLETE_KEPT = "complete-kept"
    const val PLAY = "play"
    /** Play while the game support files failed: "Can't start right now", the reason and Try again. */
    const val PLAY_FAILED = "play-failed"
    const val REPAIR = "repair"
    const val ABOUT = "about"
    const val SETTINGS = "settings"
    /** Play with its one-time notice; OK saves nothing here. */
    const val NOTICE_IMPORT = "notice-import"
    /** The Game view's overlay over an empty screen: the glyph, the menu rail, the Controls panel, the controls editor. */
    const val GAME = "game"
    const val GAME_MENU = "game-menu"
    /** The menu rail as over the official PD2 launcher. */
    const val GAME_MENU_LAUNCHER = "game-menu-launcher"
    /** The rail's question when the official PD2 launcher stopped responding. */
    const val GAME_MENU_STALLED = "game-menu-stalled"
    const val GAME_CONTROLS = "game-controls"
    const val GAME_EDIT = "game-edit"

    private const val MB = 1L shl 20

    /** A device that passed: the GPU's name as this device reports it. */
    fun device(real: DeviceFacts?): DeviceFacts = real ?: DeviceFacts(DeviceCheck.PAGE_SIZE, true, null, "Adreno", "13")

    /**
     * A Mali-G715's kbase GPU id with another product major (5): a v11 Mali that the bundled PanVK
     * has no model for, as no test device has one.
     */
    val unknownMali = MaliGpu(0xb8a51030L, 38)

    /**
     * The screen for [name] among the first run's, from the real [device], [runtime] and Android
     * settings ([system]); null for the others.
     */
    fun screen(name: String, device: DeviceFacts?, runtime: RuntimeState, system: SystemState): Screen? = when (name) {
        CHECK -> Onboarding.screen(device, runtime, FlowPage.CHECK, system.notificationsOff, system.batteryRestricted)
        CHECK_PASSED -> Onboarding.check(device(device), RuntimeState.Ready("preview"))
        CHECK_UNKNOWN_MALI -> Onboarding.check(
            DeviceFacts(DeviceCheck.PAGE_SIZE, false, unknownMali, unknownMali.name, "14"), RuntimeState.Ready("preview"),
            system.notificationsOff, system.batteryRestricted,
        )
        NOT_SUPPORTED -> Screen.NotSupported(
            listOf(Line(R.string.device_gpu_not_supported, "Mali-G78"), Line(R.string.device_page_size, 16384L)),
        )
        else -> null
    }

    /** The Game view's layer [name] previews; null for other previews. */
    fun gameLayer(name: String?): GameLayer? = when (name) {
        GAME -> GameLayer.NONE
        GAME_MENU, GAME_MENU_LAUNCHER, GAME_MENU_STALLED -> GameLayer.MENU
        GAME_CONTROLS -> GameLayer.CONTROLS
        GAME_EDIT -> GameLayer.EDIT
        else -> null
    }

    /** The Game view's preview [name] is over the official PD2 launcher. */
    fun gameLauncher(name: String?): Boolean = name == GAME_MENU_LAUNCHER || name == GAME_MENU_STALLED

    /** The Game view's preview [name] asks about the official PD2 launcher that stopped responding. */
    fun gameStalled(name: String?): Boolean = name == GAME_MENU_STALLED

    /** Complete's injected work for [name]; null for the others. */
    fun copy(name: String): CopyState? = when (name) {
        COMPLETE -> CopyState.Copying(1_234 * MB, 3_068 * MB)
        COMPLETE_DONE -> CopyState.Done(3_068 * MB)
        COMPLETE_KEPT -> CopyState.Done(3_068 * MB, Line(R.string.complete_kept_not_restored, "preview"))
        else -> null
    }
}
