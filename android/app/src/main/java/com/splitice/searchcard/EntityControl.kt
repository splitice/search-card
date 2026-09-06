package com.splitice.searchcard

import com.splitice.searchcard.core.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.*

internal enum class ControlKind { TOGGLE, ACTION, LOCK, SELECT, TEXT, NUMBER_BOX, NUMBER_SLIDER }

internal data class NumberRules(val min: BigDecimal, val max: BigDecimal, val step: BigDecimal) {
    val intervals: BigDecimal get() = (max - min).divideToIntegralValue(step)
    fun validate(value: String): String? {
        val number = value.trim().toBigDecimalOrNull() ?: return "Enter a number using a decimal point."
        if (number < min || number > max) return "Enter a value from ${min.display()} to ${max.display()}."
        if ((number - min).remainder(step).compareTo(BigDecimal.ZERO) != 0) return "Use increments of ${step.display()} from ${min.display()}."
        return null
    }
    fun fraction(value: String): Float {
        if (intervals.signum() == 0) return 0f
        val number = value.toBigDecimalOrNull() ?: min
        return ((number - min).divide(step * intervals, MathContext.DECIMAL64)).toFloat().coerceIn(0f, 1f)
    }
    fun atFraction(fraction: Float): String {
        val tick = (intervals * fraction.coerceIn(0f, 1f).toBigDecimal()).setScale(0, RoundingMode.HALF_UP)
        return (min + step * tick).display()
    }
}
internal fun BigDecimal.display(): String = stripTrailingZeros().toPlainString()

internal data class TextRules(val min: Int, val max: Int, val password: Boolean, val pattern: String?) {
    fun validate(value: String): String? {
        val length = value.codePointCount(0, value.length)
        if (length !in min..max) return "Enter $min–$max characters."
        if (pattern != null) {
            val regex = try { Regex(pattern) } catch (_: IllegalArgumentException) {
                return "Unsupported text pattern. Edit this value in Home Assistant."
            }
            if (!regex.matches(value)) return "The value does not match the required pattern."
        }
        return null
    }
}

internal data class EntityControl(
    val service: String,
    val data: JsonObject,
    val kind: ControlKind,
    val value: String,
    val label: String,
    val disabledReason: String?,
    val options: List<String> = emptyList(),
    val number: NumberRules? = null,
    val text: TextRules? = null,
    val lockCodePattern: String? = null,
) {
    val toggle: Boolean get() = kind == ControlKind.TOGGLE
    val checked: Boolean get() = value == "on"
    fun validate(value: String): String? = when (kind) {
        ControlKind.SELECT -> if (value in options) null else "Choose an available option."
        ControlKind.TEXT -> text?.validate(value)
        ControlKind.NUMBER_BOX, ControlKind.NUMBER_SLIDER -> if (number == null) "Invalid number range." else number.validate(value)
        else -> null
    }
    fun sameValue(other: String): Boolean = if (number != null) {
        val first = value.toBigDecimalOrNull(); val second = other.toBigDecimalOrNull()
        first != null && second != null && first.compareTo(second) == 0
    } else value == other
    fun payload(value: String): JsonObject {
        require(validate(value) == null) { validate(value).orEmpty() }
        return JsonObject(data + when (kind) {
            ControlKind.SELECT -> mapOf("option" to JsonPrimitive(value))
            ControlKind.TEXT -> mapOf("value" to JsonPrimitive(value))
            ControlKind.NUMBER_BOX, ControlKind.NUMBER_SLIDER -> mapOf("value" to JsonPrimitive(value.trim().toBigDecimal()))
            else -> emptyMap()
        })
    }
}

/** State-aware native controls, independent of UI layout and service transport. */
internal fun entityControl(id: String, state: PanelState): EntityControl? {
    val snapshot = state.snapshot ?: return null
    val entity = snapshot.states[id] as? JsonObject ?: return null
    val attributes = entity.obj("attributes")
    val domain = id.substringBefore('.')
    val value = entity.text("state")
    val numeric = domain in setOf("number", "input_number")
    val number = if (numeric) {
        val min = attributes.text("min").toBigDecimalOrNull()
        val max = attributes.text("max").toBigDecimalOrNull()
        val step = attributes.text("step", "1").toBigDecimalOrNull()
        if (min != null && max != null && step != null && min <= max && step.signum() > 0) NumberRules(min, max, step) else null
    } else null
    val kind = when (domain) {
        "light", "switch", "input_boolean" -> ControlKind.TOGGLE
        "scene", "script", "button", "input_button" -> ControlKind.ACTION
        "lock" -> ControlKind.LOCK
        "select", "input_select" -> ControlKind.SELECT
        "text", "input_text" -> ControlKind.TEXT
        "number", "input_number" -> {
            val mode = attributes.text("mode", if (domain == "input_number") "slider" else "auto")
            val slider = mode == "slider" || mode == "auto" && number != null &&
                (number.max - number.min) <= number.step * BigDecimal(256)
            if (slider && number != null && number.intervals.signum() > 0) ControlKind.NUMBER_SLIDER else ControlKind.NUMBER_BOX
        }
        else -> return null
    }
    val text = if (kind == ControlKind.TEXT) TextRules(
        attributes.text("min", "0").toIntOrNull() ?: 0,
        attributes.text("max", if (domain == "input_text") "100" else "255").toIntOrNull() ?: 255,
        attributes.text("mode") == "password", (attributes["pattern"] as? JsonPrimitive)?.contentOrNull,
    ) else null
    val options = attributes.array("options").mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.distinct()
    val action = when (domain) {
        "lock" -> if (value == "locked") "unlock" else "lock"
        "button", "input_button" -> "press"
        "select", "input_select" -> "select_option"
        "text", "input_text", "number", "input_number" -> "set_value"
        else -> if (kind == ControlKind.TOGGLE && value == "on") "turn_off" else "turn_on"
    }
    val reason = when {
        "entity:$id" in state.busyActions -> "Sending…"
        !state.connected -> "Waiting for fresh connection"
        value == "unavailable" -> "Unavailable"
        (kind == ControlKind.TOGGLE || domain == "script") && value !in setOf("on", "off") -> "State unknown"
        domain == "lock" && value in setOf("locking", "unlocking", "opening") -> "Moving…"
        domain == "lock" && value == "jammed" -> "Jammed"
        domain == "lock" && value !in setOf("locked", "unlocked", "open") -> "State unknown"
        numeric && number == null -> "Invalid number range"
        text != null && (text.min < 0 || text.max < text.min) -> "Invalid text limits"
        kind == ControlKind.SELECT && options.isEmpty() -> "No options available"
        action !in snapshot.services.obj(domain) -> "Control unavailable"
        else -> null
    }
    return EntityControl(
        "$domain.$action", buildJsonObject { put("entity_id", id) }, kind, value,
        when (domain) { "lock" -> if (action == "unlock") "Unlock" else "Lock"; "scene" -> "Activate"; "script" -> "Run"; "button", "input_button" -> "Press"; else -> "Save" },
        reason, options, number, text,
        if (domain == "lock") (attributes["code_format"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } else null,
    )
}
