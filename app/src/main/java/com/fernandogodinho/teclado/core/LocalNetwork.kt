package com.fernandogodinho.teclado.core

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Gate for the only non-HTTPS network use in the app: talking to a TV on the user's own
 * Wi-Fi. Connections are allowed exclusively to private/local addresses, so a malicious
 * discovery answer can never make the app connect to the internet.
 */
object LocalNetwork {

    fun isLocal(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return false
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) return true // 10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10
        return when (address) {
            is Inet6Address -> (address.address[0].toInt() and 0xFE) == 0xFC // fc00::/7 unique local
            is Inet4Address -> false
            else -> false
        }
    }

    fun requireLocal(address: InetAddress) {
        require(isLocal(address)) { "only devices on the local network are allowed" }
    }
}
