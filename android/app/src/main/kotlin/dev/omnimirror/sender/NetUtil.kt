package dev.omnimirror.sender

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    class LocalAddress(val address: Inet4Address, val prefixLength: Int)

    /**
     * The phone's address on the local network. Prefers Wi-Fi, but also finds the hotspot
     * interface, so mirroring works when the iPad joins the phone's own hotspot.
     */
    fun localAddress(): LocalAddress? {
        val candidates = ArrayList<Pair<Int, LocalAddress>>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
        for (nif in interfaces) {
            if (!runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)) continue
            val name = nif.name.lowercase()
            val rank = when {
                name.startsWith("wlan") -> 0
                name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") -> 1
                name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis") -> 2
                name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") -> continue // mobile data, VPN
                else -> 3
            }
            for (ia in nif.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                if (a.isSiteLocalAddress) candidates.add(rank to LocalAddress(a, ia.networkPrefixLength.toInt()))
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }
}
