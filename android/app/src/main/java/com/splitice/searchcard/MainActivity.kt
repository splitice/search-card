package com.splitice.searchcard

import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.splitice.searchcard.core.*
import kotlinx.serialization.json.*

class MainActivity : ComponentActivity() {
    private val model: SearchViewModel by viewModels()
    private var panelVisible by mutableStateOf(false)
    private var foregroundSession by mutableIntStateOf(0)
    private var widgetAnchor by mutableStateOf<WidgetAnchor?>(null)
    private var launchSequence by mutableIntStateOf(0)

    private fun currentWindow(): PanelBounds = windowManager.currentWindowMetrics.bounds.toPanelBounds()

    private fun readWidgetLaunch(intent: Intent) {
        widgetAnchor = if (intent.action == SearchWidget.ACTION_OPEN_WIDGET)
            intent.sourceBounds?.let { WidgetAnchor(it.toPanelBounds(), currentWindow()) } else null
        if (intent.action == SearchWidget.ACTION_OPEN_WIDGET) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            clearOverrideActivityTransition(OVERRIDE_TRANSITION_OPEN)
            clearOverrideActivityTransition(OVERRIDE_TRANSITION_CLOSE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readWidgetLaunch(intent)
        launchSequence++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        widgetAnchor?.let {
            outState.putIntArray("widgetBounds", it.bounds.toArray())
            outState.putIntArray("widgetWindow", it.window.toArray())
        }
        outState.putInt("widgetLaunchSequence", launchSequence)
        super.onSaveInstanceState(outState)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readWidgetLaunch(intent)
        if (savedInstanceState != null) {
            val bounds = savedInstanceState.getIntArray("widgetBounds")?.toPanelBounds()
            val window = savedInstanceState.getIntArray("widgetWindow")?.toPanelBounds()
            widgetAnchor = if (bounds != null && window == currentWindow()) WidgetAnchor(bounds, window) else null
            launchSequence = savedInstanceState.getInt("widgetLaunchSequence")
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF80D2FF))
                else lightColorScheme(primary = Color(0xFF00658D))) {
                SearchPanel(model, panelVisible, foregroundSession, widgetAnchor, ::currentWindow, launchSequence, ::finish, ::openLink, ::openEntity, ::openNavigation, ::pinWidget,
                    canRequestFocus = { !isFinishing && !isDestroyed })
            }
        }
    }
    override fun onStart() { super.onStart(); foregroundSession++; panelVisible = true; model.start() }
    override fun onStop() { panelVisible = false; model.stop(); super.onStop() }
    override fun finish() {
        if (isFinishing || isDestroyed) return
        // Closing animations can delay onStop. Stop work at the dismissal itself.
        panelVisible = false
        model.stop()
        super.finish()
    }
    private fun openLink(url: String) {
        try {
            val uri = Uri.parse(url)
            require(uri.scheme in listOf("https", "http") && uri.host != null) { "Only HTTP and HTTPS links are supported." }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (error: Exception) { model.reportError(error.message ?: "No app is available to open this link.") }
    }
    private fun openEntity(id: String) {
        val dashboard = Uri.parse(model.storage.settings.dashboard).buildUpon()
            .appendQueryParameter("more-info-entity-id", id).build()
        openHomeAssistant(dashboard)
    }
    private fun openNavigation(path: String) {
        if (!isNavigationPath(path)) return
        openHomeAssistant(Uri.parse(model.storage.settings.dashboard).buildUpon()
            .encodedPath(path).clearQuery().fragment(null).build())
    }
    private fun openHomeAssistant(dashboard: Uri) {
        val companion = dashboard.buildUpon().scheme("homeassistant").authority("navigate").build()
        try {
            // Launch directly: package visibility filtering can hide installed handlers from queries.
            // Leave server selection to Companion, which knows its configured server names.
            startActivity(Intent(Intent.ACTION_VIEW, companion))
        } catch (_: ActivityNotFoundException) {
            openLink(dashboard.toString())
        } catch (_: SecurityException) {
            openLink(dashboard.toString())
        }
    }
    private fun pinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this, SearchWidget::class.java), null, null)
        else model.reportError("Add Search Card through your launcher's Widgets menu.")
    }
}

