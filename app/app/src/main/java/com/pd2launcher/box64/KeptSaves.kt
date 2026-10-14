package com.pd2launcher.box64

import com.pd2launcher.box64.files.FileTransfer
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The characters through Start over (Repair): before the app's copy goes, its `Save` folder moves
 * to the app's own storage ([keep]); after the next import each kept entry goes back into the new
 * copy's `Save` when nothing of that name is there, else into `Save/Kept <date>/`, which the game
 * does not read and Android's Files app shows ([restore]). Nothing is ever deleted.
 */
object KeptSaves {
    /** The folder the characters wait in, in the app's files (never in the game folder). */
    fun folder(files: File): File = File(files, "kept-saves")

    /**
     * Moves the entries of [save] into [kept] (a rename: the same file system). An entry whose
     * name [kept] has already (a Start over whose import never finished) is kept beside it under
     * another name. Returns how many entries moved.
     */
    fun keep(save: File, kept: File): Int {
        val entries = save.listFiles() ?: return 0
        if (!kept.isDirectory && !kept.mkdirs()) throw IOException("cannot make ${kept.name}")
        for (entry in entries) {
            val name = if (File(kept, entry.name).exists()) FileTransfer.keepBothName(entry.name) { File(kept, it).exists() } else entry.name
            if (!entry.renameTo(File(kept, name))) throw IOException("cannot keep ${entry.name}")
        }
        return entries.size
    }

    /**
     * Puts the entries of [kept] into [save] (the new copy's), those whose name is taken into
     * `Kept <stamp>` below it, and removes [kept] once it is empty. Returns the names that went
     * into that folder.
     */
    fun restore(kept: File, save: File, stamp: String): List<String> {
        val entries = kept.listFiles() ?: return emptyList()
        if (!save.isDirectory && !save.mkdirs()) throw IOException("cannot make Save")
        val aside = File(save, "Kept $stamp")
        val moved = mutableListOf<String>()
        for (entry in entries.sortedBy { it.name }) {
            val free = !File(save, entry.name).exists()
            if (!free && !aside.isDirectory && !aside.mkdirs()) throw IOException("cannot make ${aside.name}")
            val target = if (free) File(save, entry.name) else File(aside, entry.name)
            if (!entry.renameTo(target)) throw IOException("cannot restore ${entry.name}")
            if (!free) moved += entry.name
        }
        kept.delete()
        return moved
    }

    /** The stamp of a `Kept` folder: "2026-10-13 14-05" (no colon: Windows names). */
    fun stamp(now: Date = Date()): String = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(now)
}
