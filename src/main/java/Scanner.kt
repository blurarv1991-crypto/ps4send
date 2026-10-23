package com.ps4send.app

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Scanner {
    /** Primeras tres partes de tu IP local, p. ej. "192.168.1". */
    fun localPrefix(): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (ni in ifaces) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses) {
                if (a is Inet4Address && a.isSiteLocalAddress) {
                    return a.hostAddress?.substringBeforeLast('.')
                }
            }
        }
        return null
    }

    /** Bloqueante: llámala desde un hilo, nunca desde el principal.
     *  Devuelve las IPs de la red con ese puerto abierto. */
    fun find(port: Int = 12800, timeoutMs: Int = 400): List<String> {
        val prefix = localPrefix() ?: return emptyList()
        val found = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(64)
        for (i in 1..254) {
            val ip = "$prefix.$i"
            pool.execute {
                try {
                    Socket().use {
                        it.connect(InetSocketAddress(ip, port), timeoutMs)
                        found.add(ip)
                    }
                } catch (_: Exception) {
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        return found.toList()
    }
}
