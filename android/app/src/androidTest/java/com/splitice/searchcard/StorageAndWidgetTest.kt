package com.splitice.searchcard

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.splitice.searchcard.core.Snapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageAndWidgetTest {
    @Test fun tokenIsEncryptedAndLogoutRemovesOfflineData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = Storage(context)
        storage.saveRefreshToken("instrumented-test-token")
        assertEquals("instrumented-test-token", storage.refreshToken())
        val stored = context.getSharedPreferences("account", 0).getString("refreshToken", "")!!
        assertFalse(stored.contains("instrumented-test-token"))
        storage.saveSnapshot(Snapshot(savedAt = 1234))
        assertEquals(1234L, Storage(context).loadSnapshot()!!.savedAt)
        storage.clearAccount()
        assertNull(storage.refreshToken())
        assertNull(storage.loadSnapshot())
    }
    @Test fun widgetHasNoPeriodicUpdates() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val component = ComponentName(context, SearchWidget::class.java)
        val provider = AppWidgetManager.getInstance(context).installedProviders.first { it.provider == component }
        assertEquals(0, provider.updatePeriodMillis)
        assertEquals(android.appwidget.AppWidgetProviderInfo.RESIZE_HORIZONTAL, provider.resizeMode)
    }
}
