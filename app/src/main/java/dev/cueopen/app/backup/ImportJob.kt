package dev.cueopen.app.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.cueopen.app.CueApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Importação em andamento: roda no escopo do app (girar a tela ou sair dela não interrompe) e expõe o
 * progresso para a tela escura de "Importando dados".
 */
object ImportJob {
    data class Status(val fraction: Float = 0f, val label: String = "", val done: Boolean = false, val error: String? = null, val report: List<String> = emptyList())

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    @Volatile private var job: Job? = null

    fun running(): Boolean = job?.isActive == true

    fun start(uri: Uri, password: CharArray) {
        if (running()) return
        _status.value = Status(0f, "Preparando")
        job = CueApp.instance.applicationScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching {
                DataTransfer.import(uri, password) { f, l -> _status.value = _status.value.copy(fraction = f.coerceIn(0f, 1f), label = l) }
            }.onSuccess { r -> _status.value = Status(1f, "Concluído", done = true, report = r.lines) }
                .onFailure { t ->
                    val msg = if (t is SecureBox.WrongPassword) t.message!! else (t.message ?: t.javaClass.simpleName)
                    _status.value = _status.value.copy(error = msg)
                }
        }
    }

    fun reset() { if (!running()) _status.value = Status() }

    /**
     * Reabre o app do zero na tela inicial: estado, agenda, agentes autônomos e perfil do browser importados
     * só valem a partir de um processo novo.
     */
    fun restartApp(ctx: Context) {
        CueApp.instance.store.flush()
        ctx.startActivity(Intent(ctx, RestartActivity::class.java)
            .putExtra(RestartActivity.EXTRA_PID, android.os.Process.myPid())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
