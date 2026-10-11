package com.lynk.android.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

private const val TAG = "LynkClient"

/**
 * Result data holder for a TCP connection handshake.
 */
data class HandshakeResult(
    val request: String,
    val response: String,
    val latencyMs: Long,
    val targetAddress: String,
    val targetPort: Int
)

/**
 * Client for opening short-lived TCP socket connections to the Lynk daemon.
 *
 * NOTE: The initial TCP connection is UNAUTHENTICATED and UNENCRYPTED (Plaintext).
 * Do not transmit sensitive data over this channel.
 */
class LynkClient(
    private val defaultTimeoutMs: Int = 5000
) {

    /**
     * Connects to the Lynk daemon at [host]:[port], sends "HELLO_LYNK\n",
     * reads a single-line response (expected: "LYNK_HELLO 1"), and closes the socket.
     *
     * Executed safely on Dispatchers.IO with strict connection and read timeouts.
     */
    suspend fun sendHello(
        host: String,
        port: Int,
        timeoutMs: Int = defaultTimeoutMs
    ): Result<HandshakeResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var socket: Socket? = null

        try {
            Log.d(TAG, "Connecting to $host:$port with timeout ${timeoutMs}ms...")
            socket = Socket()
            // 1. Establish connection with timeout
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs

            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

            // 2. Transmit HELLO command
            val request = "HELLO_LYNK"
            writer.write("$request\n")
            writer.flush()
            Log.d(TAG, "Sent: $request")

            // 3. Receive single-line response from daemon
            val responseLine = reader.readLine()
            val latency = System.currentTimeMillis() - startTime

            if (responseLine == null) {
                Log.w(TAG, "Server closed socket immediately without response")
                return@withContext Result.failure(
                    IllegalStateException("Server ($host:$port) closed the connection without responding.")
                )
            }

            Log.d(TAG, "Received: $responseLine (latency: ${latency}ms)")
            Result.success(
                HandshakeResult(
                    request = request,
                    response = responseLine.trim(),
                    latencyMs = latency,
                    targetAddress = host,
                    targetPort = port
                )
            )
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Connection/Read timed out connecting to $host:$port", e)
            Result.failure(SocketTimeoutException("Connection timed out after ${timeoutMs}ms ($host:$port)"))
        } catch (e: Exception) {
            Log.e(TAG, "TCP connection failed to $host:$port", e)
            Result.failure(e)
        } finally {
            // 4. Reliably close socket
            try {
                socket?.close()
                Log.d(TAG, "Socket closed reliably")
            } catch (closeEx: Exception) {
                Log.w(TAG, "Error closing socket: ${closeEx.message}")
            }
        }
    }
}
