package com.pd2launcher.box64

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException

/**
 * Web links from Windows programs (the official PD2 launcher's news, Donate) to Android's browser.
 * Wine opens a link through winebrowser, which runs `xdg-open <link>`: [bin] holds an `xdg-open`
 * (an Android shell script) that writes the link into the pipe [pipe]; a thread here reads it and
 * gives each http or https link to [open]. Other schemes (files, intents) are dropped.
 */
class WebLinks(private val tmp: File, private val open: (String) -> Unit) {
    /** The folder put first on the session's PATH. */
    val bin = File(tmp, "bin")

    /** The pipe xdg-open writes to (PD2_OPEN_URL in the session's environment). */
    val pipe = File(tmp, "open-url")

    @Volatile private var reader: Thread? = null
    @Volatile private var descriptor: FileDescriptor? = null

    /** Writes xdg-open, makes the pipe and starts reading it. */
    fun start() {
        bin.mkdirs()
        val script = File(bin, "xdg-open")
        script.writeText(SCRIPT)
        Os.chmod(script.path, 0x1c0) // 0700
        pipe.delete()
        Os.mkfifo(pipe.path, 0x180) // 0600
        // Read and write: the open does not wait for a writer, and the pipe never reads its end.
        val fd = Os.open(pipe.path, OsConstants.O_RDWR or OsConstants.O_CLOEXEC, 0)
        descriptor = fd
        reader = Thread({
            try {
                FileInputStream(fd).bufferedReader().use { lines ->
                    while (true) {
                        val line = lines.readLine() ?: break
                        if (line == STOP) break
                        val link = line.trim().take(MAX_LENGTH)
                        if (allowed(link)) open(link) else Log.w(Native.TAG, "web link dropped (not http or https)")
                    }
                }
            } catch (e: IOException) {
                Log.w(Native.TAG, "web links: $e")
            }
        }, "pd2-web-links").apply { isDaemon = true; start() }
    }

    /** Ends the reader (a stop line through the pipe) and removes the pipe. */
    fun stop() {
        val fd = descriptor ?: return
        descriptor = null
        try {
            Os.write(fd, "$STOP\n".toByteArray(), 0, STOP.length + 1)
        } catch (e: Exception) {
            Log.w(Native.TAG, "web links stop: $e")
        }
        reader?.join(1000)
        reader = null
        pipe.delete()
    }

    companion object {
        /** A line that ends the reader; not a link. */
        private const val STOP = "pd2-lite-stop"

        /** The longest link passed on. */
        private const val MAX_LENGTH = 4096

        /** Only web links: http and https, with a host. */
        fun allowed(link: String): Boolean {
            val lower = link.lowercase()
            return (lower.startsWith("https://") || lower.startsWith("http://")) &&
                link.substringAfter("://").substringBefore('/').isNotEmpty() && link.none { it.isISOControl() }
        }

        /** xdg-open for Wine's winebrowser: the link into the app's pipe. */
        private val SCRIPT = """
            #!/system/bin/sh
            # xdg-open <link>: hands a web link from Wine's winebrowser to PD2 Launcher Lite, which opens it in Android's browser.
            [ -p "${'$'}PD2_OPEN_URL" ] || exit 1
            printf '%s\n' "${'$'}1" > "${'$'}PD2_OPEN_URL"
        """.trimIndent() + "\n"
    }
}
