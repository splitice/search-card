package com.splitice.searchcard

import android.content.Context
import android.content.Intent
import android.app.UiModeManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.splitice.searchcard.core.Snapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class WidgetPanelTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun widgetBoundsSurvivePendingIntentDeliveryAndDifferentWidgetLaunches() = withAccount { context ->
        val first = widgetBounds(context)
        val intent = Intent(context, MainActivity::class.java).setAction(SearchWidget.ACTION_OPEN_WIDGET)
            .apply { sourceBounds = first }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitForField()
            assertFieldBounds(context, first)
            val second = widgetBounds(context, narrow = true)
            val firstClick = SearchWidget.launchPendingIntent(context, 901)
            val secondClick = SearchWidget.launchPendingIntent(context, 902)
            try {
                assertFalse(secondClick.isImmutable)
                assertNotEquals(firstClick, secondClick)
                // Exercise RemoteViews' actual fill-in mechanism and singleTop/onNewIntent delivery.
                scenario.onActivity { secondClick.send(it, 0, Intent().apply { sourceBounds = second }) }
                compose.waitUntil(30_000) {
                    val nodes = compose.onAllNodesWithTag("widget-search-field").fetchSemanticsNodes()
                    nodes.size == 1 && kotlin.math.abs(nodes.single().boundsInWindow.width - second.width()) < 2f
                }
                scenario.onActivity { assertEquals(second, it.intent.sourceBounds) }
                assertFieldBounds(context, second)
                compose.onNodeWithContentDescription("Search options").performClick()
                compose.onNodeWithText("Settings").assertIsDisplayed()
                compose.onNodeWithText("Close", useUnmergedTree = true).performClick()
            } finally {
                firstClick.cancel()
                secondClick.cancel()
            }
        }
    }

    @Test fun recreationKeepsQueryAndMissingBoundsUseTheNormalPanel() = withAccount { context ->
        val bounds = widgetBounds(context)
        val intent = Intent(context, MainActivity::class.java).setAction(SearchWidget.ACTION_OPEN_WIDGET)
            .apply { sourceBounds = bounds }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitForField()
            compose.onNode(hasSetTextAction()).performTextInput("kitchen")
            scenario.recreate()
            waitForField()
            compose.onNode(hasSetTextAction()).assertTextContains("kitchen")
            assertFieldBounds(context, bounds)
            scenario.onActivity {
                it.startActivity(Intent(it, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("widget-search-field").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Close search").assertIsDisplayed()
        }
    }

    @Test fun rotationDiscardsOldCoordinatesAndThemeChangesKeepTheQuery() = withAccount { context ->
        val modes = context.getSystemService(UiModeManager::class.java)
        val originalMode = modes.nightMode
        val intent = Intent(context, MainActivity::class.java).setAction(SearchWidget.ACTION_OPEN_WIDGET)
            .apply { sourceBounds = widgetBounds(context) }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                waitForField()
                compose.onNode(hasSetTextAction()).performTextInput("kitchen")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(30_000) {
                    context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        compose.onAllNodesWithTag("widget-search-field").fetchSemanticsNodes().isEmpty()
                }
                compose.onNode(hasSetTextAction()).assertTextContains("kitchen")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                compose.waitUntil(30_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                for (mode in listOf(UiModeManager.MODE_NIGHT_YES, UiModeManager.MODE_NIGHT_NO)) {
                    modes.setApplicationNightMode(mode)
                    val expected = if (mode == UiModeManager.MODE_NIGHT_YES) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    compose.waitUntil(30_000) { context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == expected }
                    compose.onNode(hasSetTextAction()).assertTextContains("kitchen")
                    compose.onNodeWithContentDescription("Close search").assertIsDisplayed()
                }
            }
        } finally { modes.setApplicationNightMode(originalMode) }
    }

    private fun waitForField() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("widget-search-field").fetchSemanticsNodes().size == 1
        }
        compose.waitForIdle()
    }

    private fun assertFieldBounds(context: Context, expected: Rect) {
        val window = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val actual = compose.onNodeWithTag("widget-search-field").fetchSemanticsNode().boundsInWindow
        assertEquals((expected.left - window.left).toFloat(), actual.left, 1f)
        assertEquals((expected.top - window.top).toFloat(), actual.top, 1f)
        assertEquals(expected.width().toFloat(), actual.width, 1f)
        assertEquals(expected.height().toFloat(), actual.height, 1f)
    }

    private fun widgetBounds(context: Context, narrow: Boolean = false): Rect {
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val window = metrics.bounds
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val left = window.left + insets.left + dp(16)
        val top = window.top + insets.top + dp(16)
        val width = if (narrow) dp(120) else window.width() - insets.left - insets.right - dp(32)
        return Rect(left, top, left + width, top + dp(48))
    }

    private fun withAccount(block: (Context) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = Storage(context)
        storage.clearAccount()
        storage.settings = AccountSettings(dashboard = "https://127.0.0.1:1/dashboard-test/main")
        storage.saveRefreshToken("local-test-token")
        storage.saveSnapshot(Snapshot(savedAt = System.currentTimeMillis()))
        try { block(context) } finally {
            storage.clearAccount()
            storage.settings = AccountSettings()
        }
    }
}
