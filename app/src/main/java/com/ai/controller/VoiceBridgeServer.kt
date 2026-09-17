package com.ai.controller

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom

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
 *
 * Loopback-only is not the same as private on Android: any other app/process
 * on the same device can still open a socket to 127.0.0.1 and hit these
 * routes. Every request therefore also needs [authToken] — a per-instance
 * random secret generated at construction and handed only to the trusted
 * in-process caller — via the X-Auth-Token header, checked in constant time,
 * before any body is read or a route is dispatched.
 */
class VoiceBridgeServer(
    private val onTranscribeOnly: suspend (ByteArray) -> String,
    private val onSpeak: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    /** Per-instance shared secret. Give this only to the trusted in-process caller. */
    val authToken: String = generateToken()

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
                // Request line, headers, and body are all read through this one
                // buffered stream so no bytes the reader prefetches for a header
                // line are ever lost to a separate raw-InputStream body read (F5).
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeResponse(s.getOutputStream(), 400, "{\"error\":\"bad request\"}")
                    return
                }
                val method = parts[0]
                val path = parts[1]

                var contentLength = 0
                var providedToken: String? = null
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    val sep = header.indexOf(':')
                    if (sep <= 0) continue
                    val name = header.substring(0, sep).trim()
                    val value = header.substring(sep + 1).trim()
                    when {
                        name.equals("Content-Length", ignoreCase = true) ->
                            contentLength = (value.toIntOrNull() ?: 0).coerceAtLeast(0)
                        name.equals(AUTH_HEADER, ignoreCase = true) -> providedToken = value
                    }
                }

                // Auth and route validity are both rejected before the body is
                // touched at all — an unauthenticated or unknown-route caller
                // never causes a read or an allocation (F3).
                if (!isAuthorized(providedToken)) {
                    writeResponse(s.getOutputStream(), 401, "{\"error\":\"unauthorized\"}")
                    return
                }
                if (!isValidRoute(method, path)) {
                    writeResponse(s.getOutputStream(), 404, "{\"error\":\"not found\"}")
                    return
                }
                // Bound-check the declared length BEFORE allocating for it — a
                // malicious local caller can otherwise declare an arbitrarily huge
                // Content-Length and OOM this process (F4).
                if (contentLength > MAX_BODY_BYTES) {
                    writeResponse(s.getOutputStream(), 413, "{\"error\":\"payload too large\"}")
                    return
                }

                val bodyBytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bodyBytes, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }

                when {
                    path.startsWith("/voice") -> {
                        val transcript = onTranscribeOnly(bodyBytes)
                        writeResponse(s.getOutputStream(), 200, "{\"text\":\"${jsonEscape(transcript)}\"}")
                    }
                    path.startsWith("/speak") -> {
                        val text = String(bodyBytes, Charsets.UTF_8)
                        onSpeak(text)
                        writeResponse(s.getOutputStream(), 200, "{\"spoken\":true}")
                    }
                    else -> Unit // unreachable: isValidRoute already filtered to these two paths
                }
            } catch (e: Exception) {
                Log.w(TAG, "voice bridge client handling failed", e)
            }
        }
    }

    private fun isValidRoute(method: String, path: String): Boolean =
        method == "POST" && (path.startsWith("/voice") || path.startsWith("/speak"))

    private fun isAuthorized(providedToken: String?): Boolean {
        if (providedToken == null) return false
        val expected = authToken.toByteArray(Charsets.UTF_8)
        val actual = providedToken.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expected, actual)
    }

    /** Reads one CRLF- or LF-terminated line from [input], one byte at a time, so
     * header parsing never reads past the blank line into body bytes that a
     * separate raw read would then miss (F5). */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) sb.append(b.toChar())
            b = input.read()
        }
        return sb.toString()
    }

    private fun writeResponse(out: OutputStream, status: Int, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            413 -> "Payload Too Large"
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

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "VoiceBridgeServer"
        private const val DEFAULT_PORT = 8002
        private const val BACKLOG = 4
        private const val AUTH_HEADER = "X-Auth-Token"

        /** Well above any real utterance WAV; just a ceiling against a hostile Content-Length. */
        private const val MAX_BODY_BYTES = 10 * 1024 * 1024
    }
}
