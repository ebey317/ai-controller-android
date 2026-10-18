package com.ai.controller

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Tests for VoiceBridgeServer: the loopback HTTP routes end to end over real
 * sockets (auth, routing, body handling, status codes) plus unit tests of the
 * extracted request-parsing helpers.
 *
 * Runs under Robolectric (not plain JUnit) because the server logs through
 * android.util.Log, which throws on a bare JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceBridgeServerTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startedServer(
        onTranscribe: suspend (ByteArray) -> String = { "ok" },
        onSpeak: (String) -> Unit = {}
    ): Pair<VoiceBridgeServer, Int> {
        val server = VoiceBridgeServer(onTranscribe, onSpeak)
        val port = freePort()
        server.start(port)
        val deadline = System.currentTimeMillis() + 8000
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).close()
                return server to port
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(50)
            }
        }
        throw AssertionError("server did not start on port $port: $lastError")
    }

    private fun rawRequest(port: Int, text: String): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 8000
            s.getOutputStream().write(text.toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
            // the server closes the connection after each response
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun post(path: String, token: String?, body: String = ""): String {
        val headers = buildString {
            append("POST $path HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            if (token != null) append("X-Auth-Token: $token\r\n")
            append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            append("\r\n")
        }
        return headers + body
    }

    private fun statusLine(response: String): String = response.substringBefore("\r\n")

    private fun responseBody(response: String): String = response.substringAfter("\r\n\r\n")

    // ── end to end ────────────────────────────────────────────────────────

    @Test
    fun `request without token is rejected with 401`() {
        val (server, port) = startedServer()
        try {
            val response = rawRequest(port, post("/speak", null, "hi"))
            assertEquals("HTTP/1.1 401 Unauthorized", statusLine(response))
            assertTrue(responseBody(response).contains("unauthorized"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `request with wrong token is rejected with 401`() {
        val (server, port) = startedServer()
        try {
            val response = rawRequest(port, post("/speak", "wrong-token", "hi"))
            assertEquals("HTTP/1.1 401 Unauthorized", statusLine(response))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `unknown route returns 404`() {
        val (server, port) = startedServer()
        try {
            val response = rawRequest(port, post("/nope", server.authToken))
            assertEquals("HTTP/1.1 404 Not Found", statusLine(response))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `non-POST method returns 404`() {
        val (server, port) = startedServer()
        try {
            val response = rawRequest(
                port,
                "GET /speak HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Auth-Token: ${server.authToken}\r\n\r\n"
            )
            assertEquals("HTTP/1.1 404 Not Found", statusLine(response))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `malformed request line returns 400`() {
        val (server, port) = startedServer()
        try {
            val response = rawRequest(port, "GARBAGE\r\n\r\n")
            assertEquals("HTTP/1.1 400 Bad Request", statusLine(response))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `oversized content length is rejected with 413 before any body is read`() {
        val (server, port) = startedServer()
        try {
            // 10 MiB + 1, over the server's MAX_BODY_BYTES ceiling
            val request = "POST /voice HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "X-Auth-Token: ${server.authToken}\r\nContent-Length: 10485761\r\n\r\n"
            val response = rawRequest(port, request)
            assertEquals("HTTP/1.1 413 Payload Too Large", statusLine(response))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `speak route invokes the callback with the body text`() {
        var spoken: String? = null
        val (server, port) = startedServer(onSpeak = { spoken = it })
        try {
            val response = rawRequest(port, post("/speak", server.authToken, "hello world"))
            assertEquals("HTTP/1.1 200 OK", statusLine(response))
            assertEquals("{\"spoken\":true}", responseBody(response))
            assertEquals("hello world", spoken)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `voice route passes body bytes to the transcriber and escapes the reply`() {
        var received: ByteArray? = null
        val (server, port) = startedServer(onTranscribe = {
            received = it
            "say \"hi\"\nnewline"
        })
        try {
            val audio = "fake-audio-bytes"
            val response = rawRequest(port, post("/voice", server.authToken, audio))
            assertEquals("HTTP/1.1 200 OK", statusLine(response))
            assertEquals(audio, received?.toString(Charsets.UTF_8))
            assertEquals("{\"text\":\"say \\\"hi\\\"\\nnewline\"}", responseBody(response))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop closes the listening socket`() {
        val (server, port) = startedServer()
        server.stop()
        var refused = false
        try {
            Socket("127.0.0.1", port).close()
        } catch (e: Exception) {
            refused = true
        }
        assertTrue("expected connection refused after stop()", refused)
    }

    @Test
    fun `auth tokens are unique per instance`() {
        val a = VoiceBridgeServer({ "x" }, {})
        val b = VoiceBridgeServer({ "x" }, {})
        assertNotEquals(a.authToken, b.authToken)
        assertEquals(64, a.authToken.length) // 32 random bytes as hex
        assertTrue(a.authToken.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ── extracted helpers ─────────────────────────────────────────────────

    @Test
    fun `isValidRoute accepts only POST voice and speak`() {
        val server = VoiceBridgeServer({ "x" }, {})
        assertTrue(server.isValidRoute("POST", "/voice"))
        assertTrue(server.isValidRoute("POST", "/speak"))
        assertTrue(server.isValidRoute("POST", "/voice?x=1"))
        assertFalse(server.isValidRoute("GET", "/voice"))
        assertFalse(server.isValidRoute("POST", "/other"))
        assertFalse(server.isValidRoute("POST", "/"))
    }

    @Test
    fun `isAuthorized compares in constant time and rejects nulls`() {
        val server = VoiceBridgeServer({ "x" }, {})
        assertFalse(server.isAuthorized(null))
        assertFalse(server.isAuthorized("wrong"))
        assertFalse(server.isAuthorized(server.authToken + "x")) // different length
        assertTrue(server.isAuthorized(server.authToken))
    }

    @Test
    fun `jsonEscape escapes quotes backslashes and newlines`() {
        val server = VoiceBridgeServer({ "x" }, {})
        assertEquals("plain", server.jsonEscape("plain"))
        assertEquals("a\\\"b", server.jsonEscape("a\"b"))
        assertEquals("a\\\\b", server.jsonEscape("a\\b"))
        assertEquals("a\\nb", server.jsonEscape("a\nb"))
    }

    @Test
    fun `readLine handles CRLF and bare LF`() {
        val server = VoiceBridgeServer({ "x" }, {})
        assertEquals("hello", server.readLine(ByteArrayInputStream("hello\r\nworld".toByteArray())))
        assertEquals("hello", server.readLine(ByteArrayInputStream("hello\nworld".toByteArray())))
        assertNull(server.readLine(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `writeResponse frames status headers and body`() {
        val server = VoiceBridgeServer({ "x" }, {})
        val out = ByteArrayOutputStream()
        server.writeResponse(out, 200, "{\"a\":1}")
        val text = out.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Type: application/json\r\n"))
        assertTrue(text.contains("Content-Length: 7\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{\"a\":1}"))
    }
}
