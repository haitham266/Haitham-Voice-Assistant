package com.haitham.voiceassistant.assistant

import com.haitham.voiceassistant.model.AssistantStatus
import com.haitham.voiceassistant.model.AssistantUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-wide state shared between the foreground service and the UI. */
object AssistantRepository {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    fun status(s: AssistantStatus) = _state.update { it.copy(status = s, error = if (s == AssistantStatus.ERROR) it.error else null) }
    fun running(r: Boolean) = _state.update { it.copy(serviceRunning = r, status = if (r) it.status else AssistantStatus.STOPPED, level = 0f) }
    fun heard(t: String) = _state.update { it.copy(lastHeard = t) }
    fun result(t: String) = _state.update { it.copy(lastResult = t) }
    fun level(l: Float) {
        val cur = _state.value.level
        if (kotlin.math.abs(cur - l) > 0.02f) _state.update { it.copy(level = l) }
    }
    fun modelInfo(t: String) = _state.update { it.copy(modelInfo = t) }
    fun aiInfo(t: String) = _state.update { it.copy(aiEngineInfo = t) }
    fun test(t: String) = _state.update { it.copy(testTranscript = t) }
    fun error(t: String) = _state.update { it.copy(error = t, status = AssistantStatus.ERROR) }
}