@Composable
private fun SearchPanel(model: SearchViewModel, visible: Boolean, foregroundSession: Int, widgetAnchor: WidgetAnchor?, currentWindow: () -> PanelBounds, launchSequence: Int, dismiss: () -> Unit, open: (String) -> Unit, openEntity: (String) -> Unit, openNavigation: (String) -> Unit, pin: () -> Unit, canRequestFocus: () -> Boolean) {
    val state by model.state.collectAsStateWithLifecycle()
    val query by model.query.collectAsStateWithLifecycle()
    val output by model.output.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    var login by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val window = LocalWindowInfo.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    var fieldCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val requestFocus = visible && !login && !settings && !state.needsDashboard &&
        lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && window.isWindowFocused
    LaunchedEffect(requestFocus, fieldCoordinates) {
        if (requestFocus) {
            // A widget can be dismissed before its first frame or during IME startup.
            withFrameNanos { }
            if (canRequestFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                view.isAttachedToWindow && view.hasWindowFocus() && fieldCoordinates?.isAttached == true) {
                if (focus.requestFocus()) keyboard?.show()
            }
        }
    }
    val status = state.status
    val widgetStatus = if (output.results.isNotEmpty() && state.choices.isEmpty())
        "$status\n${output.total} results" else status
    val fieldModifier = Modifier.focusRequester(focus).onGloballyPositioned { fieldCoordinates = it }
    val signIn = { keyboard?.hide(); login = true }
    WidgetPanelHost(widgetAnchor, currentWindow, launchSequence, visible, widgetStatus, dismiss,
        field = { modifier ->
            WidgetSearchField(query, { model.query.value = it }, state.snapshot?.config?.text("search_text").orEmpty(),
                modifier.then(fieldModifier))
        },
        body = { modifier, scrollStatus ->
            if (scrollStatus) {
                PanelResults(state, output, query, model, open, openEntity, openNavigation, signIn, modifier, showCount = false) {
                    WidgetStatus(widgetStatus, model::refresh, { settings = true }, dismiss)
                }
            } else Column(modifier) {
                WidgetStatus(widgetStatus, model::refresh, { settings = true }, dismiss)
                PanelResults(state, output, query, model, open, openEntity, openNavigation, signIn, Modifier.weight(1f), showCount = false)
            }
        },
        fallback = {
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.9f)
                .systemBarsPadding().imePadding(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), tonalElevation = 4.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Search Card", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = model::refresh) { Icon(Icons.Default.Refresh, "Refresh connection and configuration") }
                        IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") }
                        IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "Close search") }
                    }
                    OutlinedTextField(query, { model.query.value = it }, Modifier.fillMaxWidth().then(fieldModifier),
                        placeholder = { Text(state.snapshot?.config?.text("search_text")?.ifEmpty { "Type to search…" } ?: "Type to search…") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { model.query.value = "" }) { Icon(Icons.Default.Clear, "Clear search") } },
                        singleLine = true, shape = RoundedCornerShape(20.dp))
                    Text(status, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                    PanelResults(state, output, query, model, open, openEntity, openNavigation, signIn, Modifier.weight(1f))
                }
            }
        })
    if (state.needsDashboard && visible) {
        SettingsDialog(model, dismiss, pin, setup = true, onSaved = { settings = false; login = true })
    } else if (settings) SettingsDialog(model, { settings = false }, pin)
    if (login && visible && !state.needsDashboard) key(foregroundSession) {
        // A stop/start may happen without an intervening composition. Always replace the
        // destroyed WebView and its authorization state when returning to the foreground.
        LoginDialog(model, { login = false })
    }
}

