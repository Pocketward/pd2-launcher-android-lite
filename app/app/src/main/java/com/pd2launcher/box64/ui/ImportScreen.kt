// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/ui/SourceScreen.kt).
package com.pd2launcher.box64.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pd2launcher.box64.FolderState
import com.pd2launcher.box64.R
import com.pd2launcher.box64.Tone

/**
 * Step 2, Import: the Diablo II folder with Project Diablo 2, picked with Android's folder picker
 * ([onPickFolder], or Continue before a folder is accepted) and checked before anything is
 * copied. Continue then goes on to Complete, which copies it at once. Under the button, what the
 * page must say ([importNote]): why a folder is refused (with Share logs and Save logs), what an
 * accepted one holds, or that the folder is being checked.
 */
@Composable
fun ImportScreen(folder: FolderState, onPickFolder: () -> Unit, onContinue: () -> Unit, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    InitialFocus(focus)
    val checking = folder == FolderState.Checking
    FlowShell(
        step = FlowStep.IMPORT,
        title = stringResource(R.string.import_title),
        intro = stringResource(R.string.import_intro),
        onBack = onBack,
        backButton = true,
        action = {
            CornerPill(stringResource(R.string.action_continue), onContinue, Kind.PRIMARY, enabled = !checking, focusRequester = focus)
        },
    ) {
        Text(stringResource(R.string.import_needs), style = Type.Body, color = Palette.Text, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        LauncherButton(
            stringResource(R.string.import_pick), onPickFolder,
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), enabled = !checking,
        )
        when (importNote(folder)) {
            Note.PROBLEM -> {
                Banner(text((folder as FolderState.Problem).why), Palette.Red, Modifier.padding(top = 8.dp))
                ShareLogsLink(Modifier.padding(bottom = 8.dp))
            }
            Note.FOUND -> {
                val found = folder as FolderState.Found
                val size = found.bytes shr 20
                StatusLine(
                    Tone.OK, stringResource(R.string.import_found),
                    listOf(if (found.name != null) stringResource(R.string.import_found_where, size, found.name) else stringResource(R.string.import_found_size, size)),
                )
            }
            Note.WAITS -> WaitNote(stringResource(R.string.import_checking))
            null -> Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * The line Import shows under its button, if any: a folder refused ([Note.PROBLEM], why), what
 * the accepted folder holds ([Note.FOUND]), or that the picked folder is being checked
 * ([Note.WAITS]). With no folder picked the intro says what to do.
 */
fun importNote(folder: FolderState): Note? = when (folder) {
    FolderState.None -> null
    FolderState.Checking -> Note.WAITS
    is FolderState.Problem -> Note.PROBLEM
    is FolderState.Found -> Note.FOUND
}

/** Continue opens the folder picker: no folder is accepted yet (the main app's `continuePicks`). */
fun importContinuePicks(folder: FolderState): Boolean = folder !is FolderState.Found
