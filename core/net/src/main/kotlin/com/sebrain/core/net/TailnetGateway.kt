package com.sebrain.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** The Tailscale tunnel is not active, so no connection is made. */
class TailnetUnavailableException(message: String) : IOException(message)

/**
 * The only place in the app that opens network connections. Only HTTPS is allowed.
 *
 * By default it uses the device's normal connection; when Tailscale is on, Android routes that
 * through the tunnel as Tailscale is configured. With [requireTailscale], every connection is
 * bound to the Tailscale VPN network instead and nothing connects while Tailscale is off.
 */
class TailnetGateway(context: Context, private val requireTailscale: Boolean = false) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** Whether the Tailscale tunnel is up. */
    val isTailscaleUp: Boolean get() = tailnetNetwork() != null

    /** Whether a connection can be attempted. */
    val isAvailable: Boolean get() = if (requireTailscale) isTailscaleUp else connectivity.activeNetwork != null

    /** Opens an HTTPS connection through the tunnel. The caller connects, reads and disconnects. */
    fun open(url: URL): HttpsURLConnection {
        if (url.protocol != "https") throw IOException("Only HTTPS is allowed")
        val connection = if (requireTailscale) {
            val network = tailnetNetwork()
                ?: throw TailnetUnavailableException("Tailscale is not connected and this connection requires it")
            network.openConnection(url)
        } else {
            url.openConnection()
        }
        return (connection as HttpsURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // Redirects are followed by hand, so every hop is checked to be HTTPS.
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "SeBrain")
        }
    }

    /** Opens [url] following redirects, each one over HTTPS. */
    fun openFollowingRedirects(url: URL, headers: Map<String, String> = emptyMap()): HttpsURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = open(current)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            current = URL(current, location ?: throw IOException("Redirect without a location"))
        }
        throw IOException("Too many redirects")
    }

    /** A VPN network holding a Tailscale address, or null. */
    @Suppress("DEPRECATION") // allNetworks has no replacement for a one-shot query.
    private fun tailnetNetwork(): Network? = connectivity.allNetworks.firstOrNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network)
        val addresses = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
            addresses.any { TailnetAddresses.isTailnet(it.address) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5
    }
}
