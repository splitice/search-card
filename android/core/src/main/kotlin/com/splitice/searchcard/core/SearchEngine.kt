package com.splitice.searchcard.core

import java.text.Collator
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.serialization.json.*

/** Pure Kotlin port. The original card is the oracle in tools/reference.mjs. */
class SearchEngine {
    private val transmission = JsonCodec.parseToJsonElement("""{
        "matches":"^((magnet:.*)|(.*.torrent.*))$", "name":"Add to Transmission",
        "icon":"mdi:progress-download", "service":"transmission.add_torrent",
        "service_data":{"torrent":"{1}"}}
    """).jsonObject

    fun search(query: String, snapshot: Snapshot): SearchOutput {
        // Match ECMAScript whitespace, including non-breaking spaces and BOM.
        val terms = query.lowercase(Locale.ROOT).split(Regex("[\\s\\p{Z}\\uFEFF]+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return SearchOutput()
        return try {
            val config = snapshot.config
            fun matchesQuery(fields: List<String>): Boolean {
                val normalized = fields.map { it.lowercase(Locale.ROOT) }
                return terms.all { term -> normalized.any { term in it } }
            }
            val includes = patterns(config["included_regex"], listOf(".")).map { compile(it, true) }
            val excludes = patterns(config["excluded_regex"], emptyList()).map { compile(it, true) }
            fun matches(fields: List<String>, regexes: List<Pattern>) =
                fields.any { field -> regexes.any { it.matcher(field).find() } }
            val collator = Collator.getInstance(Locale.US)
            val entities = snapshot.states.entries.filter { (id, value) ->
                val fields = listOfNotNull(id, (value as? JsonObject)?.obj("attributes")
                    ?.get("friendly_name")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content })
                matchesQuery(fields + listOfNotNull(snapshot.entityDeviceNames[id])) && matches(fields, includes) &&
                    !matches(fields, excludes) && id !in snapshot.hidden
            }.map { SearchResult.Entity(it.key) }.sortedWith { a, b ->
                collator.compare(a.id, b.id)
            }
            val local = config.obj("local_services").array("services")
                .mapNotNull { it as? JsonObject }.filter { service ->
                    service.text("name").isNotEmpty() && service.text("url").isNotEmpty() &&
                        matchesQuery(listOf(service.text("name"), service.text("category")) +
                            patterns(service["aliases"], emptyList()))
                }.map { SearchResult.LocalService(it) }
            val navigation = navigationPages(snapshot.panels).filter { matchesQuery(it.searchTerms) }
                .map { SearchResult.Navigation(it) }
            val results = (local + navigation + entities).sortedBy {
                when (it) {
                    is SearchResult.Entity -> when {
                        it.id in snapshot.priority -> 0
                        it.id.startsWith("automation.") -> 4
                        else -> 3
                    }
                    is SearchResult.LocalService -> 1
                    is SearchResult.Navigation -> 2
                }
            }
            val actions = (listOf(transmission) + config.array("actions").map { it.jsonObject }).mapNotNull { action ->
                val service = action.text("service")
                val parts = service.split('.')
                if (parts.size < 2 || parts[1] !in snapshot.services.obj(parts[0])) return@mapNotNull null
                val matcher = compile(action.text("matches"), false).matcher(query)
                if (!matcher.find()) return@mapNotNull null
                val groups = (0..matcher.groupCount()).map { matcher.group(it) ?: "undefined" }
                SearchAction(
                    substitute(action.text("name"), groups), service,
                    replaceValue(action["service_data"] ?: JsonObject(emptyMap()), groups),
                    action.text("icon").ifEmpty { "mdi:lamp" },
                )
            }
            SearchOutput(results, results.size, actions)
        } catch (error: IllegalArgumentException) {
            SearchOutput(error = "Invalid or unsupported search pattern: ${error.message}")
        }
    }

    private fun patterns(value: JsonElement?, default: List<String>): List<String> = when (value) {
        null -> default
        is JsonArray -> value.map { jsString(it) }
        else -> listOf(jsString(value))
    }
    private fun jsString(value: JsonElement) = when (value) {
        JsonNull -> "null"
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    private fun compile(pattern: String, ignoreCase: Boolean): Pattern {
        // Reject constructs with known, substantially different Java/ECMAScript semantics.
        require(!Regex("""\(\?[a-zA-Z-]|\\[pPQRXhHVZAGz]|\[\^?[^]]*&&|[+*?]\+|\}\+""").containsMatchIn(pattern)) {
            "Use standard JavaScript character classes, groups and quantifiers."
        }
        require(!pattern.contains("(?<") && !pattern.contains("(?>")) { "Named groups, lookbehind and atomic groups are not supported." }
        return Pattern.compile(pattern, if (ignoreCase) Pattern.CASE_INSENSITIVE else 0)
    }

    private fun replaceValue(value: JsonElement, groups: List<String>): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map { replaceValue(it, groups) })
        is JsonObject -> JsonObject(value.mapValues { replaceValue(it.value, groups) })
        is JsonPrimitive -> if (value.isString) JsonPrimitive(substitute(value.content, groups)) else value
    }

    /** JS String.replace replaces the first token and interprets $$, $&, $` and $'. */
    internal fun substitute(input: String, groups: List<String>): String {
        var text = input
        groups.forEachIndexed { i, replacement ->
            val token = "{$i}"
            val at = text.indexOf(token)
            if (at >= 0) {
                val prefix = text.substring(0, at)
                val suffix = text.substring(at + token.length)
                val expanded = buildString {
                    var offset = 0
                    while (offset < replacement.length) {
                        val c = replacement[offset]
                        if (c == '$' && offset + 1 < replacement.length) {
                            val special = when (replacement[offset + 1]) {
                                '$' -> "$"; '&' -> token; '`' -> prefix; '\'' -> suffix; else -> null
                            }
                            if (special != null) { append(special); offset += 2; continue }
                        }
                        append(c); offset++
                    }
                }
                text = prefix + expanded + suffix
            }
        }
        return text
    }
}
