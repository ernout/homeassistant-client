package com.thelightphone.homeassistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlin.math.roundToInt

/**
 * Detail view for entities that have more than on/off: a dimmer's brightness,
 * a cover's position, a thermostat's target. Values are stepped with big − and
 * + targets rather than a slider — there is no room for precise dragging on
 * this screen, and stepping is unambiguous.
 */
class EntityDetailViewModel(
    private val server: ServerConfig,
    private val entityId: String,
) : LightViewModel<Unit>() {

    val state = MutableStateFlow<HaState?>(null)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

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

    fun refresh() {
        viewModelScope.launch {
            val active = client ?: return@launch
            active.fetchStates()
                .onSuccess { (states, _) -> state.value = states[entityId] }
                .onFailure { error.value = it.message }
        }
    }

    fun call(domain: String, service: String, data: Map<String, Any> = emptyMap()) {
        val active = client ?: return
        busy.value = true
        viewModelScope.launch {
            active.callService(HaActions.ServiceCall(domain, service), entityId, data)
                .onFailure { error.value = it.message }
            refresh()
            busy.value = false
        }
    }

    fun toggle() = call("homeassistant", "toggle")

    /** Brightness in whole percent, stepped by [delta]. */
    fun stepBrightness(delta: Int) {
        val current = state.value?.brightnessPercent ?: 0
        val target = (current + delta).coerceIn(0, 100)
        if (target == 0) {
            call("light", "turn_off")
        } else {
            call("light", "turn_on", mapOf("brightness_pct" to target.toDouble()))
        }
    }

    fun stepCover(delta: Int) {
        val current = state.value?.number("current_position")?.roundToInt() ?: return
        val target = (current + delta).coerceIn(0, 100)
        call("cover", "set_cover_position", mapOf("position" to target.toDouble()))
    }

    fun stepTemperature(delta: Double) {
        val current = state.value?.number("temperature") ?: return
        val step = state.value?.number("target_temp_step") ?: 0.5
        val minimum = state.value?.number("min_temp") ?: 7.0
        val maximum = state.value?.number("max_temp") ?: 35.0
        val target = (current + delta * step).coerceIn(minimum, maximum)
        call("climate", "set_temperature", mapOf("temperature" to target))
    }

    fun stepPercentage(delta: Int) {
        val current = state.value?.number("percentage")?.roundToInt() ?: 0
        val target = (current + delta).coerceIn(0, 100)
        call("fan", "set_percentage", mapOf("percentage" to target.toDouble()))
    }

    fun stepNumber(delta: Int) {
        val current = state.value?.state?.toDoubleOrNull() ?: return
        val step = state.value?.number("step") ?: 1.0
        val minimum = state.value?.number("min") ?: 0.0
        val maximum = state.value?.number("max") ?: 100.0
        val target = (current + delta * step).coerceIn(minimum, maximum)
        val domain = state.value?.domain ?: return
        call(domain, "set_value", mapOf("value" to target))
    }
}

