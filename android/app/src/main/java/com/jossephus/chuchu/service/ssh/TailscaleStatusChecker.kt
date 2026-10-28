package com.jossephus.chuchu.service.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Detects whether this device is currently attached to a Tailscale tailnet.
 *
 * [isActive] performs blocking network-interface enumeration and MUST NOT be called on the main
 * thread; run it from `Dispatchers.IO` (or an equivalent background dispatcher) and publish the
 * result back to the UI thread.
 */
class TailscaleStatusChecker(private val context: Context) {
    /**
     * Returns true if the device appears to be on a Tailscale network.
     *
     * We check for a tailnet address via network interfaces first, IPv4 `100.64.0.0/10` and IPv6
     * `fd7a:115c:a1e0::/48`. If that fails (not all Android devices expose the tun interface to
     * Java APIs), we fall back to checking if any VPN transport is active.
     *
     * This call enumerates every [NetworkInterface] and therefore blocks; see the class
     * documentation for the dispatcher requirement.
     */
    fun isActive(): Boolean {
        if (hasTailnetAddress()) return true
        return hasVpnTransport()
    }

    private fun hasVpnTransport(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun hasTailnetAddress(): Boolean {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.flatMap { it.inetAddresses?.toList() ?: emptyList() }
                ?.any { addr -> isTailnetIp(addr) } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun isTailnetIp(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress) return false
        val bytes = addr.address ?: return false
        return when (addr) {
            is Inet4Address -> isTailnetIpv4(bytes)
            is Inet6Address -> isTailnetIpv6(bytes)
            else -> false
        }
    }

    /** `100.64.0.0/10`: first octet 100, second octet in [64, 127]. */
    private fun isTailnetIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 100 && second in 64..127
    }

    /**
     * `fd7a:115c:a1e0::/48`: the first six bytes must equal `fd 7a 11 5c a1 e0`. IPv6 addresses
     * reported by Java may be IPv4-mapped, which cannot match the fixed prefix and therefore falls
     * through to false.
     */
    private fun isTailnetIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        for (index in TailnetIpv6Prefix.indices) {
            if (bytes[index] != TailnetIpv6Prefix[index]) return false
        }
        return true
    }

    private companion object {
        val TailnetIpv6Prefix =
            byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte())
    }
}
