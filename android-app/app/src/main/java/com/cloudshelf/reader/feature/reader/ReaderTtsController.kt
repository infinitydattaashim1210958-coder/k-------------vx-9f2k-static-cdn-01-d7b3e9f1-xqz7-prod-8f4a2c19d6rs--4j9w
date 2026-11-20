package com.cloudshelf.reader.feature.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class TtsUiState(
    val isReady: Boolean = false,
    val isSpeaking: Boolean = false,
    val speechRate: Float = 1.0f
)

/**
 * Lifecycle-safe Android Text-To-Speech controller for accessible document reading (P6).
 */
class ReaderTtsController(context: Context) : TextToSpeech.OnInitListener {
    private val _state = MutableStateFlow(TtsUiState())
    val state: StateFlow<TtsUiState> = _state.asStateFlow()

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _state.value = _state.value.copy(isSpeaking = true)
                }

                override fun onDone(utteranceId: String?) {
                    _state.value = _state.value.copy(isSpeaking = false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _state.value = _state.value.copy(isSpeaking = false)
                }
            })
            _state.value = _state.value.copy(isReady = true)
        }
    }

    fun speak(text: String) {
        if (!_state.value.isReady || text.isBlank()) return
        tts?.setSpeechRate(_state.value.speechRate)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cloudshelf_page_tts")
    }

    fun stop() {
        tts?.stop()
        _state.value = _state.value.copy(isSpeaking = false)
    }

    fun setRate(rate: Float) {
        val clamped = rate.coerceIn(0.6f, 2.0f)
        tts?.setSpeechRate(clamped)
        _state.value = _state.value.copy(speechRate = clamped)
    }

    fun release() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }
}