class EntityDetailScreen(
    sealedActivity: SealedLightActivity,
    private val server: ServerConfig,
    private val entityId: String,
    private val title: String,
) : LightScreen<Unit, EntityDetailViewModel>(sealedActivity) {

    override val viewModelClass: Class<EntityDetailViewModel>
        get() = EntityDetailViewModel::class.java

    override fun createViewModel() = EntityDetailViewModel(server, entityId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val error by viewModel.error.collectAsState()

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
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                    when (state?.domain) {
                        "light" -> LightControls(state)
                        "cover" -> CoverControls(state)
                        "climate" -> ClimateControls(state)
                        "fan" -> FanControls(state)
                        "input_number", "number" -> NumberControls(state)
                        else -> ToggleControl(state)
                    }

                    error?.let {
                        LightText(
                            text = it,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun LightControls(state: HaState?) {
        val percent = state?.brightnessPercent ?: 0
        BigValue(if (state?.state == "on") "$percent%" else "Off")
        Stepper(
            onDown = { viewModel.stepBrightness(-10) },
            onUp = { viewModel.stepBrightness(10) },
        )
        ActionRow("Toggle" to { viewModel.toggle() })
    }

    @Composable
    private fun CoverControls(state: HaState?) {
        val position = state?.number("current_position")?.roundToInt()
        BigValue(position?.let { "$it%" } ?: HaActions.stateLabel(state))
        if (position != null) {
            Stepper(
                onDown = { viewModel.stepCover(-10) },
                onUp = { viewModel.stepCover(10) },
            )
        }
        ActionRow(
            "Open" to { viewModel.call("cover", "open_cover") },
            "Stop" to { viewModel.call("cover", "stop_cover") },
            "Close" to { viewModel.call("cover", "close_cover") },
        )
    }

    @Composable
    private fun ClimateControls(state: HaState?) {
        val target = state?.number("temperature")
        val current = state?.number("current_temperature")
        val mode = state?.state
        // hvac_action is what the unit is doing right now (heating/idle);
        // the state is the mode it has been set to.
        val action = state?.text("hvac_action")

        BigValue(target?.let { formatTemperature(it) } ?: "—")
        LightText(
            text = buildString {
                current?.let { append("Now ${formatTemperature(it)}") }
                if (current != null && (mode != null || action != null)) append(" · ")
                append(listOfNotNull(action ?: mode).joinToString().replaceFirstChar { it.uppercase() })
            },
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Stepper(
            onDown = { viewModel.stepTemperature(-1.0) },
            onUp = { viewModel.stepTemperature(1.0) },
        )

        val modes = state?.textList("hvac_modes").orEmpty()
        if (modes.isNotEmpty()) {
            LightText(
                text = "Mode",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            modes.forEach { available ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable {
                            viewModel.call(
                                "climate",
                                "set_hvac_mode",
                                mapOf("hvac_mode" to available),
                            )
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        text = available.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.weight(1f),
                    )
                    if (available == mode) {
                        LightText(text = "\u00b7", variant = LightTextVariant.Copy)
                    }
                }
            }
        }
    }

    @Composable
    private fun FanControls(state: HaState?) {
        val percentage = state?.number("percentage")?.roundToInt()
        BigValue(percentage?.let { "$it%" } ?: HaActions.stateLabel(state))
        Stepper(
            onDown = { viewModel.stepPercentage(-10) },
            onUp = { viewModel.stepPercentage(10) },
        )
        ActionRow("Toggle" to { viewModel.toggle() })
    }

    @Composable
    private fun NumberControls(state: HaState?) {
        BigValue(state?.state ?: "—")
        Stepper(
            onDown = { viewModel.stepNumber(-1) },
            onUp = { viewModel.stepNumber(1) },
        )
    }

    @Composable
    private fun ToggleControl(state: HaState?) {
        BigValue(HaActions.stateLabel(state))
        ActionRow("Toggle" to { viewModel.toggle() })
    }

    @Composable
    private fun BigValue(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Heading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        )
    }

    /** Two oversized hit targets; nothing to drag, nothing to miss. */
    @Composable
    private fun Stepper(onDown: () -> Unit, onUp: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−", onDown)
            StepButton("+", onUp)
        }
    }

    @Composable
    private fun StepButton(label: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .lightClickable(onClick = onClick)
                .padding(horizontal = 48.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = label, variant = LightTextVariant.Heading)
        }
    }

    @Composable
    private fun ActionRow(vararg actions: Pair<String, () -> Unit>) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            actions.forEach { (label, action) ->
                LightText(
                    text = label,
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .lightClickable(onClick = action)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }

    private fun formatTemperature(value: Double): String =
        if (value == value.roundToInt().toDouble()) {
            "${value.roundToInt()}°"
        } else {
            "${(value * 10).roundToInt() / 10.0}°"
        }
}
