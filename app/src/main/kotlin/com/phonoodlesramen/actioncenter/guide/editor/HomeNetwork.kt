package com.phonoodlesramen.actioncenter.guide.editor

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The Shield's own address on the home network, for the phone editor's QR code and the
 * address on screen (plan §4). It comes from the active network's link properties, which
 * covers Ethernet as well as Wi-Fi (Wi-Fi's own address calls are deprecated).
 */
object HomeNetwork {
    /** The active network's IPv4 address; null without a network or one. */
    fun address(context: Context): Inet4Address? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val link = connectivity.getLinkProperties(network) ?: return null
        pick(link.linkAddresses.map { it.address })
    }.getOrNull()

    /** The first IPv4 address that a phone on the same network can reach: not loopback, not link-local. */
    fun pick(addresses: List<InetAddress>): Inet4Address? =
        addresses.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
}
