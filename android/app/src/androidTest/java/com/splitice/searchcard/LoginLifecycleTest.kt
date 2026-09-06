package com.splitice.searchcard

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun firstLaunchLoginSurvivesAnInterruptionAndRotationUntilCancelled() {
        val storage = Storage(ApplicationProvider.getApplicationContext<Context>())
        storage.clearAccount()
        storage.settings = AccountSettings()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                compose.onNodeWithText("Dashboard URL").performTextInput("https://127.0.0.1:1/dashboard-test/main")
                compose.onNodeWithText("Continue to sign in").performClick()
                compose.onNodeWithText("https://127.0.0.1:1").assertIsDisplayed()

                // An autofill/password-manager activity can stop the app during login.
                activity.moveToState(Lifecycle.State.CREATED)
                activity.moveToState(Lifecycle.State.RESUMED)
                compose.onNodeWithText("https://127.0.0.1:1").assertIsDisplayed()
                compose.onNodeWithText("Cancel").assertIsDisplayed()

                activity.recreate()
                compose.onNodeWithText("https://127.0.0.1:1").assertIsDisplayed()
                compose.onNodeWithText("Cancel").performClick()
                compose.onNode(hasText("Sign in to Home Assistant") and hasClickAction()).assertIsDisplayed()
            }
        } finally {
            storage.clearAccount()
            storage.settings = AccountSettings()
        }
    }
}
