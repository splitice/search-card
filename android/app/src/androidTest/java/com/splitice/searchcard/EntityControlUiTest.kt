package com.splitice.searchcard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.splitice.searchcard.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntityControlUiTest {
    @get:Rule val compose = createComposeRule()
    private val domains = listOf("light", "switch", "input_boolean", "scene", "script", "button", "input_button", "lock")
    private fun ready() = PanelState(connected = true, snapshot = Snapshot(
        states = buildJsonObject {
            for (domain in domains) put("$domain.test", buildJsonObject {
                put("state", if (domain == "lock") "unlocked" else if (domain in setOf("scene", "button", "input_button")) "unknown" else "off")
                put("attributes", buildJsonObject { put("friendly_name", "Example $domain") })
            })
        }, services = buildJsonObject {
            for (domain in domains) put(domain, buildJsonObject {
                put("turn_on", buildJsonObject {}); put("turn_off", buildJsonObject {})
                put("press", buildJsonObject {}); put("lock", buildJsonObject {}); put("unlock", buildJsonObject {})
            })
        },
    ))

    @Test fun everySupportedDomainHasASecondRowControlAtNarrowWidgetWidth() {
        val calls = mutableListOf<Pair<String, JsonElement>>()
        val details = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(180.dp).height(380.dp).verticalScroll(rememberScrollState())) {
                    for (domain in domains) EntityRow("$domain.test", ready(), details::add,
                        { _, service, data -> calls += service to data }, compact = true)
                }
            }
        }
        for (domain in domains) {
            val id = "$domain.test"
            compose.onNodeWithTag("entity-row:$id").performScrollTo()
            val name = compose.onNodeWithTag("entity-name:$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val value = compose.onNodeWithTag("entity-value:$id").fetchSemanticsNode().boundsInRoot
            val control = compose.onNodeWithTag("entity-control:$id")
            control.assertIsDisplayed().assertIsEnabled()
            val bounds = control.fetchSemanticsNode().boundsInRoot
            assertTrue("Name must be above the value/control row", name.bottom <= minOf(value.top, bounds.top))
            assertTrue("Value and control must share the second row", value.center.y in bounds.top..bounds.bottom)
            control.performTouchInput { click() }
            val service = if (domain == "lock") "lock" else if (domain in setOf("button", "input_button")) "press" else "turn_on"
            assertEquals("$domain.$service", calls.last().first)
            assertEquals(id, calls.last().second.jsonObject.text("entity_id"))
        }
        assertEquals(domains.size, calls.size)
        assertTrue("Controls must not launch Companion", details.isEmpty())
        compose.onNodeWithTag("entity-row:script.test").performScrollTo()
        compose.onNodeWithTag("entity-name:script.test", useUnmergedTree = true).performTouchInput { click() }
        assertEquals(listOf("script.test"), details)
    }

    @Test fun codedLockPromptsMasksAndForgetsCancelledCodes() {
        val calls = mutableListOf<Pair<String, JsonElement>>()
        val original = ready()
        val state = original.copy(snapshot = original.snapshot!!.copy(states = buildJsonObject {
            put("lock.test", buildJsonObject {
                put("state", "locked")
                put("attributes", buildJsonObject { put("friendly_name", "Front door"); put("code_format", "[0-9]{4}") })
            })
        }))
        compose.setContent { MaterialTheme {
            EntityRow("lock.test", state, { error("Lock controls must not navigate") },
                { _, service, data -> calls += service to data }, compact = false)
        } }
        compose.onNodeWithTag("entity-control:lock.test").performClick()
        compose.onNodeWithTag("lock-code").performTextInput("1234")
        compose.onNodeWithTag("lock-code").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("entity-control:lock.test").performClick()
        compose.onNodeWithTag("lock-code").assertTextContains("", substring = false)
        compose.onNodeWithTag("lock-code").performTextInput("5678")
        compose.onAllNodesWithText("Unlock").onLast().performClick()
        compose.runOnIdle {
            assertEquals(1, calls.size)
            assertEquals("lock.unlock", calls.single().first)
            assertEquals("5678", calls.single().second.jsonObject.text("code"))
            assertEquals("lock.test", calls.single().second.jsonObject.text("entity_id"))
        }
    }

    @Test fun pendingCallsDisableTheControlAndFailuresAppearOnTheSameResult() {
        val state = MutableStateFlow(ready())
        val runner = ServiceCalls(state)
        val response = CompletableDeferred<Unit>()
        var sent = 0
        var opened = 0
        compose.setContent {
            val current by state.collectAsState()
            val scope = rememberCoroutineScope()
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    EntityRow("light.test", current, { opened++ }, { key, service, data ->
                        runner.submit(scope, key, service, data) { sent++; response.await() }
                    }, compact = false)
                }
            }
        }
        val control = compose.onNodeWithTag("entity-control:light.test")
        control.performTouchInput { click() }
        control.assertIsNotEnabled()
        compose.onNodeWithText("Sending…").assertIsDisplayed()
        control.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, sent); assertEquals(0, opened); response.completeExceptionally(HaCommandError("Not permitted")) }
        compose.onNodeWithText("Action failed: Not permitted").assertIsDisplayed()
        control.assertIsEnabled()
        compose.runOnIdle { assertEquals(1, sent) }
    }

    @Test fun cachedAndUnavailableEntitiesKeepVisibleDisabledControls() {
        var state by mutableStateOf(ready().copy(connected = false))
        var opened = 0
        var sent = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(180.dp)) {
                    EntityRow("switch.test", state, { opened++ }, { _, _, _ -> sent++ }, compact = true)
                }
            }
        }
        val control = compose.onNodeWithTag("entity-control:switch.test")
        control.assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(0, opened); assertEquals(0, sent)
            val snapshot = ready().snapshot!!
            state = ready().copy(snapshot = snapshot.copy(states = JsonObject(snapshot.states +
                ("switch.test" to buildJsonObject { put("state", "unavailable") }))))
        }
        control.assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Unavailable", substring = false).assertIsDisplayed()
    }
}
