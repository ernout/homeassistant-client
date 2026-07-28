package com.thelightphone.homeassistant

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Persists the configured Home Assistant servers as JSON in the tool's
 * preferences DataStore. Token encryption via Keystore is a later phase.
 */
class ServerStore(private val dataStore: DataStore<Preferences>) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun servers(): List<ServerConfig> {
        val raw = dataStore.data.first()[SERVERS] ?: return emptyList()
        return runCatching { json.decodeFromString<List<ServerConfig>>(raw) }
            .getOrDefault(emptyList())
    }

    suspend fun selected(): ServerConfig? {
        val all = servers()
        if (all.isEmpty()) return null
        val id = dataStore.data.first()[SELECTED]
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    suspend fun upsert(server: ServerConfig) {
        val all = servers().filter { it.id != server.id } + server
        dataStore.edit {
            it[SERVERS] = json.encodeToString(all)
            it[SELECTED] = server.id
        }
    }

    suspend fun remove(serverId: String) {
        val all = servers().filter { it.id != serverId }
        dataStore.edit {
            it[SERVERS] = json.encodeToString(all)
            if (it[SELECTED] == serverId) it.remove(SELECTED)
        }
    }

    suspend fun select(serverId: String) {
        dataStore.edit { it[SELECTED] = serverId }
    }

    /** Selects the next server in the list; returns it. */
    suspend fun selectNext(): ServerConfig? {
        val all = servers()
        if (all.size < 2) return all.firstOrNull()
        val current = selected()
        val next = all[(all.indexOfFirst { it.id == current?.id } + 1).mod(all.size)]
        select(next.id)
        return next
    }

    /** Stable device id for mobile_app registrations, generated once. */
    suspend fun deviceId(): String {
        dataStore.data.first()[DEVICE_ID]?.let { return it }
        val generated = java.util.UUID.randomUUID().toString()
        dataStore.edit { it[DEVICE_ID] = generated }
        return generated
    }

    private companion object {
        val SERVERS = stringPreferencesKey("servers_json")
        val SELECTED = stringPreferencesKey("selected_server_id")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
