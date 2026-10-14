// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/ui/CheckScreen.kt).
package com.pd2launcher.box64.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pd2launcher.box64.CheckRow
import com.pd2launcher.box64.Line
import com.pd2launcher.box64.MainAction
import com.pd2launcher.box64.R
import com.pd2launcher.box64.Screen
import com.pd2launcher.box64.Tone

/**
 * Step 1, Check, in plain words: this device, the game support files and whether it can run the
 * game. The rows that passed fold into one line ("3 checks passed") that opens in place; what
 * needs the player (a problem) and what still runs stay open. [onTryAgain]: the game support
 * files failed or are not complete, and this unpacks them again.
 */
@Composable
fun CheckScreen(
    screen: Screen.Check,
    onContinue: () -> Unit,
    onTryAgain: () -> Unit = {},
) {
    val action = screen.action
    val focus = remember { FocusRequester() }
    InitialFocus(focus, action.kind, action.enabled)
    val tone = screen.tone
    var showPassed by rememberSaveable { mutableStateOf(false) }
    FlowShell(
        step = FlowStep.CHECK,
        failed = tone == Tone.ERROR,
        title = text(screen.title),
        intro = stringResource(screen.intro),
        action = {
            val (label, onClick) = when (action.kind) {
                MainAction.Kind.CONTINUE -> R.string.action_continue to onContinue
                MainAction.Kind.TRY_AGAIN -> R.string.action_try_again to onTryAgain
            }
            CornerPill(stringResource(label), onClick, Kind.PRIMARY, enabled = action.enabled, focusRequester = focus)
        },
    ) {
        val passed = screen.passed
        if (passed.isNotEmpty()) {
            PassedLine(passed.size, showPassed) { showPassed = !showPassed }
            if (showPassed) {
                passed.forEach { row ->
                    Rule()
                    CheckLine(row)
                }
            }
        }
        screen.open.forEachIndexed { index, row ->
            if (index > 0 || passed.isNotEmpty()) Rule()
            CheckLine(row)
        }
        checkWaits(action)?.let { WaitNote(text(it)) }
    }
}

/**
 * What Check says beside its rows: only why Continue waits (the checks still run). A problem is
 * its row's, and the title and intro say the rest.
 */
fun checkWaits(action: MainAction): Line? = action.hint.takeUnless { action.enabled }

/** One check: its mark, label and lines, and its bar while it unpacks. */
@Composable
private fun CheckLine(row: CheckRow) {
    StatusLine(row.tone, stringResource(row.label), row.lines.map { text(it) })
    row.progress?.let { Bar(it, Modifier.padding(start = 38.dp, end = 4.dp, bottom = 10.dp), height = 3.dp) }
}

/** The passed checks as one line: a tap, A or Enter shows them under it, again hides them. */
@Composable
private fun PassedLine(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val toggle = stringResource(if (expanded) R.string.check_passed_hide else R.string.check_passed_show)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).focusMark(focused)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClickLabel = toggle, onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatusMark(Tone.OK)
        Text(
            if (count == 1) stringResource(R.string.check_passed_one) else stringResource(R.string.check_passed, count),
            style = Type.Body, color = Palette.Text, modifier = Modifier.weight(1f),
        )
        Text(toggle, style = Type.Hint, color = Palette.Teal, modifier = Modifier.padding(end = 10.dp))
    }
}

/** The device check runs, before anything is unpacked. */
@Composable
fun CheckingDeviceScreen() {
    FlowShell(
        step = FlowStep.CHECK,
        title = stringResource(R.string.device_checking_title),
        intro = stringResource(R.string.device_checking_body),
        action = { CornerPill(stringResource(R.string.action_continue), {}, Kind.PRIMARY, enabled = false) },
    ) {
        Spacer(Modifier.height(12.dp))
        Bar(null, Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(4.dp))
        WaitNote(stringResource(R.string.hint_wait_for_check))
    }
}

/** The device check refused this device: what it needs, and each problem it found. */
@Composable
fun NotSupportedScreen(screen: Screen.NotSupported, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    InitialFocus(focus)
    FlowShell(
        step = FlowStep.CHECK,
        failed = true,
        title = stringResource(R.string.not_supported_title),
        intro = stringResource(R.string.not_supported_body),
        action = { CornerPill(stringResource(R.string.not_supported_close), onClose, Kind.PRIMARY, focusRequester = focus) },
    ) {
        val findings = screen.problems.map { Tone.ERROR to it } + screen.warnings.map { Tone.WARNING to it }
        findings.forEachIndexed { index, (tone, line) ->
            if (index > 0) Rule()
            StatusLine(tone, text(line), emptyList())
        }
    }
}
