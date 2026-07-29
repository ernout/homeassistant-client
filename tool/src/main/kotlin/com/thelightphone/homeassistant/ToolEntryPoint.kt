package com.thelightphone.homeassistant

import android.util.Log
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {

    override suspend fun onToolCreate(
        serverData: StateFlow<LightServerData?>,
    ) {
        serverData.collect {
            // Phase 4: forward the UnifiedPush endpoint to HA as app_data.push_url
            // via an update_registration webhook call.
            Log.d("HomeTool", "LightOS registration data: $it")
        }
    }

    override suspend fun onPushNotification(
        data: ByteArray,
    ) {
        // Phase 4: HA posts its notification JSON to the push endpoint; decode
        // and display it via LightPushService here. Log the size only —  the
        // payload is the notification's contents.
        Log.d("HomeTool", "Push notification received (${data.size} bytes)")
    }
}
