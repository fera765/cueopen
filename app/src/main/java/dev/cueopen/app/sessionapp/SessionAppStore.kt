package dev.cueopen.app.sessionapp

import java.io.File

/**
 * Session App Runtime — armazenamento. Cada Seção tem UM artefato de aplicação (app.html, arquivo único com
 * HTML+CSS+JS), versões anteriores para recuperação, dados publicados pelos agentes (data/) e o estado
 * persistente do próprio app (state.json). Tudo isolado por seção em [base]/<sectionId>/. Sem Android: testável.
 */
class SessionAppStore(private val base: File) {
    data class Meta(val version: Int, val title: String, val updatedAt: Long, val bytes: Long)

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80).ifBlank { "default" }
    private fun dir(sectionId: String) = File(base, safe(sectionId))
    fun appFile(sectionId: String) = File(dir(sectionId), "app.html")
    private fun versionsDir(sectionId: String) = File(dir(sectionId), "versions")
    private fun metaFile(sectionId: String) = File(dir(sectionId), "meta.txt")
    private fun dataDir(sectionId: String) = File(dir(sectionId), "data")
    private fun stateFile(sectionId: String) = File(dir(sectionId), "state.json")

    fun exists(sectionId: String) = appFile(sectionId).isFile

    fun meta(sectionId: String): Meta? {
        val f = appFile(sectionId)
        if (!f.isFile) return null
        val parts = runCatching { metaFile(sectionId).readText().split('\n') }.getOrDefault(emptyList())
        return Meta(parts.getOrNull(0)?.toIntOrNull() ?: 1, parts.getOrNull(1).orEmpty(), parts.getOrNull(2)?.toLongOrNull() ?: f.lastModified(), f.length())
    }

    fun html(sectionId: String): String? = appFile(sectionId).takeIf { it.isFile }?.readText()

    /** Publica uma nova versão (a anterior fica guardada). Dados e estado do app NÃO são tocados. */
    @Synchronized fun publish(sectionId: String, html: String, title: String, now: Long = System.currentTimeMillis()): Meta {
        val d = dir(sectionId).apply { mkdirs() }
        val current = meta(sectionId)
        val next = (current?.version ?: 0) + 1
        if (current != null) {
            val vd = versionsDir(sectionId).apply { mkdirs() }
            appFile(sectionId).copyTo(File(vd, "v${current.version}.html"), overwrite = true)
            vd.listFiles()?.sortedByDescending { it.name.removePrefix("v").removeSuffix(".html").toIntOrNull() ?: 0 }?.drop(KEEP_VERSIONS)?.forEach { it.delete() }
        }
        val tmp = File(d, "app.html.tmp")
        tmp.writeText(html)
        if (!tmp.renameTo(appFile(sectionId))) { appFile(sectionId).writeText(html); tmp.delete() }
        val t = title.replace('\n', ' ').take(80).ifBlank { current?.title.orEmpty() }
        metaFile(sectionId).writeText("$next\n$t\n$now")
        return Meta(next, t, now, appFile(sectionId).length())
    }

    fun versions(sectionId: String): List<Int> =
        versionsDir(sectionId).listFiles()?.mapNotNull { it.name.removePrefix("v").removeSuffix(".html").toIntOrNull() }?.sortedDescending().orEmpty()

    /** Volta a uma versão anterior (publicando-a como nova versão: o histórico nunca se perde). */
    @Synchronized fun rollback(sectionId: String, version: Int): Meta? {
        val f = File(versionsDir(sectionId), "v$version.html").takeIf { it.isFile } ?: return null
        return publish(sectionId, f.readText(), meta(sectionId)?.title.orEmpty())
    }

    // ── Dados publicados pelos agentes (fonte: backend). Cada chave é um JSON. ──
    fun validKey(key: String) = Regex("^[a-z][a-z0-9_]{0,39}$").matches(key)

    @Synchronized fun setData(sectionId: String, key: String, json: String): String? {
        if (!validKey(key)) return "chave inválida (use minúsculas, números e _; até 40 caracteres)"
        if (json.length > MAX_DATA_VALUE) return "valor grande demais (máx. ${MAX_DATA_VALUE / 1000} mil caracteres por chave; resuma ou divida)"
        val d = dataDir(sectionId).apply { mkdirs() }
        val keys = d.listFiles()?.map { it.name.removeSuffix(".json") }.orEmpty()
        if (key !in keys && keys.size >= MAX_DATA_KEYS) return "muitas chaves (máx. $MAX_DATA_KEYS); reutilize uma existente"
        File(d, "$key.json").writeText(json)
        return null
    }

    fun data(sectionId: String): Map<String, String> =
        dataDir(sectionId).listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.sortedBy { it.name }
            ?.associate { it.name.removeSuffix(".json") to it.readText() }.orEmpty()

    fun dataVersion(sectionId: String): Long = dataDir(sectionId).listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L

    // ── Estado persistente do próprio app (o que o usuário fez na interface: filtros, favoritos…). ──
    fun loadState(sectionId: String): String = stateFile(sectionId).takeIf { it.isFile }?.readText() ?: "null"

    @Synchronized fun saveState(sectionId: String, json: String): Boolean {
        if (json.length > MAX_STATE) return false
        dir(sectionId).mkdirs(); stateFile(sectionId).writeText(json); return true
    }

    /** Apaga tudo da seção (reinício do zero ou seção excluída). */
    fun delete(sectionId: String) { dir(sectionId).deleteRecursively() }

    /** Limpa só dados e estado (Limpar contexto): o app continua. */
    fun clearRuntime(sectionId: String) { dataDir(sectionId).deleteRecursively(); stateFile(sectionId).delete() }

    companion object {
        const val KEEP_VERSIONS = 8
        const val MAX_HTML = 600_000
        const val MAX_DATA_VALUE = 200_000
        const val MAX_DATA_KEYS = 40
        const val MAX_STATE = 300_000
    }
}

