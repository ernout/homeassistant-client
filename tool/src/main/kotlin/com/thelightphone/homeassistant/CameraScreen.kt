package com.thelightphone.homeassistant

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shows a camera entity as a still image refreshed every couple of seconds —
 * HA has no video stream we could decode here, and on a monochrome screen a
 * slow-refreshing snapshot answers "who is at the door" well enough.
 */
class CameraViewModel(
    private val server: ServerConfig,
    private val entityId: String,
) : LightViewModel<Unit>() {

    val frame = MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val error = MutableStateFlow<String?>(null)

    private var client: HaClient? = null
    private var pollJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        client = HaClient(server)
        startPolling()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        stop()
        super.onScreenHide(screen)
    }

    override fun onAppPause() {
        stop()
        super.onAppPause()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val active = client ?: return@launch
                active.cameraSnapshot(entityId)
                    .onSuccess { bytes ->
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap == null) {
                            error.value = "Could not decode image."
                        } else {
                            frame.value = bitmap.asImageBitmap()
                            error.value = null
                        }
                    }
                    .onFailure { error.value = it.message }
                delay(REFRESH_MILLIS)
            }
        }
    }

    private fun stop() {
        pollJob?.cancel()
        pollJob = null
        client?.close()
        client = null
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private companion object {
        const val REFRESH_MILLIS = 2_000L
    }
}

class CameraScreen(
    sealedActivity: SealedLightActivity,
    private val server: ServerConfig,
    private val entityId: String,
    private val title: String,
) : LightScreen<Unit, CameraViewModel>(sealedActivity) {

    override val viewModelClass: Class<CameraViewModel>
        get() = CameraViewModel::class.java

    override fun createViewModel() = CameraViewModel(server, entityId)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val frame by viewModel.frame.collectAsState()
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
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    frame?.let { image ->
                        Image(
                            bitmap = image,
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } ?: LightText(
                        text = error ?: "Loading…",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )
                }
            }
        }
    }
}
