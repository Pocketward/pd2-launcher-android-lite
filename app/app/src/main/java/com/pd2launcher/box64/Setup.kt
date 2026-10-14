package com.pd2launcher.box64

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import com.pd2launcher.box64.files.GameFilesProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The folder Import shows: none picked, being checked, refused, or accepted. */
sealed interface FolderState {
    data object None : FolderState

    data object Checking : FolderState

    /** Refused: [why] in the player's words (no Project Diablo 2, not enough space, unreadable). */
    data class Problem(val why: Line) : FolderState

    /**
     * Accepted: Diablo II with Project Diablo 2, [bytes] in all; [tree] the picked document tree's
     * URI, [name] its path on shared storage when known.
     */
    data class Found(val tree: String, val name: String?, val bytes: Long) : FolderState
}

/** Where Complete's work stands: the copy, then the check of the copied game. */
sealed interface CopyState {
    data object Idle : CopyState

    data class Copying(val done: Long, val total: Long, val stopping: Boolean = false) : CopyState

    data class Stopped(val done: Long, val total: Long) : CopyState

    /** It failed: in the copy ([checking] false) or in the check after it; [why] in the player's words. */
    data class Failed(val why: Line, val checking: Boolean) : CopyState

    /** Done; [keptProblem]: the characters a Start over kept did not all go back into the copy (they wait in the app). */
    data class Done(val total: Long, val keptProblem: Line? = null) : CopyState
}

/**
 * The work of the first run, for the whole process (the screens follow it and come back to it):
 * the device check, the game support files' unpack, the check of the folder the player picked,
 * and the copy into the app with its check. One import at a time ([LiteState]); none while the
 * game's processes run.
 */
object Setup {
    private val _device = MutableStateFlow<DeviceFacts?>(null)
    private val _runtime = MutableStateFlow<RuntimeState>(RuntimeState.Waiting)
    private val _folder = MutableStateFlow<FolderState>(FolderState.None)
    private val _copy = MutableStateFlow<CopyState>(CopyState.Idle)

    /** The device check's facts; null while it runs. */
    val device: StateFlow<DeviceFacts?> = _device.asStateFlow()
    val runtime: StateFlow<RuntimeState> = _runtime.asStateFlow()
    val folder: StateFlow<FolderState> = _folder.asStateFlow()
    val copy: StateFlow<CopyState> = _copy.asStateFlow()

    private val started = AtomicBoolean()
    private val unpacking = AtomicBoolean()
    private val stop = AtomicBoolean()

    /** Once per process: the device check, then, on a device that passed, the unpack when it is needed. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            val facts = DeviceCheck.run()
            Log.i(Native.TAG, "device check: $facts")
            _device.value = facts
            if (DeviceCheck.problems(facts).isEmpty()) unpack(app, again = false)
        }, "setup").start()
    }

    /**
     * The game support files: ready when the unpacked runtime is this APK's and complete, else
     * unpacked (again with [again], for Try again and Repair's Check again).
     */
    fun unpack(context: Context, again: Boolean) {
        if (!unpacking.compareAndSet(false, true)) return
        val rt = Runtime(context.applicationContext)
        Thread({
            try {
                if (!again && rt.installed() && rt.missingFiles().isEmpty()) {
                    _runtime.value = RuntimeState.Ready(rt.version().orEmpty())
                    return@Thread
                }
                val total = rt.archiveSize()
                _runtime.value = RuntimeState.Unpacking(0, total)
                val t0 = System.nanoTime()
                rt.install { done -> _runtime.value = RuntimeState.Unpacking(done, total) }
                Log.i(Native.TAG, "runtime unpacked in ${(System.nanoTime() - t0) / 1_000_000} ms")
                _runtime.value = RuntimeState.Ready(rt.version().orEmpty(), rt.missingFiles())
            } catch (e: Exception) {
                Log.e(Native.TAG, "runtime unpack failed", e)
                _runtime.value = RuntimeState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                unpacking.set(false)
            }
        }, "unpack").start()
    }

    /** Import: checks the folder the player picked ([tree]) off the main thread: Project Diablo 2, its size, the free space. */
    fun checkFolder(context: Context, tree: Uri) {
        val app = context.applicationContext
        _folder.value = FolderState.Checking
        Thread({
            _folder.value = try {
                val copy = GameCopy(app.contentResolver, tree)
                val problem = copy.problem()
                if (problem != null) {
                    FolderState.Problem(problem)
                } else {
                    val bytes = copy.totalBytes()
                    // The copy removes what the app holds of an earlier one first (one that is not
                    // complete, or what a stop left in ".part"): that space counts as free.
                    val game = Runtime(app).game
                    val earlier = GameCopy.bytes(game) + GameCopy.bytes(File(game.path + ".part"))
                    val free = StatFs(Runtime(app).files.path).availableBytes + earlier
                    Log.i(Native.TAG, "folder check: ${bytes shr 20} MB, ${free shr 20} MB free with ${earlier shr 20} MB of an earlier copy")
                    GameCopy.spaceProblem(bytes, free)?.let { FolderState.Problem(it) }
                        ?: FolderState.Found(tree.toString(), GameCopy.folderName(tree.authority, DocumentsContract.getTreeDocumentId(tree)), bytes)
                }
            } catch (e: Exception) {
                Log.e(Native.TAG, "folder check failed", e)
                FolderState.Problem(Line(R.string.import_unreadable, e.message ?: e.javaClass.simpleName))
            }
        }, "folder-check").start()
    }

