package com.splitice.searchcard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.splitice.searchcard.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class PanelState(
    val snapshot: Snapshot? = null,
    val connected: Boolean = false,
    val status: String = "Connecting…",
    val error: String? = null,
    val needsDashboard: Boolean = false,
    val needsLogin: Boolean = false,
    val choices: List<CardCandidate> = emptyList(),
    val busyActions: Set<String> = emptySet(),
    val actionErrors: Map<String, String> = emptyMap(),
)

internal fun initialPanelState(hasDashboard: Boolean, hasSession: Boolean): PanelState = when {
    !hasDashboard -> PanelState(needsDashboard = true, status = "Enter your Home Assistant dashboard URL")
    !hasSession -> PanelState(needsLogin = true, status = "Sign in to Home Assistant")
    else -> PanelState(status = "Connecting…")
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    val storage = Storage(application)
    private val http = foregroundHttpClient()
    private val tokens = TokenClient(http)
    private var accessToken: String? = null
    private var accessExpires = 0L
    private var foreground: Job? = null
    private var loginExchange: Job? = null
    private var socket: HaSocket? = null
    private var selection: CompletableDeferred<CardCandidate>? = null
    private var started = false
    private var accountEpoch = 0
    val state = MutableStateFlow(initialPanelState(storage.settings.dashboard.isNotBlank(), storage.hasSavedSession))
    private val serviceCalls = ServiceCalls(state)
    val query = MutableStateFlow("")
    private val engine = SearchEngine()
    @OptIn(FlowPreview::class)
    val output: StateFlow<SearchOutput> = combine(query.debounce(100), state.map { it.snapshot }.distinctUntilChanged()) { text, snapshot ->
        if (snapshot == null) SearchOutput() else engine.search(text, snapshot)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), SearchOutput())

    fun start() {
        started = true
        if (foreground?.isActive == true) return
        val initial = initialPanelState(storage.settings.dashboard.isNotBlank(), storage.hasSavedSession)
        state.update { initial.copy(snapshot = it.snapshot) }
        val previous = foreground
        foreground = viewModelScope.launch {
            previous?.join()
            if (storage.settings.dashboard.isBlank()) {
                state.value = PanelState(needsDashboard = true, status = "Enter your Home Assistant dashboard URL")
                return@launch
            }
            val cached = withContext(Dispatchers.IO) { storage.loadSnapshot() }
            state.update { it.copy(snapshot = it.snapshot ?: cached) }
            val refresh = withContext(Dispatchers.IO) { storage.refreshToken() }
            if (refresh == null) { state.update { it.copy(needsLogin = true, connected = false, status = "Sign in to Home Assistant") }; return@launch }
            var failures = 0
            while (isActive) {
                try {
                    state.update { it.copy(status = "Connecting…", error = null, connected = false, needsLogin = false) }
                    runSession(refresh)
                } catch (error: CancellationException) { throw error }
                catch (error: LoginRequired) {
                    state.update { it.copy(needsLogin = true, connected = false, status = "Sign in again", error = error.message) }
                    return@launch
                } catch (error: Exception) {
                    state.update { it.copy(connected = false, status = "Offline · cached results", error = error.message) }
                    // Config errors need user correction; network failures reconnect only while visible.
                    if (error is IllegalArgumentException || error is HaCommandError) return@launch
                    val seconds = listOf(1L, 2L, 4L, 8L, 30L)[failures.coerceAtMost(4)]
                    failures++
                    delay(seconds * 1000)
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun runSession(refresh: String) = coroutineScope {
        val address = DashboardAddress.parse(storage.settings.dashboard)
        if (accessToken == null || System.currentTimeMillis() >= accessExpires - 60_000) {
            val response = tokens.exchange(address.origin, "refresh_token", refresh)
            accessToken = response.text("access_token").ifEmpty { throw LoginRequired() }
            accessExpires = System.currentTimeMillis() + response["expires_in"]!!.jsonPrimitive.long * 1000
        }
        val session = HaSocket(http, address.origin.replaceFirst("https://", "wss://") + "/api/websocket", accessToken!!)
        socket = session
        try {
            session.connect()
            session.request("subscribe_events", buildJsonObject { put("event_type", "state_changed") })
            val states = async { session.request("get_states").jsonArray }
            val services = async { session.request("get_services").jsonObject }
            val panels = async {
                try { session.request("get_panels").jsonObject }
                catch (_: HaCommandError) { null }
            }
            val dashboard = async { session.request("lovelace/config", buildJsonObject { put("url_path", address.dashboard) }).jsonObject }
            suspend fun optionalMetadata(type: String): JsonElement? = try {
                session.request(type)
            } catch (error: CancellationException) { throw error }
            catch (_: HaCommandError) { null }
            val registry = async { optionalMetadata("config/entity_registry/list_for_display") as? JsonObject }
            val labelsMetadata = async { optionalMetadata("config/label_registry/list") as? JsonArray }
            val devicesMetadata = async { optionalMetadata("config/device_registry/list") as? JsonArray }
            val cards = discoverCards(dashboard.await(), address.view)
            val chosen = selectCard(cards, storage.settings.selected) ?: run {
                val deferred = CompletableDeferred<CardCandidate>()
                selection = deferred
                state.update { it.copy(choices = cards, status = "Choose a search card") }
                try { deferred.await() } finally { selection = null }
            }
            withContext(Dispatchers.IO) { storage.settings = storage.settings.copy(selected = chosen.selection) }
            val entries = registry.await()
            val labelRegistry = labelsMetadata.await()
            val devices = devicesMetadata.await()
            val labels = if (entries != null && labelRegistry != null) labelSets(labelRegistry, entries) else null
            val deviceNames = if (entries != null && devices != null) entityDeviceNames(entries, devices) else emptyMap()
            val navigation = panels.await()
            val fetched = states.await().map { it.jsonObject }.associateBy { it.text("entity_id") }
            var snapshot = Snapshot(chosen.config, JsonObject(fetched), services.await(),
                labels?.first ?: emptySet(), labels?.second ?: emptySet(), System.currentTimeMillis(),
                panels = navigation ?: JsonObject(emptyMap()), entityDeviceNames = deviceNames)
            // Events received since subscribing are applied after the snapshot, using timestamps.
            while (true) {
                val event = session.events.tryReceive().getOrNull() ?: break
                snapshot = snapshot.copy(states = applyStateEvent(snapshot.states, event))
            }
            withContext(Dispatchers.IO) { storage.saveSnapshot(snapshot) }
            state.update { it.copy(snapshot = snapshot, connected = true, choices = emptyList(),
                status = "Connected", error = listOfNotNull(
                    if (labels == null) "Label metadata unavailable; priority/hidden labels could not be applied." else null,
                    if (entries == null || devices == null) "Device names unavailable; searching entity IDs and names only." else null,
                    if (navigation == null) "Navigation pages unavailable; refresh to try again." else null,
                ).joinToString("\n").ifEmpty { null }) }
            val cacheWriter = launch {
                state.map { it.snapshot }.distinctUntilChanged().debounce(750).collect { value ->
                    if (value != null) withContext(Dispatchers.IO) { storage.saveSnapshot(value) }
                }
            }
            try {
                for (event in session.events) {
                    state.update { current -> current.copy(snapshot = current.snapshot?.let {
                        it.copy(states = applyStateEvent(it.states, event), savedAt = System.currentTimeMillis())
                    }) }
                }
            } finally { cacheWriter.cancelAndJoin() }
        } finally {
            session.close()
            if (socket === session) socket = null
            state.update { it.copy(connected = false, busyActions = emptySet()) }
        }
    }

    fun stop() {
        started = false
        foreground?.cancel()
        loginExchange?.cancel()
        socket?.close()
        socket = null
        http.dispatcher.cancelAll()
        state.update { it.copy(connected = false, choices = emptyList(), busyActions = emptySet()) }
    }
    fun refresh() { stop(); start() }
    fun choose(card: CardCandidate) { selection?.complete(card) }
    fun changeCard() {
        storage.settings = storage.settings.copy(selected = null)
        refresh()
    }
    fun reportError(message: String) { state.update { it.copy(error = message) } }
    fun cancelLogin() { loginExchange?.cancel(); loginExchange = null }
    fun setDashboard(value: String) {
        val parsed = DashboardAddress.parse(value)
        if (parsed.url == storage.settings.dashboard) return
        stop(); accountEpoch++
        storage.clearAccount()
        clearWebLogin()
        storage.settings = AccountSettings(dashboard = parsed.url)
        accessToken = null
        state.value = initialPanelState(hasDashboard = true, hasSession = false)
        query.value = ""
        start()
    }
    fun acceptCode(code: String, done: (Boolean) -> Unit) {
        val epoch = accountEpoch
        loginExchange = viewModelScope.launch {
            try {
                val response = tokens.exchange(DashboardAddress.parse(storage.settings.dashboard).origin, "authorization_code", code)
                if (epoch != accountEpoch) { done(false); return@launch }
                val refresh = response.text("refresh_token").ifEmpty { throw LoginRequired() }
                withContext(Dispatchers.IO) { storage.saveRefreshToken(refresh) }
                accessToken = response.text("access_token")
                accessExpires = System.currentTimeMillis() + response["expires_in"]!!.jsonPrimitive.long * 1000
                state.update { it.copy(needsLogin = false, error = null) }
                done(true)
                if (started) {
                    // Avoid cancelling this exchange through stop() until it has completed.
                    foreground?.cancel()
                    start()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError(error.message ?: "Sign-in failed."); done(false) }
        }
    }
    fun logout() {
        stop(); accountEpoch++
        val refresh = storage.refreshToken()
        val origin = storage.settings.dashboard.takeIf { it.isNotBlank() }?.let { DashboardAddress.parse(it).origin }
        storage.clearAccount(); accessToken = null; accessExpires = 0
        clearWebLogin()
        query.value = ""
        state.value = initialPanelState(hasDashboard = origin != null, hasSession = false)
        // Best effort revocation belongs to the visible screen, never background work.
        started = true
        foreground = viewModelScope.launch {
            if (refresh != null && origin != null) try { tokens.revoke(origin, refresh) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { reportError("Signed out locally. Server token revocation failed; remove the session in your Home Assistant profile if needed.") }
        }
    }
    fun callService(key: String, service: String, data: JsonElement) {
        val session = socket ?: return
        val owner = foreground ?: return
        serviceCalls.submit(CoroutineScope(viewModelScope.coroutineContext + owner), key, service, data) { fields ->
            session.request("call_service", fields)
        }
    }
    override fun onCleared() { stop() }

    private fun clearWebLogin() {
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
    }
}
