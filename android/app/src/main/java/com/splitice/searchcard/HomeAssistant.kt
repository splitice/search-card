package com.splitice.searchcard

import com.splitice.searchcard.core.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*

class LoginRequired : IOException("Please sign in to Home Assistant again.")
class HaCommandError(message: String) : IOException(message)

suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

class TokenClient(private val http: OkHttpClient) {
    suspend fun exchange(origin: String, grant: String, value: String): JsonObject {
        val body = FormBody.Builder().add("grant_type", grant).add("client_id", CLIENT_ID)
            .add(if (grant == "authorization_code") "code" else "refresh_token", value).build()
        return http.newCall(Request.Builder().url("$origin/auth/token").post(body).build()).awaitResponse().use {
            if (it.code in listOf(400, 401, 403)) throw LoginRequired()
            if (!it.isSuccessful) throw IOException("Sign-in request failed (${it.code}).")
            JsonCodec.parseToJsonElement(it.body!!.string()).jsonObject
        }
    }
    suspend fun revoke(origin: String, refreshToken: String) {
        http.newCall(Request.Builder().url("$origin/auth/revoke")
            .post(FormBody.Builder().add("token", refreshToken).build()).build()).awaitResponse().use {
                if (!it.isSuccessful) throw IOException("Token revocation failed (${it.code}).")
            }
    }
}

/** One foreground session owns this socket. close() also fails every pending request. */
class HaSocket(private val http: OkHttpClient, private val endpoint: String, private val token: String) {
    private val auth = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val ids = AtomicInteger(0)
    val events = Channel<JsonObject>(Channel.UNLIMITED)
    private var socket: WebSocket? = null
    @Volatile private var closed = false

    suspend fun connect() {
        socket = http.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JsonCodec.parseToJsonElement(text).jsonObject
                    when (message.text("type")) {
                        "auth_required" -> webSocket.send(buildJsonObject { put("type", "auth"); put("access_token", token) }.toString())
                        "auth_ok" -> auth.complete(Unit)
                        "auth_invalid" -> fail(LoginRequired())
                        "result" -> {
                            val request = pending.remove((message["id"] as? JsonPrimitive)?.intOrNull) ?: return
                            if (message["success"] == JsonPrimitive(true)) request.complete(message["result"] ?: JsonNull)
                            else request.completeExceptionally(HaCommandError(message.obj("error").text("message", "Home Assistant rejected the request.")))
                        }
                        "event" -> events.trySend(message.obj("event"))
                    }
                } catch (error: Exception) { fail(IOException("Invalid Home Assistant response.", error)) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail(IOException("Connection lost. Check your network and server.", t)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); fail(IOException("Home Assistant closed the connection.")) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(IOException("Connection closed.")) }
        })
        try { withTimeout(20_000) { auth.await() } }
        catch (error: TimeoutCancellationException) {
            close(); currentCoroutineContext().ensureActive()
            throw IOException("Timed out connecting to Home Assistant.", error)
        }
        catch (error: Exception) { close(); throw error }
    }
    suspend fun request(type: String, fields: JsonObject = JsonObject(emptyMap())): JsonElement {
        check(!closed) { "Connection is closed." }
        val id = ids.incrementAndGet()
        val result = CompletableDeferred<JsonElement>()
        pending[id] = result
        try {
            val payload = JsonObject(fields + mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive(type)))
            if (socket?.send(payload.toString()) != true) throw IOException("Could not send request.")
            return withTimeout(20_000) { result.await() }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Home Assistant did not confirm the request in time.", error)
        } finally { pending.remove(id); result.cancel() }
    }
    private fun fail(error: Exception) {
        closed = true
        auth.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        events.close(error)
    }
    fun close() {
        closed = true
        socket?.cancel() // No close-handshake timer or heartbeat left behind in the background.
        fail(IOException("Session ended."))
    }
}