@Composable
private fun PanelResults(
    state: PanelState, output: SearchOutput, query: String, model: SearchViewModel,
    open: (String) -> Unit, openEntity: (String) -> Unit, openNavigation: (String) -> Unit, signIn: () -> Unit, modifier: Modifier,
    showCount: Boolean = true,
    header: (@Composable () -> Unit)? = null,
) {
    val error = output.error ?: state.error
    // Status, errors, sign-in, and choices must remain reachable even in a very short window.
    BoxWithConstraints(modifier) {
        val compact = maxWidth < 240.dp
        PagedResults(
            query, output.results, Modifier.fillMaxSize(),
            showResults = state.choices.isEmpty(),
            beforeResults = {
                if (header != null) item(key = "status") { header() }
                if (state.needsLogin) item(key = "login") {
                    Button(onClick = signIn, Modifier.fillMaxWidth()) { Text("Sign in to Home Assistant") }
                }
                if (error != null) item(key = "error") {
                    Text(error, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.choices.isNotEmpty()) {
                    item { Text("Choose the card to sync", style = MaterialTheme.typography.titleSmall) }
                    items(state.choices) { card -> TextButton(onClick = { model.choose(card) }) { Text(card.title) } }
                } else {
                    if (output.results.isNotEmpty() && showCount) item {
                        Text("${output.total} results", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    items(output.actions.size, key = { "action:$it" }, contentType = { "action" }) { index ->
                        val action = output.actions[index]
                        val key = "action:${action.service}:${action.data}"
                        ListItem(
                            headlineContent = { Text(action.name) },
                            supportingContent = { Text(if (key in state.busyActions) "Sending…" else "Run action") },
                            leadingContent = if (compact) null else ({ Icon(iconFor(action.icon, action.service.substringBefore('.')), null) }),
                            modifier = Modifier.clickable(enabled = state.connected && key !in state.busyActions) {
                                model.callService(key, action.service, action.data)
                            },
                        )
                    }
                }
            }, afterResults = {
                if (state.choices.isEmpty()) {
                    if (query.isNotEmpty() && output.results.isEmpty() && output.actions.isEmpty() && error == null) {
                        item { Text("No matching entities or services", Modifier.padding(16.dp)) }
                    }
                    if (query.isEmpty()) item {
                        Text("Search entities, pages, local services, or a configured action.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        ) { result ->
            when (result) {
                is SearchResult.LocalService -> ListItem(
                    headlineContent = { Text(result.service.text("name")) },
                    supportingContent = { Text(result.service.text("category").ifEmpty { result.service.text("url") }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = if (compact) null else ({ Icon(iconFor(result.service.text("icon"), "local"), null) }),
                    trailingContent = if (compact) null else ({ Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open service") }),
                    modifier = Modifier.clickable { open(result.service.text("url")) },
                )
                is SearchResult.Navigation -> ListItem(
                    headlineContent = { Text(result.panel.name) },
                    supportingContent = { Text("Home Assistant", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = if (compact) null else ({ Icon(iconFor(result.panel.icon, "navigation"), null) }),
                    modifier = Modifier.clickable { openNavigation(result.panel.path) },
                )
                is SearchResult.Entity -> EntityRow(result.id, state, model, openEntity, compact)
            }
        }
    }
}

@Composable
private fun EntityRow(id: String, state: PanelState, model: SearchViewModel, openEntity: (String) -> Unit, compact: Boolean) {
    val entity = state.snapshot?.states?.get(id) as? JsonObject ?: return
    val domain = id.substringBefore('.')
    val entityState = entity.text("state")
    val entityName = entity.obj("attributes").text("friendly_name", id)
    val key = "entity:$id"
    val enabled = state.connected && entityState !in listOf("unavailable", "unknown") && key !in state.busyActions
    val controls: @Composable () -> Unit = {
        when (domain) {
            "light", "switch", "input_boolean" -> Switch(entityState == "on", { on ->
                model.callService(key, "$domain.${if (on) "turn_on" else "turn_off"}", buildJsonObject { put("entity_id", id) })
            }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Toggle $entityName" })
            "scene", "script" -> TextButton(onClick = {
                model.callService(key, "$domain.turn_on", buildJsonObject { put("entity_id", id) })
            }, enabled = enabled) { Text(if (key in state.busyActions) "Sending…" else "Run") }
            else -> Icon(Icons.Default.ChevronRight, "Entity details")
        }
    }
    Column(Modifier.clickable { openEntity(id) }) {
        ListItem(
            headlineContent = { Text(entityName) },
            supportingContent = { Text("$entityState${entity.obj("attributes").text("unit_of_measurement").let { if (it.isEmpty()) "" else " $it" }}", maxLines = 1) },
            leadingContent = if (compact) null else ({ Icon(iconFor(entity.obj("attributes").text("icon"), domain), null) }),
            trailingContent = if (compact) null else controls,
        )
        if (compact) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(entity.obj("attributes").text("icon"), domain), null)
            Spacer(Modifier.weight(1f))
            controls()
        }
    }
}

private fun iconFor(mdi: String, domain: String): ImageVector = when (mdi.removePrefix("mdi:")) {
    "television-classic", "television", "plex" -> Icons.Default.Tv
    "server-network", "server", "web" -> Icons.Default.Dns
    "progress-download", "download" -> Icons.Default.Download
    "lamp", "lightbulb" -> Icons.Default.Lightbulb
    "power", "toggle-switch" -> Icons.Default.PowerSettingsNew
    "lightning-bolt" -> Icons.Default.Bolt
    "chart-box" -> Icons.Default.BarChart
    "format-list-bulleted", "clipboard-list" -> Icons.AutoMirrored.Filled.List
    "map" -> Icons.Default.Map
    "calendar" -> Icons.Default.CalendarMonth
    "cog" -> Icons.Default.Settings
    "hammer" -> Icons.Default.Build
    "view-dashboard" -> Icons.Default.Dashboard
    "play-box-multiple" -> Icons.Default.PlayArrow
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

private fun android.graphics.Rect.toPanelBounds() = PanelBounds(left, top, right, bottom)
private fun PanelBounds.toArray() = intArrayOf(left, top, right, bottom)
private fun IntArray.toPanelBounds(): PanelBounds? = if (size == 4) PanelBounds(this[0], this[1], this[2], this[3]) else null
