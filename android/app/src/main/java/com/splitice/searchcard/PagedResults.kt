package com.splitice.searchcard

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.splitice.searchcard.core.SearchResult

/** The snapshot already contains every match; paging creates no network requests. */
@Composable
internal fun PagedResults(
    query: String,
    results: List<SearchResult>,
    modifier: Modifier = Modifier,
    showResults: Boolean = true,
    beforeResults: LazyListScope.() -> Unit = {},
    afterResults: LazyListScope.() -> Unit = {},
    row: @Composable (SearchResult) -> Unit,
) = key(query) {
    var loaded by rememberSaveable { mutableIntStateOf(100) }
    val count = if (showResults) minOf(loaded, results.size) else 0
    val list = rememberLazyListState()
    LaunchedEffect(count, results.size, showResults) {
        if (count >= results.size || !showResults) return@LaunchedEffect
        snapshotFlow {
            list.layoutInfo.visibleItemsInfo.mapNotNull {
                (it.key as? String)?.removePrefix("result:")?.toIntOrNull()
            }.maxOrNull() ?: -1
        }.collect { last ->
            if (last >= count - 10) loaded = minOf(count + 100, results.size)
        }
    }
    LazyColumn(
        modifier.testTag("search-results").semantics {
            stateDescription = "$count of ${results.size} results loaded"
        }, state = list, contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        beforeResults()
        // Index directly into the ordered snapshot, without copying or wrapping every item.
        items(count, key = { "result:$it" }, contentType = { results[it]::class }) { row(results[it]) }
        if (showResults && count < results.size) item(key = "more") {
            // Also reachable by accessibility services that move directly between controls.
            TextButton(onClick = { loaded = minOf(count + 100, results.size) }) { Text("More results") }
        }
        afterResults()
    }
}
