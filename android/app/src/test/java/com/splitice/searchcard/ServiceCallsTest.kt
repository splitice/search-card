package com.splitice.searchcard

import com.splitice.searchcard.core.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceCallsTest {
    private val id = "light.test"
    private val key = "entity:$id"
    private val data = buildJsonObject { put("entity_id", id) }

    @Test fun `rapid submissions reserve a control synchronously until the first response`() = runTest {
        val state = MutableStateFlow(controlState(id))
        val calls = ServiceCalls(state)
        val response = CompletableDeferred<Unit>()
        val requests = mutableListOf<JsonObject>()
        repeat(10) { calls.submit(backgroundScope, key, "light.turn_on", data) { requests += it; response.await() } }
        assertEquals(1, requests.size)
        assertEquals(setOf(key), state.value.busyActions)
        assertEquals("light", requests.single().text("domain"))
        assertEquals("turn_on", requests.single().text("service"))
        assertEquals(data, requests.single()["service_data"])
        response.complete(Unit)
        runCurrent()
        assertTrue(state.value.busyActions.isEmpty())
        assertTrue(state.value.actionErrors.isEmpty())
        calls.submit(backgroundScope, key, "light.turn_off", data) { requests += it }
        assertEquals(2, requests.size, "Only a new user submission may send again")
    }

    @Test fun `rejections and lost confirmations remain visible across reconnect and never replay`() = runTest {
        for (error in listOf(HaCommandError("Not permitted"), IOException("Connection lost"), IOException("Request timed out"))) {
            val state = MutableStateFlow(controlState(id))
            val calls = ServiceCalls(state)
            var sent = 0
            calls.submit(backgroundScope, key, "light.turn_on", data) { sent++; throw error }
            assertTrue(state.value.actionErrors[key]!!.contains(error.message!!))
            assertTrue(state.value.busyActions.isEmpty())
            state.value = state.value.copy(connected = false)
            state.value = state.value.copy(connected = true)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, sent)
            assertNotNull(state.value.actionErrors[key])
            calls.submit(backgroundScope, key, "light.turn_on", data) { sent++ }
            assertEquals(2, sent)
            assertNull(state.value.actionErrors[key], "An explicit retry clears only this row's error")
        }
    }

    @Test fun `dismissal cancels pending work and prevents new calls in a stopped scope`() = runTest {
        val state = MutableStateFlow(controlState(id))
        val calls = ServiceCalls(state)
        val owner = Job()
        val scope = CoroutineScope(coroutineContext + owner)
        var attempts = 0
        var cancelled = false
        calls.submit(scope, key, "light.turn_on", data) {
            attempts++
            try { awaitCancellation() } finally { cancelled = true }
        }
        owner.cancel(); runCurrent()
        assertTrue(cancelled)
        assertTrue(state.value.busyActions.isEmpty())
        calls.submit(scope, key, "light.turn_on", data) { attempts++ }
        assertEquals(1, attempts)
    }

    @Test fun `offline unknown services and invalid service names never send requests`() = runTest {
        val state = MutableStateFlow(controlState(id, connected = false))
        val calls = ServiceCalls(state)
        var attempts = 0
        calls.submit(backgroundScope, key, "light.turn_on", data) { attempts++ }
        state.value = state.value.copy(connected = true)
        for (service in listOf("invalid", "light.missing", "light.turn_on.extra")) {
            calls.submit(backgroundScope, key, service, data) { attempts++ }
            assertNotNull(state.value.actionErrors[key])
        }
        assertEquals(0, attempts)
    }
}
