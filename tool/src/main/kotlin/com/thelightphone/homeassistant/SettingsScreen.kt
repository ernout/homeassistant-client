package com.thelightphone.homeassistant

import androidx.compose.foundation.background
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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
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

class SettingsViewModel(dataStore: DataStore<Preferences>) : LightViewModel<Boolean>() {

    private val store = ServerStore(dataStore)

    val servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val selectedId = MutableStateFlow<String?>(null)
    var changed = false
        private set

    override fun onScreenShow(screen: SimpleLightScreen<Boolean>) {
        super.onScreenShow(screen)
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            servers.value = store.servers()
            selectedId.value = store.selected()?.id
        }
    }

    fun toggleLocation(server: ServerConfig) = update(server.copy(sendLocation = !server.sendLocation))

    fun toggleBattery(server: ServerConfig) = update(server.copy(sendBattery = !server.sendBattery))

    fun select(server: ServerConfig) {
        viewModelScope.launch {
            store.select(server.id)
            changed = true
            reload()
        }
    }

    fun remove(server: ServerConfig) {
        viewModelScope.launch {
            store.remove(server.id)
            changed = true
            reload()
        }
    }

    private fun update(server: ServerConfig) {
        viewModelScope.launch {
            val previouslySelected = store.selected()?.id
            store.upsert(server)
            // upsert selects the server it writes; keep the old selection.
            previouslySelected?.let { store.select(it) }
            changed = true
            reload()
        }
    }
}

/** Settings: servers, what to report to each of them. Returns true if anything changed. */
class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Boolean, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val servers by viewModel.servers.collectAsState()
        val selectedId by viewModel.selectedId.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack(viewModel.changed) },
                    ),
                    center = LightTopBarCenter.Text("Settings"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                    servers.forEach { server ->
                        LightText(
                            text = if (server.id == selectedId) "${server.name} ·" else server.name,
                            variant = LightTextVariant.Heading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable { viewModel.select(server) }
                                .padding(top = 20.dp, bottom = 4.dp),
                        )
                        ToggleRow(
                            label = "Send location",
                            enabled = server.sendLocation,
                            onClick = { viewModel.toggleLocation(server) },
                        )
                        ToggleRow(
                            label = "Send battery",
                            enabled = server.sendBattery,
                            onClick = { viewModel.toggleBattery(server) },
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        ) {
                            LightText(
                                text = "Edit",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier
                                    .lightClickable { editServer(server) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            )
                            LightText(
                                text = "Remove",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier
                                    .lightClickable { viewModel.remove(server) }
                                    .padding(vertical = 10.dp, horizontal = 16.dp),
                            )
                        }
                    }

                    LightText(
                        text = "Add server",
                        variant = LightTextVariant.Heading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { editServer(null) }
                            .padding(top = 28.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun ToggleRow(label: String, enabled: Boolean, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = label,
                variant = LightTextVariant.Copy,
                modifier = Modifier.weight(1f),
            )
            LightIcon(
                icon = if (enabled) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
            )
        }
    }

    private fun editServer(existing: ServerConfig?) {
        navigateTo(
            screenFactory = { SetupScreen(it, existing) },
            resultCallback = { saved -> if (saved == true) viewModel.reload() },
        )
    }
}
