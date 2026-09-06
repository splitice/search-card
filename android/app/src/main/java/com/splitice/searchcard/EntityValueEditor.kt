package com.splitice.searchcard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject

/** Editing stays local until Save, option selection, or the end of a slider gesture. */
@Composable
internal fun EntityValueEditor(
    id: String, name: String, unit: String, control: EntityControl, submit: (JsonObject) -> Unit,
) {
    val enabled = control.disabledReason == null
    if (control.kind == ControlKind.SELECT) {
        var expanded by remember(id) { mutableStateOf(false) }
        LaunchedEffect(enabled) { if (!enabled) expanded = false }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("entity-control:$id")
                    .semantics { contentDescription = "Select $name" }) {
                Text(control.value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                for (option in control.options) DropdownMenuItem(text = { Text(option) }, onClick = {
                    expanded = false
                    if (enabled && option in control.options && option != control.value) submit(control.payload(option))
                })
            }
        }
        return
    }
    val current = control.value.takeUnless { it == "unknown" || it == "unavailable" }.orEmpty()
    // Drafts are not persisted; in particular, password text must not enter saved instance state.
    var draft by remember(id) { mutableStateOf(current) }
    var dirty by remember(id) { mutableStateOf(false) }
    LaunchedEffect(control.value) {
        if (!dirty || control.sameValue(draft)) { draft = current; dirty = false }
    }
    val validation = control.validate(draft)
    val changed = !control.sameValue(draft)
    val save = {
        if (enabled && !control.sameValue(draft) && control.validate(draft) == null) submit(control.payload(draft))
    }
    if (control.kind == ControlKind.NUMBER_SLIDER && control.number != null) {
        val rules = control.number
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(listOf(draft.ifEmpty { "—" }, unit).filter { it.isNotEmpty() }.joinToString(" "),
                Modifier.weight(.35f).testTag("entity-value:$id"), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = rules.fraction(draft),
                onValueChange = { draft = rules.atFraction(it); dirty = true },
                onValueChangeFinished = save,
                // Avoid allocating thousands of tick marks for fine-grained ranges.
                steps = if (rules.intervals <= BigDecimal(100)) (rules.intervals.toInt() - 1).coerceAtLeast(0) else 0,
                enabled = enabled,
                modifier = Modifier.weight(1f).testTag("entity-control:$id")
                    .semantics { contentDescription = "Adjust $name" },
            )
        }
    } else {
        val numeric = control.kind == ControlKind.NUMBER_BOX
        val password = control.text?.password == true
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it; dirty = true }, enabled = enabled,
                modifier = Modifier.weight(1f).testTag("entity-control:$id").semantics { contentDescription = "Edit $name" },
                singleLine = true, isError = dirty && validation != null,
                suffix = if (numeric && unit.isNotEmpty()) ({ Text(unit) }) else null,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when { numeric -> KeyboardType.Decimal; password -> KeyboardType.Password; else -> KeyboardType.Text },
                    imeAction = ImeAction.Done,
                ), keyboardActions = KeyboardActions(onDone = { save() }),
            )
            IconButton(onClick = { save() }, enabled = enabled && changed && validation == null,
                modifier = Modifier.testTag("entity-save:$id")) { Icon(Icons.Default.Check, "Save $name") }
        }
    }
    if (dirty && validation != null) ActionFailure(validation)
}