    /**
     * Complete: copies the accepted folder into the app, then checks the copy (Game.exe, Project
     * Diablo 2 and its launcher). Afterwards: a new copy's settings start over, the characters a
     * Start over kept come back ([KeptSaves]), and the folder it came from is what Storage names.
     */
    fun startCopy(context: Context) {
        val found = _folder.value as? FolderState.Found ?: return
        // Never again over a copy that is done: the characters may be back in it already.
        if (_copy.value is CopyState.Copying || _copy.value is CopyState.Done) return
        val app = context.applicationContext
        // One import at a time, never under a running game; meanwhile the game folder takes no
        // change through Files and Start launcher waits.
        if (LiteState.sessionActive || !LiteState.startImport()) {
            _copy.value = CopyState.Failed(Line(R.string.import_busy), checking = false)
            return
        }
        GameFilesProvider.stateChanged(app)
        stop.set(false)
        _copy.value = CopyState.Copying(0, found.bytes)
        Thread({
            val rt = Runtime(app)
            val t0 = System.nanoTime()
            try {
                GameCopy(app.contentResolver, Uri.parse(found.tree)).copyTo(rt.game, stop::get) { done ->
                    _copy.value = CopyState.Copying(done, found.bytes, stop.get())
                }
                Log.i(Native.TAG, "import copied ${found.bytes shr 20} MB in ${(System.nanoTime() - t0) / 1_000_000} ms")
                // A new copy: the next start writes the graphics profile into its d2gl.json.
                AppliedProfile.set(app, null)
                SavedFrameLimit.set(app, null)
                ImportRecord.imported(app, found.name)
                val missing = Onboarding.missing(rt.game)
                if (missing.isEmpty()) {
                    // Only into a copy that passed: Try again replaces one that did not. The copy
                    // is done from here on, so no Try again can remove characters put back into it;
                    // what did not move waits in kept-saves for the next import.
                    val kept = KeptSaves.folder(rt.files)
                    val keptProblem = if (kept.isDirectory) {
                        try {
                            val aside = KeptSaves.restore(kept, GameCopy.find(rt.game, "Save"), KeptSaves.stamp())
                            Log.i(Native.TAG, "kept characters restored; ${aside.size} beside the copy's own")
                            null
                        } catch (e: Exception) {
                            Log.e(Native.TAG, "kept characters not all restored", e)
                            Line(R.string.complete_kept_not_restored, e.message ?: e.javaClass.simpleName)
                        }
                    } else {
                        null
                    }
                    _copy.value = CopyState.Done(found.bytes, keptProblem)
                } else {
                    _copy.value = CopyState.Failed(Line(R.string.complete_check_missing, missing.joinToString(", ")), checking = true)
                }
            } catch (_: GameCopy.Stopped) {
                val done = (_copy.value as? CopyState.Copying)?.done ?: 0
                _copy.value = CopyState.Stopped(done, found.bytes)
            } catch (e: Exception) {
                Log.e(Native.TAG, "import failed", e)
                _copy.value = CopyState.Failed(Line(R.string.complete_copy_failed, e.message ?: e.javaClass.simpleName), checking = false)
            } finally {
                LiteState.endImport()
                GameFilesProvider.stateChanged(app)
            }
        }, "import").start()
    }

    /** Stop on Complete: the copy ends after the block it copies; Try again starts it over. */
    fun stopCopy() {
        val now = _copy.value as? CopyState.Copying ?: return
        stop.set(true)
        _copy.value = now.copy(stopping = true)
    }

    /** Complete was left for Play: the next import starts fresh. */
    fun finished() {
        _folder.value = FolderState.None
        _copy.value = CopyState.Idle
    }

    /**
     * Repair's Start over, off the main thread: the copy's characters go to [KeptSaves] first,
     * then the copy goes, and the first run starts again. [done] gets null, or why nothing was
     * removed (the game's processes or an import run; the characters could not be moved).
     */
    fun startOver(context: Context, done: (Line?) -> Unit) {
        val app = context.applicationContext
        if (LiteState.sessionActive || !LiteState.startImport()) {
            done(Line(R.string.repair_refused_busy))
            return
        }
        GameFilesProvider.stateChanged(app)
        Thread({
            val outcome = try {
                val rt = Runtime(app)
                val moved = KeptSaves.keep(GameCopy.find(rt.game, "Save"), KeptSaves.folder(rt.files))
                Log.i(Native.TAG, "start over: $moved entries of Save kept")
                deleteTree(rt.game)
                deleteTree(File(rt.game.path + ".part"))
                finished()
                null
            } catch (e: Exception) {
                Log.e(Native.TAG, "start over failed", e)
                Line(R.string.repair_saves_not_kept, e.message ?: e.javaClass.simpleName)
            } finally {
                LiteState.endImport()
                GameFilesProvider.stateChanged(app)
            }
            done(outcome)
        }, "start-over").start()
    }
}