/** Validação do artefato antes de publicar (puro). */
object SessionAppValidator {
    fun validate(html: String): String? {
        val low = html.lowercase()
        return when {
            html.length > SessionAppStore.MAX_HTML -> "app grande demais (máx. ${SessionAppStore.MAX_HTML / 1000} mil caracteres); simplifique"
            html.length < 400 -> "app vazio ou curto demais: entregue a interface completa, não um esqueleto"
            !low.contains("<html") && !low.contains("<!doctype") -> "precisa ser um documento HTML completo (<!doctype html><html>…)"
            !low.contains("<body") -> "faltou <body>"
            !low.contains("<script") -> "sem <script>: o app precisa de lógica para mostrar os dados da sessão"
            !html.contains("SessionApp") -> "o app não usa a API SessionApp (getState/onUpdate/send): sem ela não recebe dados reais da sessão"
            low.count { it == '<' } < 12 -> "layout vazio: faltam elementos de interface"
            unbalancedScript(low) -> "tag <script> sem fechamento"
            else -> null
        }
    }

    private fun unbalancedScript(low: String): Boolean =
        Regex("<script\\b").findAll(low).count() != Regex("</script\\s*>").findAll(low).count()

    /** Script da API, inserido no começo do documento ao carregar (a ponte nativa é window.__sar). */
    const val BOOTSTRAP = """<script>(function(){var B=window.__sar,cbs=[];
function st(){try{return JSON.parse(B.getState());}catch(e){return {};}}
window.SessionApp={getState:st,onUpdate:function(cb){cbs.push(cb);try{cb(st());}catch(e){console.error(e);}},
send:function(action,payload){try{B.send(String(action||''),payload==null?'':(typeof payload==='string'?payload:JSON.stringify(payload)));}catch(e){}},
saveState:function(o){try{return B.saveState(JSON.stringify(o));}catch(e){return false;}},
loadState:function(){try{return JSON.parse(B.loadState());}catch(e){return null;}}};
window.__sarEmit=function(){var s=st();cbs.forEach(function(cb){try{cb(s);}catch(e){console.error(e);}});};
window.addEventListener('error',function(e){try{B.reportError(String(e.message||e).slice(0,300));}catch(x){}});})();</script>"""

    fun withBootstrap(html: String): String {
        val i = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (i != null) html.substring(0, i.range.last + 1) + BOOTSTRAP + html.substring(i.range.last + 1) else BOOTSTRAP + html
    }
}
