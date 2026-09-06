package com.splitice.searchcard

import com.splitice.searchcard.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SearchLabelActionsTest {
    private fun obj(value: String) = JsonCodec.parseToJsonElement(value).jsonObject
    private fun ready() = MutableStateFlow(PanelState(connected = true,
        snapshot = Snapshot(states = obj("""{"sensor.test":{}}"""))))

    @Test fun createsMissingLabelAndPreservesUnrelatedLabels() = runBlocking {
        val state = ready()
        val commands = mutableListOf<Pair<String, JsonObject>>()
        SearchLabelActions(state).submit(this, "sensor.test", SearchLabelAction.IGNORE) { type, fields ->
            commands += type to fields
            when (type) {
                "config/entity_registry/get" -> obj("""{"labels":["other"]}""")
                "config/label_registry/list" -> JsonArray(emptyList())
                "config/label_registry/create" -> obj("""{"label_id":"hidden-id"}""")
                else -> obj("{}")
            }
        }
        assertEquals("search_hidden", commands[2].second.text("name"))
        assertEquals(setOf("other", "hidden-id"), commands.last().second.array("labels").map { it.jsonPrimitive.content }.toSet())
        assertTrue("sensor.test" in state.value.snapshot!!.hidden)
        assertTrue(state.value.busyActions.isEmpty())
    }

    @Test fun togglesPriorityWithExistingLabelAndPreservesOtherLabels() = runBlocking {
        for (action in listOf(SearchLabelAction.PRIORITY, SearchLabelAction.UNPRIORITY)) {
            val state = ready()
            var updated = emptySet<String>()
            SearchLabelActions(state).submit(this, "sensor.test", action) { type, fields ->
                when (type) {
                    "config/entity_registry/get" -> obj(if (action.add) """{"labels":["other"]}""" else """{"labels":["other","p"]}""")
                    "config/label_registry/list" -> JsonCodec.parseToJsonElement("""[{"name":" SEARCH_PRIORITY ","label_id":"p"}]""")
                    "config/entity_registry/update" -> { updated = fields.array("labels").map { it.jsonPrimitive.content }.toSet(); obj("{}") }
                    else -> error("Unexpected command $type")
                }
            }
            assertEquals(if (action.add) setOf("other", "p") else setOf("other"), updated)
            assertEquals(action.add, "sensor.test" in state.value.snapshot!!.priority)
        }
    }

    @Test fun failuresDoNotChangeResultsAndOfflineDoesNotSend() = runBlocking {
        val state = ready()
        val original = state.value.snapshot
        SearchLabelActions(state).submit(this, "sensor.test", SearchLabelAction.IGNORE) { _, _ -> throw HaCommandError("Permission denied") }
        assertEquals(original, state.value.snapshot)
        assertTrue(state.value.actionErrors.getValue("search-label:sensor.test").contains("Permission denied"))
        assertTrue(state.value.busyActions.isEmpty())
        state.value = state.value.copy(connected = false)
        SearchLabelActions(state).submit(this, "sensor.test", SearchLabelAction.IGNORE) { _, _ -> error("Offline request") }
    }

    @Test fun duplicateSubmissionAndCancellationDoNotReplay() = runBlocking {
        val state = ready()
        val actions = SearchLabelActions(state)
        val owner = Job()
        val scope = CoroutineScope(coroutineContext + owner)
        var calls = 0
        val send: suspend (String, JsonObject) -> JsonElement = { _, _ -> calls++; awaitCancellation() }
        actions.submit(scope, "sensor.test", SearchLabelAction.IGNORE, send)
        actions.submit(scope, "sensor.test", SearchLabelAction.IGNORE, send)
        assertEquals(1, calls)
        owner.cancelAndJoin()
        assertTrue(state.value.busyActions.isEmpty())
        assertTrue(state.value.snapshot!!.hidden.isEmpty())
        assertTrue(state.value.actionErrors.isEmpty())
    }
}
