package com.haitham.voiceassistant.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "assistant_settings")

data class AssistantSettings(
    val wakePhrase: String = DEFAULT_WAKE_PHRASE,
    /** 0 = low (fewer false wakes), 1 = medium, 2 = high (wakes more easily). */
    val sensitivity: Int = 1,
    val autoStartOnBoot: Boolean = false,
    val voiceFeedback: Boolean = true,
    val keepCpuAwake: Boolean = true,
    /** Run the free (open-vocabulary) recogniser in parallel with the grammar one. */
    val dualRecognition: Boolean = true,
    /** "bundled" or "custom" (a model imported by the user). */
    val modelId: String = MODEL_BUNDLED,
    val commandTimeoutSec: Int = 7,
    val lastDialedNumber: String = "",
    val wasRunning: Boolean = false
) {
    companion object {
        const val DEFAULT_WAKE_PHRASE = "يا مساعد"
        const val MODEL_BUNDLED = "bundled"
        const val MODEL_CUSTOM = "custom"
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val wake = stringPreferencesKey("wake_phrase")
        val sensitivity = intPreferencesKey("sensitivity")
        val autoStart = booleanPreferencesKey("auto_start_boot")
        val voiceFeedback = booleanPreferencesKey("voice_feedback")
        val keepCpu = booleanPreferencesKey("keep_cpu_awake")
        val dual = booleanPreferencesKey("dual_recognition")
        val model = stringPreferencesKey("model_id")
        val timeout = intPreferencesKey("command_timeout")
        val lastNumber = stringPreferencesKey("last_dialed")
        val wasRunning = booleanPreferencesKey("was_running")
    }

    val settings: Flow<AssistantSettings> = context.dataStore.data.map { p ->
        AssistantSettings(
            wakePhrase = p[Keys.wake]?.takeIf { it.isNotBlank() } ?: AssistantSettings.DEFAULT_WAKE_PHRASE,
            sensitivity = p[Keys.sensitivity] ?: 1,
            autoStartOnBoot = p[Keys.autoStart] ?: false,
            voiceFeedback = p[Keys.voiceFeedback] ?: true,
            keepCpuAwake = p[Keys.keepCpu] ?: true,
            dualRecognition = p[Keys.dual] ?: true,
            modelId = p[Keys.model] ?: AssistantSettings.MODEL_BUNDLED,
            commandTimeoutSec = p[Keys.timeout] ?: 7,
            lastDialedNumber = p[Keys.lastNumber] ?: "",
            wasRunning = p[Keys.wasRunning] ?: false
        )
    }

    suspend fun current(): AssistantSettings = settings.first()

    suspend fun setWakePhrase(v: String) = context.dataStore.edit { it[Keys.wake] = v.trim() }
    suspend fun setSensitivity(v: Int) = context.dataStore.edit { it[Keys.sensitivity] = v.coerceIn(0, 2) }
    suspend fun setAutoStart(v: Boolean) = context.dataStore.edit { it[Keys.autoStart] = v }
    suspend fun setVoiceFeedback(v: Boolean) = context.dataStore.edit { it[Keys.voiceFeedback] = v }
    suspend fun setKeepCpuAwake(v: Boolean) = context.dataStore.edit { it[Keys.keepCpu] = v }
    suspend fun setDualRecognition(v: Boolean) = context.dataStore.edit { it[Keys.dual] = v }
    suspend fun setModel(v: String) = context.dataStore.edit { it[Keys.model] = v }
    suspend fun setCommandTimeout(v: Int) = context.dataStore.edit { it[Keys.timeout] = v.coerceIn(4, 15) }
    suspend fun setLastDialed(v: String) = context.dataStore.edit { it[Keys.lastNumber] = v }
    suspend fun setWasRunning(v: Boolean) = context.dataStore.edit { it[Keys.wasRunning] = v }
}
