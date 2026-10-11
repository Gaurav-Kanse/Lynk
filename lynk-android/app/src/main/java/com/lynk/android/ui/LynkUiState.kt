package com.lynk.android.ui

import com.lynk.android.data.DiscoveredService
import com.lynk.android.network.HandshakeResult

/**
 * UI State for the Lynk discovery and connection screen.
 */
data class LynkUiState(
    val isScanning: Boolean = false,
    val services: List<DiscoveredService> = emptyList(),
    val discoveryError: String? = null,
    val isConnecting: Boolean = false,
    val connectingTarget: String? = null,
    val connectionResult: HandshakeResult? = null,
    val connectionError: String? = null
)
