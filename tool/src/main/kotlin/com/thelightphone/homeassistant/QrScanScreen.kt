package com.thelightphone.homeassistant

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.ui.LightQrCodeScanner
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Scans one QR code and returns its raw string via goBack.
 *
 * Uses the UI-layer scanner with our own permission callbacks instead of the
 * client-SDK wrapper: on current LightOS builds the permission RPC can fail
 * (version mismatch), which would block scanning even when the CAMERA
 * permission is actually granted. If the RPC fails we optimistically proceed —
 * worst case the camera preview stays black instead of a dead end.
 */
class QrScanScreen(
    sealedActivity: SealedLightActivity,
    private val title: String = "Scan QR Code",
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var pendingScan by remember { mutableStateOf<String?>(null) }
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.CAMERA)
        LightTheme(colors = themeColors) {
            LightQrCodeScanner(
                title = title,
                onScanned = { pendingScan = it },
                onBack = { goBack(null) },
                modifier = Modifier.background(LightThemeTokens.colors.background),
                checkCameraPermission = {
                    checkPermission(Manifest.permission.CAMERA).asKotlinResult
                        .map {
                            it.permissionResult == LightServiceMethod.GetPermission.Result.Granted
                        }
                        .recover { true }
                },
                launchCameraPermissionRequest = {
                    runCatching { permissionLauncher?.launch() }
                },
            )
        }
        LaunchedEffect(pendingScan) {
            pendingScan?.let { goBack(it) }
        }
    }
}
