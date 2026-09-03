package com.thelightphone.homeassistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** How far back the screen is looking. */
enum class HistoryRange(val label: String, val hours: Int) {
    DAY("24 hours", 24),
    WEEK("7 days", 7 * 24),
    MONTH("30 days", 30 * 24),
}

/**
 * What an entity has been doing: its shape over time, what changed it, and the
 * handful of attributes worth reading.
 *
 * This is where a plain sensor finally has somewhere to go. A tap on one did
 * nothing at all before — there is no service to call on a thermometer — so the
 * gesture was free, and history is the only thing anyone wants from it.
 */
class EntityHistoryViewModel(
    private val server: ServerConfig,
    private val entityId: String,
) : LightViewModel<Unit>() {

    val state = MutableStateFlow<HaState?>(null)
    val series = MutableStateFlow<ChartSeries?>(null)
    val logbook = MutableStateFlow<List<LogbookEntry>>(emptyList())
    val range = MutableStateFlow(HistoryRange.DAY)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val expanded = MutableStateFlow(false)

    private var client: HaClient? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        client = client ?: HaClient(server)
        refresh()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        client?.close()
        client = null
        super.onScreenHide(screen)
    }

    fun choose(next: HistoryRange) {
        if (next == range.value) return
        range.value = next
        // The old series would otherwise stay on screen under the new label,
        // reading as though a month looked exactly like a day.
        series.value = null
        refresh()
    }

    fun toggleExpanded() {
        expanded.value = !expanded.value
    }

    fun refresh() {
        val active = client ?: return
        loading.value = true
        viewModelScope.launch {
            val since = System.currentTimeMillis() -
                TimeUnit.HOURS.toMillis(range.value.hours.toLong())
            try {
                active.fetchStates()
                    .onSuccess { (states, _) -> state.value = states[entityId] }
                    .onFailure { error.value = it.message }

                active.fetchHistory(entityId, since)
                    .onSuccess { series.value = ChartSeries.from(it, state.value?.unit) }
                    .onFailure { error.value = it.message }

                // A logbook failure is not worth an error line of its own: the
                // chart above it is the point, and the recorder can be trimmed
                // shorter than the history it keeps.
                active.fetchLogbook(entityId, since)
                    .onSuccess { logbook.value = it.take(MAX_ENTRIES) }
                    .onFailure {
                        android.util.Log.w("HomeTool", "logbook: ${it.message}")
                    }
            } finally {
                loading.value = false
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 30
    }
}

class EntityHistoryScreen(
    sealedActivity: SealedLightActivity,
    private val server: ServerConfig,
    private val entityId: String,
    private val title: String,
) : LightScreen<Unit, EntityHistoryViewModel>(sealedActivity) {

    override val viewModelClass: Class<EntityHistoryViewModel>
        get() = EntityHistoryViewModel::class.java

    override fun createViewModel() = EntityHistoryViewModel(server, entityId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val series by viewModel.series.collectAsState()
        val logbook by viewModel.logbook.collectAsState()
        val range by viewModel.range.collectAsState()
        val loading by viewModel.loading.collectAsState()
        val expanded by viewModel.expanded.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack(null) },
                    ),
                    center = LightTopBarCenter.Text(title),
                    rightButton = LightBarButton.Text(
                        text = if (loading) "…" else "↻",
                        onClick = { viewModel.refresh() },
                    ),
                )

                LazyColumn(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                    item { Reading(state) }
                    item {
                        when {
                            series == null -> LightText(
                                text = if (loading) "Loading history…" else "No history.",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                            else -> HistoryChart(
                                series = series!!,
                                style = ChartStyle.LINE,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                    item { RangePicker(range) }

                    if (logbook.isNotEmpty()) {
                        item { SectionHeading("Happened") }
                        items(logbook.size) { index -> LogbookRow(logbook[index]) }
                    }

                    val chosen = state?.let { EntityDetails.chosen(it) }.orEmpty()
                    val all = state?.let { EntityDetails.everything(it) }.orEmpty()
                    if (chosen.isNotEmpty() || all.isNotEmpty()) {
                        item { SectionHeading("Details") }
                        val shown = if (expanded) all else chosen
                        items(shown.size) { index ->
                            DetailRow(shown[index].first, shown[index].second)
                        }
                        // Only worth offering when there is more behind it.
                        if (all.size > chosen.size) {
                            item {
                                LightText(
                                    text = if (expanded) "Less" else "More",
                                    variant = LightTextVariant.Copy,
                                    lighten = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable { viewModel.toggleExpanded() }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                    }

                    item { Column(modifier = Modifier.padding(bottom = 24.dp)) {} }
                }
            }
        }
    }

    /** The current value, given the room a number deserves when it is the point. */
    @Composable
    private fun Reading(state: HaState?) {
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                LightText(
                    text = HaActions.stateLabel(state),
                    variant = LightTextVariant.Subtitle,
                )
            }
        }
    }

    @Composable
    private fun RangePicker(selected: HistoryRange) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HistoryRange.entries.forEach { option ->
                // The badge treatment, reused: filled is chosen, outlined is
                // not, and both boxes are the same so they sit on one line.
                val chosen = option == selected
                val colors = LightThemeTokens.colors
                val edge = if (chosen) colors.content else colors.contentSecondary
                val shape = RoundedCornerShape(4.dp)
                Box(
                    modifier = Modifier
                        .background(if (chosen) colors.content else Color.Transparent, shape)
                        .border(1.5.dp, edge, shape)
                        .lightClickable { viewModel.choose(option) }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                ) {
                    LightText(
                        text = option.label,
                        variant = LightTextVariant.Detail,
                        color = if (chosen) colors.background else edge,
                    )
                }
            }
        }
    }

    @Composable
    private fun SectionHeading(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Heading,
            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
        )
    }

    /**
     * One thing that happened, and — when Home Assistant recorded one — what set
     * it off. The cause is left out rather than guessed at when it is absent,
     * which is often: a sensor that simply changed was not caused by anybody.
     */
    @Composable
    private fun LogbookRow(entry: LogbookEntry) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LightText(
                    text = entry.message?.replaceFirstChar { it.uppercase() }
                        ?: entry.state.orEmpty(),
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.weight(1f),
                )
                LightText(
                    text = clockTime(entry.atMillis),
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
            entry.triggeredBy?.let { cause ->
                LightText(
                    text = "via $cause",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    @Composable
    private fun DetailRow(label: String, value: String) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
            LightText(
                text = label,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.weight(1f),
            )
            LightText(text = value, variant = LightTextVariant.Detail)
        }
    }

    /** Today gets a clock, anything older gets a date; the year is never news. */
    private fun clockTime(atMillis: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = atMillis }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "d MMM HH:mm"
        return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
            .format(java.util.Date(atMillis))
    }
}
