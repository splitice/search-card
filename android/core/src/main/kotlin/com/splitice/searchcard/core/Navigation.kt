package com.splitice.searchcard.core

import java.text.Collator
import java.util.Locale
import kotlinx.serialization.json.*

data class NavigationPage(val name: String, val path: String, val icon: String, val searchTerms: List<String>)

private val panelNames = mapOf(
    "energy" to "Energy", "history" to "History", "logbook" to "Activity", "map" to "Map",
    "calendar" to "Calendar", "config" to "Settings", "developer_tools" to "Developer tools",
    "lovelace" to "Overview", "home" to "Home", "todo" to "To-do lists", "media_browser" to "Media",
    "shopping_list" to "Shopping list", "hassio" to "Settings",
)
private val panelIcons = mapOf(
    "energy" to "mdi:lightning-bolt", "history" to "mdi:chart-box", "logbook" to "mdi:format-list-bulleted",
    "map" to "mdi:map", "calendar" to "mdi:calendar", "config" to "mdi:cog",
    "developer_tools" to "mdi:hammer", "lovelace" to "mdi:view-dashboard", "home" to "mdi:home",
    "todo" to "mdi:clipboard-list", "media_browser" to "mdi:play-box-multiple",
)

fun isNavigationPath(path: String) = Regex("^/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*$").matches(path)

/** get_panels already filters pages according to the authenticated user's admin permissions. */
fun navigationPages(panels: JsonObject): List<NavigationPage> {
    val seen = mutableSetOf<String>()
    val collator = Collator.getInstance(Locale.US)
    return panels.values.mapNotNull { it as? JsonObject }.mapNotNull { panel ->
        fun string(key: String) = (panel[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val title = string("title")
        val route = string("url_path")
        val path = "/$route"
        if (title.isBlank() || !isNavigationPath(path) || route in setOf("app", "notfound", "_my_redirect") ||
            panel["show_in_sidebar"] == JsonPrimitive(false) || panel["default_visible"] == JsonPrimitive(false) ||
            !seen.add(path)) return@mapNotNull null
        val name = panelNames[title] ?: title
        val component = string("component_name")
        NavigationPage(name, path, string("icon").ifEmpty { panelIcons[component] ?: "mdi:view-dashboard" },
            listOf(name, title, route, if (component == "lovelace") "dashboard" else component))
    }.sortedWith { a, b -> collator.compare(a.name, b.name) }
}
