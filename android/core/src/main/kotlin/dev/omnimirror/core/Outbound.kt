package dev.omnimirror.core

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Sender-initiated connections: the phone dials a receiver app that is listening. */
object Outbound {
    fun open(host: String, port: Int, timeoutMs: Int = 3000): MessageChannel {
        val socket = Socket()
        try {
            tuneSocket(socket)
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.getOutputStream().write(Proto.MAGIC)
            return TcpChannel(socket)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /** Every other host address in the local IPv4 subnet (capped at a /22 so a scan stays quick). */
    fun subnetHosts(local: Inet4Address, prefixLength: Int): List<String> {
        val prefix = prefixLength.coerceIn(22, 30)
        val addr = local.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
        val mask = -1 shl (32 - prefix)
        val network = addr and mask
        val count = 1 shl (32 - prefix)
        return (1 until count - 1).map { network + it }.filter { it != addr }.map {
            "${(it ushr 24) and 0xff}.${(it ushr 16) and 0xff}.${(it ushr 8) and 0xff}.${it and 0xff}"
        }
    }

    /** Which of [hosts] accept TCP connections on [port]. Used to pair by code when mDNS is blocked. */
    fun probe(hosts: List<String>, port: Int, timeoutMs: Int = 600): List<String> {
        if (hosts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(64, hosts.size))
        try {
            val futures = hosts.map { host ->
                pool.submit(Callable {
                    Socket().use { s ->
                        runCatching { s.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs) }.isSuccess
                    }
                })
            }
            return hosts.filterIndexed { i, _ -> runCatching { futures[i].get(timeoutMs * 20L, TimeUnit.MILLISECONDS) }.getOrDefault(false) }
        } finally {
            pool.shutdownNow()
        }
    }
}
