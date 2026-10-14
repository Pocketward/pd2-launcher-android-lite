// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/ui/PlayScreen.kt).
package com.pd2launcher.box64.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pd2launcher.box64.Line
import com.pd2launcher.box64.R

/** Play: what the app shows once the game is imported. */
object Play {
    /**
     * Where Play's button starts in a stage [height] tall, with [above] standing on it and
     * [below] hanging from it ([PlayStage]): centred, unless either would not fit; then it gives
     * way only as far as needed.
     */
    fun stageTop(height: Int, above: Int, middle: Int, below: Int): Int =
        ((height - middle) / 2).coerceAtMost(height - middle - below).coerceAtLeast(above)

    /**
     * The one line of facts under Start launcher: the GPU the game runs on and the screen size
     * the next start uses ([size], the X screen). Only what is known; the official launcher's
     * Options choose the renderer.
     */
    fun facts(gpu: String?, size: Pair<Int, Int>?): List<Line> = buildList {
        if (!gpu.isNullOrBlank()) add(Line(R.string.play_fact_plain, gpu))
        if (size != null) add(Line(R.string.play_fact_size, size.first, size.second))
    }

    /** The overlay's status line: what runs. */
    fun overlayStatus(launcher: Boolean): Int = if (launcher) R.string.launcher_running else R.string.game_running

    /** The overlay's Stop button label. */
    fun overlayStopLabel(launcher: Boolean): Int = if (launcher) R.string.launcher_view_stop else R.string.game_view_stop

    /** The overlay's answer that keeps what runs. */
    fun overlayKeepLabel(launcher: Boolean): Int = if (launcher) R.string.launcher_menu_keep_open else R.string.game_menu_keep_playing

    /** The overlay's question before stopping: the game warns about unsaved progress, the launcher does not. */
    fun overlayStopBody(launcher: Boolean): Int = if (launcher) R.string.launcher_menu_stop_body else R.string.game_menu_stop_body
}

/** Where Play leads, along the foot of the stage. */
class PlayDestinations(
    val onSettings: () -> Unit,
    val onControls: () -> Unit,
    val onRepair: () -> Unit,
    val onAbout: () -> Unit,
)

/**
 * Play: Start launcher alone in the middle of a quiet field, one line of facts beneath, the
 * destinations (Settings, Controls, Repair, About) along the foot. Start launcher opens the
 * official PD2 launcher; its own Play starts the game (no button here starts the game itself).
 * [problem]: why the last start did not open the launcher, with Share logs and Save logs under it. [waiting]: why it cannot start yet
 * ("Getting ready…"), shown where "Ready to play" goes; the button is disabled while it shows.
 */
@Composable
fun PlayScreen(
    facts: List<Line>,
    problem: String?,
    onStartLauncher: () -> Unit,
    destinations: PlayDestinations,
    waiting: String? = null,
    onTryAgain: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    InitialFocus(focus, waiting == null)
    val phone = LocalFormFactor.current == FormFactor.PHONE
    val width = if (phone) 280.dp else 300.dp
    Box(Modifier.fillMaxSize()) {
        Lattice(Modifier.matchParentSize())
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(Modifier.weight(1f))
            }
            PlayStage(
                Modifier.weight(1f).fillMaxWidth(),
                above = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            waiting ?: stringResource(R.string.play_ready), style = Type.Title.copy(fontSize = 15.sp),
                            color = Palette.Muted, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                },
                middle = {
                    LauncherButton(
                        stringResource(R.string.action_start_launcher), onStartLauncher, Modifier.width(width), Kind.PRIMARY,
                        enabled = waiting == null, focusRequester = focus, height = 68.dp, style = Type.Play,
                    )
                },
                below = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(10.dp))
                        if (problem != null) {
                            Text(problem, style = Type.Hint, color = Palette.Red, textAlign = TextAlign.Center)
                            if (onTryAgain != null) {
                                Spacer(Modifier.height(4.dp))
                                TextLink(stringResource(R.string.action_try_again), onTryAgain)
                            }
                            ShareLogsLink(Modifier.padding(top = 4.dp))
                        } else if (facts.isNotEmpty()) {
                            Text(facts.map { text(it) }.joinToString("  ·  "), style = Type.Hint, color = Palette.Muted, textAlign = TextAlign.Center)
                        }
                    }
                },
            )
            Rule(Modifier.widthIn(max = 460.dp))
            val link = Type.Hint.copy(fontWeight = FontWeight.Medium)
            Row(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    R.string.play_settings to destinations.onSettings,
                    R.string.play_controls to destinations.onControls,
                    R.string.play_repair to destinations.onRepair,
                    R.string.play_about to destinations.onAbout,
                ).forEach { (label, go) ->
                    LauncherButton(stringResource(label), go, Modifier.weight(1f), Kind.QUIET, height = 48.dp, style = link, padding = 8.dp)
                }
            }
        }
    }
}

/**
 * Play's middle: [middle] (the button) centred in the room, [above] standing on it and [below]
 * hanging from it, so what shows above or below never moves the button while it fits; where it
 * does not, the button gives way only as far as needed.
 */
@Composable
private fun PlayStage(
    modifier: Modifier,
    above: @Composable () -> Unit,
    middle: @Composable () -> Unit,
    below: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(Modifier.layoutId(StageSlot.ABOVE)) { above() }
            Box(Modifier.layoutId(StageSlot.MIDDLE)) { middle() }
            Box(Modifier.layoutId(StageSlot.BELOW)) { below() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        fun slot(slot: StageSlot, maxHeight: Int) =
            measurables.first { it.layoutId == slot }.measure(loose.copy(maxHeight = maxHeight.coerceAtLeast(0)))
        val height = constraints.maxHeight
        val middlePlaced = slot(StageSlot.MIDDLE, height)
        val belowPlaced = slot(StageSlot.BELOW, height - middlePlaced.height)
        val abovePlaced = slot(StageSlot.ABOVE, height - middlePlaced.height - belowPlaced.height)
        val top = Play.stageTop(height, abovePlaced.height, middlePlaced.height, belowPlaced.height)
        layout(constraints.maxWidth, height) {
            val centre = { width: Int -> (constraints.maxWidth - width) / 2 }
            abovePlaced.placeRelative(centre(abovePlaced.width), top - abovePlaced.height)
            middlePlaced.placeRelative(centre(middlePlaced.width), top)
            belowPlaced.placeRelative(centre(belowPlaced.width), top + middlePlaced.height)
        }
    }
}

private enum class StageSlot { ABOVE, MIDDLE, BELOW }
