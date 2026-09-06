package com.splitice.searchcard

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.splitice.searchcard.core.*
import kotlinx.serialization.json.*

@Composable
internal fun EntityRow(
    id: String, state: PanelState, openEntity: (String) -> Unit,
    callService: (String, String, JsonElement) -> Unit, compact: Boolean,
    updateSearchLabel: (String, SearchLabelAction) -> Unit = { _, _ -> },
) {
    val entity = state.snapshot?.states?.get(id) as? JsonObject ?: return
    val attributes = entity.obj("attributes")
    val name = attributes.text("friendly_name", id)
    val key = "entity:$id"
    val control = entityControl(id, state)
    val error = state.actionErrors[key]
    var menuOpen by remember(id) { mutableStateOf(false) }
    val labelKey = "search-label:$id"
    val labelEnabled = state.connected && labelKey !in state.busyActions
    var requestingCode by remember(id, control?.service, control?.disabledReason) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("entity-row:$id")) {
        // Only the details area navigates. Tapping disabled controls or the space
        // around them must never launch Companion and dismiss the native panel.
        Box {
            Row(Modifier.fillMaxWidth().combinedClickable(
                onClickLabel = "Open $name details", onClick = { openEntity(id) },
                onLongClickLabel = "Search options for $name", onLongClick = { menuOpen = true },
            )
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!compact) Icon(iconFor(attributes.text("icon"), id.substringBefore('.')), null)
                Text(name, Modifier.weight(1f).testTag("entity-name:$id"), style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Default.ChevronRight, "Entity details")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Ignore") }, enabled = labelEnabled, onClick = {
                    menuOpen = false
                    updateSearchLabel(id, SearchLabelAction.IGNORE)
                })
                val priority = id in state.snapshot?.priority.orEmpty()
                DropdownMenuItem(text = { Text(if (priority) "Unpriority" else "Priority") }, enabled = labelEnabled, onClick = {
                    menuOpen = false
                    updateSearchLabel(id, if (priority) SearchLabelAction.UNPRIORITY else SearchLabelAction.PRIORITY)
                })
            }
        }
        state.actionErrors[labelKey]?.let { ActionFailure(it) }
        if (control != null && control.kind !in setOf(ControlKind.TOGGLE, ControlKind.ACTION, ControlKind.LOCK)) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                EntityValueEditor(id, name, attributes.text("unit_of_measurement"), control) { data ->
                    callService(key, control.service, data)
                }
                control.disabledReason?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(listOf(entity.text("state"), attributes.text("unit_of_measurement"))
                        .filter { it.isNotEmpty() }.joinToString(" "), Modifier.testTag("entity-value:$id"),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    control?.disabledReason?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
                if (control != null) {
                    if (control.toggle) {
                        Switch(
                            checked = control.checked,
                            onCheckedChange = { callService(key, control.service, control.data) },
                            enabled = control.disabledReason == null,
                            modifier = Modifier.testTag("entity-control:$id").semantics { contentDescription = "Toggle $name" },
                        )
                    } else {
                        FilledTonalButton(
                            onClick = {
                                if (control.lockCodePattern != null) requestingCode = true
                                else callService(key, control.service, control.data)
                            },
                            enabled = control.disabledReason == null,
                            modifier = Modifier.testTag("entity-control:$id").semantics { contentDescription = "${control.label} $name" },
                        ) { Text(control.label) }
                    }
                }
            }
        }
        if (requestingCode && control != null && control.disabledReason == null) {
            LockCodeDialog(name, control, dismiss = { requestingCode = false }) { code ->
                requestingCode = false
                callService(key, control.service, JsonObject(control.data + ("code" to JsonPrimitive(code))))
            }
        }
        if (error != null) ActionFailure(if (control?.text?.password == true || control?.lockCodePattern != null)
            "The protected action was not confirmed. Check its result before retrying." else error)
    }
}

@Composable
internal fun ActionFailure(message: String) {
    Text(message, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** Codes only live in this dialog's memory, never saved state or the entity snapshot. */
@Composable
private fun LockCodeDialog(name: String, control: EntityControl, dismiss: () -> Unit, submit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("${control.label} $name") },
        text = { OutlinedTextField(code, { code = it }, label = { Text("Lock code") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.testTag("lock-code")) },
        confirmButton = { TextButton(onClick = { submit(code) }, enabled = code.isNotEmpty()) { Text(control.label) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
