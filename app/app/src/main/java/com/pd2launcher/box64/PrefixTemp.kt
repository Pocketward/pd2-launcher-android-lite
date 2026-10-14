package com.pd2launcher.box64

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The prefix's Temp folder (C:\users\wine\AppData\Local\Temp), emptied when a session starts
 * ([Session.start]: no Wine process runs then, so no program has a file there open).
 *
 * Programs leave files there when the session ends them. Wine's JPEG decoder (windowscodecs, its
 * libjpeg with the ANSI memory manager) keeps the coefficients of a large progressive JPEG in
 * tmpfile() files (t*.tmp, deleted when closed): the official launcher's picture (800 x 600)
 * takes three of 960,000 bytes, open until .NET releases the decoder. A session that ends the
 * launcher before that (Stop, Close launcher, the app stopped) leaves them: about 2.9 MB at each
 * such start, never removed.
 *
 * [KEEP] stays: the .NET host's folder, where the launcher's bundle extracts WebView2Loader.dll
 * once and finds it again at each start.
 */
object PrefixTemp {
    const val KEEP = ".net"

    fun dir(prefix: File) = File(prefix, "drive_c/users/wine/AppData/Local/Temp")

    /** What [clear] removed: files (a link counts as a file) and their bytes. */
    data class Removed(val files: Int, val bytes: Long)

    /**
     * Removes everything in [prefix]'s Temp folder but [KEEP]. A link is removed, never what it
     * points to. What cannot be removed stays.
     */
    fun clear(prefix: File): Removed {
        val entries = dir(prefix).listFiles() ?: return Removed(0, 0)
        var files = 0
        var bytes = 0L
        fun remove(path: Path) {
            try {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.newDirectoryStream(path).use { it.toList() }.forEach { remove(it) }
                    Files.deleteIfExists(path)
                } else {
                    val size = if (Files.isSymbolicLink(path)) 0L else Files.size(path)
                    if (Files.deleteIfExists(path)) {
                        files++
                        bytes += size
                    }
                }
            } catch (_: IOException) {
                // It stays.
            }
        }
        entries.filter { it.name != KEEP }.forEach { remove(it.toPath()) }
        return Removed(files, bytes)
    }
}
