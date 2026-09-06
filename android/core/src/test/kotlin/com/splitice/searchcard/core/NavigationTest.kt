package com.splitice.searchcard.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class NavigationTest {
    @Test fun `visible pages have readable names and deterministic ordering`() {
        val panels = JsonCodec.parseToJsonElement("""{
            "settings":{"title":"config","url_path":"config","component_name":"config"},
            "dashboard":{"title":"Phone Dashboard","url_path":"dashboard-phone","component_name":"lovelace","icon":"mdi:cellphone"},
            "energy":{"title":"energy","url_path":"energy","component_name":"energy"},
            "hidden":{"title":"Secret","url_path":"secret","show_in_sidebar":false},
            "blank":{"title":"","url_path":"blank"},
            "internal":{"title":"App","url_path":"app"},
            "duplicate":{"title":"Duplicate","url_path":"energy"}
        }""").jsonObject
        val pages = navigationPages(panels)
        assertEquals(listOf("Energy", "Phone Dashboard", "Settings"), pages.map { it.name })
        assertEquals(listOf("/energy", "/dashboard-phone", "/config"), pages.map { it.path })
        assertEquals("mdi:cellphone", pages[1].icon)
    }

    @Test fun `old caches load without navigation and new caches preserve pages`() {
        assertEquals(JsonObject(emptyMap()), JsonCodec.decodeFromString<Snapshot>("{}").panels)
        val snapshot = Snapshot(panels = JsonCodec.parseToJsonElement("""{
            "energy":{"title":"energy","url_path":"energy","component_name":"energy"}
        }""").jsonObject)
        val cached = JsonCodec.decodeFromString<Snapshot>(JsonCodec.encodeToString(snapshot))
        assertEquals("/energy", SearchEngine().search("Energy", cached).results.filterIsInstance<SearchResult.Navigation>().single().panel.path)
    }

    @Test fun `external traversal and ambiguous paths are rejected`() {
        for (path in listOf("//evil.test", "/../config", "/%2f%2fevil", "/a\\b", "/a?b", "https://evil.test", "")) {
            assertFalse(isNavigationPath(path), path)
        }
        assertTrue(isNavigationPath("/dashboard-phone"))
        assertTrue(isNavigationPath("/config/automation"))
    }
}
