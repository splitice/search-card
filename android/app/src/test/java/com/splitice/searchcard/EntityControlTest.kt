package com.splitice.searchcard

import com.splitice.searchcard.core.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal fun controlState(id: String, value: String = "off", connected: Boolean = true, attributes: JsonObject = buildJsonObject {}): PanelState {
    val domain = id.substringBefore('.')
    return PanelState(connected = connected, snapshot = Snapshot(
        states = buildJsonObject { put(id, buildJsonObject { put("state", value); put("attributes", attributes) }) },
        services = buildJsonObject { put(domain, buildJsonObject {
            for (service in listOf("turn_on", "turn_off", "press", "set_value", "select_option")) put(service, buildJsonObject {})
        }) },
    ))
}

class EntityControlTest {
    @Test fun `native toggles issue explicit on and off services for every supported domain`() {
        for (domain in listOf("light", "switch", "input_boolean")) {
            val id = "$domain.test"
            for ((state, expected) in listOf("off" to "turn_on", "on" to "turn_off")) {
                val control = assertNotNull(entityControl(id, controlState(id, state)))
                assertTrue(control.toggle)
                assertEquals(state == "on", control.checked)
                assertEquals("$domain.$expected", control.service)
                assertEquals(id, control.data.text("entity_id"))
                assertNull(control.disabledReason)
            }
        }
    }

    @Test fun `scene activation works before its first activation and scripts have a run action`() {
        for ((id, value) in listOf("scene.test" to "unknown", "scene.test" to "2026-09-06T00:00:00+00:00", "script.test" to "off")) {
            val control = assertNotNull(entityControl(id, controlState(id, value)))
            assertFalse(control.toggle)
            assertEquals("${id.substringBefore('.')}.turn_on", control.service)
            assertEquals(id, control.data.text("entity_id"))
            assertNull(control.disabledReason)
        }
    }

    @Test fun `controls stay visible but disabled for offline unavailable busy and missing service states`() {
        for (id in listOf("light.test", "switch.test", "input_boolean.test", "scene.test", "script.test")) {
            val initial = controlState(id)
            val missing = initial.copy(snapshot = initial.snapshot!!.copy(services = buildJsonObject {}))
            for (state in listOf(initial.copy(connected = false), initial.copy(busyActions = setOf("entity:$id")),
                missing, controlState(id, "unavailable"))) {
                assertNotNull(assertNotNull(entityControl(id, state)).disabledReason)
            }
        }
        assertNotNull(entityControl("light.test", controlState("light.test", "unknown"))!!.disabledReason)
        assertNull(entityControl("sensor.test", controlState("sensor.test")))
    }
}

class EditableEntityControlTest {
    private fun attrs(value: String) = JsonCodec.parseToJsonElement(value).jsonObject

    @Test fun `button and input button press once even before their first press`() {
        for (domain in listOf("button", "input_button")) {
            val id = "$domain.test"
            val control = assertNotNull(entityControl(id, controlState(id, "unknown")))
            assertEquals(ControlKind.ACTION, control.kind)
            assertEquals("Press", control.label)
            assertEquals("$domain.press", control.service)
            assertEquals(buildJsonObject { put("entity_id", id) }, control.data)
            assertNull(control.disabledReason)
        }
    }

    @Test fun `selects preserve option strings and reject missing options`() {
        for (domain in listOf("select", "input_select")) {
            val id = "$domain.test"
            val state = controlState(id, "Day", attributes = attrs("""{"options":["Day","Night mode", "Night mode"]}"""))
            val control = entityControl(id, state)!!
            assertEquals(ControlKind.SELECT, control.kind)
            assertEquals("$domain.select_option", control.service)
            assertEquals(listOf("Day", "Night mode"), control.options)
            assertEquals("Night mode", control.payload("Night mode").text("option"))
            assertFailsWith<IllegalArgumentException> { control.payload("removed") }
            assertNotNull(entityControl(id, controlState(id))!!.disabledReason)
        }
    }

