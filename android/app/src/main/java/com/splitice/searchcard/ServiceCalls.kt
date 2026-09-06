package com.splitice.searchcard

import com.splitice.searchcard.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

/** One attempt per user submission; a connection retry never replays a call. */
internal class ServiceCalls(private val state: MutableStateFlow<PanelState>) {
    fun submit(
        scope: CoroutineScope, key: String, service: String, data: JsonElement,
        send: suspend (JsonObject) -> Unit,
    ) {
        if (!scope.isActive) return
        val parts = service.split('.')
        while (true) {
            val current = state.value
            if (!current.connected || key in current.busyActions) return
            if (parts.size != 2 || parts[1] !in current.snapshot?.services?.obj(parts[0]).orEmpty()) {
                state.update { it.copy(actionErrors = it.actionErrors + (key to "This control is not available on Home Assistant.")) }
                return
            }
            if (state.compareAndSet(current, current.copy(
                busyActions = current.busyActions + key, actionErrors = current.actionErrors - key,
            ))) break
        }
        // Start the try/finally immediately, so cancellation before the first
        // dispatch cannot leave a control permanently busy.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                ensureActive()
                send(buildJsonObject {
                    put("domain", parts[0]); put("service", parts[1]); put("service_data", data)
                })
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val message = if (error is HaCommandError) "Action failed: ${error.message}"
                    else "Action was not confirmed. Check its result before retrying. ${error.message.orEmpty()}"
                state.update { it.copy(actionErrors = it.actionErrors + (key to message)) }
            } finally { state.update { it.copy(busyActions = it.busyActions - key) } }
        }
    }
}
