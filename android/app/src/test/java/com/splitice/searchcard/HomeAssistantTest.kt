package com.splitice.searchcard

import com.splitice.searchcard.core.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class HomeAssistantTest {
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
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
