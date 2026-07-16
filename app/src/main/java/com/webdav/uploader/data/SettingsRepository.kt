package com.webdav.uploader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "webdav_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val username = stringPreferencesKey("username")
        val password = stringPreferencesKey("password")
        val connectTimeoutSec = longPreferencesKey("connect_timeout_sec")
        val writeTimeoutSec = longPreferencesKey("write_timeout_sec")
        val readTimeoutSec = longPreferencesKey("read_timeout_sec")
        val callTimeoutSec = longPreferencesKey("call_timeout_sec")
        val remoteDir = stringPreferencesKey("remote_dir")
    }

    val configFlow: Flow<WebDavConfig> = context.dataStore.data.map { prefs ->
        WebDavConfig(
            baseUrl = prefs[Keys.baseUrl] ?: WebDavConfig().baseUrl,
            username = prefs[Keys.username] ?: "",
            password = prefs[Keys.password] ?: "",
            connectTimeoutSec = prefs[Keys.connectTimeoutSec] ?: 60L,
            writeTimeoutSec = prefs[Keys.writeTimeoutSec] ?: 0L,
            readTimeoutSec = prefs[Keys.readTimeoutSec] ?: 1800L,
            callTimeoutSec = prefs[Keys.callTimeoutSec] ?: 0L,
            remoteDir = prefs[Keys.remoteDir] ?: "crypt",
        )
    }

    suspend fun save(config: WebDavConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.baseUrl] = config.baseUrl.trim()
            prefs[Keys.username] = config.username
            prefs[Keys.password] = config.password
            prefs[Keys.connectTimeoutSec] = config.connectTimeoutSec.coerceAtLeast(1)
            prefs[Keys.writeTimeoutSec] = config.writeTimeoutSec.coerceAtLeast(0)
            prefs[Keys.readTimeoutSec] = config.readTimeoutSec.coerceAtLeast(1)
            prefs[Keys.callTimeoutSec] = config.callTimeoutSec.coerceAtLeast(0)
            prefs[Keys.remoteDir] = config.remoteDir.trim().trim('/')
        }
    }
}
