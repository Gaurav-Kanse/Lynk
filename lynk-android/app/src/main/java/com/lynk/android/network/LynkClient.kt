
package com.lynk.android.network

import android.util.Log
import kotlinx.coroutines.CancellationException
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
private const val EXPECTED_RESPONSE = "LYNK_HELLO 1"

data class HandshakeResult(
    val request: String,
    val response: String,
    val latencyMs: Long,
    val targetAddress: String,
    val targetPort: Int
)

class LynkClient(
    private val defaultTimeoutMs: Int = 5000
) {
    suspend fun sendHello(
        host: String,
        port: Int,
        timeoutMs: Int = defaultTimeoutMs
    ): Result<HandshakeResult> = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) { "Host cannot be empty" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(timeoutMs > 0) { "Timeout must be positive" }

        val startedAt = System.nanoTime()

        try {
            Socket().use { socket ->
                Log.d(TAG, "Connecting to $host:$port")

                socket.connect(
                    InetSocketAddress(host, port),
                    timeoutMs
                )
                socket.soTimeout = timeoutMs

                val writer = BufferedWriter(
                    OutputStreamWriter(
                        socket.getOutputStream(),
                        Charsets.UTF_8
                    )
                )

                val reader = BufferedReader(
                    InputStreamReader(
                        socket.getInputStream(),
                        Charsets.UTF_8
                    )
                )

                val request = "HELLO_LYNK"
                writer.write("$request\n")
                writer.flush()

                val response = reader.readLine()
                    ?: throw IllegalStateException(
                        "Server closed the connection without responding"
                    )

                if (response.length > 4096) {
                    throw IllegalStateException(
                        "Server response exceeded the allowed length"
                    )
                }

                if (response.trim() != EXPECTED_RESPONSE) {
                    throw IllegalStateException(
                        "Unexpected Lynk protocol response: $response"
                    )
                }

                val latencyMs =
                    (System.nanoTime() - startedAt) / 1_000_000

                Log.d(TAG, "Handshake successful in ${latencyMs}ms")

                Result.success(
                    HandshakeResult(
                        request = request,
                        response = response.trim(),
                        latencyMs = latencyMs,
                        targetAddress = host,
                        targetPort = port
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SocketTimeoutException) {
            Log.e(TAG, "Connection timed out", error)
            Result.failure(
                SocketTimeoutException(
                    "Connection timed out after ${timeoutMs}ms ($host:$port)"
                )
            )
        } catch (error: Exception) {
            Log.e(TAG, "Handshake failed", error)
            Result.failure(error)
        }
    }
}
