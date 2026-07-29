package com.thelightphone.homeassistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    dataStore: DataStore<Preferences>,
    private val battery: com.thelightphone.sdk.LightBattery,
    private val location: com.thelightphone.sdk.LightLocation,
) : LightViewModel<Unit>() {

    private val store = ServerStore(dataStore)

    val server = MutableStateFlow<ServerConfig?>(null)
    val hasServers = MutableStateFlow<Boolean?>(null)
    val views = MutableStateFlow<List<DashView>>(emptyList())
    val viewIndex = MutableStateFlow(0)
    val states = MutableStateFlow<Map<String, HaState>>(emptyMap())
    val error = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)

    val live = MutableStateFlow(false)

    private var client: HaClient? = null
    private var clientConfig: ServerConfig? = null
    private var liveConnection: HaLiveConnection? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reload()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        stopLive()
        super.onScreenHide(screen)
    }

    override fun onAppPause() {
        stopLive()
        super.onAppPause()
    }

    /** Subscribes to just the entities the current dashboard renders. */
    private fun startLive() {
        val cfg = clientConfig ?: return
        val ids = views.value
            .flatMap { view -> view.rows.filterIsInstance<DashRow.Entity>().map { it.entityId } }
            .distinct()
        if (ids.isEmpty()) return
        liveConnection?.stop()
        liveConnection = HaLiveConnection(
            server = cfg,
            entityIds = ids,
            scope = viewModelScope,
            onStates = { updated -> states.value = states.value + updated },
            onConnected = { live.value = it },
        ).also { it.start() }
    }

    private fun stopLive() {
        liveConnection?.stop()
        liveConnection = null
    }

    fun reload() {
        viewModelScope.launch {
            val selected = store.selected()
            hasServers.value = selected != null
            server.value = selected
            if (selected == null) return@launch
            if (selected != clientConfig) {
                val switchedServer = selected.id != clientConfig?.id
                clientConfig = selected
                client?.close()
                client = HaClient(selected)
                if (switchedServer) {
                    views.value = emptyList()
                    viewIndex.value = 0
                    states.value = emptyMap()
                    loadFromCache(selected)
                }
            }
            refresh()
        }
    }

    fun refresh() {
        if (client == null || loading.value) return
        loading.value = true
        error.value = null
        // Device state (registration, battery, a GPS fix) must never hold up the
        // dashboard: a cold GPS start can take many seconds.
        viewModelScope.launch {
            ensureRegistered()
            syncDeviceState()
        }
        viewModelScope.launch {
            val active = client ?: return@launch
            val serverId = clientConfig?.id
            active.fetchStates()
                .onSuccess { (parsed, raw) ->
                    states.value = parsed
                    serverId?.let { store.cacheStates(it, raw) }
                }
                .onFailure {
                    android.util.Log.e("HomeTool", "states failed", it)
                    error.value = it.message
                }
            active.fetchDashboard()
                .onSuccess { (parsed, raw) ->
                    views.value = parsed
                    serverId?.let { store.cacheDashboard(it, raw) }
                }
                .onFailure {
                    android.util.Log.e("HomeTool", "dashboard failed", it)
                    if (error.value == null) error.value = it.message
                }
            loading.value = false
            startLive()
        }
    }

    /** Paints the last known dashboard and states so the screen is never empty. */
    private suspend fun loadFromCache(server: ServerConfig) {
        val active = client ?: return
        store.cachedDashboard(server.id)?.let { raw ->
            active.parseDashboard(raw)?.let { views.value = it }
        }
        store.cachedStates(server.id)?.let { raw ->
            active.parseStates(raw)?.let { states.value = it }
        }
    }

    /** Registers with HA's mobile_app integration once per server. */
    private suspend fun ensureRegistered() {
        val cfg = clientConfig ?: return
        if (cfg.webhookId != null) return
        val active = client ?: return
        active.register(store.deviceId())
            .onSuccess { reg ->
                val updated = cfg.copy(
                    webhookId = reg.webhook_id,
                    cloudhookUrl = reg.cloudhook_url,
                    remoteUiUrl = reg.remote_ui_url,
                )
                store.upsert(updated)
                clientConfig = updated
                client?.close()
                client = HaClient(updated)
            }
            .onFailure { android.util.Log.e("HomeTool", "mobile_app registration failed", it) }
    }

    /** Reports battery level and location to HA; clears the webhook on HTTP 410. */
    private suspend fun syncDeviceState() {
        val cfg = clientConfig ?: return
        if (cfg.webhookId == null) return
        val active = client ?: return
        val level = if (cfg.sendBattery) battery.levelPercent() else null

        val result = runCatching {
            if (level != null) {
                active.registerBatterySensor(level).getOrThrow()
                active.updateBatterySensor(level).getOrThrow()
            }
            (if (cfg.sendLocation) location.current() else null).let { fix ->
                if (fix != null) {
                    active.updateLocation(
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        accuracyMeters = fix.accuracyMeters,
                        battery = level,
                    ).getOrThrow()
                }
            }
        }

        val failure = result.exceptionOrNull() ?: return
        android.util.Log.e("HomeTool", "device state sync failed", failure)
        if (failure is HaClient.WebhookGoneException) {
            val cleared = cfg.copy(webhookId = null, cloudhookUrl = null, remoteUiUrl = null)
            store.upsert(cleared)
            clientConfig = cleared
            client?.close()
            client = HaClient(cleared)
        }
    }

    fun nextServer() {
        viewModelScope.launch {
            store.selectNext()
            reload()
        }
    }

    fun nextView() {
        val count = views.value.size
        if (count > 1) viewIndex.value = (viewIndex.value + 1).mod(count)
    }

    fun tap(entityId: String) {
        val active = client ?: return
        val domain = entityId.substringBefore(".")
        val action = HaActions.actionFor(domain, states.value[entityId]?.state) ?: return
        viewModelScope.launch {
            active.callService(action, entityId)
                .onFailure { error.value = it.message }
            // Service calls return after the state change; refresh states only.
            active.fetchStates().onSuccess { (parsed, _) -> states.value = parsed }
        }
    }

    override fun onCleared() {
        client?.close()
        super.onCleared()
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(
        dataStore = lightContext.dataStore,
        battery = lightContext.battery,
        location = lightContext.location,
    )

    override fun willShow() {
        super.willShow()
        // Keep the background reporting job in sync with the current toggles.
        viewModel.viewModelScope.launch {
            val reporting = ServerStore(lightContext.dataStore).servers()
                .any { it.webhookId != null && (it.sendLocation || it.sendBattery) }
            if (reporting) {
                scheduleLocationReporting(lightContext)
            } else {
                cancelLocationReporting(lightContext)
            }
        }
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val hasServers by viewModel.hasServers.collectAsState()
        val server by viewModel.server.collectAsState()
        val views by viewModel.views.collectAsState()
        val viewIndex by viewModel.viewIndex.collectAsState()
        val states by viewModel.states.collectAsState()
        val error by viewModel.error.collectAsState()
        val loading by viewModel.loading.collectAsState()
        val live by viewModel.live.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (hasServers) {
                    null -> Unit
                    false -> EmptyState()
                    true -> Dashboard(server, views, viewIndex, states, error, loading, live)
                }
            }
        }
    }

    @Composable
    private fun EmptyState() {
        LightTopBar(center = LightTopBarCenter.Text("Home"))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = "No Home Assistant server configured yet.",
                variant = LightTextVariant.Copy,
                lighten = true,
                modifier = Modifier.padding(top = 32.dp, bottom = 24.dp),
            )
            LightText(
                text = "Add server",
                variant = LightTextVariant.Heading,
                modifier = Modifier
                    .lightClickable { openSetup() }
                    .padding(vertical = 12.dp),
            )
        }
    }

    @Composable
    private fun Dashboard(
        server: ServerConfig?,
        views: List<DashView>,
        viewIndex: Int,
        states: Map<String, HaState>,
        error: String?,
        loading: Boolean,
        live: Boolean,
    ) {
        val view = views.getOrNull(viewIndex)
        val centerText = buildString {
            append(server?.name ?: "Home")
            if (views.size > 1 && view != null) append(" · ${view.title}")
        }
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.SETTINGS,
                onClick = { openSettings() },
            ),
            center = LightTopBarCenter.Text(
                text = centerText,
                onClick = {
                    if (views.size > 1) viewModel.nextView() else viewModel.nextServer()
                },
            ),
            rightButton = LightBarButton.Text(
                text = when {
                    loading -> "…"
                    live -> "•"
                    else -> "↻"
                },
                onClick = { viewModel.refresh() },
            ),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        error?.let {
            LightText(
                text = it,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 8.dp),
            )
        }

        if (view == null) {
            if (!loading && error == null) {
                LightText(
                    text = "Dashboard is empty.",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 16.dp),
                )
            }
            return
        }

        LazyColumn(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
            items(view.rows) { row ->
                when (row) {
                    is DashRow.Header -> LightText(
                        text = row.text,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                    )
                    is DashRow.Text -> LightText(
                        text = row.text,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    is DashRow.Entity -> EntityRow(row, states)
                    is DashRow.Map -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { openMap(row.entityIds, row.title) }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LightText(
                            text = row.title,
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.weight(1f),
                        )
                        LightText(text = "▸", variant = LightTextVariant.Copy)
                    }
                }
            }
        }
    }

    @Composable
    private fun EntityRow(row: DashRow.Entity, states: Map<String, HaState>) {
        val state = states[row.entityId]
        val domain = row.entityId.substringBefore(".")
        val isCamera = domain == "camera"
        val actionable = isCamera || HaActions.actionFor(domain, state?.state) != null
        val label = row.nameOverride ?: state?.friendlyName ?: row.entityId

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let {
                    when {
                        isCamera -> it.lightClickable { openCamera(row.entityId, label) }
                        actionable -> it.lightClickable { viewModel.tap(row.entityId) }
                        else -> it
                    }
                }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = label,
                variant = LightTextVariant.Copy,
                modifier = Modifier.weight(1f),
            )
            LightText(
                text = when {
                    isCamera -> "▸"
                    HaActions.isRunAction(domain) -> "▷"
                    else -> HaActions.stateLabel(state)
                },
                variant = LightTextVariant.Copy,
                lighten = !actionable,
            )
        }
    }

    private fun openSetup(existing: ServerConfig? = null) {
        navigateTo(
            screenFactory = { SetupScreen(it, existing) },
            resultCallback = { saved -> if (saved == true) viewModel.reload() },
        )
    }

    private fun openCamera(entityId: String, label: String) {
        val server = viewModel.server.value ?: return
        navigateTo(screenFactory = { CameraScreen(it, server, entityId, label) })
    }

    private fun openMap(entityIds: List<String>, title: String) {
        val server = viewModel.server.value ?: return
        navigateTo(screenFactory = { MapScreen(it, server, entityIds, title) })
    }

    private fun openSettings() {
        navigateTo(
            screenFactory = { SettingsScreen(it) },
            resultCallback = { changed -> if (changed == true) viewModel.reload() },
        )
    }
}
