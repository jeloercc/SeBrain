package com.sebrain.core.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** The address ranges Tailscale assigns to devices. */
object TailnetAddresses {
    /** 100.64.0.0/10 (CGNAT range used by Tailscale) or fd7a:115c:a1e0::/48 (Tailscale ULA). */
    fun isTailnet(address: InetAddress): Boolean {
        val bytes = address.address
        return when (address) {
            is Inet4Address -> (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 0x40
            is Inet6Address -> TAILSCALE_ULA.indices.all { bytes[it] == TAILSCALE_ULA[it] }
            else -> false
        }
    }

    private val TAILSCALE_ULA = byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte())
}
