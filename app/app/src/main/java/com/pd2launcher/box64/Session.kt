package com.pd2launcher.box64

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.pd2launcher.box64.input.GamepadFile
import com.pd2launcher.box64.input.RelayEvent
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One game session: Xvfb (its framebuffer in a memfd), x-input-relay (touch, keys and mouse mode
 * into the X screen through XTEST), the gamepad file (the controller for the game's XInput
 * bridge, `xinput1_4.dll` in the prefix) and Wine's loader with ProjectD2\Game.exe. Every
 * process runs as the app's user from the unpacked runtime; [stop] ends them all.
 */
class Session(
    private val rt: Runtime,
    val width: Int,
    val height: Int,
    private val renderer: Renderer,
    private val program: Program = Program.GAME,
    /** The game opens on the online screen (-skiptobnet). */
    private val skipToBnet: Boolean = true,
    /** The game keeps its sound when its window is not in front (-sndbkg). */
    private val soundInBackground: Boolean = false,
) {

    /**
     * What the session starts in ProjectD2: the game, or the official PD2 launcher (a .NET 6 WPF
     * program, which starts the game itself with its Play button, in the same environment).
     */
    enum class Program { GAME, LAUNCHER }

    var memfd = -1
        private set
    private var relay: FileOutputStream? = null
    private var relayAppEnd: ParcelFileDescriptor? = null

    /** The pad the game's XInput bridge reads (input/Gamepad.kt); null until [start]. */
    @Volatile var gamepad: GamepadFile? = null
        private set
    private val gamepadPath get() = File(rt.files, "gamepad.bin")
    val counter = File(rt.tmp, "present-counter")
    @Volatile var startedAt = 0L
        private set

    private val ul get() = rt.userland.path

    /** A web link a Windows program opened (http or https): the game view hands it to Android. */
    @Volatile var onWebLink: ((String) -> Unit)? = null
    private val webLinks = WebLinks(rt.tmp) { link -> onWebLink?.invoke(link) }

    private fun baseEnv(): List<String> = listOf(
        "HOME=${rt.home}",
        "USER=wine",
        "LANG=C.UTF-8",
        // Our xdg-open first (WebLinks), then Android's shell tools.
        "PATH=${webLinks.bin}:/system/bin",
        "PD2_OPEN_URL=${webLinks.pipe}",
        "TMPDIR=${rt.tmp}",
        "XDG_RUNTIME_DIR=${rt.tmp}/run",
        "DISPLAY=$DISPLAY",
        "XKB_CONFIG_ROOT=$ul/usr/share/X11/xkb",
        "XLOCALEDIR=$ul/usr/share/X11/locale",
        "FONTCONFIG_FILE=$ul/etc/fonts/fonts.conf",
    )

    /** What Wine, its x86 emulation and the ARM64 Mesa under them need. */
    private fun gameEnv(): List<String> = withExtra(baseEnv() + listOf(
        "WINEPREFIX=${rt.prefix}",
        "WINEDEBUG=-all",
        // xinput1_4=n: the prefix's XInput bridge (bridge/xinput-bridge.c), not Wine's own.
        "WINEDLLOVERRIDES=mscoree=;mshtml=;winemenubuilder.exe=;winedbg.exe=d;ddraw=n;nsiproxy.sys=d;xinput1_4=n",
        "PD2_GAMEPAD_FILE=$gamepadPath",
        "BOX64_LOG=0",
        "BOX64_NOBANNER=1",
        "__EGL_VENDOR_LIBRARY_DIRS=$ul/usr/share/glvnd/egl_vendor.d",
        "__GLX_VENDOR_LIBRARY_NAME=mesa",
        "MESA_LOADER_DRIVER_OVERRIDE=zink",
        "LIBGL_DRI3_DISABLE=1",
        "LIBGL_KOPPER_DRI2=1",
        "MESA_VK_WSI_DEBUG=sw",
        "PD2_PRESENT_COUNTER=$counter",
        "PULSE_RUNTIME_PATH=$pulseDir",
        "PULSE_SERVER=unix:$pulseDir/native",
    ) + VulkanDriver.environment(ul, HostGpu.panvk, GameCopy.adrenoModel()) + programEnv())

    /** [env] with the extra variables of a test start, which replace a variable of the same name. */
    private fun withExtra(env: List<String>): List<String> {
        val names = extraEnv.map { it.substringBefore('=') }.toSet()
        return env.filter { it.substringBefore('=') !in names } + extraEnv
    }

    /** What the session's program adds to the environment ([LAUNCHER_ENV] for the launcher). */
    private fun programEnv(): List<String> = when (program) {
        Program.GAME -> emptyList()
        Program.LAUNCHER -> LAUNCHER_ENV
    }

    private val pulseDir get() = File(rt.tmp, "run/pulse")
    private val sinkFifo get() = File(rt.tmp, "pulse-sink")
    @Volatile private var audioRunning = false
    private var audioThread: Thread? = null

    /**
     * PulseAudio for Wine's winepulse (the native libpulse): a Unix socket, and a pipe
     * sink at 22.05 kHz (the main app's rate: Wine's mixer costs a quarter of a big core less than
     * at 48 kHz) whose samples an AudioTrack plays.
     */
    private fun startSound() {
        pulseDir.mkdirs()
        File(pulseDir, "native").delete()
        File(rt.home, ".config/pulse").mkdirs()
        sinkFifo.delete()
        Os.mkfifo(sinkFifo.path, 384) // 0600
        val script = File(rt.tmp, "pulse.pa")
        script.writeText("load-module module-native-protocol-unix auth-anonymous=1\n" +
            "load-module module-pipe-sink file=$sinkFifo sink_name=pipe format=s16le rate=$RATE channels=2\n" +
            "set-default-sink pipe\n")
        spawn("pulseaudio", listOf("$ul/usr/bin/pulseaudio", "-n", "-F", script.path, "--daemonize=no",
            "--use-pid-file=no", "--exit-idle-time=-1", "--disable-shm=yes", "-p", "$ul/usr/lib/pulse-17.0+dfsg1/modules"),
            baseEnv() + listOf("PULSE_RUNTIME_PATH=$pulseDir", "PULSE_STATE_PATH=${rt.home}/.config/pulse"))
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!File(pulseDir, "native").exists() && System.nanoTime() < deadline) Thread.sleep(20)
        if (!File(pulseDir, "native").exists()) { Log.w(Native.TAG, "PulseAudio did not start: no sound"); return }
        audioRunning = true
        audioThread = Thread({
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(min, RATE * 4 / 25)) // at least 40 ms
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            val buf = ByteArray(4096)
            try {
                FileInputStream(sinkFifo).use { input ->
                    while (audioRunning) {
                        val n = input.read(buf)
                        if (n < 0) break
                        track.write(buf, 0, n and 3.inv())
                    }
                }
            } catch (e: IOException) {
                Log.w(Native.TAG, "sound: $e")
            } finally {
                track.release()
            }
        }, "game-audio").apply { start() }
    }

    /**
     * The Windows command line: Game.exe with the renderer's argument and the start options (as
     * the main app's engine and PD2's launcher pass them), or for a test the program
     * that PD2_EXE names in the extra variables (with PD2_ARGS), for example a GL check.
     */
    private fun gameArguments(): List<String> {
        val exe = extraEnv.firstOrNull { it.startsWith("PD2_EXE=") }?.substringAfter('=')
            ?: return if (program == Program.LAUNCHER) {
                listOf("PD2Launcher.exe")
            } else {
                listOf("Game.exe", renderer.argument) + (if (skipToBnet) listOf("-skiptobnet") else emptyList()) +
                    (if (soundInBackground) listOf("-sndbkg") else emptyList())
            }
        val args = extraEnv.firstOrNull { it.startsWith("PD2_ARGS=") }?.substringAfter('=')?.split(' ')?.filter { it.isNotEmpty() }
        return listOf(exe) + (args ?: emptyList())
    }

    /** Extra variables for a test start (adb: --es env "A=1 B=2"). */
    var extraEnv: List<String> = emptyList()

    private fun spawn(name: String, argv: List<String>, env: List<String>, stdin: Int = -1, fd3: Int = -1): Int {
        val log = ParcelFileDescriptor.open(File(rt.logs, "$name.log"),
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        val nullIn = if (stdin < 0) ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY) else null
        try {
            val pid = Native.spawn(argv[0], argv.toTypedArray(), env.toTypedArray(), nullIn?.fd ?: stdin, log.fd, fd3)
            if (pid <= 0) throw IOException("$name did not start: errno ${-pid}")
            Native.running[pid] = name
            Log.i(Native.TAG, "started $name ($pid): ${argv.joinToString(" ")}")
            return pid
        } finally {
            log.close()
            nullIn?.close()
        }
    }

    /** Starts everything; returns once the game process runs. Call off the main thread. */
    fun start() {
        startedAt = System.nanoTime()
        killAll()
        rt.logs.mkdirs()
        File(rt.tmp, "xkb").mkdirs()
        File(rt.tmp, "run").mkdirs()
        try {
            webLinks.start()
        } catch (e: Exception) {
            Log.w(Native.TAG, "web links: $e")
        }
        // Mesa's present counter (userland Mesa patch 0002): one page; the game view never paces.
        FileOutputStream(counter).use { it.write(ByteArray(4096)) }
        memfd = Native.memfd("xvfb-framebuffer").also { if (it < 0) throw IOException("memfd: errno ${-it}") }
        spawn("xvfb", listOf("$ul/usr/bin/Xvfb", DISPLAY, "-screen", "0", "${width}x${height}x24", "-fbfd", "3",
            "-extension", "MIT-SHM", "-nolock", "-nolisten", "tcp", "-xkbdir", "$ul/usr/share/X11/xkb"),
            baseEnv(), fd3 = memfd)
        waitForX()
        checkStopped()
        val pair = Native.socketPair() ?: throw IOException("socketpair failed")
        relayAppEnd = ParcelFileDescriptor.adoptFd(pair[0])
        relay = FileOutputStream(relayAppEnd!!.fileDescriptor)
        val child = ParcelFileDescriptor.adoptFd(pair[1])
        try {
            spawn("relay", listOf("$ul/usr/bin/x-input-relay"), baseEnv(), stdin = child.fd)
        } finally {
            child.close()
        }
        try {
            startSound()
        } catch (e: Exception) {
            Log.w(Native.TAG, "no sound: $e")
        }
        checkStopped()
        // A connected, neutral pad before the game looks for one. Without it the game still
        // runs (touch, keys).
        val pad = try {
            GamepadFile.open(gamepadPath)
        } catch (e: Exception) {
            Log.w(Native.TAG, "no gamepad file: $e")
            null
        }
        synchronized(this) {
            if (stopped) {
                pad?.close()
                throw IOException("stopped during the start")
            }
            gamepad = pad
        }
        startGuard()
        val dir = File(rt.game, "ProjectD2").path
        checkStopped()
        spawn("game", listOf("/system/bin/sh", "-c", "cd \"\$1\" && shift && exec \"\$@\"", "sh", dir) +
            wineCommand + gameArguments(), gameEnv())
        // The official launcher's window kept on the X screen (helpers/keep-on-screen.c) by a
        // program in the same Wine, started a little later so that the two do not both start
        // Wine's server. It ends with the session.
        if (program == Program.LAUNCHER) {
            spawn("keeper", listOf("/system/bin/sh", "-c", "sleep 3 && exec \"\$@\"", "sh") +
                wineCommand + KEEPER, gameEnv())
        }
        // Stopped while the game was being started: nothing may run on without the view.
        if (stopped) killAll()
    }

    /**
     * Wine's loader. Alternative B (the ARM64 Wine, its x86 CPU DLL Box64's wowbox64.dll) runs
     * natively; the x86-64 Wine of the first path runs under Box64.
     */
    private val wineCommand: List<String> get() {
        val armWine = File(rt.wine, "lib/wine/aarch64-unix/wine")
        return if (armWine.isFile) listOf(armWine.path) else listOf("$ul/usr/bin/box64", "${rt.wine}/bin/wine")
    }

    /**
     * Runs close-launcher.exe ([CLOSER]) in the session's Wine: it asks the official launcher to
     * close when a window of the game shows ([LauncherExit]). Its end comes to [Native.exitListener]
     * as [CLOSER_NAME].
     */
    fun closeLauncher() {
        if (stopped) return
        spawn(CLOSER_NAME, wineCommand + CLOSER, gameEnv())
    }

    private fun waitForX() {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            try {
                LocalSocket().use { it.connect(LocalSocketAddress("/tmp/.X11-unix/X${DISPLAY.drop(1)}", LocalSocketAddress.Namespace.ABSTRACT)) }
                return
            } catch (_: IOException) {
                Thread.sleep(50)
            }
        }
        throw IOException("the X screen did not start (see logs/xvfb.log)")
    }

    private val event = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)

    /** x-input-relay events in order, in one write. */
    @Synchronized fun send(events: List<RelayEvent>) {
        val out = relay ?: return
        if (events.isEmpty()) return
        val bytes = ByteArray(events.size * RelayEvent.SIZE)
        events.forEachIndexed { i, e -> e.encode().copyInto(bytes, i * RelayEvent.SIZE) }
        try {
            out.write(bytes)
        } catch (e: IOException) {
            Log.w(Native.TAG, "relay: $e")
        }
    }

    /** One x-input-relay event (x-input-relay.c): 1 motion, 2 relative motion, 3 button, 4 key. */
    @Synchronized fun send(type: Int, a: Int, b: Int) {
        val out = relay ?: return
        event.clear()
        event.putInt(type).putInt(a).putInt(b)
        try {
            out.write(event.array(), 0, 12)
        } catch (e: IOException) {
            Log.w(Native.TAG, "relay: $e")
        }
    }

    @Volatile private var guardRunning = false

    /**
     * A runaway guard: once a second, our processes are ended when more than [MAX_EXPLORER]
     * explorer.exe run (Wine starting desktops in a loop, as when its window setup fails). It has
     * no memory rule: our processes are not immune to Android's memory killer, which handles
     * memory pressure (a phone whose cached apps fill its memory goes far under any fixed floor
     * as the game starts, and the killer then drops cached apps), as in the main app.
     */
    private fun startGuard() {
        guardRunning = true
        Thread({
            while (guardRunning) {
                Thread.sleep(1000)
                val explorers = File("/proc").listFiles()?.count { p ->
                    p.name.all { it.isDigit() } && try { File(p, "comm").readText().trim() == "explorer.exe" } catch (_: Exception) { false }
                } ?: 0
                if (explorers > MAX_EXPLORER) {
                    Log.e(Native.TAG, "guard: $explorers explorer.exe: stopping the game")
                    guardRunning = false
                    killAll()
                }
            }
        }, "runaway-guard").apply { isDaemon = true; start() }
    }

    @Volatile private var stopped = false

    /** The game view went while [start] still ran: it ends there (what it started, [stop] ends). */
    private fun checkStopped() {
        if (stopped) throw IOException("stopped during the start")
    }

    fun stop() {
        stopped = true
        guardRunning = false
        webLinks.stop()
        synchronized(this) {
            try { gamepad?.close() } catch (_: IOException) {}
            gamepad = null
        }
        audioRunning = false
        try { relay?.close() } catch (_: IOException) {}
        relay = null
        try { relayAppEnd?.close() } catch (_: IOException) {}
        relayAppEnd = null
        killAll()
        if (memfd >= 0) {
            try { ParcelFileDescriptor.adoptFd(memfd).close() } catch (_: IOException) {}
            memfd = -1
        }
    }

    companion object {
        /**
         * Its own X display: Xvfb's abstract socket is device-wide (main app :0, its dev build :1;
         * Lite :7, its dev build :8, from the build type).
         */
        val DISPLAY: String = BuildConfig.X_DISPLAY

        /** The official launcher's .NET programs: the launcher and its updater. */
        val LAUNCHER_PROGRAMS = listOf("PD2Launcher.exe", "UpdateUtility.exe")

        /**
         * The launcher's .NET runtime under the x86 emulation, as the main app runs it: .NET's
         * write-xor-execute double mapping and tiered compilation's later code patching are off
         * (each method is compiled once, in place), and Box64 keeps x86 memory order for its
         * threads. BOX64_DYNAREC_WAIT=0: a thread runs a block in Box64's interpreter instead of
         * waiting while another thread translates code. .NET suspends threads for its garbage
         * collector at any point; one suspended while it held the translator's lock stopped every
         * other thread, so the launcher's window stopped at random clicks.
         *
         * The game that the launcher starts inherits this environment. Box64's settings are for
         * [LAUNCHER_PROGRAMS] only (BOX64_DYNAREC_PROGRAMS, Box64 patch 0006): Game.exe starts
         * with Box64's defaults, as they only cost it time.
         */
        val LAUNCHER_ENV = listOf(
            "DOTNET_TieredCompilation=0", "DOTNET_EnableWriteXorExecute=0",
            "BOX64_DYNAREC_STRONGMEM=1", "BOX64_DYNAREC_WAIT=0",
            "BOX64_DYNAREC_PROGRAMS=${LAUNCHER_PROGRAMS.joinToString(";")}",
        )
        const val MOTION = 1
        const val BUTTON = 3
        const val KEY = 4
        const val RATE = 22_050
        const val MAX_EXPLORER = 2
        /** The prefix's keep-on-screen.exe (build/helpers.sh, put there by build/prefix.sh). */
        const val KEEPER = "C:\\windows\\keep-on-screen.exe"

        /** The prefix's close-launcher.exe (the same way), and its name among the session's processes. */
        const val CLOSER = "C:\\windows\\close-launcher.exe"
        const val CLOSER_NAME = "closer"

        /**
         * Ends the official launcher's process [pid] when it still is the launcher (it did not
         * close when asked: [LauncherExit.Step.KILL]).
         */
        fun killLauncher(pid: Int): Boolean {
            if (pid !in ProgramWatch.pids(ProgramWatch.LAUNCHER)) return false
            return try {
                Os.kill(pid, OsConstants.SIGKILL)
                true
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Ends keep-on-screen.exe ([KEEPER]): it is needed only while the official launcher's
         * window shows. Wine's server notices that the process ended.
         */
        fun stopKeeper() {
            for (pid in ProgramWatch.pids("keep-on-screen.exe")) {
                try {
                    Os.kill(pid, OsConstants.SIGKILL)
                    Log.i(Native.TAG, "keep-on-screen.exe ($pid) stopped: the game runs")
                } catch (_: Exception) {
                }
            }
        }

        /** Ends every other process of the app's user (Wine's server and services included). */
        fun killAll() {
            val me = Os.getpid()
            val uid = Os.getuid()
            File("/proc").listFiles()?.forEach { p ->
                val pid = p.name.toIntOrNull() ?: return@forEach
                if (pid == me) return@forEach
                try {
                    if (Os.stat(p.path).st_uid == uid) Os.kill(pid, OsConstants.SIGKILL)
                } catch (_: Exception) {
                }
            }
        }
    }
}
