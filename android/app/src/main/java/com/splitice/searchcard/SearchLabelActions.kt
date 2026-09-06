package com.splitice.searchcard

import com.splitice.searchcard.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal enum class SearchLabelAction(val label: String, val add: Boolean) {
    IGNORE("search_hidden", true), PRIORITY("search_priority", true), UNPRIORITY("search_priority", false),
}

/** Registry writes are serialized and never replayed after a connection failure. */
internal class SearchLabelActions(private val state: MutableStateFlow<PanelState>) {
    private val mutex = Mutex()

    fun submit(scope: CoroutineScope, id: String, action: SearchLabelAction,
        request: suspend (String, JsonObject) -> JsonElement,
    ) {
        if (!scope.isActive) return
        val key = "search-label:$id"
        while (true) {
            val current = state.value
            if (!current.connected || key in current.busyActions || id !in current.snapshot?.states.orEmpty()) return
            if (state.compareAndSet(current, current.copy(busyActions = current.busyActions + key,
                    actionErrors = current.actionErrors - key))) break
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                mutex.withLock {
                    ensureActive()
                    // Fetch first: unregistered entities cannot be labeled, and unrelated labels must survive.
                    val entity = request("config/entity_registry/get", buildJsonObject { put("entity_id", id) }).jsonObject
                    val labels = request("config/label_registry/list", buildJsonObject {}).jsonArray
                    var labelId = labels.mapNotNull { it as? JsonObject }
                        .firstOrNull { it.text("name").trim().lowercase() == action.label }?.text("label_id")
                    if (labelId == null && action.add) {
                        labelId = request("config/label_registry/create", buildJsonObject { put("name", action.label) })
                            .jsonObject.text("label_id")
                        require(labelId.isNotEmpty()) { "Home Assistant returned an empty label ID." }
                    }
                    val existing = entity.array("labels").map { it.jsonPrimitive.content }.toSet()
                    val updated = if (labelId == null) existing else if (action.add) existing + labelId else existing - labelId
                    if (updated != existing) {
                        request("config/entity_registry/update", buildJsonObject {
                            put("entity_id", id); put("labels", JsonArray(updated.map(::JsonPrimitive)))
                        })
                    }
                    ensureActive()
                    state.update { current -> current.copy(snapshot = current.snapshot?.let { snapshot ->
                        when (action) {
                            SearchLabelAction.IGNORE -> snapshot.copy(hidden = snapshot.hidden + id)
                            SearchLabelAction.PRIORITY -> snapshot.copy(priority = snapshot.priority + id)
                            SearchLabelAction.UNPRIORITY -> snapshot.copy(priority = snapshot.priority - id)
                        }
                    }) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                state.update { it.copy(actionErrors = it.actionErrors + (key to
                    "Search label change was not confirmed. Refresh before retrying. ${error.message.orEmpty()}")) }
            } finally {
                state.update { it.copy(busyActions = it.busyActions - key) }
            }
        }
    }
}
