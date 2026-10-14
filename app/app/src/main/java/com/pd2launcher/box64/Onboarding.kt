// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/Onboarding.kt).
package com.pd2launcher.box64

import androidx.annotation.StringRes
import java.io.File

/** One line of text as a string resource and its arguments. */
data class Line(@param:StringRes val id: Int, val args: List<Any>) {
    constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
}

/** How a check row stands; the screen shows each with a glyph and words, not color alone. */
enum class Tone { PENDING, WORKING, OK, WARNING, ERROR }

/** One row of step 1: what is checked, how it stands, and what to say about it. */
data class CheckRow(
    @param:StringRes val label: Int,
    val tone: Tone,
    val lines: List<Line>,
    /** 0..1 while [Tone.WORKING] has a known amount; null otherwise. */
    val progress: Float? = null,
    /** What the row's button grants in place; null: nothing to grant. */
    val grant: Grant? = null,
)

/** What a Check row can ask Android for: its own prompt where there is one, else its settings page. */
enum class Grant { NOTIFICATIONS, BATTERY }

/** The first-run pages, in the order the player goes through them. */
enum class FlowPage { CHECK, IMPORT, COMPLETE }

/**
 * Where the player is in the first run and how they got there: going on pushes a page, Back pops
 * it. The choices themselves live in the activity and [Setup], so going back loses none.
 */
data class FlowNav(val stack: List<FlowPage> = listOf(FlowPage.CHECK)) {
    val page: FlowPage get() = stack.last()

    fun open(next: FlowPage): FlowNav = if (next == page) this else copy(stack = stack + next)

    /** The previous page; Check stays (Back there leaves the app). */
    fun back(): FlowNav = if (stack.size > 1) copy(stack = stack.dropLast(1)) else this
}

/** Step 1's one main action (the bottom-right corner, `ui/Shell.kt`) and why it waits while disabled. */
data class MainAction(val kind: Kind, val enabled: Boolean, val hint: Line) {
    /** [TRY_AGAIN]: the game support files failed to unpack or are not complete; it unpacks them again. */
    enum class Kind { CONTINUE, TRY_AGAIN }
}

/** Where the game support files (the runtime in the APK) stand. */
sealed interface RuntimeState {
    /** Not started yet: the device check runs first. */
    data object Waiting : RuntimeState

    /** Unpacking: [done] of [total] bytes. */
    data class Unpacking(val done: Long, val total: Long) : RuntimeState

    /** Unpacked: [version] is the runtime build (About shows it); [missing] the files not there. */
    data class Ready(val version: String, val missing: List<String> = emptyList()) : RuntimeState

    data class Failed(val reason: String) : RuntimeState
}

/** The native screen of the first run, from the device check and the game support files. */
sealed interface Screen {
    /** The device check runs (before anything is unpacked). */
    data object CheckingDevice : Screen

    /** The device check refused this device; [problems] say why. */
    data class NotSupported(val problems: List<Line>, val warnings: List<Line> = emptyList()) : Screen

