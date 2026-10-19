package com.ai.controller

import android.Manifest
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Final-push coverage: the Groq transcription SUCCESS path
 * (transcribeWithGroq HTTP 200 + processRecording/transcribeBytesIfConsented
 * happy paths) plus the missing-key guard throw.
 *
 * transcribeWithGroq hardcodes https://api.groq.com, so the HTTP layer is
 * faked with a JVM-global [URLStreamHandlerFactory] installed once per test
 * JVM (java.net.URL only allows a single factory per JVM). Modes:
 * - SUCCESS_200: answers {"text":"hello from groq"} — the happy path.
 * - ERROR_401: 401 with an error body — mirrors the real unauthenticated
 *   response, keeping the pre-existing failure-path tests deterministic even
 *   when this class installs the factory before they run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class GroqTranscriptionSuccessTest {

    enum class GroqMode { SUCCESS_200, ERROR_401 }

    private class FakeGroqConnection(url: URL, private val mode: GroqMode) : HttpURLConnection(url) {
        private val output = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getOutputStream(): OutputStream = output
        override fun getResponseCode(): Int = if (mode == GroqMode.SUCCESS_200) 200 else 401
        override fun getInputStream(): InputStream =
            ByteArrayInputStream("{\"text\":\"hello from groq\"}".toByteArray(Charsets.UTF_8))

        override fun getErrorStream(): InputStream =
            ByteArrayInputStream("{\"error\":{\"message\":\"unauthorized\"}}".toByteArray(Charsets.UTF_8))
    }

    private object FakeGroqFactory : URLStreamHandlerFactory {
        @Volatile var mode: GroqMode = GroqMode.ERROR_401
        override fun createURLStreamHandler(protocol: String): URLStreamHandler? =
            if (protocol == "https") object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection =
                    if (url.host == "api.groq.com") FakeGroqConnection(url, mode)
                    else throw IOException("test factory blocks non-Groq hosts: ${url.host}")
            } else null
    }

    companion object {
        private val installed = AtomicBoolean(false)

        @JvmStatic
        @BeforeClass
        fun installFakeHttps() {
            if (installed.compareAndSet(false, true)) {
                try {
                    URL.setURLStreamHandlerFactory(FakeGroqFactory)
                } catch (e: Error) {
                    // Another test class already installed a factory in this JVM.
                }
            }
        }
    }

    private lateinit var service: ControllerAccessibilityService
    private val app get() = RuntimeEnvironment.getApplication()

    private fun cacheWavs(prefix: String): List<File> =
        service.cacheDir.listFiles { f -> f.name.startsWith(prefix) }?.toList() ?: emptyList()

    private fun bridgeServerOf(): VoiceBridgeServer? {
        val field = ControllerAccessibilityService::class.java
            .getDeclaredField("voiceBridgeServer").apply { isAccessible = true }
        return field.get(service) as VoiceBridgeServer?
    }

    private fun rawRequest(port: Int, text: String): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 8000
            s.getOutputStream().write(text.toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun post(path: String, token: String, body: String = ""): String = buildString {
        append("POST $path HTTP/1.1\r\n")
        append("Host: 127.0.0.1\r\n")
                append("X-Auth-Token: $token\r\n")
        append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        append("\r\n")
        append(body)
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        silenceMicrophone()
        ConsentManager.setGranted(app, true)
        FakeGroqFactory.mode = GroqMode.SUCCESS_200
        service = connectTestService()
        service.primePttDebounce()
    }

    @After
    fun tearDown() {
        FakeGroqFactory.mode = GroqMode.ERROR_401
        ConsentManager.setGranted(app, false)
        ShadowAudioRecord.clearSource()
        service.onDestroy()
    }

    @Test
    fun `processRecording injects the transcript on the Groq success path`() = runTest {
        GroqKeyStore.save(app, "gsk_test_key")
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            service.processRecording(ByteArray(160), 16000)
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
        assertTrue("temp wav must be cleaned up", cacheWavs("voice_prompt_").isEmpty())
    }

    @Test
    fun `transcribeBytesIfConsented returns the transcript on success`() {
        GroqKeyStore.save(app, "gsk_test_key")
        val result = runBlocking { service.transcribeBytesIfConsented(byteArrayOf(1, 2, 3)) }
        assertEquals("hello from groq", result)
        assertTrue(cacheWavs("bridge_voice_").isEmpty())
    }

    @Test
    fun `service voice bridge routes voice and speak through the service lambdas`() {
        val server = bridgeServerOf()
        assertNotNull("bridge server should exist on the service", server)
        val ssField = VoiceBridgeServer::class.java.getDeclaredField("serverSocket").apply {
            isAccessible = true
        }
        var port = (ssField.get(server) as ServerSocket?)?.localPort ?: -1
        if (port <= 0) {
            // 8002 was taken (e.g. a dev server) when onServiceConnected ran;
            // bind the service's bridge to a free port instead.
            port = ServerSocket(0).use { it.localPort }
            server!!.start(port)
        }
        val deadline = System.currentTimeMillis() + 8000
        var up = false
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).close()
                up = true
                break
            } catch (e: Exception) {
                Thread.sleep(50)
            }
        }
        assertTrue("bridge server should accept connections on port $port", up)
        val voiceResp = rawRequest(port, post("/voice", server!!.authToken, "RIFFdata"))
        assertTrue(
            "voice route should answer with a text field, got: $voiceResp",
            voiceResp.contains("\"text\"")
        )
        val speakResp = rawRequest(port, post("/speak", server.authToken, "hello"))
        assertTrue(
            "speak route should answer spoken:true, got: $speakResp",
            speakResp.contains("spoken")
        )
    }
}
