// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/ui/InstallScreen.kt).
package com.pd2launcher.box64.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pd2launcher.box64.R
import com.pd2launcher.box64.RuntimeState
import com.pd2launcher.box64.Tone

/**
 * What Repair shows of the app's copy: where it came from ([folder], its path on shared storage
 * when known), its size, and the game files it lacks ([missing], from Onboarding.GAME_FILES).
 */
data class CopyFacts(val folder: String?, val bytes: Long, val missing: List<String>)

/**
 * Repair, from Play (and at the start when the copy lacks Project Diablo 2 or its launcher): the
 * copy of your game folder ([copy], null while it is checked), the official PD2 launcher in it, and
 * the game support files ([runtime]). Check again ([onCheckAgain]) checks the copy and the game
 * support files again, and unpacks those again when files are missing. Start over removes the
 * app's copy after an in-place confirmation that says what goes and what is kept (the
 * characters). [resetting]: Start over runs; nothing can be pressed until it ends. [problem]: why
 * the last Start over removed nothing.
 */
@Composable
fun RepairScreen(
    copy: CopyFacts?,
    runtime: RuntimeState,
    resetting: Boolean,
    problem: String?,
    onCheckAgain: () -> Unit,
    onStartOver: () -> Unit,
    onBack: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var confirm by remember { mutableStateOf(false) }
    InitialFocus(focus, confirm)
    val checking = copy == null || runtime is RuntimeState.Waiting || runtime is RuntimeState.Unpacking
    FlowShell(
        step = null,
        title = stringResource(R.string.repair_title),
        intro = stringResource(R.string.repair_intro),
        onBack = { if (confirm) confirm = false else onBack() },
        // Its two actions take the corners: Start over (then Keep) left, Check again (then the
        // confirmed Start over) right.
        secondary = {
            if (!confirm) {
                CornerPill(stringResource(R.string.repair_start_over), { confirm = true }, Kind.DANGER, enabled = !checking && !resetting)
            } else {
                CornerPill(stringResource(R.string.repair_keep), { confirm = false }, enabled = !resetting, focusRequester = focus)
            }
        },
        action = {
            if (!confirm) {
                CornerPill(stringResource(R.string.action_check_again), onCheckAgain, Kind.PRIMARY, enabled = !checking && !resetting, focusRequester = focus)
            } else {
                CornerPill(stringResource(R.string.repair_confirm), onStartOver, Kind.DANGER, enabled = !resetting)
            }
        },
    ) {
        if (confirm) {
            Banner(stringResource(R.string.repair_confirm_body), Palette.Red, Modifier.padding(vertical = 8.dp))
        }
        if (problem != null) Banner(problem, Palette.Red, Modifier.padding(vertical = 8.dp))
        val gameMissing = copy?.missing.orEmpty().filter { it != LAUNCHER }
        StatusLine(
            when {
                copy == null -> Tone.WORKING
                gameMissing.isEmpty() -> Tone.OK
                else -> Tone.WARNING
            },
            stringResource(R.string.repair_row_folder),
            if (copy == null) {
                listOf(stringResource(R.string.repair_status_checking))
            } else {
                listOfNotNull(
                    copy.folder?.let { stringResource(R.string.repair_folder_from, it) },
                    if (gameMissing.isEmpty()) {
                        stringResource(R.string.repair_folder_complete, copy.bytes shr 20)
                    } else {
                        stringResource(R.string.repair_folder_missing, gameMissing.joinToString(", "))
                    },
                )
            },
        )
        Rule()
        val launcherMissing = copy?.missing?.contains(LAUNCHER)
        StatusLine(
            when (launcherMissing) {
                null -> Tone.WORKING
                false -> Tone.OK
                true -> Tone.WARNING
            },
            stringResource(R.string.repair_row_launcher),
            listOf(
                stringResource(
                    when (launcherMissing) {
                        null -> R.string.repair_status_checking
                        false -> R.string.repair_launcher_found
                        true -> R.string.repair_launcher_missing
                    },
                ),
            ),
        )
        Rule()
        StatusLine(
            when (runtime) {
                is RuntimeState.Ready -> if (runtime.missing.isEmpty()) Tone.OK else Tone.ERROR
                is RuntimeState.Failed -> Tone.ERROR
                else -> Tone.WORKING
            },
            stringResource(R.string.row_userland),
            listOf(
                when (runtime) {
                    is RuntimeState.Ready -> if (runtime.missing.isEmpty()) {
                        stringResource(R.string.repair_support_ready, runtime.version)
                    } else {
                        stringResource(R.string.host_missing_files, runtime.missing.joinToString(", "))
                    }
                    is RuntimeState.Failed -> stringResource(R.string.userland_failed, runtime.reason)
                    is RuntimeState.Unpacking -> stringResource(R.string.repair_support_unpacking)
                    RuntimeState.Waiting -> stringResource(R.string.repair_status_checking)
                },
            ),
        )
        repairWaits(resetting, checking)?.let { WaitNote(stringResource(it)) }
    }
}

/** The official launcher's file in the copy, the one Repair shows on its own row. */
private const val LAUNCHER = "ProjectD2/PD2Launcher.exe"

/**
 * Why Repair's actions wait: Start over runs, or the copy and the game support files are being
 * checked. How they stand is their rows' alone.
 */
@StringRes
fun repairWaits(resetting: Boolean, checking: Boolean): Int? = when {
    resetting -> R.string.repair_status_resetting
    checking -> R.string.repair_status_checking
    else -> null
}
