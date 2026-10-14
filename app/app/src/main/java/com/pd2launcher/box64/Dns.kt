package com.pd2launcher.box64

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * The userland's `resolv.conf` (from the main app's Dns.kt): the default network's DNS servers,
 * written when the session starts and again whenever they change. The userland's glibc reads it
 * from `<userland>/etc` (its SYSCONFDIR patch); Android itself has none, so without it Wine
 * resolves no name (the official PD2 launcher's updates, loot filters and news, the game's realm).
 */
class Dns(private val context: Context, private val target: File) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Writes the current servers at once, then follows the default network until [stop]. */
    fun start() {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.activeNetwork?.let { manager.getLinkProperties(it) }?.let { write(it.dnsServers) }
        val follow = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                write(properties.dnsServers)
            }
        }
        manager.registerDefaultNetworkCallback(follow)
        callback = follow
    }

    fun stop() {
        val follow = callback ?: return
        callback = null
        try {
            context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(follow)
        } catch (_: IllegalArgumentException) {
        }
    }

    @Synchronized
    private fun write(servers: List<InetAddress>) {
        val text = render(servers)
        if (target.parentFile?.isDirectory != true) return
        if (target.isFile && target.readText() == text) return
        try {
            val staging = File(target.parentFile, "${target.name}.tmp")
            // Created anew: a link left at its name goes, and is never written through.
            Files.deleteIfExists(staging.toPath())
            Files.write(staging.toPath(), text.toByteArray(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            if (!staging.renameTo(target)) throw java.io.IOException("rename failed")
            Log.i(Native.TAG, "resolv.conf: ${servers.take(MAX_SERVERS).joinToString(" ") { address(it) }}")
        } catch (error: Exception) {
            Log.w(Native.TAG, "resolv.conf not written: $error")
        }
    }

    companion object {
        /** glibc reads at most three servers (`MAXNS`). */
        private const val MAX_SERVERS = 3

        /** `resolv.conf` for [servers], in order; IPv6 link-local ones keep their interface. */
        fun render(servers: List<InetAddress>): String = buildString {
            append("# Written by the app from Android's DNS servers.\n")
            for (server in servers.take(MAX_SERVERS)) append("nameserver ").append(address(server)).append('\n')
        }

        private fun address(server: InetAddress): String {
            val text = server.hostAddress ?: ""
            // Java names the scope by number when it has no interface; glibc takes either.
            return if (server is Inet6Address && !server.isLinkLocalAddress) text.substringBefore('%') else text
        }
    }
}
