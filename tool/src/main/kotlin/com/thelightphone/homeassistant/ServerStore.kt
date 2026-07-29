package com.thelightphone.homeassistant

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Persists the configured Home Assistant servers in the tool's preferences
 * DataStore, sealed with a Keystore-backed key (see [SecretVault]) because the
 * tokens they hold can unlock doors.
 */
class ServerStore(
    private val dataStore: DataStore<Preferences>,
    private val vault: SecretVault = SecretVault(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun servers(): List<ServerConfig> {
        val stored = dataStore.data.first()[SERVERS] ?: return emptyList()
        val decrypted = vault.decrypt(stored)
        val list = runCatching {
            json.decodeFromString<List<ServerConfig>>(decrypted ?: stored)
        }.getOrDefault(emptyList())

        // Anything written before encryption existed is still plain JSON on
        // disk: seal it now rather than waiting for the next edit.
        if (decrypted == null && list.isNotEmpty()) writeServers(list)
        return list
    }

    suspend fun selected(): ServerConfig? {
        val all = servers()
        if (all.isEmpty()) return null
        val id = dataStore.data.first()[SELECTED]
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    suspend fun upsert(server: ServerConfig) {
        val all = servers().filter { it.id != server.id } + server
        val id = server.id
        writeServers(all)
        dataStore.edit { it[SELECTED] = id }
    }

    suspend fun remove(serverId: String) {
        val all = servers().filter { it.id != serverId }
        writeServers(all)
        dataStore.edit {
            if (it[SELECTED] == serverId) it.remove(SELECTED)
        }
        // Nothing left worth protecting; drop the caches with it.
        if (all.isEmpty()) clearCaches()
    }

    private suspend fun writeServers(all: List<ServerConfig>) {
        val encoded = json.encodeToString(all)
        val sealed = vault.encrypt(encoded)
        if (sealed == null) {
            android.util.Log.e("HomeTool", "could not encrypt credentials; refusing to store")
            return
        }
        dataStore.edit { it[SERVERS] = sealed }
    }

    private suspend fun clearCaches() {
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith("dashboard_") || it.name.startsWith("states_") }
                .forEach { prefs.remove(stringPreferencesKey(it.name)) }
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

    /**
     * Caches the raw Lovelace config per server so the dashboard renders
     * instantly on open; the network fetch then refreshes it in the background.
     */
    suspend fun cachedDashboard(serverId: String): String? =
        dataStore.data.first()[dashboardKey(serverId)]

    suspend fun cacheDashboard(serverId: String, rawConfig: String) {
        dataStore.edit { it[dashboardKey(serverId)] = rawConfig }
    }

    /** Caches last known entity states, same idea as the dashboard cache. */
    suspend fun cachedStates(serverId: String): String? =
        dataStore.data.first()[statesKey(serverId)]

    suspend fun cacheStates(serverId: String, rawStates: String) {
        dataStore.edit { it[statesKey(serverId)] = rawStates }
    }

    private fun dashboardKey(serverId: String) = stringPreferencesKey("dashboard_$serverId")

    private fun statesKey(serverId: String) = stringPreferencesKey("states_$serverId")

    /** Scratch state for the reporting job: last sent fix, current cadence. */
    suspend fun reportingState(): Map<String, String> {
        val prefs = dataStore.data.first()
        return listOf("lat", "lon", "interval", "stillReported", "lastSent")
            .mapNotNull { key -> prefs[reportingKey(key)]?.let { key to it } }
            .toMap()
    }

    suspend fun saveReportingState(
        latitude: Double?,
        longitude: Double?,
        intervalMinutes: Int,
        reportedWhileStill: Boolean,
        lastSentAtMillis: Long,
    ) {
        dataStore.edit { prefs ->
            latitude?.let { prefs[reportingKey("lat")] = it.toString() }
            longitude?.let { prefs[reportingKey("lon")] = it.toString() }
            prefs[reportingKey("interval")] = intervalMinutes.toString()
            prefs[reportingKey("stillReported")] = reportedWhileStill.toString()
            prefs[reportingKey("lastSent")] = lastSentAtMillis.toString()
        }
    }

    private fun reportingKey(name: String) = stringPreferencesKey("reporting_$name")

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
