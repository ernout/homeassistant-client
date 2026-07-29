package com.thelightphone.homeassistant

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Talking to Home Assistant's conversation agent, by typing or by voice. */
class AssistViewModel(
    private val server: ServerConfig,
    private val audio: LightAudio,
    private val cacheDir: File,
) : LightViewModel<Unit>() {

    data class Turn(val text: String, val fromUser: Boolean)

    enum class Voice { IDLE, CONNECTING, LISTENING, THINKING }

    val turns = MutableStateFlow<List<Turn>>(emptyList())
    val busy = MutableStateFlow(false)
    val voice = MutableStateFlow(Voice.IDLE)
    val error = MutableStateFlow<String?>(null)

    private var client: HaClient? = null
    private var pipeline: HaAssistPipeline? = null
    private var conversationId: String? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        client = client ?: HaClient(server)
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        pipeline?.requestStop()
        client?.close()
        client = null
        super.onScreenHide(screen)
    }

    fun ask(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || busy.value) return
        val active = client ?: return
        turns.value = turns.value + Turn(trimmed, fromUser = true)
        busy.value = true
        error.value = null
        viewModelScope.launch {
            active.converse(trimmed, conversationId)
                .onSuccess { reply ->
                    conversationId = reply.conversationId
                    turns.value = turns.value + Turn(reply.speech, fromUser = false)
                }
                .onFailure { error.value = it.message }
            busy.value = false
        }
    }

    fun startListening() {
        if (voice.value != Voice.IDLE) return
        voice.value = Voice.CONNECTING
        error.value = null
        val runner = HaAssistPipeline(server, audio, viewModelScope)
        pipeline = runner
        viewModelScope.launch {
            runner.run(
                conversationId = conversationId,
                onListening = { voice.value = Voice.LISTENING },
                onTranscript = { heard ->
                    voice.value = Voice.THINKING
                    turns.value = turns.value + Turn(heard, fromUser = true)
                },
                onReply = { reply ->
                    conversationId = reply.conversationId ?: conversationId
                    turns.value = turns.value + Turn(reply.speech, fromUser = false)
                },
                onSpeech = { url -> play(url) },
                onError = { error.value = it },
            )
            voice.value = Voice.IDLE
            pipeline = null
        }
    }

    fun stopListening() {
        pipeline?.requestStop()
        voice.value = Voice.THINKING
    }

    /** The player takes a file, so the spoken answer is fetched first. */
    private fun play(url: String) {
        val active = client ?: return
        viewModelScope.launch {
            active.download(url, File(cacheDir, "assist-reply.mp3"))
                .onSuccess { file ->
                    runCatching {
                        audio.newPlayer().apply {
                            setSource(file)
                            play()
                        }
                    }
                }
                .onFailure { android.util.Log.w("HomeTool", "tts: ${it.message}") }
        }
    }
}

class AssistScreen(
    private val sealedActivity: SealedLightActivity,
    private val server: ServerConfig,
) : LightScreen<Unit, AssistViewModel>(sealedActivity) {

    override val viewModelClass: Class<AssistViewModel>
        get() = AssistViewModel::class.java

    override fun createViewModel() = AssistViewModel(
        server = server,
        audio = com.thelightphone.sdk.audio.DefaultLightAudio(sealedActivity),
        cacheDir = lightContext.filesDir,
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val turns by viewModel.turns.collectAsState()
        val busy by viewModel.busy.collectAsState()
        val voice by viewModel.voice.collectAsState()
        val error by viewModel.error.collectAsState()
        val listState = rememberLazyListState()
        val microphone = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)

        LaunchedEffect(turns.size) {
            if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
        }

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
                    center = LightTopBarCenter.Text(server.name),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    items(turns) { turn ->
                        LightText(
                            text = turn.text,
                            // The agent answers in full type; what you said sits
                            // back, the way a transcript reads.
                            variant = if (turn.fromUser) {
                                LightTextVariant.Detail
                            } else {
                                LightTextVariant.Copy
                            },
                            lighten = turn.fromUser,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        )
                    }
                }

                val status = when {
                    error != null -> error
                    busy -> "Thinking…"
                    voice == AssistViewModel.Voice.CONNECTING -> "Connecting…"
                    voice == AssistViewModel.Voice.LISTENING -> "Listening…"
                    voice == AssistViewModel.Voice.THINKING -> "Thinking…"
                    turns.isEmpty() -> "Ask about your home."
                    else -> null
                }
                status?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(
                            horizontal = 1f.gridUnitsAsDp(),
                            vertical = 8.dp,
                        ),
                    )
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "Type",
                            onClick = { promptForText() },
                        ),
                        LightBarButton.Text(
                            text = if (voice == AssistViewModel.Voice.LISTENING) "Stop" else "Speak",
                            onClick = {
                                if (voice == AssistViewModel.Voice.LISTENING) {
                                    viewModel.stopListening()
                                } else {
                                    listenWithPermission(microphone)
                                }
                            },
                        ),
                    ),
                )
            }
        }
    }

    private fun promptForText() {
        navigateTo(
            screenFactory = { TextEditScreen(it, "Ask", "") },
            resultCallback = { text -> text?.let(viewModel::ask) },
        )
    }

    /** LightOS grants the microphone through its own prompt, not ours. */
    private fun listenWithPermission(
        launcher: com.thelightphone.sdk.PermissionRequestLauncher?,
    ) {
        viewModel.viewModelScope.launch {
            val granted = checkPermission(Manifest.permission.RECORD_AUDIO).asKotlinResult
                .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
                .getOrElse { true }
            if (!granted) {
                runCatching { launcher?.launch() }
                return@launch
            }
            viewModel.startListening()
        }
    }
}
