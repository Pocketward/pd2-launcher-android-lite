package com.pd2launcher.box64

import android.content.Context
import android.system.Os
import com.github.luben.zstd.ZstdInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The runtime in the APK (assets/runtime.tar.zst, made by build/pack.py): the native
 * ARM64 userland part (userland/current), the reduced Wine (wine), the Wine prefix template
 * (prefix) and the official PD2 launcher's x86 .NET runtime (dotnet, which the prefix's
 * C:\Program Files (x86)\dotnet links to), unpacked once into the app's files folder. Its first entry, relocate.list,
 * names the files that hold the build's data-folder placeholder, which the unpack rewrites to
 * this app's real data folder (binary files: padded with '/', so their offsets stay; text files:
 * replaced), as the main app's UserlandInstaller does.
 */
class Runtime(private val context: Context) {
    val data: File = File(context.applicationInfo.dataDir).canonicalFile
    val files = File(data, "files")
    val userland = File(files, "userland/current")
    val home = File(files, "home")
    val wine = File(files, "wine")
    val prefix = File(files, "prefix")
    val dotnet = File(files, "dotnet")
    val game = File(files, "game/Diablo II")
    val logs = File(files, "logs")
    val tmp = File(files, "tmp")
    private val stamp = File(files, "runtime.version")

    private fun assetVersion(): String =
        context.assets.open("runtime.version").use { it.readBytes().toString(Charsets.UTF_8).trim() }

    fun installed(): Boolean = stamp.isFile && stamp.readText().trim() == assetVersion()

    /** The unpacked runtime's build (its archive's hash), for About; null before it is unpacked. */
    fun version(): String? = if (stamp.isFile) stamp.readText().trim().take(12) else null

    /**
     * The files every session starts with, relative to the files folder: what Check's "Can run
     * the game" looks for once the runtime is unpacked.
     */
    private val needed = listOf(
        "userland/current/usr/bin/Xvfb",
        "userland/current/usr/bin/x-input-relay",
        "wine/lib/wine/aarch64-unix/wine",
        "wine/bin/wineserver",
        "prefix/system.reg",
        "dotnet/dotnet.exe",
    )

    /** Of the files a session needs, those not there (relative paths). */
    fun missingFiles(): List<String> = needed.filterNot { File(files, it).isFile }

    /** The unpacked archive's size in bytes, from its zstd frame header; 0 when it does not say. */
    fun archiveSize(): Long = try {
        context.assets.open("runtime.tar.zst").use { input ->
            val header = ByteArray(18)
            var n = 0
            while (n < header.size) {
                val r = input.read(header, n, header.size - n)
                if (r < 0) break
                n += r
            }
            frameContentSize(header.copyOf(n))
        }
    } catch (_: IOException) {
        0L
    }

    /** Unpacks the runtime (replacing an older one); [progress] gets the bytes written so far. */
    fun install(progress: (Long) -> Unit) {
        val version = assetVersion()
        stamp.delete()
        for (d in listOf(userland.parentFile!!, wine, prefix, dotnet)) deleteTree(d)
        files.mkdirs()
        var written = 0L
        var lastReport = 0L
        context.assets.open("runtime.tar.zst").use { raw ->
            val input = BufferedInputStream(ZstdInputStream(BufferedInputStream(raw, 1 shl 20)), 1 shl 20)
            var relocate: Map<String, String> = emptyMap()
            Tar(input).forEach { entry, body ->
                val target = File(files, entry.name)
                when (entry.type) {
                    Tar.Type.DIR -> target.mkdirs()
                    Tar.Type.SYMLINK -> {
                        target.parentFile?.mkdirs()
                        target.delete()
                        Os.symlink(entry.link, target.path)
                    }
                    Tar.Type.FILE -> {
                        target.parentFile?.mkdirs()
                        if (entry.name == "relocate.list") {
                            val text = body.readBytes().toString(Charsets.UTF_8)
                            relocate = text.lines().filter { it.isNotBlank() }
                                .associate { line -> line.substringAfter(' ').trim() to line.substringBefore(' ') }
                            target.writeText(text)
                        } else {
                            val kind = relocate[entry.name]
                            FileOutputStream(target).use { out ->
                                if (kind != null) out.write(relocated(body.readBytes(), kind, data.path))
                                else body.copyTo(out, 1 shl 20)
                            }
                        }
                        Os.chmod(target.path, entry.mode and 0x1ff)
                        written += entry.size
                        if (written - lastReport > 8_000_000) {
                            lastReport = written
                            progress(written)
                        }
                    }
                }
            }
        }
        for (d in listOf(logs, tmp, File(tmp, "xkb"), File(tmp, "run"), home)) d.mkdirs()
        stamp.writeText(version)
    }

