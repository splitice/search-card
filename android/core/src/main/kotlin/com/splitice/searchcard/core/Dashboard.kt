package com.splitice.searchcard.core

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

const val CLIENT_ID = "https://github.com/splitice/search-card"
const val AUTH_CALLBACK = "$CLIENT_ID/android/auth-callback"

data class DashboardAddress(val url: String, val origin: String, val dashboard: String, val view: String) {
    companion object {
        fun parse(value: String): DashboardAddress {
            val uri = URI(value.trim())
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) { "Enter an HTTPS dashboard URL." }
            val parts = uri.path.trim('/').split('/')
            require(parts.size == 2 && parts.all { it.isNotBlank() }) { "Include the dashboard and view paths." }
            require(uri.query == null && uri.fragment == null) { "Remove query parameters and fragments from the dashboard URL." }
            return DashboardAddress(value.trim(), "${uri.scheme}://${uri.rawAuthority}", parts[0], parts[1])
        }
    }
}

@Serializable
data class CardSelection(val path: String, val fingerprint: String)
data class CardCandidate(val path: String, val config: JsonObject) {
    val selection get() = CardSelection(path, fingerprint(config))
    val title get() = config.text("search_text", "Type to search...") + " · " + path
}
private fun fingerprint(config: JsonObject): String {
    fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map { canonical(it) })
        else -> value
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical(config).toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
}
fun discoverCards(dashboard: JsonObject, view: String): List<CardCandidate> {
    val views = dashboard.array("views").mapNotNull { it as? JsonObject }
    val target = views.firstOrNull { it.text("path") == view }
        ?: view.toIntOrNull()?.let { views.getOrNull(it) }
        ?: throw IllegalArgumentException("Dashboard view '$view' was not found.")
    val found = mutableListOf<CardCandidate>()
    fun walk(value: JsonElement, path: String) {
        when (value) {
            is JsonArray -> value.forEachIndexed { index, item -> walk(item, "$path/$index") }
            is JsonObject -> {
                if (value.text("type") == "custom:search-card") { found += CardCandidate(path, value); return }
                // Only static container keys; do not treat templates/actions as live cards.
                for (key in listOf("cards", "card", "sections")) value[key]?.let { walk(it, "$path/$key") }
            }
            else -> Unit
        }
    }
    walk(target, "view:$view")
    require(found.isNotEmpty()) { "No static custom:search-card found in this view. Template-generated cards are unsupported." }
    return found
}

/** Follow a uniquely matching card across reorders; permit edits at its original path. */
fun selectCard(cards: List<CardCandidate>, previous: CardSelection?): CardCandidate? {
    if (previous == null) return cards.singleOrNull()
    val identical = cards.filter { it.selection.fingerprint == previous.fingerprint }
    if (identical.size > 1) return null
    if (identical.size == 1) return identical.single()
    return cards.singleOrNull { it.path == previous.path }
}

/** Callback values are accepted only for the exact URI and this in-memory login attempt. */
fun authorizationCode(url: String, expectedState: String): String? {
    val uri = URI(url)
    val expected = URI(AUTH_CALLBACK)
    if (uri.scheme != expected.scheme || uri.rawAuthority != expected.rawAuthority || uri.path != expected.path) return null
    require(uri.fragment == null) { "Invalid authorization callback." }
    val fields = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
        val pair = it.split('=', limit = 2)
        java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
    }.groupBy({ it.first }, { it.second })
    require(expectedState.isNotEmpty() && fields["state"] == listOf(expectedState)) { "Login state did not match. Start sign-in again." }
    return fields["code"]?.singleOrNull()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Home Assistant did not return an authorization code.")
}
