package com.splitice.searchcard

import com.splitice.searchcard.core.JsonCodec
import kotlin.test.*
import kotlinx.serialization.encodeToString

class AccountSettingsTest {
    @Test fun `new or missing dashboard settings never supply a server`() {
        assertEquals("", AccountSettings().dashboard)
        assertEquals("", JsonCodec.decodeFromString<AccountSettings>("{}").dashboard)
        assertEquals("", JsonCodec.decodeFromString<AccountSettings>("""{"selected":null}""").dashboard)
    }

    @Test fun `an explicitly saved dashboard survives loading and saving`() {
        val url = "https://ha.example:8123/dashboard-phone/main"
        val settings = JsonCodec.decodeFromString<AccountSettings>("""{"dashboard":"$url"}""")
        assertEquals(url, settings.dashboard)
        assertEquals(settings, JsonCodec.decodeFromString<AccountSettings>(JsonCodec.encodeToString(settings)))
    }
}
