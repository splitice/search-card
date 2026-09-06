package com.splitice.searchcard.core

import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

class ParityTest {
    @Test fun `Kotlin matches the actual web card for every shared fixture`() {
        val fixtures = JsonCodec.parseToJsonElement(File(System.getProperty("fixtures")).readText()).jsonObject
        val reference = JsonCodec.parseToJsonElement(File(System.getProperty("reference")).readText()).jsonArray
        val base = fixtures.obj("base")
        assertTrue(reference.size >= 20, "Keep broad behavior coverage")
        for ((index, item) in fixtures.array("cases").withIndex()) {
            val case = item.jsonObject
            val input = JsonObject(base + case)
            val generated = input["generated_entities"]?.jsonPrimitive?.intOrNull
            val states = if (generated == null) input.obj("states") else buildJsonObject {
                repeat(generated) { index ->
                    val id = "sensor.bulk_${index.toString().padStart(3, '0')}"
                    put(id, buildJsonObject { put("entity_id", id); put("state", "on"); put("attributes", buildJsonObject {}) })
                }
            }
            val snapshot = Snapshot(
                config = JsonObject(base.obj("config") + case.obj("config")), states = states, services = input.obj("services"),
                priority = input.array("priority").map { it.jsonPrimitive.content }.toSet(),
                hidden = input.array("hidden").map { it.jsonPrimitive.content }.toSet(),
                panels = input.obj("panels"),
            )
            val actual = SearchEngine().search(input.text("query"), snapshot)
            assertEquals(reference[index].jsonObject["output"], actual.toJson(), case.text("name"))
            assertEquals(reference[index].jsonObject.array("navigations").map { it.jsonPrimitive.content },
                actual.results.filterIsInstance<SearchResult.Navigation>().map { it.panel.path }, case.text("name"))
        }
    }
    @Test fun `unsupported patterns give useful errors`() {
        for (query in listOf("(?i)lamp", "(?<named>lamp)", "a++", "[a-z&&[^x]]")) {
            assertNotNull(SearchEngine().search(query, Snapshot()).error, query)
        }
    }
}