    @Test fun `text inputs validate limits and patterns with string payloads and password mode`() {
        for (domain in listOf("text", "input_text")) {
            val id = "$domain.test"
            val control = entityControl(id, controlState(id, "old", attributes = attrs(
                """{"min":2,"max":5,"pattern":"[a-z]+","mode":"password"}""")))!!
            assertEquals(ControlKind.TEXT, control.kind)
            assertEquals("$domain.set_value", control.service)
            assertTrue(control.text!!.password)
            assertEquals(JsonPrimitive("hello"), control.payload("hello")["value"])
            for (invalid in listOf("a", "abcdef", "ABC")) assertNotNull(control.validate(invalid))
            val empty = entityControl(id, controlState(id, "old", attributes = attrs("""{"min":0,"max":3}""")))!!
            assertEquals(JsonPrimitive(""), empty.payload("")["value"])
            assertNull(empty.validate("😀"), "Count Unicode code points like Home Assistant")
        }
    }

    @Test fun `numeric box and slider honor negative ranges decimal steps and numeric payloads`() {
        for (domain in listOf("number", "input_number")) for (mode in listOf("box", "slider")) {
            val id = "$domain.test"
            val control = entityControl(id, controlState(id, "0", attributes = attrs(
                """{"min":-1,"max":1,"step":0.1,"mode":"$mode"}""")))!!
            assertEquals(if (mode == "box") ControlKind.NUMBER_BOX else ControlKind.NUMBER_SLIDER, control.kind)
            assertEquals("$domain.set_value", control.service)
            val value = control.payload("0.3")["value"]!!.jsonPrimitive
            assertFalse(value.isString)
            assertEquals("0.3", value.content)
            for (invalid in listOf("NaN", "Infinity", "2", "-2", "0.35")) assertNotNull(control.validate(invalid))
            val rules = control.number!!
            assertEquals("-1", rules.atFraction(0f))
            assertEquals("1", rules.atFraction(1f))
            assertEquals("0.3", rules.atFraction(.65f))
            assertEquals(.65f, rules.fraction("0.3"), .00001f)
            assertTrue(control.sameValue("0.0"))
        }
    }

    @Test fun `automatic number mode and invalid metadata are handled without creating unsafe sliders`() {
        for ((max, expected) in listOf(256 to ControlKind.NUMBER_SLIDER, 257 to ControlKind.NUMBER_BOX)) {
            val control = entityControl("number.test", controlState("number.test", "0", attributes = attrs(
                """{"min":0,"max":$max,"step":1,"mode":"auto"}""")))!!
            assertEquals(expected, control.kind)
        }
        for (json in listOf("""{"min":10,"max":0,"step":1}""", """{"min":0,"max":10,"step":0}""", """{"min":"NaN","max":10,"step":1}""")) {
            assertNotNull(entityControl("number.test", controlState("number.test", attributes = attrs(json)))!!.disabledReason)
        }
        val constant = entityControl("number.test", controlState("number.test", "1", attributes = attrs("""{"min":1,"max":1,"step":1,"mode":"slider"}""")))!!
        assertEquals(ControlKind.NUMBER_BOX, constant.kind)
    }

    @Test fun `new controls remain visible but disabled offline while pending and when unavailable`() {
        val attributes = attrs("""{"min":0,"max":100,"step":1,"options":["one","two"]}""")
        for (domain in listOf("button", "input_button", "select", "input_select", "text", "input_text", "number", "input_number")) {
            val id = "$domain.test"
            val ready = controlState(id, "0", attributes = attributes)
            for (state in listOf(ready.copy(connected = false), ready.copy(busyActions = setOf("entity:$id")),
                controlState(id, "unavailable", attributes = attributes))) {
                assertNotNull(entityControl(id, state)!!.disabledReason)
            }
        }
    }
}
