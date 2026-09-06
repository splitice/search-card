package com.splitice.searchcard

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.splitice.searchcard.core.JsonCodec
import com.splitice.searchcard.core.text
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only account smoke test. Credentials are supplied through private app storage. */
@RunWith(AndroidJUnit4::class)
class LiveAccountTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun nativeLoginCacheReconnectDismissalAndLogout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accountFile = File(context.filesDir, "runtime-account.json")
        assumeTrue("Live account was not supplied", accountFile.exists())
        val account = JsonCodec.parseToJsonElement(accountFile.readText()).jsonObject
        accountFile.delete()
        val storage = Storage(context)
        storage.clearAccount()
        storage.settings = AccountSettings()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithText("Dashboard URL").performTextInput(account.text("dashboard"))
                compose.onNodeWithText("Continue to sign in").performClick()
                compose.waitUntil(90_000) { webScript("""
                    (() => {
                      const nodes = [];
                      function visit(root) {
                        for (const el of root.querySelectorAll('*')) {
                          nodes.push(el);
                          if (el.shadowRoot) visit(el.shadowRoot);
                        }
                      }
                      visit(document);
                      const username = nodes.find(el => el.matches('input[name="username"]'));
                      const password = nodes.find(el => el.matches('input[type="password"]'));
                      const button = nodes.find(el => /^(Log in|Sign in)${'$'}/i.test(el.textContent.trim()) &&
                        (el.matches('button, ha-button, mwc-button')));
                      if (!username || !password || !button) return false;
                      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                      set.call(username, ${JsonPrimitive(account.text("username"))});
                      username.dispatchEvent(new Event('input', {bubbles:true, composed:true}));
                      set.call(password, ${JsonPrimitive(account.text("password"))});
                      password.dispatchEvent(new Event('input', {bubbles:true, composed:true}));
                      button.click();
                      return true;
                    })()
                """.trimIndent()) == "true" }
                lateinit var model: SearchViewModel
                scenario.onActivity { model = ViewModelProvider(it)[SearchViewModel::class.java] }
                compose.waitUntil(90_000) { model.state.value.connected }
                assertFalse(model.state.value.needsLogin)
                assertTrue(storage.hasSavedSession)
                assertTrue(model.state.value.snapshot!!.states.isNotEmpty())
                compose.onAllNodes(hasText("Sign in to Home Assistant") and hasClickAction()).assertCountEquals(0)

                // Real state/configuration must produce controls, without operating real devices.
                val snapshot = model.state.value.snapshot!!
                assertTrue(snapshot.states.keys.any { id ->
                    id.substringBefore('.') in setOf("light", "switch", "input_boolean") &&
                        entityControl(id, model.state.value) != null
                })
                assertNotNull(storage.loadSnapshot())
                scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse(model.state.value.connected)
                SystemClock.sleep(2_000) // Let cancellation/FIN packets drain before measurement.
                val tx = TrafficStats.getUidTxBytes(Process.myUid())
                val rx = TrafficStats.getUidRxBytes(Process.myUid())
                assertTrue("UID traffic counters must be available", tx >= 0 && rx >= 0)
                SystemClock.sleep(5_000)
                assertEquals("No continuing network sends after dismissal", tx, TrafficStats.getUidTxBytes(Process.myUid()))
                assertEquals("No continuing network receives after dismissal", rx, TrafficStats.getUidRxBytes(Process.myUid()))
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertFalse(model.state.value.needsLogin)
                assertNotNull(model.state.value.snapshot)
                compose.waitUntil(60_000) { model.state.value.connected }
                scenario.onActivity { model.logout() }
                assertFalse(storage.hasSavedSession)
                assertNull(storage.loadSnapshot())
                compose.onNode(hasText("Sign in to Home Assistant") and hasClickAction()).assertIsDisplayed()
                SystemClock.sleep(2_000) // Allow this test session's best-effort revocation to finish.
            }
        } finally {
            accountFile.delete()
            storage.clearAccount()
            storage.settings = AccountSettings()
        }
    }

    private fun webScript(script: String): String? {
        var result: String? = null
        val completed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fun find(view: View): WebView? {
                if (view is WebView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            val web = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
            if (web == null) completed.countDown()
            else web.evaluateJavascript(script) { result = it; completed.countDown() }
        }
        completed.await(2, TimeUnit.SECONDS)
        return result
    }
}
