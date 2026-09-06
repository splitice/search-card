package com.splitice.searchcard

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitice.searchcard.core.*
import kotlinx.serialization.json.*

class MainActivity : ComponentActivity() {
    private val model: SearchViewModel by viewModels()
    private var panelVisible by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF80D2FF))
                else lightColorScheme(primary = Color(0xFF00658D))) {
                SearchPanel(model, panelVisible, ::finish, ::openLink, ::pinWidget)
            }
        }
    }
    override fun onStart() { super.onStart(); panelVisible = true; model.start() }
    override fun onStop() { panelVisible = false; model.stop(); super.onStop() }
    private fun openLink(url: String) {
        try {
            val uri = Uri.parse(url)
            require(uri.scheme in listOf("https", "http") && uri.host != null) { "Only HTTP and HTTPS links are supported." }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (error: Exception) { model.reportError(error.message ?: "No app is available to open this link.") }
    }
    private fun pinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this, SearchWidget::class.java), null, null)
        else model.reportError("Add Search Card through your launcher's Widgets menu.")
    }
}

@Composable
private fun SearchPanel(model: SearchViewModel, visible: Boolean, dismiss: () -> Unit, open: (String) -> Unit, pin: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val query by model.query.collectAsStateWithLifecycle()
    val output by model.output.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    var login by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(visible, login, settings, state.needsDashboard) {
        if (visible && !login && !settings && !state.needsDashboard) { focus.requestFocus(); keyboard?.show() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f))) {
        Box(Modifier.fillMaxSize().clickable(onClick = dismiss))
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.9f)
            .systemBarsPadding().imePadding(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), tonalElevation = 4.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Search Card", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = model::refresh) { Icon(Icons.Default.Refresh, "Refresh connection and configuration") }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "Close search") }
                }
                OutlinedTextField(query, { model.query.value = it }, Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text(state.snapshot?.config?.text("search_text")?.ifEmpty { "Type to search…" } ?: "Type to search…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { model.query.value = "" }) { Icon(Icons.Default.Clear, "Clear search") } },
                    singleLine = true, shape = RoundedCornerShape(20.dp))
                val cached = state.snapshot
                val status = if (!state.connected && cached != null) {
                    val minutes = ((System.currentTimeMillis() - cached.savedAt).coerceAtLeast(0) / 60_000)
                    "${state.status} · saved ${if (minutes == 0L) "just now" else "$minutes min ago"}"
                } else state.status
                Text(status, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                if (state.needsLogin) Button(onClick = { keyboard?.hide(); login = true }, Modifier.fillMaxWidth()) { Text("Sign in to Home Assistant") }
                val error = output.error ?: state.error
                if (error != null) Text(error, Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (state.choices.isNotEmpty()) {
                    Text("Choose the card to sync", style = MaterialTheme.typography.titleSmall)
                    LazyColumn {
                        items(state.choices) { card -> TextButton(onClick = { model.choose(card) }) { Text(card.title) } }
                    }
                } else {
                    if (output.results.isNotEmpty()) Text("Showing ${output.results.size} of ${output.total} results", style = MaterialTheme.typography.labelSmall)
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(output.actions.withIndex().toList(), key = { "action:${it.index}" }) { indexed ->
                            val action = indexed.value
                            val key = "action:${action.service}:${action.data}"
                            ListItem(
                                headlineContent = { Text(action.name) },
                                supportingContent = { Text(if (key in state.busyActions) "Sending…" else "Run action") },
                                leadingContent = { Icon(iconFor(action.icon, action.service.substringBefore('.')), null) },
                                modifier = Modifier.clickable(enabled = state.connected && key !in state.busyActions) {
                                    model.callService(key, action.service, action.data)
                                },
                            )
                        }
                        items(output.results.withIndex().toList(), key = { "result:${it.index}" }) { indexed ->
                            when (val result = indexed.value) {
                                is SearchResult.LocalService -> ListItem(
                                    headlineContent = { Text(result.service.text("name")) },
                                    supportingContent = { Text(result.service.text("category").ifEmpty { result.service.text("url") }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { Icon(iconFor(result.service.text("icon"), "local"), null) },
                                    trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open service") },
                                    modifier = Modifier.clickable { open(result.service.text("url")) },
                                )
                                is SearchResult.Entity -> EntityRow(result.id, state, model, open)
                            }
                        }
                        if (query.isNotEmpty() && output.results.isEmpty() && output.actions.isEmpty() && error == null) {
                            item { Text("No matching entities or services", Modifier.padding(16.dp)) }
                        }
                        if (query.isEmpty()) item {
                            Text("Search entities, local services, or a configured action.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (state.needsDashboard && visible) {
        SettingsDialog(model, dismiss, pin, setup = true, onSaved = { settings = false; login = true })
    } else if (settings) SettingsDialog(model, { settings = false }, pin)
    if (login && visible && !state.needsDashboard) LoginDialog(model, { login = false })
}

@Composable
private fun EntityRow(id: String, state: PanelState, model: SearchViewModel, open: (String) -> Unit) {
    val entity = state.snapshot?.states?.get(id) as? JsonObject ?: return
    val domain = id.substringBefore('.')
    val entityState = entity.text("state")
    val entityName = entity.obj("attributes").text("friendly_name", id)
    val key = "entity:$id"
    val enabled = state.connected && entityState !in listOf("unavailable", "unknown") && key !in state.busyActions
    ListItem(
        headlineContent = { Text(entityName) },
        supportingContent = { Text("$entityState${entity.obj("attributes").text("unit_of_measurement").let { if (it.isEmpty()) "" else " $it" }}", maxLines = 1) },
        leadingContent = { Icon(iconFor(entity.obj("attributes").text("icon"), domain), null) },
        trailingContent = {
            when (domain) {
                "light", "switch", "input_boolean" -> Switch(entityState == "on", { on ->
                    model.callService(key, "$domain.${if (on) "turn_on" else "turn_off"}", buildJsonObject { put("entity_id", id) })
                }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Toggle $entityName" })
                "scene", "script" -> TextButton(onClick = {
                    model.callService(key, "$domain.turn_on", buildJsonObject { put("entity_id", id) })
                }, enabled = enabled) { Text(if (key in state.busyActions) "Sending…" else "Run") }
                else -> Icon(Icons.Default.ChevronRight, "Entity details")
            }
        },
        modifier = Modifier.clickable {
            open(Uri.parse(model.storage.settings.dashboard).buildUpon().appendQueryParameter("more-info-entity-id", id).build().toString())
        },
    )
}

private fun iconFor(mdi: String, domain: String): ImageVector = when (mdi.removePrefix("mdi:")) {
    "television-classic", "television", "plex" -> Icons.Default.Tv
    "server-network", "server", "web" -> Icons.Default.Dns
    "progress-download", "download" -> Icons.Default.Download
    "lamp", "lightbulb" -> Icons.Default.Lightbulb
    "power", "toggle-switch" -> Icons.Default.PowerSettingsNew
    else -> when (domain) {
        "light" -> Icons.Default.Lightbulb
        "switch", "input_boolean" -> Icons.Default.PowerSettingsNew
        "scene" -> Icons.Default.Palette
        "script" -> Icons.Default.PlayArrow
        "climate" -> Icons.Default.Thermostat
        "media_player" -> Icons.Default.Speaker
        "cover" -> Icons.Default.Blinds
        "local" -> Icons.Default.Dns
        else -> Icons.Default.Home
    }
}

@Composable
private fun SettingsDialog(
    model: SearchViewModel,
    close: () -> Unit,
    pin: () -> Unit,
    setup: Boolean = false,
    onSaved: () -> Unit = close,
) {
    var address by remember { mutableStateOf(model.storage.settings.dashboard) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    AlertDialog(onDismissRequest = close, title = { Text(if (setup) "Connect Home Assistant" else "Search Card settings") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(address, { address = it }, modifier = Modifier.focusRequester(focus),
                label = { Text("Dashboard URL") }, singleLine = true,
                supportingText = { Text(if (setup) "Enter the HTTPS URL of the dashboard view containing your search card." else "Changing servers clears the current login and cache.") })
            LaunchedEffect(setup) { if (setup) focus.requestFocus() }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            if (!setup) {
                TextButton(onClick = { model.changeCard(); close() }) { Text("Choose search card again") }
                TextButton(onClick = pin) { Text("Add home-screen widget") }
                TextButton(onClick = { model.logout(); close() }) { Text("Sign out and clear cached data") }
                Text("Data refreshes while search is open. No background refresh runs.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = { try { model.setDashboard(address); onSaved() } catch (e: Exception) { error = e.message } }) { Text(if (setup) "Continue to sign in" else "Save") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}
