package dev.cueopen.app.browser

import android.content.Context
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pedidos de recursos do aparelho feitos por sites no browser (câmera, microfone, localização, conteúdo protegido,
 * envio de arquivos). O app mostra "o site X quer usar Y" e, se o usuário permitir, pede a permissão do Android
 * quando ainda não foi dada. A escolha pode ficar salva por site (Browser › Permissões dos sites).
 */
object BrowserPermissions {
    enum class Kind(val label: String, val androidPermission: String?) {
        CAMERA("câmera", android.Manifest.permission.CAMERA),
        MIC("microfone", android.Manifest.permission.RECORD_AUDIO),
        LOCATION("localização", android.Manifest.permission.ACCESS_FINE_LOCATION),
        PROTECTED_MEDIA("conteúdo protegido (DRM)", null),
    }

    class Pending(val id: Long, val origin: String, val kinds: List<Kind>, val decide: (allow: Boolean, remember: Boolean) -> Unit)
    class FilePick(val id: Long, val mimeTypes: List<String>, val multiple: Boolean, val callback: ValueCallback<Array<Uri>>)

    val pending = MutableStateFlow<List<Pending>>(emptyList())
    val filePick = MutableStateFlow<FilePick?>(null)
    private var seq = 0L
    private const val PREFS = "browser_site_permissions"

    fun originOf(url: String?): String = runCatching { Uri.parse(url).let { "${it.scheme}://${it.host}" } }.getOrDefault(url.orEmpty())

    /** Decisão salva para o site (null = perguntar). */
    fun saved(ctx: Context, origin: String, kind: Kind): Boolean? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = "$origin|${kind.name}"
        return if (p.contains(k)) p.getBoolean(k, false) else null
    }

    fun save(ctx: Context, origin: String, kinds: List<Kind>, allow: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { kinds.forEach { putBoolean("$origin|${it.name}", allow) } }.apply()

    fun savedAll(ctx: Context): Map<String, Boolean> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? Boolean)?.let { k to it } }.toMap()

    fun forget(ctx: Context, key: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply()

    /** Recursos do WebView → tipos do app. MIDI SysEx e desconhecidos ficam de fora (negados). */
    fun kindsOf(resources: Array<String>): List<Kind> = resources.mapNotNull {
        when (it) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Kind.CAMERA
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Kind.MIC
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> Kind.PROTECTED_MEDIA
            else -> null
        }
    }.distinct()

    private fun enqueue(ctx: Context, origin: String, kinds: List<Kind>, done: (Boolean) -> Unit) {
        val savedAll = kinds.map { saved(ctx, origin, it) }
        if (savedAll.all { it == true }) return done(true)
        if (savedAll.any { it == false }) return done(false)
        val id = ++seq
        val item = Pending(id, origin, kinds) { allow, remember ->
            if (remember) save(ctx, origin, kinds, allow)
            pending.value = pending.value.filterNot { it.id == id }
            done(allow)
        }
        pending.value = pending.value + item
        // App fechado (browser do agente em segundo plano): avisa por notificação para o usuário decidir.
        if (!OffscreenStage.foreground) notifyBackground(ctx, origin, kinds)
    }

    fun onWebPermission(ctx: Context, request: PermissionRequest) {
        val kinds = kindsOf(request.resources)
        if (kinds.isEmpty()) { request.deny(); return }
        val origin = originOf(request.origin?.toString())
        enqueue(ctx, origin, kinds) { allow ->
            val res = request.resources.filter { r -> kindsOf(arrayOf(r)).isNotEmpty() }.toTypedArray()
            runCatching { if (allow && res.isNotEmpty()) request.grant(res) else request.deny() }
        }
    }

    fun onGeolocation(ctx: Context, origin: String, callback: GeolocationPermissions.Callback) {
        enqueue(ctx, originOf(origin), listOf(Kind.LOCATION)) { allow -> runCatching { callback.invoke(origin, allow, false) } }
    }

    fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        filePick.value?.let { runCatching { it.callback.onReceiveValue(null) } }
        val types = params.acceptTypes.orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotBlank() }
        filePick.value = FilePick(++seq, types, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE, callback)
        return true
    }

    private fun notifyBackground(ctx: Context, origin: String, kinds: List<Kind>) = runCatching {
        val intent = android.content.Intent(ctx, dev.cueopen.app.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = android.app.PendingIntent.getActivity(ctx, 7301, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, dev.cueopen.app.CueApp.CH_TASKS)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Site pede ${kinds.joinToString { it.label }}")
            .setContentText("${origin.removePrefix("https://")} — abra o Fluwy para permitir ou negar").setContentIntent(pi).setAutoCancel(true).build()
        androidx.core.app.NotificationManagerCompat.from(ctx).notify(7301, n)
    }
}
