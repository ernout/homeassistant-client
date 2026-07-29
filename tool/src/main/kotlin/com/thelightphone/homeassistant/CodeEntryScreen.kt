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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.thelightphone.sdk.ui.lightClickable

/**
 * A keypad for alarm and lock codes. The SDK's text editor is a full QWERTY,
 * which is the wrong shape for a PIN — here the digits are large targets and
 * the code stays masked.
 */
class CodeEntryScreen(
    sealedActivity: SealedLightActivity,
    private val title: String = "Code",
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var code by remember { mutableStateOf("") }

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
                        text = "OK",
                        onClick = { if (code.isNotEmpty()) goBack(code) },
                    ),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = if (code.isEmpty()) "—" else "•".repeat(code.length),
                        variant = LightTextVariant.Heading,
                    )
                }

                listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("⌫", "0", "OK"),
                ).forEach { keys ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        keys.forEach { key ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .lightClickable {
                                        when (key) {
                                            "⌫" -> code = code.dropLast(1)
                                            "OK" -> if (code.isNotEmpty()) goBack(code)
                                            else -> if (code.length < MAX_LENGTH) code += key
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                LightText(
                                    text = key,
                                    variant = if (key.length > 1) {
                                        LightTextVariant.Copy
                                    } else {
                                        LightTextVariant.Heading
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_LENGTH = 12
    }
}
