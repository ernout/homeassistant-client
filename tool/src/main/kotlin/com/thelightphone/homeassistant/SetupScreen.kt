package com.thelightphone.homeassistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
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
import kotlinx.serialization.json.Json
import java.util.UUID

class SetupViewModel(private val existing: ServerConfig?) : LightViewModel<Boolean>() {

    val name = MutableStateFlow(existing?.name ?: "")
    val url = MutableStateFlow(existing?.url ?: "")
    val token = MutableStateFlow(existing?.token ?: "")
    val dashboard = MutableStateFlow(existing?.dashboard ?: "light-phone")
    val status = MutableStateFlow<String?>(null)
    val saving = MutableStateFlow(false)

    private val json = Json { ignoreUnknownKeys = true }

    /** Handles a scanned QR: either our combined JSON payload or a bare HA token. */
    fun applyScan(value: String) {
        val trimmed = value.trim()
        if (trimmed.startsWith("{")) {
            runCatching { json.decodeFromString<QrPayload>(trimmed) }
                .onSuccess { payload ->
                    url.value = payload.url
                    token.value = payload.token
                    payload.name?.let { name.value = it }
                    status.value = "Scanned server details."
                }
                .onFailure { status.value = "Unrecognized QR code." }
        } else {
            token.value = trimmed
            status.value = "Scanned token."
        }
    }

    fun buildConfig(): ServerConfig? {
        if (url.value.isBlank() || token.value.isBlank()) {
            status.value = "URL and token are required."
            return null
        }
        var normalized = url.value.trim().trimEnd('/')
        if (!normalized.startsWith("http")) normalized = "http://$normalized"
        return ServerConfig(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.value.trim().ifBlank { "Home" },
            url = normalized,
            token = token.value.trim(),
            dashboard = dashboard.value.trim().ifBlank { "light-phone" },
        )
    }
}

/** Add or edit a Home Assistant server. Returns true via goBack when saved. */
class SetupScreen(
    sealedActivity: SealedLightActivity,
    private val existing: ServerConfig? = null,
) : LightScreen<Boolean, SetupViewModel>(sealedActivity) {

    override val viewModelClass: Class<SetupViewModel>
        get() = SetupViewModel::class.java

    override fun createViewModel() = SetupViewModel(existing)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val name by viewModel.name.collectAsState()
        val url by viewModel.url.collectAsState()
        val token by viewModel.token.collectAsState()
        val dashboard by viewModel.dashboard.collectAsState()
        val status by viewModel.status.collectAsState()
        val saving by viewModel.saving.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack(false) },
                    ),
                    center = LightTopBarCenter.Text(if (existing == null) "Add server" else "Edit server"),
                    rightButton = LightBarButton.Text(
                        text = if (saving) "…" else "Save",
                        onClick = { if (!saving) save() },
                    ),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                    LightTextField(
                        label = "Name:",
                        value = name,
                        placeholder = "Home",
                        onClick = { edit("Name", name) { viewModel.name.value = it } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LightTextField(
                        label = "URL:",
                        value = url,
                        placeholder = "http://homeassistant.local:8123",
                        onClick = { edit("URL", url) { viewModel.url.value = it } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LightTextField(
                        label = "Token:",
                        value = if (token.isBlank()) "" else "••••" + token.takeLast(4),
                        placeholder = "Scan or enter token",
                        onClick = { edit("Token", token) { viewModel.token.value = it } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LightTextField(
                        label = "Dashboard:",
                        value = dashboard,
                        placeholder = "light-phone",
                        onClick = { edit("Dashboard", dashboard) { viewModel.dashboard.value = it } },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    LightText(
                        text = "Scan QR code",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable {
                                navigateTo(
                                    screenFactory = { QrScanScreen(it, "Scan token or server QR") },
                                    resultCallback = { scanned ->
                                        scanned?.let(viewModel::applyScan)
                                    },
                                )
                            }
                            .padding(vertical = 16.dp),
                    )

                    status?.let {
                        LightText(
                            text = it,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }

    private fun edit(title: String, current: String, onResult: (String) -> Unit) {
        navigateTo(
            screenFactory = { TextEditScreen(it, title, current) },
            resultCallback = { value -> value?.let(onResult) },
        )
    }

    private fun save() {
        val config = viewModel.buildConfig() ?: return
        viewModel.saving.value = true
        viewModel.status.value = "Connecting…"
        viewModel.viewModelScope.launch {
            val client = HaClient(config)
            val result = client.validate()
            client.close()
            result
                .onSuccess {
                    ServerStore(lightContext.dataStore).upsert(config)
                    viewModel.status.value = null
                    goBack(true)
                }
                .onFailure {
                    viewModel.saving.value = false
                    viewModel.status.value = "Could not connect: ${it.message}"
                }
        }
    }
}
