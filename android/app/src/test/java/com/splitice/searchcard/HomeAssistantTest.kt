package com.splitice.searchcard

import com.splitice.searchcard.core.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

class HomeAssistantTest {
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    @Test fun `foreground client releases HTTP connections before the panel can stop`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"access","refresh_token":"refresh","expires_in":1800}"""))
            val client = foregroundHttpClient()
            val response = TokenClient(client).exchange(server.url("").toString().trimEnd('/'), "authorization_code", "code")
            assertEquals("access", response.text("access_token"))
            assertEquals(0, client.connectionPool.idleConnectionCount())
            assertEquals(0, client.connectionPool.connectionCount())
        }
    }

    @Test fun `authorization response body is never read on the calling UI thread`() {
        Executors.newSingleThreadExecutor { Thread(it, "login-test-ui") }.asCoroutineDispatcher().use { ui ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBodyDelay(100, TimeUnit.MILLISECONDS)
                    .setBody("""{"access_token":"access","refresh_token":"refresh","expires_in":1800}"""))
                val client = http.newBuilder().addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val body = response.body!!
                    val checkedSource = object : ForwardingSource(body.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            // Android's StrictMode rejects this same socket read on its main thread.
                            check(!Thread.currentThread().name.startsWith("login-test-ui")) { "Token body read on UI thread" }
                            return super.read(sink, byteCount)
                        }
                    }.buffer()
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source() = checkedSource
                    }).build()
                }.build()
                runBlocking(ui) {
                    val response = TokenClient(client).exchange(server.url("").toString().trimEnd('/'), "authorization_code", "code")
                    assertEquals("access", response.text("access_token"))
                    assertEquals("refresh", response.text("refresh_token"))
                }
                val request = server.takeRequest()
                assertEquals("/auth/token", request.path)
                assertTrue(request.body.readUtf8().contains("grant_type=authorization_code"))
            }
        }
    }

    @Test fun `cancelling sign-in cancels the HTTP call even after response headers arrive`() = runBlocking {
        MockWebServer().use { server ->
            val bodyReadStarted = CompletableDeferred<Unit>()
            val startedCall = CompletableDeferred<Call>()
            val client = http.newBuilder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { startedCall.complete(call) }
            }).addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body!!
                val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        bodyReadStarted.complete(Unit)
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = source
                }).build()
            }.build()
            server.enqueue(MockResponse().setBodyDelay(1500, TimeUnit.MILLISECONDS)
                .setBody("""{"access_token":"access","refresh_token":"refresh","expires_in":1800}"""))
            val exchange = launch(Dispatchers.Default) {
                TokenClient(client).exchange(server.url("").toString().trimEnd('/'), "authorization_code", "code")
            }
            withTimeout(2000) { bodyReadStarted.await() }
            withTimeout(1000) { exchange.cancelAndJoin() }
            assertTrue(startedCall.await().isCanceled(), "The response-body read must not keep running after cancellation")
        }
    }
    @Test fun `server-side logout errors are reported instead of silently accepted`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            assertFailsWith<java.io.IOException> { TokenClient(http).revoke(server.url("").toString().trimEnd('/'), "refresh") }
            assertEquals("/auth/revoke", server.takeRequest().path)
            server.enqueue(MockResponse().setResponseCode(200))
            TokenClient(http).revoke(server.url("").toString().trimEnd('/'), "refresh")
        }
    }
    @Test fun `token exchange handles expired and revoked credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400))
            assertFailsWith<LoginRequired> { TokenClient(http).exchange(server.url("").toString().trimEnd('/'), "refresh_token", "expired") }
            server.enqueue(MockResponse().setBody("""{"access_token":"new","expires_in":1800}"""))
            assertEquals("new", TokenClient(http).exchange(server.url("").toString().trimEnd('/'), "refresh_token", "valid").text("access_token"))
            assertTrue(server.takeRequest().body.readUtf8().contains("grant_type=refresh_token"))
        }
    }
    @Test fun `socket authenticates subscribes and dispatches service error without replay`() = runBlocking {
        val calls = AtomicInteger()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"auth_required"}""") }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JsonCodec.parseToJsonElement(text).jsonObject
                    when (message.text("type")) {
                        "auth" -> webSocket.send("""{"type":"auth_ok"}""")
                        "subscribe_events" -> {
                            webSocket.send("""{"id":${message["id"]},"type":"result","success":true,"result":null}""")
                            webSocket.send("""{"type":"event","event":{"data":{"entity_id":"light.a","new_state":null}}}""")
                        }
                        "call_service" -> {
                            calls.incrementAndGet()
                            webSocket.send("""{"id":${message["id"]},"type":"result","success":false,"error":{"message":"Not permitted"}}""")
                        }
                    }
                }
            }))
            val socket = HaSocket(http, server.url("/api/websocket").toString(), "access")
            try {
                socket.connect()
                socket.request("subscribe_events", buildJsonObject { put("event_type", "state_changed") })
                assertEquals("light.a", withTimeout(2000) { socket.events.receive() }.obj("data").text("entity_id"))
                assertFailsWith<HaCommandError> { socket.request("call_service") }
                assertEquals(1, calls.get())
            } finally { socket.close() }
        }
    }
    @Test fun `closing a foreground socket cancels pending work`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"auth_ok"}""") }
            }))
            val socket = HaSocket(http, server.url("/api/websocket").toString(), "token")
            socket.connect()
            supervisorScope {
                val pending = async { runCatching { socket.request("get_states") } }
                yield()
                socket.close()
                assertTrue(withTimeout(2000) { pending.await() }.isFailure)
            }
            assertTrue(withTimeout(2000) { socket.events.receiveCatching() }.isClosed)
        }
    }
    @Test fun `reopening uses a new authenticated connection and rejects invalid authentication`() = runBlocking {
        val authCount = AtomicInteger()
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"auth_required"}""") }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (JsonCodec.parseToJsonElement(text).jsonObject.text("type") == "auth") {
                            authCount.incrementAndGet()
                            webSocket.send("""{"type":"auth_ok"}""")
                        }
                    }
                }))
                val socket = HaSocket(http, server.url("/api/websocket").toString(), "token")
                socket.connect()
                socket.close()
            }
            assertEquals(2, authCount.get())
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"auth_invalid"}""") }
            }))
            val invalid = HaSocket(http, server.url("/api/websocket").toString(), "revoked")
            assertFailsWith<LoginRequired> { invalid.connect() }
            invalid.close()
        }
    }
}
