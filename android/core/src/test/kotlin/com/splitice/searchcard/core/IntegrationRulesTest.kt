package com.splitice.searchcard.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class IntegrationRulesTest {
    private fun obj(json: String) = JsonCodec.parseToJsonElement(json).jsonObject
    private val card = obj("""{"type":"custom:search-card","search_text":"Search me"}""")
    private fun dashboard(cards: JsonElement) = buildJsonObject { put("views", buildJsonArray { add(buildJsonObject { put("path", "main"); put("cards", cards) }) }) }
    @Test fun `discovers static nested and section cards`() {
        val config = obj("""{"views":[{"path":"main","sections":[{"cards":[{"type":"vertical-stack","cards":[{"type":"conditional","card":{"type":"custom:search-card"}}]}]}]}]}""")
        assertEquals(1, discoverCards(config, "main").size)
        assertFailsWith<IllegalArgumentException> { discoverCards(config, "missing") }
        assertFailsWith<IllegalArgumentException> { discoverCards(dashboard(JsonArray(emptyList())), "main") }
    }
    @Test fun `selection follows reordering and does not silently choose ambiguous or deleted cards`() {
        val original = CardCandidate("view:main/cards/0", card)
        assertEquals(original, selectCard(listOf(original), null))
        assertNull(selectCard(listOf(original, original.copy(path = "second")), original.selection))
        val moved = original.copy(path = "third")
        assertEquals(moved, selectCard(listOf(moved), original.selection))
        assertNull(selectCard(listOf(CardCandidate("other", obj("""{"type":"custom:search-card"}"""))), original.selection))
        val edited = original.copy(config = obj("""{"type":"custom:search-card","max_results":4}"""))
        assertEquals(edited, selectCard(listOf(edited), original.selection))
    }
    @Test fun `validates HTTPS dashboard and exact login callback`() {
        val dashboardUrl = "https://ha.example:8123/dashboard-phone/main"
        val address = DashboardAddress.parse(dashboardUrl)
        assertEquals("https://ha.example:8123", address.origin)
        assertEquals("dashboard-phone", address.dashboard)
        assertEquals("main", address.view)
        for (url in listOf("", "   ", "http://ha/main/view", "https://u:p@ha/main/view", "https://ha/main", "$dashboardUrl?x=1")) {
            assertFailsWith<IllegalArgumentException> { DashboardAddress.parse(url) }
        }
        assertEquals("abc", authorizationCode("$AUTH_CALLBACK?state=nonce&code=abc", "nonce"))
        assertNull(authorizationCode("https://evil.test/android/auth-callback?state=nonce&code=abc", "nonce"))
        assertFailsWith<IllegalArgumentException> { authorizationCode("$AUTH_CALLBACK?state=wrong&code=abc", "nonce") }
        assertFailsWith<IllegalArgumentException> { authorizationCode("$AUTH_CALLBACK?state=nonce&state=nonce&code=abc", "nonce") }
    }
    @Test fun `labels map display registry abbreviations and trim names`() {
        val labels = JsonCodec.parseToJsonElement("""[{"name":" SEARCH_PRIORITY ","label_id":"p"},{"name":"search_hidden","label_id":"h"}]""").jsonArray
        val (priority, hidden) = labelSets(labels, obj("""{"entities":[{"ei":"light.a","lb":["p"]},{"ei":"light.b","lb":["h","p"]},{"ei":"light.c"}]}"""))
        assertEquals(setOf("light.a", "light.b"), priority)
        assertEquals(setOf("light.b"), hidden)
    }
    @Test fun `device metadata resolves overrides fallback and absent links`() {
        val registry = obj("""{"entities":[{"ei":"sensor.a","di":"a"},{"ei":"sensor.b","di":"b"},{"ei":"sensor.c"},{"ei":"sensor.d","di":"missing"}]}""")
        val devices = JsonCodec.parseToJsonElement("""[{"id":"a","name":"Original","name_by_user":"Rumpus Motion"},{"id":"b","name":"Fallback","name_by_user":null}]""").jsonArray
        assertEquals(mapOf("sensor.a" to "Rumpus Motion", "sensor.b" to "Fallback"), entityDeviceNames(registry, devices))
        assertTrue(JsonCodec.decodeFromString<Snapshot>("{}").entityDeviceNames.isEmpty())
    }
    @Test fun `snapshot reconciliation ignores old events and applies updates and removal`() {
        val current = obj("""{"light.a":{"state":"on","last_updated":"2026-01-01T00:00:02Z"}}""")
        val old = obj("""{"data":{"entity_id":"light.a","new_state":{"state":"off","last_updated":"2026-01-01T00:00:01Z"}}}""")
        assertEquals(current, applyStateEvent(current, old))
        val newer = obj("""{"data":{"entity_id":"light.a","new_state":{"state":"off","last_updated":"2026-01-01T00:00:03Z"}}}""")
        assertEquals("off", applyStateEvent(current, newer).obj("light.a").text("state"))
        val deletion = obj("""{"time_fired":"2026-01-01T00:00:04Z","data":{"entity_id":"light.a","new_state":null}}""")
        assertTrue(applyStateEvent(current, deletion).isEmpty())
    }
    @Test fun `offline snapshot roundtrips without authentication data`() {
        val snapshot = Snapshot(config = card, states = obj("""{"light.a":{"state":"unavailable"}}"""), hidden = setOf("light.a"), savedAt = 1234, entityDeviceNames = mapOf("light.a" to "Rumpus Motion"))
        val encoded = JsonCodec.encodeToString(snapshot)
        assertEquals(snapshot, JsonCodec.decodeFromString<Snapshot>(encoded))
        assertFalse(encoded.contains("token"))
    }
}
