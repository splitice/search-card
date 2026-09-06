package com.splitice.searchcard

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.splitice.searchcard.core.SearchResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PagedResultsTest {
    @get:Rule val compose = createComposeRule()
    private val results = (0 until 250).map { SearchResult.Entity("sensor.result_$it") }

    @Test fun scrollLoadsEveryBatchAndQueryChangeReturnsToFirstPage() {
        var query by mutableStateOf("sensor")
        compose.setContent {
            PagedResults(query, results, Modifier.height(300.dp), beforeResults = {
                item { Text("Connection status") }
            }) { Text((it as SearchResult.Entity).id, Modifier.fillMaxWidth().height(72.dp)) }
        }
        val list = compose.onNodeWithTag("search-results")
        list.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100 of 250 results loaded"))
        compose.onNodeWithText("sensor.result_100").assertDoesNotExist()
        list.performScrollToIndex(98)
        list.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "200 of 250 results loaded"))
        list.performScrollToIndex(198)
        list.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "250 of 250 results loaded"))
        list.performScrollToIndex(250)
        compose.onNodeWithText("sensor.result_249").assertIsDisplayed()
        compose.runOnIdle { query = "result" }
        list.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100 of 250 results loaded"))
        compose.onNodeWithText("sensor.result_0").assertIsDisplayed()
    }

    @Test fun loadedPageAndScrollPositionSurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PagedResults("sensor", results, Modifier.height(300.dp)) {
                Text((it as SearchResult.Entity).id, Modifier.fillMaxWidth().height(72.dp))
            }
        }
        val list = compose.onNodeWithTag("search-results")
        list.performScrollToIndex(98)
        list.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "200 of 250 results loaded"))
        list.performScrollToIndex(120)
        restoration.emulateSavedInstanceStateRestore()
        list.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "200 of 250 results loaded"))
        compose.onNodeWithText("sensor.result_120").assertIsDisplayed()
    }
}