    /**
     * Step 1, Check: this device, notifications, battery, the game support files and whether it
     * can run the game, in plain words.
     */
    data class Check(
        val device: CheckRow,
        /** Whether the session's notification shows; never blocks. */
        val notifications: CheckRow,
        /** Whether Android's battery optimisation may pause the app in the background; never blocks. */
        val battery: CheckRow,
        val support: CheckRow,
        val game: CheckRow,
        val action: MainAction,
    ) : Screen {
        val rows: List<CheckRow> get() = listOf(device, notifications, battery, support, game)

        /** The rows that passed, folded into one line ("3 checks passed") the player can open. */
        val passed: List<CheckRow> get() = rows.filter { it.tone == Tone.OK }

        /** The rows that stay open: what needs the player (an ask, a warning, a problem) and what still runs. */
        val open: List<CheckRow> get() = rows.filter { it.tone != Tone.OK }

        /** The asks left: rows the player may grant in place (notifications, battery); none blocks. */
        val optionalAsks: Int get() = rows.count { it.grant != null && it.tone == Tone.WARNING }

        /** A warning the player cannot grant away: the device itself is untested. */
        private val untested: Boolean get() = rows.any { it.tone == Tone.WARNING && it.grant == null }

        /** How Check stands, for its title and intro: a problem first, then work still running, then a warning. */
        val tone: Tone get() {
            val tones = rows.map { it.tone }
            return when {
                Tone.ERROR in tones -> Tone.ERROR
                Tone.PENDING in tones || Tone.WORKING in tones -> Tone.PENDING
                Tone.WARNING in tones -> Tone.WARNING
                else -> Tone.OK
            }
        }

        /**
         * The page's title: "Ready" only when nothing but optional asks remain ("Ready — 2 optional
         * settings"), never beside a warning about the device itself.
         */
        val title: Line get() = when (tone) {
            Tone.ERROR -> Line(R.string.check_title_problem)
            Tone.PENDING, Tone.WORKING -> Line(R.string.check_title)
            Tone.OK -> Line(R.string.check_title_ready)
            Tone.WARNING -> when {
                untested -> Line(R.string.check_title_untested)
                optionalAsks == 1 -> Line(R.string.check_title_optional_one)
                else -> Line(R.string.check_title_optional, optionalAsks)
            }
        }

        @get:StringRes
        val intro: Int get() = when (tone) {
            Tone.ERROR -> R.string.check_intro_problem
            Tone.PENDING, Tone.WORKING -> R.string.check_intro
            Tone.OK -> R.string.check_intro_ready
            Tone.WARNING -> if (untested) R.string.check_intro_untested else R.string.check_intro_optional
        }
    }

    /** Step 2, Import: the Diablo II folder with Project Diablo 2 the player picks (ui/ImportScreen.kt). */
    data object Import : Screen

    /** Step 3, Complete: the folder copied into the app and checked (ui/CompleteScreen.kt). */
    data object Complete : Screen
}

/** Where the installed app opens: read from the app's copy of the game, not from a record. */
enum class Opening { PLAY, REPAIR, FLOW }

object Onboarding {
    /** The files a complete copy has: the game, Project Diablo 2 and its official launcher. */
    val GAME_FILES = listOf("Game.exe", "ProjectD2/ProjectDiablo.dll", "ProjectD2/PD2Launcher.exe")

    /**
     * Of [GAME_FILES], the ones the copy at [game] lacks. Each name matches in any case, as in
     * Import's check and as Wine opens it ([GameCopy.find]).
     */
    fun missing(game: File): List<String> = GAME_FILES.filterNot { GameCopy.find(game, it).isFile }

    /**
     * Where the app opens with its copy at [game]: Play when it is complete; Repair when it has
     * Diablo II but lacks Project Diablo 2 or its launcher; else the first run, at Check.
     */
    fun opening(game: File): Opening = opening(missing(game))

    fun opening(missing: List<String>): Opening = when {
        missing.isEmpty() -> Opening.PLAY
        "Game.exe" !in missing -> Opening.REPAIR
        else -> Opening.FLOW
    }

    /**
     * The first run's screen on [page], from the device check ([device], null while it runs), the
     * [runtime], and whether Android shows the app's notifications ([notificationsOff]) and
     * optimises its battery use ([batteryRestricted]): warnings on Check, never blockers.
     */
    fun screen(
        device: DeviceFacts?,
        runtime: RuntimeState,
        page: FlowPage,
        notificationsOff: Boolean = false,
        batteryRestricted: Boolean = false,
    ): Screen {
        device ?: return Screen.CheckingDevice
        val problems = DeviceCheck.problems(device)
        if (problems.isNotEmpty()) return Screen.NotSupported(problems, DeviceCheck.warnings(device))
        return when (page) {
            FlowPage.CHECK -> check(device, runtime, notificationsOff, batteryRestricted)
            FlowPage.IMPORT -> Screen.Import
            FlowPage.COMPLETE -> Screen.Complete
        }
    }

    fun check(device: DeviceFacts, runtime: RuntimeState, notificationsOff: Boolean = false, batteryRestricted: Boolean = false): Screen.Check =
        Screen.Check(
            device = deviceRow(device),
            notifications = notificationsRow(notificationsOff),
            battery = batteryRow(batteryRestricted),
            support = supportRow(runtime),
            game = gameRow(runtime),
            action = action(runtime),
        )

