package com.splitice.searchcard.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

val JsonCodec = Json { ignoreUnknownKeys = true }
fun JsonObject.text(key: String, default: String = ""): String =
    (get(key) as? JsonPrimitive)?.contentOrNull ?: default
fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())

@Serializable
data class Snapshot(
    val config: JsonObject = JsonObject(emptyMap()),
    val states: JsonObject = JsonObject(emptyMap()),
    val services: JsonObject = JsonObject(emptyMap()),
    val priority: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val savedAt: Long = 0,
    val panels: JsonObject = JsonObject(emptyMap()),
)

sealed interface SearchResult {
    data class Entity(val id: String) : SearchResult
    data class LocalService(val service: JsonObject) : SearchResult
    data class Navigation(val panel: NavigationPage) : SearchResult
}
data class SearchAction(val name: String, val service: String, val data: JsonElement, val icon: String)
data class SearchOutput(
    val results: List<SearchResult> = emptyList(),
    val total: Int = 0,
    val actions: List<SearchAction> = emptyList(),
    val error: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("results", JsonArray(results.map {
            when (it) {
                is SearchResult.Entity -> buildJsonObject { put("type", "entity"); put("entity_id", it.id) }
                is SearchResult.LocalService -> buildJsonObject { put("type", "local_service"); put("service", it.service) }
                is SearchResult.Navigation -> buildJsonObject {
                    put("type", "navigation"); put("panel", buildJsonObject {
                        put("name", it.panel.name); put("path", it.panel.path); put("icon", it.panel.icon)
                    })
                }
            }
        }))
        put("total", total)
        put("actions", JsonArray(actions.map {
            buildJsonObject {
                put("name", it.name); put("service", it.service); put("service_data", it.data); put("icon", it.icon)
            }
        }))
    }
}

fun labelSets(labels: JsonArray, registry: JsonObject): Pair<Set<String>, Set<String>> {
    val ids = labels.mapNotNull { it as? JsonObject }.associate {
        it.text("name").trim().lowercase() to it.text("label_id")
    }
    fun entities(name: String): Set<String> {
        val label = ids[name] ?: return emptySet()
        return registry.array("entities").mapNotNull { it as? JsonObject }.filter {
            it.array("lb").any { value -> (value as? JsonPrimitive)?.content == label }
        }.map { it.text("ei") }.filter { it.isNotEmpty() }.toSet()
    }
    return entities("search_priority") to entities("search_hidden")
}

/** Keep the newest server version when buffered events overlap the initial snapshot. */
fun applyStateEvent(states: JsonObject, event: JsonObject): JsonObject {
    val data = event.obj("data")
    val id = data.text("entity_id")
    if (id.isEmpty()) return states
    val next = data["new_state"] as? JsonObject
    val previous = states[id] as? JsonObject
    val nextTime = next?.text("last_updated") ?: event.text("time_fired")
    val previousTime = previous?.text("last_updated").orEmpty()
    if (nextTime.isNotEmpty() && previousTime.isNotEmpty() &&
        java.time.Instant.parse(nextTime).isBefore(java.time.Instant.parse(previousTime))) return states
    return JsonObject(states.toMutableMap().apply { if (next == null) remove(id) else put(id, next) })
}
