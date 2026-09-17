package com.ai.controller

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Minimal loopback-only HTTP server exposing POST /voice and POST /speak —
 * the Android analogue of ai-controller's voice_bridge.py FastAPI service.
 * Built on raw java.net sockets rather than a web framework: the project may
 * not add a third-party HTTP library, and this only needs to answer two tiny
 * routes for on-device parity/testability, not serve production traffic.
 *
 * Bound to 127.0.0.1 only, mirroring voice_bridge.py's `_local_only` guard —
 * this never accepts a connection originating off-device. /voice always
 * behaves as transcribe_only (never drives an LLM or speaks unprompted),
 * matching voice_bridge.py's documented fail-closed mode handling.
 */
class VoiceBridgeServer(
    private val onTranscribeOnly: suspend (ByteArray) -> String,
    private val onSpeak: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun start(port: Int = DEFAULT_PORT) {
        if (serverSocket != null) return
        job = scope.launch {
            try {
                val socket = ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                Log.i(TAG, "voice bridge listening on 127.0.0.1:$port")
                while (true) {
                    val client = socket.accept()
                    launch { handleClient(client) }
                }
            } catch (e: SocketException) {
                Log.i(TAG, "voice bridge socket closed")
            } catch (e: Exception) {
                Log.e(TAG, "voice bridge server loop failed", e)
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // already closed
        }
        serverSocket = null
        job?.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val input = s.getInputStream()
                val reader = BufferedReader(InputStreamReader(input))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeResponse(s.getOutputStream(), 400, "{\"error\":\"bad request\"}")
                    return
                }
                val method = parts[0]
                val path = parts[1]

                var contentLength = 0
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    val sep = header.indexOf(':')
                    if (sep <= 0) continue
                    val name = header.substring(0, sep).trim()
                    val value = header.substring(sep + 1).trim()
                    if (name.equals("Content-Length", ignoreCase = true)) {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }

                val bodyBytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bodyBytes, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }

                when {
                    method == "POST" && path.startsWith("/voice") -> {
                        val transcript = onTranscribeOnly(bodyBytes)
                        writeResponse(s.getOutputStream(), 200, "{\"text\":\"${jsonEscape(transcript)}\"}")
                    }
                    method == "POST" && path.startsWith("/speak") -> {
                        val text = String(bodyBytes, Charsets.UTF_8)
                        onSpeak(text)
                        writeResponse(s.getOutputStream(), 200, "{\"spoken\":true}")
                    }
                    else -> writeResponse(s.getOutputStream(), 404, "{\"error\":\"not found\"}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "voice bridge client handling failed", e)
            }
        }
    }

    private fun writeResponse(out: OutputStream, status: Int, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            else -> "Not Found"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    companion object {
        private const val TAG = "VoiceBridgeServer"
        private const val DEFAULT_PORT = 8002
        private const val BACKLOG = 4
    }
}
