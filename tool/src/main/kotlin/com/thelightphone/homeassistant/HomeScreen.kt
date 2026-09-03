package com.thelightphone.homeassistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    /** Recorded history per entity, for whichever rows plot it. */
    val histories = MutableStateFlow<Map<String, ChartSeries>>(emptyMap())
    private var historyJob: kotlinx.coroutines.Job? = null

    /** Entity waiting for a confirming second tap, if any. */
    val pendingConfirm = MutableStateFlow<String?>(null)
    private var confirmTimeout: kotlinx.coroutines.Job? = null

    private var client: HaClient? = null
    private var clientConfig: ServerConfig? = null
    private var liveConnection: HaLiveConnection? = null

    private companion object {
        const val CONFIRM_WINDOW_MILLIS = 5_000L
    }

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

    /** Follows a navigate tap action, e.g. "/light-phone/upstairs" or "upstairs". */
    /**
     * Fetches the history the visible view's charts need, and only that.
     *
     * A dashboard can carry a lot of them, and each is its own request against
     * the recorder, so this follows the eye: whatever view is open, nothing
     * else. Already-loaded series are left alone — flicking between views is
     * meant to be cheap.
     */
    fun loadHistories() {
        val active = client ?: return
        val view = views.value.getOrNull(viewIndex.value) ?: return
        val wanted = view.rows
            .flatMap { row ->
                when (row) {
                    is DashRow.Chart -> row.entityIds.map { it to row.hours }
                    is DashRow.EntityGraph -> listOf(row.entityId to row.hours)
                    else -> emptyList()
                }
            }
            .distinctBy { it.first }
            .filterNot { (entityId, _) -> histories.value.containsKey(entityId) }
        if (wanted.isEmpty()) return

        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            wanted.forEach { (entityId, hours) ->
                val since = System.currentTimeMillis() - hours * 60L * 60L * 1000L
                active.fetchHistory(entityId, since)
                    .onSuccess { points ->
                        val unit = states.value[entityId]
                            ?.attributes?.get("unit_of_measurement")
                            ?.let { (it as? JsonPrimitive)?.contentOrNull }
                        // Publish per entity, so the first chart appears while
                        // the rest are still coming in.
                        histories.value = histories.value +
                            (entityId to ChartSeries.from(points, unit))
                    }
                    .onFailure {
                        android.util.Log.w("HomeTool", "history for $entityId: ${it.message}")
                    }
            }
        }
    }

    fun openViewByPath(path: String) {
        val wanted = path.trimEnd('/').substringAfterLast('/')
        val index = views.value.indexOfFirst { it.path == wanted }
        if (index >= 0) {
            viewIndex.value = index
        } else {
            error.value = "No view '$wanted' on this dashboard."
        }
    }

    fun nextView() {
        val count = views.value.size
        if (count > 1) viewIndex.value = (viewIndex.value + 1).mod(count)
    }

    /** What a tap should do next; the screen handles the code prompt. */
    enum class TapOutcome { AWAITING_CONFIRM, NEEDS_CODE, PERFORMED }

    fun tap(entityId: String, code: String? = null): TapOutcome {
        val active = client ?: return TapOutcome.PERFORMED
        val domain = entityId.substringBefore(".")
        val entity = states.value[entityId]
        val state = entity?.state

        // Opening a lock, or arming/disarming, takes two taps: a pocket press
        // should never unlock a door or switch the alarm.
        if (code == null &&
            HaActions.needsConfirmation(domain, state) &&
            pendingConfirm.value != entityId
        ) {
            pendingConfirm.value = entityId
            confirmTimeout?.cancel()
            confirmTimeout = viewModelScope.launch {
                kotlinx.coroutines.delay(CONFIRM_WINDOW_MILLIS)
                if (pendingConfirm.value == entityId) pendingConfirm.value = null
            }
            return TapOutcome.AWAITING_CONFIRM
        }

        // Ask for the keypad code only where the panel actually demands one:
        // many are configured to arm without it.
        if (code == null && entity?.text("code_format") != null) {
            val arming = state == "disarmed" || state == null
            val required = !arming || entity.flag("code_arm_required") == true
            if (required) return TapOutcome.NEEDS_CODE
        }

        pendingConfirm.value = null
        confirmTimeout?.cancel()

        val action = HaActions.actionFor(domain, state) ?: return TapOutcome.PERFORMED
        val data = code?.let { mapOf("code" to it) } ?: emptyMap()
        viewModelScope.launch {
            active.callService(action, entityId, data)
                .onFailure { failure ->
                    val rejectedCode = data.containsKey("code") &&
                        failure.message?.contains("HTTP 500") == true
                    error.value = if (rejectedCode) "Incorrect code." else failure.message
                }
            // Service calls return after the state change; refresh states only.
            active.fetchStates().onSuccess { (parsed, _) -> states.value = parsed }
        }
        return TapOutcome.PERFORMED
    }

    fun clearPendingConfirm() {
        pendingConfirm.value = null
        confirmTimeout?.cancel()
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
            val servers = ServerStore(lightContext.dataStore).servers()
                .filter { it.webhookId != null && (it.sendLocation || it.sendBattery) }
            if (servers.isEmpty()) {
                cancelLocationReporting(lightContext)
                return@launch
            }
            scheduleLocationReporting(lightContext)
            servers.firstOrNull { it.sendLocation }?.let {
                refreshZoneGeofences(lightContext, it)
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
        val pendingConfirm by viewModel.pendingConfirm.collectAsState()
        val histories by viewModel.histories.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (hasServers) {
                    null -> Unit
                    false -> EmptyState()
                    true -> Dashboard(
                        server, views, viewIndex, states, error, loading, live, pendingConfirm,
                        histories,
                    )
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
    private fun androidx.compose.foundation.layout.ColumnScope.Dashboard(
        server: ServerConfig?,
        views: List<DashView>,
        viewIndex: Int,
        states: Map<String, HaState>,
        error: String?,
        loading: Boolean,
        live: Boolean,
        pendingConfirm: String?,
        histories: Map<String, ChartSeries>,
    ) {
        val view = views.getOrNull(viewIndex)
        // Charts belong to a view, so the fetch follows whichever is open.
        LaunchedEffect(viewIndex, views) { viewModel.loadHistories() }
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

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (view.badges.isNotEmpty()) {
                item { BadgeStrip(view.badges, states) }
            }
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
                    is DashRow.Entity -> EntityRow(row, states, pendingConfirm)
                    is DashRow.Chart -> ChartCard(row, states, histories)
                    is DashRow.EntityGraph -> EntityGraphRow(row, states, histories)
                    is DashRow.Map -> LinkRow(row.title) { openMap(row.entityIds, row.title) }
                    is DashRow.Navigate -> LinkRow(row.title) { viewModel.openViewByPath(row.path) }
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "Assist", onClick = { openAssist() }),
            ),
        )
    }

    /** A row that leads somewhere else: another view, a map, a camera. */
    @Composable
    private fun LinkRow(title: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = title,
                variant = LightTextVariant.Copy,
                modifier = Modifier.weight(1f),
            )
            LightText(text = "▸", variant = LightTextVariant.Copy)
        }
    }

    /**
     * The badges a dashboard view carries, which are already the user's own
     * answer to what deserves noticing first — no second list to keep.
     *
     * Every badge is the same box; only the ink changes. Giving the quiet ones
     * an outline too is what puts all three on one baseline, and it means the
     * eye compares weight rather than shape. The filled one keeps a border in
     * its own fill colour, or its text would sit a hair off the others.
     */
    @Composable
    private fun BadgeStrip(badges: List<DashBadge>, states: Map<String, HaState>) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            badges.forEach { badge -> Badge(badge, states[badge.entityId]) }
        }
    }

    @Composable
    private fun Badge(badge: DashBadge, state: HaState?) {
        val colors = LightThemeTokens.colors
        val edge = when (badge.urgency) {
            BadgeUrgency.CALM -> colors.contentSecondary
            BadgeUrgency.ATTENTION, BadgeUrgency.URGENT -> colors.content
        }
        val fill = if (badge.urgency == BadgeUrgency.URGENT) colors.content else Color.Transparent
        val ink = if (badge.urgency == BadgeUrgency.URGENT) colors.background else edge

        val name = badge.nameOverride ?: state?.friendlyName ?: badge.entityId
        val reading = HaActions.stateLabel(state)
        val label = buildString {
            if (badge.urgency == BadgeUrgency.URGENT) append("! ")
            if (badge.showName) append("$name ")
            append(reading)
        }

        val shape = RoundedCornerShape(4.dp)
        Box(
            modifier = Modifier
                .background(fill, shape)
                .border(1.5.dp, edge, shape)
                .lightClickable { openDetail(badge.entityId, name) }
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            LightText(text = label, variant = LightTextVariant.Detail, color = ink)
        }
    }

    /** A history-graph or statistics-graph card: a title and one plot per entity. */
    @Composable
    private fun ChartCard(
        row: DashRow.Chart,
        states: Map<String, HaState>,
        histories: Map<String, ChartSeries>,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
            LightText(text = row.title, variant = LightTextVariant.Heading)
            row.entityIds.forEach { entityId ->
                // With one entity the card title already says what this is.
                if (row.entityIds.size > 1) {
                    LightText(
                        text = states[entityId]?.friendlyName ?: entityId,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                when (val series = histories[entityId]) {
                    null -> LightText(
                        text = "Loading history…",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    else -> HistoryChart(
                        series = series,
                        style = row.style,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    /** A sensor card with a graph: the value, with its recent shape beside it. */
    @Composable
    private fun EntityGraphRow(
        row: DashRow.EntityGraph,
        states: Map<String, HaState>,
        histories: Map<String, ChartSeries>,
    ) {
        val state = states[row.entityId]
        val label = row.nameOverride ?: state?.friendlyName ?: row.entityId
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { openDetail(row.entityId, label) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = label,
                variant = LightTextVariant.Copy,
                modifier = Modifier.weight(1f),
            )
            histories[row.entityId]?.let { series ->
                Sparkline(
                    series = series,
                    style = row.style,
                    modifier = Modifier
                        .width(SPARKLINE_WIDTH)
                        .padding(horizontal = 10.dp),
                )
            }
            LightText(
                text = HaActions.stateLabel(state),
                variant = LightTextVariant.Copy,
            )
        }
    }

    @Composable
    private fun EntityRow(
        row: DashRow.Entity,
        states: Map<String, HaState>,
        pendingConfirm: String?,
    ) {
        val state = states[row.entityId]
        val domain = row.entityId.substringBefore(".")
        val isCamera = domain == "camera"
        val hasDetail = HaActions.hasDetailScreen(state)
        val actionable = isCamera || hasDetail || HaActions.actionFor(domain, state?.state) != null
        val label = row.nameOverride ?: state?.friendlyName ?: row.entityId

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let {
                    when {
                        isCamera -> it.lightClickable { openCamera(row.entityId, label) }
                        hasDetail -> it.lightClickable { openDetail(row.entityId, label) }
                        actionable -> it.lightClickable { onEntityTap(row.entityId, label) }
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
                    pendingConfirm == row.entityId -> confirmLabel(domain, state?.state)
                    isCamera -> "▸"
                    HaActions.isRunAction(domain) -> "▷"
                    else -> HaActions.stateLabel(state)
                },
                variant = LightTextVariant.Copy,
                lighten = !actionable,
            )
        }
    }

    private val SPARKLINE_WIDTH = 84.dp

    private fun openSetup(existing: ServerConfig? = null) {
        navigateTo(
            screenFactory = { SetupScreen(it, existing) },
            resultCallback = { saved -> if (saved == true) viewModel.reload() },
        )
    }

    private fun openAssist() {
        val server = viewModel.server.value ?: return
        navigateTo(screenFactory = { AssistScreen(it, server) })
    }

    private fun openCamera(entityId: String, label: String) {
        val server = viewModel.server.value ?: return
        navigateTo(screenFactory = { CameraScreen(it, server, entityId, label) })
    }

    private fun onEntityTap(entityId: String, label: String) {
        if (viewModel.tap(entityId) != HomeViewModel.TapOutcome.NEEDS_CODE) return
        navigateTo(
            screenFactory = { CodeEntryScreen(it, "$label code") },
            resultCallback = { code ->
                if (code.isNullOrBlank()) viewModel.clearPendingConfirm()
                else viewModel.tap(entityId, code)
            },
        )
    }

    private fun confirmLabel(domain: String, state: String?): String = when {
        domain == "lock" -> "Unlock?"
        domain == "alarm_control_panel" && state == "disarmed" -> "Arm away?"
        domain == "alarm_control_panel" -> "Disarm?"
        else -> "Confirm?"
    }

    private fun openDetail(entityId: String, label: String) {
        val server = viewModel.server.value ?: return
        navigateTo(
            screenFactory = { EntityDetailScreen(it, server, entityId, label) },
            resultCallback = { viewModel.refresh() },
        )
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
