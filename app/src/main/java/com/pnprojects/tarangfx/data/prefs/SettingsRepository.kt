package com.pnprojects.tarangfx.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pnprojects.tarangfx.domain.model.Codec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val defaultCodec: Codec = Codec.MP3,
    val defaultBitrate: Int = 192,
    val recordFormat: Codec = Codec.WAV,
    val splitCoachSeen: Boolean = false,
    val keepScreenOn: Boolean = true,
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val codec = stringPreferencesKey("default_codec")
        val bitrate = intPreferencesKey("default_bitrate")
        val recordFormat = stringPreferencesKey("record_format")
        val splitCoach = booleanPreferencesKey("split_coach_seen")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            defaultCodec = p[K.codec]?.let { runCatching { Codec.valueOf(it) }.getOrNull() } ?: Codec.MP3,
            defaultBitrate = p[K.bitrate] ?: 192,
            recordFormat = p[K.recordFormat]?.let { runCatching { Codec.valueOf(it) }.getOrNull() } ?: Codec.WAV,
            splitCoachSeen = p[K.splitCoach] ?: false,
            keepScreenOn = p[K.keepScreenOn] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDefaultCodec(c: Codec) = context.dataStore.edit { it[K.codec] = c.name }
    suspend fun setDefaultBitrate(b: Int) = context.dataStore.edit { it[K.bitrate] = b }
    suspend fun setRecordFormat(c: Codec) = context.dataStore.edit { it[K.recordFormat] = c.name }
    suspend fun setSplitCoachSeen() = context.dataStore.edit { it[K.splitCoach] = true }
    suspend fun setKeepScreenOn(v: Boolean) = context.dataStore.edit { it[K.keepScreenOn] = v }
}
