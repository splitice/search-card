package com.splitice.searchcard

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.splitice.searchcard.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchLabelMenuTest {
    @get:Rule val compose = createComposeRule()
    private fun ready(priority: Boolean = false, connected: Boolean = true) = PanelState(connected = connected,
        snapshot = Snapshot(states = JsonCodec.parseToJsonElement("""{"sensor.test":{"state":"20","attributes":{"friendly_name":"Temperature"}}}""").jsonObject,
            priority = if (priority) setOf("sensor.test") else emptySet()))

    @Test fun longPressOffersIgnoreAndTogglesPriorityWithoutOpeningDetails() {
        val actions = mutableListOf<SearchLabelAction>()
        val details = mutableListOf<String>()
        val state = mutableStateOf(ready())
        compose.setContent {
            MaterialTheme {
                EntityRow("sensor.test", state.value, details::add, { _, _, _ -> error("No service call") }, true,
                    updateSearchLabel = { _, action ->
                        actions += action
                        if (action == SearchLabelAction.PRIORITY) state.value = ready(priority = true)
                    })
            }
        }
        compose.onNodeWithText("Temperature").performTouchInput { longClick() }
        compose.onNodeWithText("Ignore").assertIsDisplayed()
        compose.onNodeWithText("Priority").performClick()
        compose.runOnIdle { assertEquals(listOf(SearchLabelAction.PRIORITY), actions); assertTrue(details.isEmpty()) }
        compose.onNodeWithText("Temperature").performTouchInput { longClick() }
        compose.onNodeWithText("Unpriority").performClick()
        compose.onNodeWithText("Temperature").performTouchInput { longClick() }
        compose.onNodeWithText("Ignore").performClick()
        compose.runOnIdle { assertEquals(listOf(SearchLabelAction.PRIORITY, SearchLabelAction.UNPRIORITY, SearchLabelAction.IGNORE), actions) }
        compose.onNodeWithText("Temperature").performClick()
        compose.runOnIdle { assertEquals(listOf("sensor.test"), details) }
    }

    @Test fun offlineMenuIsAvailableButCannotSubmit() {
        compose.setContent {
            MaterialTheme { EntityRow("sensor.test", ready(connected = false), {}, { _, _, _ -> }, true) }
        }
        compose.onNodeWithText("Temperature").performTouchInput { longClick() }
        compose.onNodeWithText("Ignore").assertIsNotEnabled()
        compose.onNodeWithText("Priority").assertIsNotEnabled()
    }
}
