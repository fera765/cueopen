package dev.cueopen.app.device

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Resposta direta pelas notificações (a API oficial do Android usada pelo botão "Responder" do WhatsApp, Telegram,
 * Instagram, Messenger etc.): o texto vai pelo RemoteInput da própria notificação, sem abrir o app nem ler a tela.
 * É a forma mais rápida e barata em tokens de ler e responder mensagens.
 */
object NotificationReply {
    private data class Target(val app: String, val title: String, val intent: PendingIntent, val inputs: Array<RemoteInput>, val at: Long)

    private val targets = java.util.concurrent.ConcurrentHashMap<String, Target>()
    private val keyToId = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sent = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    private var counter = 0
    const val MAX_PER_HOUR = 30
    private const val TTL_MS = 6 * 3_600_000L

    /** Ação de resposta da notificação: a que aceita texto livre por RemoteInput. */
    fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    /** Registra a notificação e devolve o id curto estável dela (a mesma conversa mantém o id). */
    @Synchronized
    fun register(key: String, app: String, title: String, action: Notification.Action?): String {
        val id = keyToId.getOrPut(key) { "n" + (++counter) }
        if (action != null) targets[id] = Target(app, title, action.actionIntent, action.remoteInputs, System.currentTimeMillis())
        val cutoff = System.currentTimeMillis() - TTL_MS
        targets.entries.removeIf { it.value.at < cutoff }
        if (keyToId.size > 400) keyToId.entries.filter { it.value !in targets }.take(200).forEach { keyToId.remove(it.key) }
        return id
    }

    fun forget(key: String) { keyToId[key]?.let { targets.remove(it) } }

    /** Destino (app · conversa) de uma resposta, para o card de confirmação. */
    fun describe(id: String): String? = targets[id]?.let { "${it.app} · ${it.title}" }

    /** Envia [text] como resposta à notificação [id]. Devolve a mensagem para o agente. */
    fun send(context: Context, id: String, text: String): String {
        val body = text.trim()
        if (body.isEmpty()) return "ERRO: escreva o texto da resposta."
        if (body.length > 2_000) return "ERRO: a resposta passa de 2.000 caracteres; encurte."
        val t = targets[id.trim()] ?: return "ERRO: a notificação $id não está mais disponível para resposta (o app pode tê-la removido ou ela não tem o botão Responder). " +
            "Leia de novo com device_notifications; se a conversa não aparecer como [pode responder], abra o chat pelo app."
        val now = System.currentTimeMillis()
        while (sent.peekFirst()?.let { it < now - 3_600_000L } == true) sent.pollFirst()
        if (sent.size >= MAX_PER_HOUR) return "RECUSADO: limite de $MAX_PER_HOUR respostas por hora atingido. Isso protege a conta do usuário contra bloqueio por envio em massa."
        val results = Bundle().apply { t.inputs.forEach { putCharSequence(it.resultKey, body) } }
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(t.inputs, fill, results)
        return runCatching {
            t.intent.send(context, 0, fill)
            sent.addLast(now)
            NotifStore.addOutgoing(id, body)
            "Resposta enviada para ${t.app} · ${t.title}."
        }.getOrElse { "ERRO: o app recusou a resposta pela notificação (${it.javaClass.simpleName}). Abra a conversa no app e envie por lá." }
    }

    /** Linhas da conversa a partir dos extras da notificação (MessagingStyle e InboxStyle). */
    fun conversation(extras: Bundle): List<String> {
        val out = mutableListOf<String>()
        @Suppress("DEPRECATION")
        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        msgs?.forEach { p ->
            val b = p as? Bundle ?: return@forEach
            val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@forEach
            val sender = b.getCharSequence("sender")?.toString()
                ?: (if (android.os.Build.VERSION.SDK_INT >= 28) b.getParcelable<android.app.Person>("sender_person")?.name?.toString() else null)
            out += (sender?.let { "$it: " } ?: "") + text
        }
        if (out.isEmpty()) extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { l -> l?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it } }
        return out.takeLast(12).map { it.take(400) }
    }
}
