package com.splitice.searchcard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.splitice.searchcard.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditableEntityControlUiTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<Pair<String, JsonElement>>()
    private fun state(id: String, value: String, attributes: String): PanelState {
        val domain = id.substringBefore('.')
        return PanelState(connected = true, snapshot = Snapshot(
            states = buildJsonObject { put(id, buildJsonObject {
                put("state", value); put("attributes", JsonCodec.parseToJsonElement(attributes))
            }) }, services = buildJsonObject { put(domain, buildJsonObject {
                for (service in listOf("press", "set_value", "select_option")) put(service, buildJsonObject {})
            }) },
        ))
    }
    private fun row(id: String, state: PanelState) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(220.dp)) {
                    EntityRow(id, state, { error("Editing must not open entity details") },
                        { _, service, data -> calls += service to data }, compact = true)
                }
            }
        }
    }

    @Test fun selectUsesTheAvailableOptionsAndSubmitsOnceOnSelection() {
        val id = "input_select.test"
        row(id, state(id, "Day", """{"options":["Day","Night"]}"""))
        compose.onNodeWithTag("entity-control:$id").performClick()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Night").performClick()
        compose.runOnIdle {
            assertEquals(1, calls.size)
            assertEquals("input_select.select_option", calls.single().first)
            assertEquals("Night", calls.single().second.jsonObject.text("option"))
        }
    }

    @Test fun textEditsStayLocalUntilSaveAndInvalidValuesCannotBeSent() {
        val id = "input_text.test"
        row(id, state(id, "old", """{"min":2,"max":5,"pattern":"[a-z]+"}"""))
        val editor = compose.onNodeWithTag("entity-control:$id")
        val save = compose.onNodeWithTag("entity-save:$id")
        editor.performTextReplacement("x")
        save.assertIsNotEnabled()
        editor.performTextReplacement("hello")
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        save.performClick()
        compose.runOnIdle {
            assertEquals("input_text.set_value", calls.single().first)
            assertEquals(JsonPrimitive("hello"), calls.single().second.jsonObject["value"])
        }
    }

    @Test fun numberBoxValidatesBoundsAndSubmitsANumberUsingTheKeyboardDoneAction() {
        val id = "number.test"
        row(id, state(id, "0", """{"min":-1,"max":1,"step":0.1,"mode":"box"}"""))
        val editor = compose.onNodeWithTag("entity-control:$id")
        editor.performTextReplacement("2")
        compose.onNodeWithTag("entity-save:$id").assertIsNotEnabled()
        editor.performTextReplacement("0.3")
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        editor.performImeAction()
        compose.runOnIdle {
            assertEquals(1, calls.size)
            val payload = calls.single().second.jsonObject["value"]!!.jsonPrimitive
            assertFalse(payload.isString)
            assertEquals("0.3", payload.content)
        }
    }

    @Test fun sliderSendsOnlyWhenTheGestureEnds() {
        val id = "input_number.test"
        row(id, state(id, "0", """{"min":0,"max":10,"step":1,"mode":"slider"}"""))
        val slider = compose.onNodeWithTag("entity-control:$id")
        slider.performTouchInput {
            down(Offset(width * .2f, height / 2f))
            moveTo(Offset(width * .7f, height / 2f))
        }
        compose.runOnIdle { assertTrue("Dragging must not send requests", calls.isEmpty()) }
        slider.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, calls.size)
            assertEquals("input_number.set_value", calls.single().first)
            val number = calls.single().second.jsonObject["value"]!!.jsonPrimitive
            assertFalse(number.isString)
            assertTrue(number.int in 1..10)
        }
    }

    @Test fun passwordTextIsMarkedAsPasswordAndNeverPrintedBesideTheEditor() {
        val id = "text.test"
        row(id, state(id, "private-value", """{"min":0,"max":100,"mode":"password"}"""))
        compose.onNodeWithTag("entity-control:$id").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onAllNodesWithTag("entity-value:$id").assertCountEquals(0)
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test fun backgroundStateUpdatesDoNotOverwriteAnUnsubmittedDraft() {
        val id = "text.test"
        var current by mutableStateOf(state(id, "old", """{"min":0,"max":100}"""))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(220.dp)) {
                    EntityRow(id, current, {}, { _, service, data -> calls += service to data }, compact = true)
                }
            }
        }
        val editor = compose.onNodeWithTag("entity-control:$id")
        editor.performTextReplacement("my draft")
        compose.runOnIdle { current = state(id, "server update", """{"min":0,"max":100}""") }
        editor.assertTextContains("my draft")
        compose.onNodeWithTag("entity-save:$id").performClick()
        compose.runOnIdle { assertEquals("my draft", calls.single().second.jsonObject.text("value")) }
    }
}
