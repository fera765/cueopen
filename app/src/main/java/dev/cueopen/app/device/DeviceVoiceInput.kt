package dev.cueopen.app.device

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Faz a ponte entre a ferramenta suspensa do agente e a Activity visível do reconhecedor do Android. */
object DeviceVoiceInput {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    suspend fun recognize(context: Context): String {
        val requestId = UUID.randomUUID().toString()
        val result = CompletableDeferred<String>()
        pending[requestId] = result
        try {
            context.startActivity(Intent(context, DeviceVoiceInputActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return withTimeoutOrNull(120_000) { result.await() }
                ?: "A entrada de voz expirou sem resultado. Nenhum áudio foi armazenado pelo app."
        } catch (t: Throwable) {
            return "Não foi possível abrir o reconhecimento de voz do Android: ${t.message ?: "serviço indisponível"}."
        } finally {
            pending.remove(requestId)
        }
    }

    internal fun complete(requestId: String?, text: String) {
        if (requestId != null) pending.remove(requestId)?.complete(text)
    }

    internal const val EXTRA_REQUEST_ID = "device_voice_request_id"
    internal const val REQUEST_CODE = 6304
}

/** Activity transparente para que o reconhecedor do sistema seja a única interface de gravação. */
class DeviceVoiceInputActivity : Activity() {
    private var requestId: String? = null
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(DeviceVoiceInput.EXTRA_REQUEST_ID)
        val prompt = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale para o agente. Revise o texto antes de confirmar.")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { startActivityForResult(prompt, DeviceVoiceInput.REQUEST_CODE) }
            .onFailure { finishWith("O Android não encontrou um reconhecedor de voz compatível.") }
    }

    @Deprecated("The system speech recognizer returns through the legacy activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DeviceVoiceInput.REQUEST_CODE) return
        val text = if (resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.take(4000)
                ?.takeIf { it.isNotBlank() } ?: "Nenhuma transcrição retornada pelo reconhecedor."
        } else "Entrada de voz cancelada pelo usuário."
        finishWith(text)
    }

    private fun finishWith(text: String) {
        if (!completed) {
            completed = true
            DeviceVoiceInput.complete(requestId, text)
        }
        finish()
    }

    override fun onDestroy() {
        if (!completed) DeviceVoiceInput.complete(requestId, "Entrada de voz cancelada ou interrompida.")
        super.onDestroy()
    }
}
