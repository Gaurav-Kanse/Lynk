package com.lynk.android.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.lynk.android.data.DiscoveredService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import kotlin.coroutines.resume

private const val TAG = "NsdDiscoveryManager"
const val LYNK_SERVICE_TYPE = "_lynk._tcp."

/**
 * Manages mDNS / DNS-SD discovery for Lynk daemon services using Android NsdManager.
 *
 * Implements:
 * - MulticastLock acquisition to prevent Wi-Fi packet filtering on Android devices.
 * - Sequential service resolution to avoid NsdManager.FAILURE_ALREADY_ACTIVE errors.
 * - Reactive state via StateFlow.
 */
class NsdDiscoveryManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _services = MutableStateFlow<List<DiscoveredService>>(emptyList())
    val services: StateFlow<List<DiscoveredService>> = _services.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    init {
        // Start background worker to process service resolutions sequentially
        scope.launch {
            for (serviceInfo in resolveQueue) {
                resolveServiceSequentially(serviceInfo)
            }
        }
    }

    /**
     * Start mDNS discovery for _lynk._tcp.
     */
    fun startDiscovery() {
        if (_isScanning.value) {
            Log.d(TAG, "Discovery already active, restarting...")
            stopDiscovery()
        }

        if (nsdManager == null) {
            _errorMessage.value = "NsdManager is not available on this device."
            return
        }

        _errorMessage.value = null
        _services.value = emptyList()

        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Discovery started for $regType")
                _isScanning.value = true
                _errorMessage.value = null
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: name=${service.serviceName}, type=${service.serviceType}")
                // Only resolve services matching our Lynk service type
                if (service.serviceType.contains("_lynk._tcp")) {
                    resolveQueue.trySend(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                _services.value = _services.value.filterNot { it.name == service.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
                _isScanning.value = false
                releaseMulticastLock()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: error $errorCode")
                _isScanning.value = false
                _errorMessage.value = "Failed to start discovery (error code: $errorCode)"
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: error $errorCode")
                _isScanning.value = false
                releaseMulticastLock()
            }
        }

        discoveryListener = listener
        try {
            nsdManager.discoverServices(
                LYNK_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during discoverServices", e)
            _errorMessage.value = "Discovery error: ${e.localizedMessage}"
            _isScanning.value = false
            releaseMulticastLock()
        }
    }

    /**
     * Stop mDNS discovery and release resources.
     */
    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsdManager?.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop discovery cleanly", e)
        } finally {
            _isScanning.value = false
            releaseMulticastLock()
        }
    }

    /**
     * Resolves an NsdServiceInfo one at a time using suspendCancellableCoroutine
     * to prevent NsdManager.FAILURE_ALREADY_ACTIVE.
     */
    private suspend fun resolveServiceSequentially(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        suspendCancellableCoroutine { continuation ->
            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${info.serviceName}: code $errorCode")
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    Log.d(TAG, "Resolved service: ${resolvedInfo.serviceName} at ${resolvedInfo.host}:${resolvedInfo.port}")

                    val host: InetAddress? = resolvedInfo.host
                    val hostAddress = host?.hostAddress
                    val hostName = resolvedInfo.host?.hostName ?: "unknown"
                    val addresses = if (hostAddress != null) listOf(hostAddress) else emptyList()

                    // Extract TXT records if available
                    val txtMap = mutableMapOf<String, String>()
                    try {
                        val attributes = resolvedInfo.attributes
                        for ((key, valueBytes) in attributes) {
                            if (valueBytes != null) {
                                txtMap[key] = String(valueBytes, Charsets.UTF_8)
                            } else {
                                txtMap[key] = ""
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not parse TXT records: ${e.message}")
                    }

                    val service = DiscoveredService(
                        name = resolvedInfo.serviceName,
                        serviceType = resolvedInfo.serviceType,
                        host = host,
                        hostName = hostName,
                        addresses = addresses,
                        port = resolvedInfo.port,
                        txtRecords = txtMap
                    )

                    // Update services list (replace if already exists, else append)
                    val currentList = _services.value.toMutableList()
                    val existingIndex = currentList.indexOfFirst { it.name == service.name }
                    if (existingIndex >= 0) {
                        currentList[existingIndex] = service
                    } else {
                        currentList.add(service)
                    }
                    _services.value = currentList

                    if (continuation.isActive) continuation.resume(Unit)
                }
            }

            try {
                manager.resolveService(serviceInfo, resolveListener)
            } catch (e: Exception) {
                Log.e(TAG, "Exception in resolveService", e)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager?.createMulticastLock("LynkMulticastLock")?.apply {
                setReferenceCounted(true)
            }
        }
        try {
            multicastLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    Log.d(TAG, "MulticastLock acquired")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "MulticastLock released")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MulticastLock: ${e.message}")
        }
    }
}
