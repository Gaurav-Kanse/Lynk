package com.lynk.android.data

import java.net.InetAddress

/**
 * Represents a Lynk daemon service discovered via mDNS / DNS-SD using NsdManager.
 */
data class DiscoveredService(
    val name: String,
    val serviceType: String,
    val host: InetAddress?,
    val hostName: String,
    val addresses: List<String>,
    val port: Int,
    val txtRecords: Map<String, String> = emptyMap(),
    val discoveredAtMillis: Long = System.currentTimeMillis()
) {
    /**
     * Primary reachable IP address or hostname to connect to.
     */
    val primaryAddress: String
        get() = addresses.firstOrNull() ?: host?.hostAddress ?: hostName
}
