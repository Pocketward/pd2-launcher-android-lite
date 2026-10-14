// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/ui/InstallScreen.kt).
package com.pd2launcher.box64.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pd2launcher.box64.CopyState
import com.pd2launcher.box64.R
import com.pd2launcher.box64.Tone

/** How one of Complete's tasks stands. */
enum class TaskState { WAITING, RUNNING, DONE, STOPPED, FAILED }

/** Complete's two tasks: copy the folder into the app, then check Project Diablo 2 in the copy. */
data class CompleteTasks(val copy: TaskState, val check: TaskState)

object Complete {
    /** The tasks as [copy] leaves them. */
    fun tasks(copy: CopyState): CompleteTasks = when (copy) {
        CopyState.Idle -> CompleteTasks(TaskState.WAITING, TaskState.WAITING)
        is CopyState.Copying -> CompleteTasks(TaskState.RUNNING, TaskState.WAITING)
        is CopyState.Stopped -> CompleteTasks(TaskState.STOPPED, TaskState.WAITING)
        is CopyState.Failed -> if (copy.checking) CompleteTasks(TaskState.DONE, TaskState.FAILED) else CompleteTasks(TaskState.FAILED, TaskState.WAITING)
        is CopyState.Done -> CompleteTasks(TaskState.DONE, TaskState.DONE)
    }

    /** The copy runs (or stops): the figure and Stop show, Back does not. */
    fun working(copy: CopyState): Boolean = copy is CopyState.Copying

    /** Back shows when nothing runs, and not once done: only on to Play then (Back would offer to copy it again). */
    fun back(copy: CopyState): Boolean = backShown(hasBack = copy !is CopyState.Done, working = working(copy))

    /** The figure's percent: the copy's, 100 once done; null before it starts or after it ended otherwise. */
    fun percent(copy: CopyState): Int? = when (copy) {
        is CopyState.Copying -> if (copy.total > 0) (copy.done * 100 / copy.total).toInt().coerceIn(0, 100) else 0
        is CopyState.Done -> 100
        else -> null
    }

    @StringRes
    fun title(copy: CopyState): Int = when (copy) {
        CopyState.Idle -> R.string.complete_title
        is CopyState.Copying -> if (copy.stopping) R.string.complete_title_stopping else R.string.complete_title_running
        is CopyState.Stopped -> R.string.complete_title_stopped
        is CopyState.Failed -> R.string.complete_title_failed
        is CopyState.Done -> R.string.complete_title_done
    }

    @StringRes
    fun intro(copy: CopyState): Int = when (copy) {
        is CopyState.Done -> R.string.complete_intro_done
        is CopyState.Stopped, is CopyState.Failed -> R.string.complete_intro_stopped
        else -> R.string.complete_intro
    }
}

/**
 * Step 3, Complete: the copy of the picked folder into the app and the check of Project Diablo 2
 * in it, each a task row; the copy's figure (percent, MB) while it runs; Stop while it copies (no
 * Back then); Try again after a stop or a failure ([onTryAgain] starts the copy over); once both
 * are done, "Setup complete" and Continue ([onContinue], to Play).
 */
@Composable
fun CompleteScreen(
    copy: CopyState,
    onStop: () -> Unit,
    onTryAgain: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val working = Complete.working(copy)
    val done = copy is CopyState.Done
    InitialFocus(focus, working, done)
    val percent = Complete.percent(copy)
    val color = when (copy) {
        is CopyState.Failed -> Palette.Red
        is CopyState.Stopped -> Palette.Amber
        else -> Palette.Teal
    }
    val caption = (copy as? CopyState.Copying)?.let { stringResource(R.string.complete_amount, it.done shr 20, it.total shr 20) }
        ?: (copy as? CopyState.Done)?.let { stringResource(R.string.complete_amount, it.total shr 20, it.total shr 20) }
    FlowShell(
        step = FlowStep.COMPLETE,
        failed = copy is CopyState.Failed,
        title = stringResource(Complete.title(copy)),
        intro = stringResource(Complete.intro(copy)),
        onBack = onBack.takeIf { Complete.back(copy) },
        backButton = Complete.back(copy),
        progress = stepProgress(working, percent),
        lead = if (working || done) ({ Figure(percent, caption, color = color) }) else null,
        action = {
            when {
                copy is CopyState.Copying -> CornerPill(
                    stringResource(if (copy.stopping) R.string.action_stopping else R.string.action_stop), onStop,
                    enabled = !copy.stopping, focusRequester = focus,
                )
                done -> CornerPill(stringResource(R.string.action_continue), onContinue, Kind.PRIMARY, focusRequester = focus)
                else -> CornerPill(stringResource(R.string.action_try_again), onTryAgain, Kind.PRIMARY, focusRequester = focus)
            }
        },
    ) {
        val tasks = Complete.tasks(copy)
        TaskLine(tasks.copy, R.string.complete_task_copy, copyDetail(tasks.copy, copy))
        Rule()
        TaskLine(tasks.check, R.string.complete_task_check, checkDetail(tasks.check))
        if (copy is CopyState.Failed) {
            Spacer(Modifier.height(12.dp))
            Banner(text(copy.why), Palette.Red)
        }
        // Under a failure, and while the copy runs or is stopped, so a copy that looks stuck can be
        // reported; one place, so the links and their note stay when the copy fails.
        if (copy is CopyState.Failed || copy is CopyState.Copying || copy is CopyState.Stopped) {
            ShareLogsLink(Modifier.padding(top = 4.dp))
        }
        (copy as? CopyState.Done)?.keptProblem?.let {
            Spacer(Modifier.height(12.dp))
            Banner(text(it), Palette.Amber)
        }
    }
}

@Composable
private fun TaskLine(state: TaskState, @StringRes label: Int, detail: String?) {
    val tone = when (state) {
        TaskState.WAITING -> Tone.PENDING
        TaskState.RUNNING -> Tone.WORKING
        TaskState.DONE -> Tone.OK
        TaskState.STOPPED -> Tone.WARNING
        TaskState.FAILED -> Tone.ERROR
    }
    StatusLine(tone, stringResource(label), listOfNotNull(detail), dim = state == TaskState.WAITING)
}

@Composable
private fun copyDetail(state: TaskState, copy: CopyState): String? = when (state) {
    TaskState.WAITING -> null
    TaskState.RUNNING -> stringResource(if ((copy as? CopyState.Copying)?.stopping == true) R.string.complete_title_stopping else R.string.complete_row_copying)
    TaskState.DONE -> stringResource(R.string.complete_row_copied)
    TaskState.STOPPED -> stringResource(R.string.complete_row_stopped)
    TaskState.FAILED -> stringResource(R.string.complete_row_failed)
}

@Composable
private fun checkDetail(state: TaskState): String? = when (state) {
    TaskState.DONE -> stringResource(R.string.complete_row_checked)
    TaskState.FAILED -> stringResource(R.string.complete_row_failed)
    else -> null
}