    /**
     * The session's notification: on Android 13 its channel, made once the device check passed,
     * brings Android's own prompt over Check (at targetSdk 28 nothing else can ask); off is a
     * warning with Turn on (the app's notification settings), never a blocker.
     */
    fun notificationsRow(off: Boolean): CheckRow = if (off) {
        CheckRow(R.string.row_notifications, Tone.WARNING, listOf(Line(R.string.notifications_off_warning)), grant = Grant.NOTIFICATIONS)
    } else {
        CheckRow(R.string.row_notifications, Tone.OK, listOf(Line(R.string.notifications_on)))
    }

    /** Android's battery optimisation of the app: a warning with Allow (Android's own prompt), never a blocker. */
    fun batteryRow(restricted: Boolean): CheckRow = if (restricted) {
        CheckRow(R.string.row_battery, Tone.WARNING, listOf(Line(R.string.battery_restricted_warning)), grant = Grant.BATTERY)
    } else {
        CheckRow(R.string.row_battery, Tone.OK, listOf(Line(R.string.battery_unrestricted)))
    }

    /** "Adreno 740 graphics · Android 13", then a warning for each finding that lets the app go on ([DeviceCheck.warnings]). */
    fun deviceRow(device: DeviceFacts): CheckRow {
        val gpu = device.gpu
        val summary = if (gpu.isNullOrBlank()) {
            Line(R.string.device_summary_android, device.androidRelease)
        } else {
            Line(R.string.device_summary, gpu, device.androidRelease)
        }
        val warnings = DeviceCheck.warnings(device)
        return CheckRow(R.string.row_device, if (warnings.isEmpty()) Tone.OK else Tone.WARNING, listOf(summary) + warnings)
    }

    /** The game support files: unpacked from the APK at the first start and after an app update. */
    fun supportRow(runtime: RuntimeState): CheckRow = when (runtime) {
        RuntimeState.Waiting -> CheckRow(R.string.row_userland, Tone.PENDING, listOf(Line(R.string.userland_checking)))
        is RuntimeState.Unpacking -> if (runtime.total > 0) {
            val fraction = (runtime.done.toFloat() / runtime.total).coerceIn(0f, 1f)
            CheckRow(
                R.string.row_userland, Tone.WORKING,
                listOf(Line(R.string.userland_unpacking, (fraction * 100).toInt(), runtime.done shr 20, runtime.total shr 20)),
                progress = fraction,
            )
        } else {
            // The archive does not say its size: the amount alone, no bar.
            CheckRow(R.string.row_userland, Tone.WORKING, listOf(Line(R.string.userland_unpacking_open, runtime.done shr 20)))
        }
        // Its version is a runtime build id: About shows it, Check does not.
        is RuntimeState.Ready -> CheckRow(R.string.row_userland, Tone.OK, listOf(Line(R.string.userland_ready)))
        is RuntimeState.Failed -> CheckRow(R.string.row_userland, Tone.ERROR, listOf(Line(R.string.userland_failed, runtime.reason)))
    }

    /** Whether the game can run: every file of the unpacked game support files the game starts with is there. */
    fun gameRow(runtime: RuntimeState): CheckRow = when (runtime) {
        is RuntimeState.Ready -> if (runtime.missing.isEmpty()) {
            CheckRow(R.string.row_host, Tone.OK, listOf(Line(R.string.host_supported)))
        } else {
            CheckRow(R.string.row_host, Tone.ERROR, listOf(Line(R.string.host_missing_files, runtime.missing.joinToString(", "))))
        }
        is RuntimeState.Failed -> CheckRow(R.string.row_host, Tone.ERROR, listOf(Line(R.string.host_after_unpack_failed)))
        else -> CheckRow(R.string.row_host, Tone.PENDING, listOf(Line(R.string.host_after_unpack)))
    }

    /** Whether the game support files failed: Try again unpacks them again. */
    fun failed(runtime: RuntimeState): Boolean =
        runtime is RuntimeState.Failed || (runtime is RuntimeState.Ready && runtime.missing.isNotEmpty())

    fun action(runtime: RuntimeState): MainAction = when {
        failed(runtime) -> MainAction(MainAction.Kind.TRY_AGAIN, true, Line(R.string.hint_try_again))
        runtime is RuntimeState.Ready -> MainAction(MainAction.Kind.CONTINUE, true, Line(R.string.hint_continue_to_import))
        else -> MainAction(MainAction.Kind.CONTINUE, false, Line(R.string.hint_wait_for_check))
    }
}
