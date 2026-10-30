package dev.cueopen.app.assistant

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Adaptador para o reconhecedor de voz que a pessoa já escolheu no Android.
 * Não seleciona nem inicia um provedor de terceiros por conta própria.
 */
class FluwyRecognitionService : RecognitionService() {
    private val activeRecognizers = mutableMapOf<Callback, SpeechRecognizer>()

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val component = selectedSystemRecognizer()
        if (component == null) {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(this, component) }
            .getOrElse {
                listener.error(SpeechRecognizer.ERROR_CLIENT)
                return
            }
        activeRecognizers[listener] = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runCatching { listener.readyForSpeech(params ?: Bundle()) }
            }

            override fun onBeginningOfSpeech() {
                runCatching { listener.beginningOfSpeech() }
            }

            override fun onRmsChanged(rmsdB: Float) {
                runCatching { listener.rmsChanged(rmsdB) }
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                if (buffer != null) runCatching { listener.bufferReceived(buffer) }
            }

            override fun onEndOfSpeech() {
                runCatching { listener.endOfSpeech() }
            }

            override fun onError(error: Int) {
                release(listener)
                runCatching { listener.error(error) }
            }

            override fun onResults(results: Bundle?) {
                release(listener)
                runCatching { listener.results(results ?: Bundle()) }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                runCatching { listener.partialResults(partialResults ?: Bundle()) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        runCatching { recognizer.startListening(Intent(recognizerIntent)) }
            .onFailure {
                release(listener, cancel = true)
                runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            }
    }

    override fun onStopListening(listener: Callback) {
        val recognizer = activeRecognizers[listener]
        if (recognizer == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        runCatching { recognizer.stopListening() }
            .onFailure {
                release(listener, cancel = true)
                runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            }
    }

    override fun onCancel(listener: Callback) {
        release(listener, cancel = true)
    }

    override fun onDestroy() {
        activeRecognizers.keys.toList().forEach { release(it, cancel = true) }
        super.onDestroy()
    }

    private fun selectedSystemRecognizer(): ComponentName? {
        val selected = runCatching {
            Settings.Secure.getString(contentResolver, VOICE_RECOGNITION_SERVICE_SETTING)
                ?.let(ComponentName::unflattenFromString)
        }.getOrNull() ?: return null

        // Evita uma chamada recursiva caso Fluwy esteja selecionado como seu próprio reconhecedor.
        if (selected.packageName == packageName) return null

        val query = Intent(RecognitionService.SERVICE_INTERFACE)
            .addCategory(Intent.CATEGORY_DEFAULT)
        val installed = runCatching {
            packageManager.queryIntentServices(query, PackageManager.MATCH_DEFAULT_ONLY)
                .any { resolved ->
                    resolved.serviceInfo?.let { service ->
                        ComponentName(service.packageName, service.name) == selected
                    } == true
                }
        }.getOrDefault(false)
        return selected.takeIf { installed }
    }

    private fun release(listener: Callback, cancel: Boolean = false) {
        val recognizer = activeRecognizers.remove(listener) ?: return
        if (cancel) runCatching { recognizer.cancel() }
        runCatching { recognizer.destroy() }
    }

    private companion object {
        // Settings.Secure key used by Android to store the user's speech-recognition provider.
        const val VOICE_RECOGNITION_SERVICE_SETTING = "voice_recognition_service"
    }
}
