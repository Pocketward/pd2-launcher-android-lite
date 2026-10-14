package com.pd2launcher.box64

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Import: copies the Diablo II folder the player picks (Android's folder picker, a document tree)
 * into the app's own storage, read only on the source side. Then the app's copy gets the two
 * settings the X screen needs; no game file (DLL, MPQ, executable) is ever changed.
 */
class GameCopy(private val resolver: ContentResolver, private val tree: Uri) {
    private data class Doc(val id: String, val name: String, val dir: Boolean, val size: Long)

    private fun children(parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        val out = mutableListOf<Doc>()
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Doc(c.getString(0), c.getString(1), c.getString(2) == Document.MIME_TYPE_DIR,
                    if (c.isNull(3)) 0 else c.getLong(3))
            }
        }
        return out
    }

    private val rootId = DocumentsContract.getTreeDocumentId(tree)

    /**
     * Null when the folder is Diablo II with Project Diablo 2 and its official launcher installed,
     * else why it is refused. Lite only imports: Project Diablo 2 must already be in the folder
     * (its own launcher keeps it up to date afterwards).
     */
    fun problem(): Line? {
        val top = children(rootId)
        if (top.none { !it.dir && it.name.equals("Game.exe", true) }) return Line(R.string.import_not_diablo)
        val pd2 = top.firstOrNull { it.dir && it.name.equals("ProjectD2", true) } ?: return Line(R.string.import_no_pd2)
        val inner = children(pd2.id)
        if (inner.none { !it.dir && it.name.equals("ProjectDiablo.dll", true) }) return Line(R.string.import_pd2_incomplete)
        if (inner.none { !it.dir && it.name.equals("PD2Launcher.exe", true) }) return Line(R.string.import_no_launcher)
        return null
    }

    fun totalBytes(): Long {
        fun walk(id: String): Long = children(id).sumOf { if (it.dir) walk(it.id) else it.size }
        return walk(rootId)
    }

    /** [copyTo] ended early: the player stopped it. What was copied stays in the ".part" folder until the next copy. */
    class Stopped : IOException("stopped")

    /**
     * Copies the tree into [dest] (through dest + ".part", renamed when complete); [progress] gets
     * the bytes copied so far. [stop] is asked between blocks: true ends the copy with [Stopped].
     * What [dest] and the ".part" folder hold goes first, so the copy needs only its own space
     * ([Setup.checkFolder] counts both as free): the first run imports only over a copy that is
     * not complete, and Start over moves the characters out before it removes a copy.
     */
    fun copyTo(dest: File, stop: () -> Boolean = { false }, progress: (Long) -> Unit) {
        val part = File(dest.path + ".part")
        deleteTree(part)
        deleteTree(dest)
        var copied = 0L
        var last = 0L
        val buffer = ByteArray(1 shl 20)
        fun walk(id: String, dir: File) {
            dir.mkdirs()
            for (doc in children(id)) {
                if (doc.name.contains('/') || doc.name == ".." || doc.name == ".") throw IOException("bad name ${doc.name}")
                val target = File(dir, doc.name)
                if (doc.dir) {
                    walk(doc.id, target)
                    continue
                }
                resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc.id))!!.use { input ->
                    FileOutputStream(target).use { out ->
                        while (true) {
                            if (stop()) throw Stopped()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            copied += n
                            if (copied - last > 16_000_000) {
                                last = copied
                                progress(copied)
                            }
                        }
                    }
                }
            }
        }
        walk(rootId, part)
        dest.parentFile?.mkdirs()
        if (!part.renameTo(dest)) throw IOException("rename failed")
        progress(copied)
    }

    /** What [applySettings] did to `d2gl.json`: kept its other values, wrote a new one, or left one that is not JSON. */
    enum class D2glFile { EDITED, CREATED, UNREADABLE, NONE }

    /**
     * What [applySettings] did: the player's frame limit values to keep, the graphics profile
     * it wrote (null: none) and what became of `d2gl.json`.
     */
    data class Applied(val keep: D2glConfig.FpsValues?, val profile: GraphicsProfile?, val d2gl: D2glFile)

    companion object {
        /** Room kept free beyond the copy itself: the game's settings, logs and the official launcher's updates. */
        const val SPARE_BYTES = 200L shl 20

        /**
         * Why a copy of [needed] bytes does not fit in [free] bytes (with [SPARE_BYTES] to spare),
         * in MB as the player reads them; null when it fits.
         */
        fun spaceProblem(needed: Long, free: Long): Line? =
            if (free < needed + SPARE_BYTES) Line(R.string.import_no_space, (needed + SPARE_BYTES) shr 20, free shr 20) else null

        /** Android's shared storage provider, whose tree IDs are paths ("primary:Download/Diablo II"). */
        private const val SHARED_STORAGE = "com.android.externalstorage.documents"

        /** A picked folder as the player finds it: its path on shared storage ("Download/Diablo II"); null from another provider. */
        fun folderName(authority: String?, treeDocumentId: String): String? =
            treeDocumentId.substringAfter(':', "").trim('/').takeIf { authority == SHARED_STORAGE && it.isNotEmpty() }

        /**
         * Whether the GPU is an older or entry-level Adreno, where Automatic picks 480 lines and
         * D2GL's Performance profile: before the 6xx, a 6xx below the 630, a 7xx below the 710.
         * Read from KGSL's sysfs model ("Adreno610v1"); false when unknown.
         */
        fun smallAdreno(): Boolean {
            val n = adrenoModel() ?: return false
            return n < 600 || n in 600..629 || n in 700..709
        }

        /** The Adreno model number from KGSL's sysfs ("Adreno610v1" → 610), or null. */
        fun adrenoModel(): Int? {
            val model = try { File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText() } catch (_: Exception) { return null }
            return Regex("Adreno(\\d+)").find(model)?.groupValues?.get(1)?.toIntOrNull()
        }

        /**
         * Before each Start launcher: the two settings files of the app's copy (the only files of
         * the game it changes). `d2gl.json` gets the X screen of [width] x [height] ([D2glConfig]),
         * and the graphics [profile] when it is not the [lastProfile] written; a copy without the
         * file gets the main app's first-launch configuration ([D2glConfig.firstLaunch]) with the
         * [profile]. `ddraw.ini` (cnc-ddraw, when the official launcher's Options pick DirectDraw)
         * gets [DdrawIni]'s values. A file is written only when its content changes; one D2GL
         * cannot read either is left to D2GL.
         */
        fun applySettings(
            game: File,
            width: Int,
            height: Int,
            profile: GraphicsProfile,
            lastProfile: GraphicsProfile?,
            limit: Boolean = false,
            savedLimit: D2glConfig.FpsValues? = null,
            ddrawRenderer: String = "gdi",
        ): Applied {
            var keep: D2glConfig.FpsValues? = savedLimit
            var written: GraphicsProfile? = null
            var file = D2glFile.NONE
            val project = find(game, "ProjectD2")
            val d2gl = settingsFile(project, "d2gl.json")
            if (d2gl.isFile) {
                val before = d2gl.readText().removePrefix("\uFEFF")
                val write = profile.takeIf { it != lastProfile }
                try {
                    val json = D2glConfig.apply(JSONObject(before), width, height, write)
                    keep = D2glConfig.limitFrames(json, limit, savedLimit)
                    val after = json.toString(4)
                    if (after != before) d2gl.writeText(after)
                    written = write
                    file = D2glFile.EDITED
                } catch (_: org.json.JSONException) {
                    // D2GL reads its backup or starts from its defaults.
                    file = D2glFile.UNREADABLE
                }
            } else if (project.isDirectory && !d2gl.exists()) {
                val json = D2glConfig.firstLaunch(width, height, profile)
                keep = D2glConfig.limitFrames(json, limit, null)
                d2gl.writeText(json.toString(4))
                written = profile
                file = D2glFile.CREATED
            }
            val ini = settingsFile(project, "ddraw.ini")
            if (ini.isFile) {
                val before = ini.readText()
                val after = DdrawIni.apply(before, width, height, ddrawRenderer)
                if (after != before) ini.writeText(after)
            }
            return Applied(keep, written, file)
        }

        /**
         * [name] in [project] as Wine opens it: that spelling, else one that differs only in case
         * (Wine finds it for [name]); [name] itself when there is neither.
         */
        fun settingsFile(project: File, name: String): File {
            val exact = File(project, name)
            if (exact.exists()) return exact
            return project.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: exact
        }

        /**
         * [path] ("ProjectD2/PD2Launcher.exe") below [root] as Wine opens it: each name in that
         * spelling, else in one that differs only in case ([settingsFile]); the spelling of [path]
         * from the first name not found on.
         */
        fun find(root: File, path: String): File =
            path.split('/').filter { it.isNotEmpty() }.fold(root) { dir, name -> settingsFile(dir, name) }

        /** The size of [folder] and everything below it, never following a link; 0 when it is not there. */
        fun bytes(folder: File): Long {
            val st = try { android.system.Os.lstat(folder.path) } catch (_: Exception) { return 0 }
            if (!android.system.OsConstants.S_ISDIR(st.st_mode)) return if (android.system.OsConstants.S_ISREG(st.st_mode)) st.st_size else 0
            return folder.list()?.sumOf { bytes(File(folder, it)) } ?: 0
        }
    }
}
