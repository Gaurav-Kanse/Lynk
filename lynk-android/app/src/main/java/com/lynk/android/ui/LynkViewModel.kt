package com.lynk.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lynk.android.data.DiscoveredService
import com.lynk.android.discovery.NsdDiscoveryManager
import com.lynk.android.network.LynkClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LynkViewModel(application: Application) : AndroidViewModel(application) {

    private val discoveryManager = NsdDiscoveryManager(application)
    private val client = LynkClient()

    private val _uiState = MutableStateFlow(LynkUiState())
    val uiState: StateFlow<LynkUiState> = _uiState.asStateFlow()

    init {
        // Observe discovery manager states
        viewModelScope.launch {
            discoveryManager.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
            }
        }
        viewModelScope.launch {
            discoveryManager.services.collect { list ->
                _uiState.update { it.copy(services = list) }
            }
        }
        viewModelScope.launch {
            discoveryManager.errorMessage.collect { err ->
                _uiState.update { it.copy(discoveryError = err) }
            }
        }

        // Start discovery on startup
        startDiscovery()
    }

    fun startDiscovery() {
        discoveryManager.startDiscovery()
    }

    fun stopDiscovery() {
        discoveryManager.stopDiscovery()
    }

    fun refreshDiscovery() {
        _uiState.update {
            it.copy(
                connectionResult = null,
                connectionError = null
            )
        }
        discoveryManager.startDiscovery()
    }

    /**
     * Connect to a discovered Lynk service, send HELLO_LYNK, and await response.
     */
    fun connectToService(service: DiscoveredService) {
        val host = service.primaryAddress
        val port = service.port

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isConnecting = true,
                    connectingTarget = "${service.name} ($host:$port)",
                    connectionResult = null,
                    connectionError = null
                )
            }

            val result = client.sendHello(host = host, port = port)

            result.onSuccess { handshake ->
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        connectingTarget = null,
                        connectionResult = handshake
                    )
                }
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        connectingTarget = null,
                        connectionError = ex.localizedMessage ?: "Failed to connect to $host:$port"
                    )
                }
            }
        }
    }

    /**
     * Manual connection support (e.g. for emulator testing with 10.0.2.2 or custom IP).
     */
    fun connectDirect(host: String, port: Int) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isConnecting = true,
                    connectingTarget = "$host:$port",
                    connectionResult = null,
                    connectionError = null
                )
            }

            val result = client.sendHello(host = host, port = port)

            result.onSuccess { handshake ->
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        connectingTarget = null,
                        connectionResult = handshake
                    )
                }
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        connectingTarget = null,
                        connectionError = ex.localizedMessage ?: "Failed to connect to $host:$port"
                    )
                }
            }
        }
    }

    fun clearConnectionMessage() {
        _uiState.update {
            it.copy(
                connectionResult = null,
                connectionError = null
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        discoveryManager.stopDiscovery()
    }
}
