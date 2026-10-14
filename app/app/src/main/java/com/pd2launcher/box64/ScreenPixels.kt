// Ported from PD2 Launcher for Android (app/src/main/java/com/pd2launcher/app/FrameCopy.kt and Framebuffer.kt).
package com.pd2launcher.box64

import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The X screen's framebuffer as Xvfb lays it out (Lite: in the session's memfd, [Session.memfd]):
 * an XWD header (25 32-bit fields in Xvfb's byte order written as big-endian, then the window
 * name), the colormap (ncolors entries of 12 bytes), then the pixels.
 */
data class XwdHeader(val width: Int, val height: Int, val bitsPerPixel: Int, val bytesPerLine: Int, val pixelOffset: Long) {
    /**
     * A 32-bit screen of sizes the copy can take: each side 1 to [MAX_SIDE], lines at least as long
     * as the width's pixels and whole pixels long. The header is in memory a guest can write;
     * pd2lite.c checks the same.
     */
    val plausible: Boolean
        get() = bitsPerPixel == 32 && width in 1..MAX_SIDE && height in 1..MAX_SIDE &&
            bytesPerLine > 0 && bytesPerLine % 4 == 0 && bytesPerLine.toLong() >= width * 4L

    companion object {
        const val SIZE = 100
        /** The largest X screen side taken. */
        const val MAX_SIDE = 16384
        private const val FILE_VERSION = 7
        private const val COLOR_SIZE = 12

        /** The header in [bytes] (at least [SIZE]), or null when it is not an XWD version 7 header. */
        fun parse(bytes: ByteArray): XwdHeader? {
            if (bytes.size < SIZE) return null
            val order = listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)
                .firstOrNull { ByteBuffer.wrap(bytes).order(it).getInt(4) == FILE_VERSION } ?: return null
            val b = ByteBuffer.wrap(bytes).order(order)
            fun field(index: Int) = b.getInt(index * 4).toLong() and 0xFFFFFFFFL
            val headerSize = field(0)
            val ncolors = field(19)
            return XwdHeader(
                width = field(4).toInt(),
                height = field(5).toInt(),
                bitsPerPixel = field(11).toInt(),
                bytesPerLine = field(12).toInt(),
                pixelOffset = headerSize + ncolors * COLOR_SIZE,
            )
        }
    }
}

/**
 * The X screen's pixels mapped read-only: Xvfb's [header] and the [pixels] it describes, from the
 * first pixel, `bytesPerLine` x `height` bytes of little-endian BGRX words (0xXXRRGGBB). The
 * mapping shares Xvfb's memory, so it shows every later frame, and it stays valid after the
 * framebuffer is closed.
 */
class ScreenPixels(val header: XwdHeader, val pixels: ByteBuffer) {
    /** The pixel at ([x], [y]). */
    fun pixel(x: Int, y: Int): Int = pixels.getInt(y * header.bytesPerLine + x * 4)

    companion object {
        /** [channel]'s pixels, or null before its header is written or while it is shorter than the header says. */
        fun map(channel: FileChannel): ScreenPixels? {
            val header = readHeader(channel) ?: return null
            val length = header.bytesPerLine.toLong() * header.height
            if (channel.size() < header.pixelOffset + length) return null
            val pixels = channel.map(FileChannel.MapMode.READ_ONLY, header.pixelOffset, length).order(ByteOrder.LITTLE_ENDIAN)
            return ScreenPixels(header, pixels)
        }

        /** The header at the start of [channel] (a positional read), when it is a 32-bit screen's of sizes taken ([XwdHeader.plausible]). */
        fun readHeader(channel: FileChannel): XwdHeader? {
            val bytes = ByteBuffer.allocate(XwdHeader.SIZE)
            while (bytes.hasRemaining()) {
                if (channel.read(bytes, bytes.position().toLong()) < 0) break
            }
            return XwdHeader.parse(bytes.array().copyOf(bytes.position()))?.takeIf { it.plausible }
        }

        /**
         * Lite: the pixels of the framebuffer memfd [descriptor] (the session's, which it keeps),
         * through a duplicate that the mapping outlives; null before Xvfb has written them, or once
         * the session closed it.
         */
        fun ofMemfd(descriptor: Int): ScreenPixels? = try {
            if (descriptor < 0) {
                null
            } else {
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.fromFd(descriptor)).channel.use { map(it) }
            }
        } catch (_: Exception) {
            null
        }
    }
}
