package com.splitice.searchcard

import android.content.Context
import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.splitice.searchcard.core.Snapshot
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PanelLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun widgetCanBeDismissedImmediatelyAndReopenedRepeatedly() = withStoredAccount { context ->
        repeat(12) {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(SearchWidget.ACTION_OPEN_WIDGET)
                .apply { sourceBounds = android.graphics.Rect(16, 100, 300, 148) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                // Avoid Compose's idle synchronization: dismissal must race the initial frame.
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[SearchViewModel::class.java]
                    model.state.value = model.state.value.copy(connected = true)
                    activity.finish()
                    assertFalse("Controls must be disabled before onStop/closing animations", model.state.value.connected)
                }
            }
        }
    }

    @Test fun normalOpenFocusesSearchAndBackgroundTapClosesThePanel() = withStoredAccount {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Dismiss search").performTouchInput {
                click(Offset(center.x, height * .06f))
            }
            compose.waitUntil(30_000) { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    private fun withStoredAccount(block: (Context) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = Storage(context)
        storage.clearAccount()
        storage.settings = AccountSettings(dashboard = "https://127.0.0.1:1/dashboard-test/main")
        storage.saveRefreshToken("local-test-token")
        storage.saveSnapshot(Snapshot(savedAt = System.currentTimeMillis()))
        try {
            block(context)
        } finally {
            storage.clearAccount()
            storage.settings = AccountSettings()
        }
    }
}
