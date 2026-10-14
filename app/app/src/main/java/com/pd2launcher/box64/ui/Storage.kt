// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/ui/Storage.kt).
package com.pd2launcher.box64.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pd2launcher.box64.ImportRecord
import com.pd2launcher.box64.R
import com.pd2launcher.box64.Runtime

/**
 * The one-time notice over Play after the first game session of [ImportRecord.PLAYED_MS] or
 * more: the app has its own copy now, so the Diablo II folder copied onto the device ([folder],
 * its path when known) can go (the app never deletes it). A sheet from the bottom on phones, a
 * panel in the middle on handhelds; OK or Back answers it.
 */
@Composable
fun ImportNotice(folder: String?, onOk: () -> Unit) {
    BackHandler(onBack = onOk)
    val phone = LocalFormFactor.current == FormFactor.PHONE
    val focus = remember { FocusRequester() }
    InitialFocus(focus)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)).clickable(remember { MutableInteractionSource() }, null) {})
        Column(
            (if (phone) Modifier.align(Alignment.BottomCenter).fillMaxWidth() else Modifier.align(Alignment.Center).widthIn(max = 460.dp))
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)
                .clip(RowShape).background(Palette.Surface).border(1.dp, Palette.Rule, RowShape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GroupLabel(stringResource(R.string.settings_section_storage))
            Text(stringResource(R.string.notice_title), style = Type.Title, color = Palette.Text)
            Text(
                if (folder != null) stringResource(R.string.notice_folder, folder) else stringResource(R.string.notice_folder_unnamed),
                style = Type.Body, color = Palette.Text,
            )
            Text(stringResource(R.string.notice_where), style = Type.Hint, color = Palette.Muted)
            Spacer(Modifier.heightIn(min = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                LauncherButton(stringResource(R.string.notice_ok), onOk, kind = Kind.PRIMARY, focusRequester = focus, height = 48.dp, padding = 18.dp)
            }
        }
    }
}

/**
 * Settings → App → Storage: once the app has its own copy, the Diablo II folder copied onto the
 * device can go (its path when known). Lite downloads no installers, so there is nothing to remove.
 */
@Composable
fun ColumnScope.StorageRows() {
    val context = LocalContext.current
    val copied = remember { Runtime(context).game.isDirectory }
    val folder = remember { ImportRecord.folder(context) }
    Text(
        when {
            !copied -> stringResource(R.string.settings_no_game_folder)
            folder != null -> stringResource(R.string.settings_copied_folder, folder)
            else -> stringResource(R.string.settings_copied_folder_unnamed)
        },
        style = Type.Hint, color = Palette.Muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
