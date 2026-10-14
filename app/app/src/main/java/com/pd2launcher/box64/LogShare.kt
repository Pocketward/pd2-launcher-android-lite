// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/LogShare.kt).
package com.pd2launcher.box64

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Share logs (Settings → App → Help, and under a failure on Check, Import, Complete and Play): one
 * zip with `device.txt` (what the app knows about this device, its game support files, its copy of
 * the game and the session), the app's own lines from Android's log (`app/logcat.txt`: an app reads
 * only its own) and the logs of the session's processes (`files/logs`, under `session/`: the X
 * screen, the input relay, the sound server, Wine with the official launcher and the game), handed
 * to Android's share sheet. Nothing from the game folder or the Wine prefix goes in, and none holds
 * a CD key: Lite never asks for one. The logs are not redacted: they can name the player's
 * characters (the Files root's lines) and network addresses (the DNS servers), which the row says.
 * The zips are in [folder] (`res/xml/shared_logs.xml`); the next share or save removes those older
 * than [KEEP_MS]. Save logs packs the same zip and copies it where the player chooses on Android's
 * "Save as" screen ([saveTo]): the share sheet lists only apps that take a zip, and many phones have
 * none that keeps it on the device.
 */
object LogShare {
    /** How long a zip stays in [folder]: an app it was shared with may read it only when it sends it. */
    const val KEEP_MS = 60 * 60 * 1000L

    /** The most recent lines of Android's log that [captureAppLog] keeps. */
    const val APP_LOG_LINES = 20_000

    /** How long [captureAppLog] waits for Android's log tool. */
    private const val APP_LOG_TIMEOUT_MS = 15_000L

    /** One file in the zip: [name] in it, read from [file]. */
    data class Entry(val name: String, val file: File)

    /** The zip's folder in the app's cache, the one the share sheet's provider serves. */
    fun folder(cacheDir: File): File = File(cacheDir, "shared-logs")

    /** Where [captureAppLog] writes before the pack: in the cache, but not in [folder], which only holds zips. */
    fun appLogFile(cacheDir: File): File = File(cacheDir, "log-capture/logcat.txt")

    /**
     * The files to pack: the app's own lines ([appLog], when there are any) as `app/logcat.txt`,
     * then every regular file directly in the session's [sessionLogs] as `session/<name>`. Links
     * and folders are left out, so nothing outside these places can be packed through one.
     */
    fun entries(sessionLogs: File, appLog: File?): List<Entry> {
        val app = listOfNotNull(appLog?.takeIf(::isRegularFile)).map { Entry("app/logcat.txt", it) }
        return app + regularFiles(sessionLogs).map { Entry("session/${it.name}", it) }
    }

    /**
     * Writes the zip to [target]: [about] as `device.txt`, then each of [entries], opened without
     * following a link. One that can no longer be opened (a new session truncated it since it was
     * listed) is left out; one that fails part way ends with a line that says so; a log still being
     * written is packed as far as it is when read. Only a failure to write the zip fails it.
     */
    fun pack(target: File, about: String, entries: List<Entry>) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("device.txt"))
            zip.write(about.toByteArray())
            zip.closeEntry()
            for (entry in entries) {
                val input = try {
                    Files.newInputStream(entry.file.toPath(), LinkOption.NOFOLLOW_LINKS)
                } catch (_: IOException) {
                    continue
                }
                input.use {
                    zip.putNextEntry(ZipEntry(entry.name))
                    try {
                        it.copyTo(zip)
                    } catch (error: IOException) {
                        // A read failed; a failed write of the zip throws again here and ends the share.
                        zip.write("\n[PD2 Launcher Lite: the rest of this file could not be read: $error]\n".toByteArray())
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    /** `device.txt`: one "label: value" line for each of [facts]. */
    fun about(facts: List<Pair<String, String>>): String = facts.joinToString("") { (label, value) -> "$label: $value\n" }

    /** The zip's name, from the UTC time [now] (milliseconds since the epoch). */
    fun fileName(now: Long): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "pd2-launcher-lite-logs-${format.format(Date(now))}.zip"
    }

    /**
     * Packs a new zip into [folder] after removing those earlier shares and saves left more than
     * [KEEP_MS] before [now]: [about], the app's own lines ([appLog]) and the session's logs
     * ([sessionLogs]). Returns the zip.
     */
    fun write(cacheDir: File, sessionLogs: File, about: String, appLog: File?, now: Long = System.currentTimeMillis()): File {
        val folder = folder(cacheDir)
        folder.mkdirs()
        folder.listFiles()?.filter { now - it.lastModified() >= KEEP_MS }?.forEach { it.delete() }
        val target = File(folder, fileName(now))
        try {
            pack(target, about, entries(sessionLogs, appLog))
        } catch (error: IOException) {
            target.delete()
            throw error
        }
        return target
    }

    /** The zip the last share or save packed into [folder], if it is still there. */
    fun latest(cacheDir: File): File? =
        regularFiles(folder(cacheDir)).filter { it.name.endsWith(".zip") }.maxByOrNull { it.name }

    /**
     * Save logs' copy of [zip] (from [latest]) into the document the "Save as" screen made, which
     * [open] opens. A copy that cannot start or fails part way [delete]s that document, so no empty
     * or cut file stays where the player chose; then it throws what went wrong.
     */
    fun saveTo(zip: File?, open: () -> OutputStream?, delete: () -> Unit) {
        try {
            if (zip == null) throw IOException("no zip to save")
            val output = open() ?: throw IOException("no stream for the document")
            output.use { zip.inputStream().use { input -> input.copyTo(it) } }
        } catch (error: Exception) {
            runCatching(delete)
            throw error
        }
    }

    /**
     * The app's own lines of Android's log (its main, system and crash buffers, the newest
     * [APP_LOG_LINES]) into [target], replaced: Android's log tool gives an app only its own lines.
     * Returns why it failed, or null.
     */
    fun captureAppLog(target: File): String? {
        target.parentFile?.mkdirs()
        target.delete()
        return try {
            val process = ProcessBuilder(
                "/system/bin/logcat", "-b", "main,system,crash", "-v", "threadtime", "-t", APP_LOG_LINES.toString(),
            ).redirectErrorStream(true).redirectOutput(target).start()
            if (!process.waitFor(APP_LOG_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroy()
                "logcat did not end within $APP_LOG_TIMEOUT_MS ms"
            } else {
                process.exitValue().takeIf { it != 0 }?.let { "logcat exited $it" }
            }
        } catch (error: Exception) {
            error.toString()
        }
    }

    /** Android's share sheet for [zip]; the app the player picks reads it through the URI the share grants. */
    fun chooser(context: Context, zip: File, subject: String, title: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    private fun regularFiles(folder: File): List<File> =
        folder.listFiles()?.filter(::isRegularFile)?.sortedBy { it.name }.orEmpty()

    private fun isRegularFile(file: File): Boolean = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}