    companion object {
        /**
         * A zstd frame's content size from its first bytes ([header]: the magic number, the frame
         * header descriptor, the window descriptor when there is one, then the size field); 0
         * when the frame does not record it.
         */
        fun frameContentSize(header: ByteArray): Long {
            if (header.size < 6 || header[0] != 0x28.toByte() || header[1] != 0xB5.toByte() ||
                header[2] != 0x2F.toByte() || header[3] != 0xFD.toByte()
            ) {
                return 0
            }
            val descriptor = header[4].toInt() and 0xff
            val singleSegment = descriptor and 0x20 != 0
            val dictionaryBytes = when (descriptor and 3) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
            val sizeBytes = when (descriptor ushr 6) { 0 -> if (singleSegment) 1 else 0; 1 -> 2; 2 -> 4; else -> 8 }
            val at = 5 + (if (singleSegment) 0 else 1) + dictionaryBytes
            if (sizeBytes == 0 || header.size < at + sizeBytes) return 0
            var size = 0L
            for (i in sizeBytes - 1 downTo 0) size = (size shl 8) or (header[at + i].toLong() and 0xff)
            return if (sizeBytes == 2) size + 256 else size
        }

        /** config.sh's DATA_PLACEHOLDER. */
        val PLACEHOLDER: ByteArray = ("/pd2launcher-data-placeholder-" + "0".repeat(170)).toByteArray()

        /**
         * [bytes] with every copy of the placeholder replaced by [dataDir]: in a "binary" file padded
         * with '/' to the placeholder's length (offsets stay; repeated slashes name the same path),
         * in a "text" file as is.
         */
        fun relocated(bytes: ByteArray, kind: String, dataDir: String): ByteArray {
            val real = dataDir.toByteArray()
            require(real.size <= PLACEHOLDER.size) { "data folder longer than the placeholder" }
            val out = ByteArrayOutputStream(bytes.size)
            var i = 0
            var from = 0
            while (true) {
                i = indexOf(bytes, PLACEHOLDER, i)
                if (i < 0) break
                out.write(bytes, from, i - from)
                out.write(real)
                if (kind == "binary") repeat(PLACEHOLDER.size - real.size) { out.write('/'.code) }
                i += PLACEHOLDER.size
                from = i
            }
            out.write(bytes, from, bytes.size - from)
            return out.toByteArray()
        }

        private fun indexOf(hay: ByteArray, needle: ByteArray, start: Int): Int {
            val first = needle[0]
            var i = start
            val last = hay.size - needle.size
            outer@ while (i <= last) {
                if (hay[i] != first) { i++; continue }
                for (j in 1 until needle.size) if (hay[i + j] != needle[j]) { i++; continue@outer }
                return i
            }
            return -1
        }
    }
}

/** A reader of the tar files Python's tarfile writes (GNU format: long names in 'L'/'K' entries). */
class Tar(private val input: InputStream) {
    enum class Type { FILE, DIR, SYMLINK }
    data class Entry(val name: String, val type: Type, val mode: Int, val size: Long, val link: String)

    private val header = ByteArray(512)

    fun forEach(action: (Entry, InputStream) -> Unit) {
        var longName: String? = null
        var longLink: String? = null
        while (true) {
            if (!readFully(header)) return
            if (header.all { it == 0.toByte() }) return
            val size = octal(124, 12)
            val type = header[156].toInt().toChar()
            val body = Bounded(input, size)
            when (type) {
                'L' -> longName = body.readBytes().toString(Charsets.UTF_8).trimEnd('\u0000')
                'K' -> longLink = body.readBytes().toString(Charsets.UTF_8).trimEnd('\u0000')
                else -> {
                    var name = longName ?: string(0, 100).let { n ->
                        val prefix = string(345, 155)
                        if (header[257] == 'u'.code.toByte() && header[262] == 0.toByte() && prefix.isNotEmpty()) "$prefix/$n" else n
                    }
                    name = name.trimEnd('/')
                    val link = longLink ?: string(157, 100)
                    longName = null
                    longLink = null
                    val kind = when (type) {
                        '0', '\u0000', '7' -> Type.FILE
                        '5' -> Type.DIR
                        '2' -> Type.SYMLINK
                        else -> throw IOException("tar entry type $type ($name) not handled")
                    }
                    if (name.startsWith("/") || name.split('/').contains("..")) throw IOException("bad tar path $name")
                    action(Entry(name, kind, octal(100, 8).toInt(), size, link), body)
                }
            }
            body.skipRest()
            val pad = (512 - size % 512) % 512
            skipFully(pad)
        }
    }

    private fun string(off: Int, len: Int): String {
        var end = off
        while (end < off + len && header[end] != 0.toByte()) end++
        return String(header, off, end - off, Charsets.UTF_8)
    }

    private fun octal(off: Int, len: Int): Long {
        if (header[off].toInt() and 0x80 != 0) { // base-256
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (header[i].toLong() and 0xff)
            return v
        }
        val s = string(off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun readFully(b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) {
            val r = input.read(b, n, b.size - n)
            if (r < 0) return n == 0 && false
            n += r
        }
        return true
    }

    private fun skipFully(count: Long) {
        var left = count
        val buf = ByteArray(512)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(left, 512).toInt())
            if (r < 0) throw IOException("tar truncated")
            left -= r
        }
    }

    /** The entry's body: at most [size] bytes of the archive. */
    private class Bounded(private val input: InputStream, size: Long) : InputStream() {
        private var left = size
        override fun read(): Int {
            if (left <= 0) return -1
            val r = input.read()
            if (r >= 0) left--
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
        fun skipRest() {
            val buf = ByteArray(65536)
            while (left > 0) {
                val r = read(buf, 0, buf.size)
                if (r < 0) throw IOException("tar truncated")
            }
        }
    }
}

/**
 * Deletes [file] and, for a real folder, everything below it, never following a symbolic link:
 * the prefix links drive G: to the game copy and Z: to "/" (File.deleteRecursively follows them).
 */
fun deleteTree(file: File) {
    val st = try { Os.lstat(file.path) } catch (_: Exception) { return }
    if (android.system.OsConstants.S_ISDIR(st.st_mode)) {
        file.list()?.forEach { deleteTree(File(file, it)) }
    }
    file.delete()
}

