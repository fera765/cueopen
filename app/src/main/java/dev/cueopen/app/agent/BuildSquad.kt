package dev.cueopen.app.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Exército de agentes para desenvolvimento de software (inspirado no time do OpenBot):
 * dezenas de agentes com nome, mascote e cor trabalham juntos no MESMO projeto; no front-end,
 * o browser mostra o cursor e a caixa de cada um sobre o elemento que ele está editando, ao vivo.
 */
object BuildSquad {
    const val STEP = "build_squad"
    const val MAX_TASKS = 40
    const val PARALLEL = 6

    data class Persona(val name: String, val role: String, val face: String, val color: Long, val keys: List<String>)

    val ROSTER = listOf(
        Persona("Forge", "Layout", "bot", 0xFF2FBF71, listOf("layout", "estrutura", "grid", "seção", "secao", "section", "hero", "header", "footer", "esqueleto")),
        Persona("Scout", "Copy", "ghost", 0xFF8A5CFF, listOf("copy", "texto", "títul", "titul", "headline", "conteúdo", "conteudo", "label", "cta")),
        Persona("Pulse", "Design", "flame", 0xFFFF4FA0, listOf("design", "estilo", "css", "cor", "tema", "visual", "chip", "botão", "botao", "animação", "animacao")),
        Persona("Nova", "Builder", "octo", 0xFFFF5C5C, listOf("build", "componente", "component", "integra", "montar", "javascript", "interação", "interacao")),
        Persona("Echo", "Research", "owl", 0xFF4D8DFF, listOf("pesquis", "research", "referência", "referencia", "benchmark", "dados")),
        Persona("Iris", "Visuals", "star", 0xFFB57BFF, listOf("imagem", "ícone", "icone", "ilustra", "svg", "logo", "visuals", "asset")),
        Persona("Bolt", "Frontend", "cloud", 0xFF2EC4C4, listOf("frontend", "front-end", "react", "vue", "html", "página", "pagina", "tela", "responsiv")),
        Persona("Juno", "QA", "bee", 0xFFFFA62B, listOf("qa", "teste", "test", "revis", "valida", "bug", "verifica")),
        Persona("Milo", "SEO", "cat", 0xFFFF6B6B, listOf("seo", "meta", "og:", "sitemap", "performance", "lighthouse")),
        Persona("Kai", "Backend", "orbit", 0xFF3DDC97, listOf("backend", "back-end", "servidor", "server", "node", "python", "express", "fastapi")),
        Persona("Rex", "API", "jelly", 0xFFFFC53D, listOf("api", "endpoint", "rota", "route", "rest", "graphql", "webhook")),
        Persona("Luna", "Banco de dados", "sprout", 0xFF7AD3FF, listOf("banco", "database", "sql", "sqlite", "postgres", "schema", "migra", "tabela")),
        Persona("Zed", "Testes", "bot", 0xFF9C978E, listOf("unit", "unitário", "unitario", "pytest", "jest", "cobertura")),
        Persona("Orion", "DevOps", "orbit", 0xFF5B8CFF, listOf("deploy", "docker", "ci", "publica", "infra", "build script")),
        Persona("Vega", "Performance", "star", 0xFFFFD166, listOf("otimiz", "velocidade", "cache", "lazy", "bundle")),
        Persona("Sage", "Docs", "owl", 0xFF6FCF97, listOf("readme", "docs", "document", "guia")),
        Persona("Nyx", "Segurança", "ghost", 0xFF7B61FF, listOf("seguran", "auth", "login", "senha", "token", "cors", "valida entrada")),
        Persona("Remy", "Acessibilidade", "cat", 0xFFFF8C42, listOf("acessib", "a11y", "aria", "contraste", "leitor de tela")),
        Persona("Tess", "Dados", "sprout", 0xFF56CCF2, listOf("json", "mock", "seed", "fixture", "dados de exemplo")),
        Persona("Hugo", "Mobile", "cloud", 0xFFEB5757, listOf("mobile", "celular", "menu hamburguer", "touch", "breakpoint")),
        Persona("Lia", "Idiomas", "bee", 0xFFF2994A, listOf("tradu", "idioma", "i18n", "inglês", "ingles")),
        Persona("Otto", "Refatoração", "jelly", 0xFFBB6BD9, listOf("refator", "limpa", "organiza código", "organiza codigo")),
        Persona("Pixel", "Ícones", "flame", 0xFF27AE60, listOf("emoji", "favicon", "pixel")),
        Persona("Cora", "Formulários", "octo", 0xFFE84393, listOf("formulário", "formulario", "form", "input", "newsletter", "contato")),
    )

    /** Agente que o orquestrador chama sozinho quando o preview quebra (erro de compilação, import, tela de erro). */
    val FIXER = Persona("Doc", "Correções", "bot", 0xFFE53935, listOf("corrig", "erro", "fix"))

    /** Cada tarefa ganha um agente: primeiro por afinidade com o papel/texto, depois quem ainda não trabalhou. */
    fun assign(tasks: List<Pair<String, String>>): List<Persona> {
        val used = mutableMapOf<String, Int>()
        return tasks.map { (role, task) ->
            val text = (role + " " + task).lowercase()
            val byRole = ROSTER.firstOrNull { role.isNotBlank() && (it.role.equals(role, true) || it.name.equals(role, true)) }
            val byKey = ROSTER.map { p -> p to p.keys.count { text.contains(it) } }.filter { it.second > 0 }
                .sortedWith(compareBy<Pair<Persona, Int>> { used[it.first.name] ?: 0 }.thenByDescending { it.second }).firstOrNull()?.first
            val p = byRole?.takeIf { (used[it.name] ?: 0) == 0 }
                ?: byKey?.takeIf { (used[it.name] ?: 0) == 0 }
                ?: ROSTER.minByOrNull { used[it.name] ?: 0 }!!
            used[p.name] = (used[p.name] ?: 0) + 1
            val n = used[p.name]!!
            if (n == 1) p else p.copy(name = "${p.name} $n")
        }
    }

    enum class St { QUEUED, RUNNING, DONE, FAILED }
    data class Worker(
        val idx: Int, val persona: Persona, val task: String, val label: String,
        val sessionId: String, val target: String = "", val status: St = St.QUEUED, val doing: String = "",
        /** Componente/arquivo que está editando (ex.: "footer" de src/components/Footer.jsx): o cursor procura esse elemento. */
        val hint: String = "",
    )
    data class Squad(
        val id: String, val parentSessionId: String, val goal: String, val preview: String?,
        val workers: List<Worker>, val log: List<String> = emptyList(), val running: Boolean = true,
        /** Aviso sobre o preview (ex.: servidor respondendo erro), mostrado no card. */
        val notice: String = "",
    ) {
        val active get() = workers.count { it.status == St.RUNNING }
        val done get() = workers.count { it.status == St.DONE }
    }

    private val _squads = MutableStateFlow<Map<String, Squad>>(emptyMap())
    val squads: StateFlow<Map<String, Squad>> = _squads
    fun get(id: String) = _squads.value[id]
    fun put(s: Squad) { _squads.update { it + (s.id to s) }; saveSoon(s.id) }
    fun edit(id: String, f: (Squad) -> Squad) { _squads.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }; saveSoon(id) }

    // ── Persistência: o time continua no card depois de o app reiniciar (antes sumia, só ficava o card vazio) ──
    @Volatile var dir: java.io.File? = null
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val dirty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun saveSoon(id: String) {
        if (dir == null || !dirty.add(id)) return
        io.launch { kotlinx.coroutines.delay(700); dirty.remove(id); runCatching { save(id) } }
    }
    private fun save(id: String) {
        val s = get(id) ?: return
        val d = dir ?: return
        d.mkdirs()
        val tmp = java.io.File(d, "$id.json.tmp")
        tmp.writeText(toJson(s))
        tmp.renameTo(java.io.File(d, "$id.json"))
    }
    /** Carrega um time salvo (app reiniciado): quem estava trabalhando fica marcado como interrompido. */
    fun restore(id: String): Squad? {
        get(id)?.let { return it }
        val f = dir?.let { java.io.File(it, "$id.json") }?.takeIf { it.isFile } ?: return null
        val s = runCatching { fromJson(f.readText()) }.getOrNull() ?: return null
        val fixed = if (!s.running) s else s.copy(running = false, notice = "Interrompido: o app foi reiniciado. Peça para continuar que o time retoma.", workers = s.workers.map {
            if (it.status == St.RUNNING || it.status == St.QUEUED) it.copy(status = St.FAILED, doing = "interrompido (app reiniciado)") else it })
        _squads.update { it + (id to fixed) }
        return fixed
    }

    private fun str(o: kotlinx.serialization.json.JsonObject, k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    fun toJson(s: Squad): String = kotlinx.serialization.json.buildJsonObject {
        fun p(v: Any?) = when (v) { is Number -> kotlinx.serialization.json.JsonPrimitive(v); is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
            else -> kotlinx.serialization.json.JsonPrimitive(v?.toString()) }
        put("id", p(s.id)); put("parent", p(s.parentSessionId)); put("goal", p(s.goal)); put("preview", p(s.preview))
        put("running", p(s.running)); put("notice", p(s.notice))
        put("log", kotlinx.serialization.json.buildJsonArray { s.log.forEach { add(p(it)) } })
        put("workers", kotlinx.serialization.json.buildJsonArray {
            s.workers.forEach { w ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("idx", p(w.idx)); put("name", p(w.persona.name)); put("role", p(w.persona.role)); put("face", p(w.persona.face))
                    put("color", p(w.persona.color)); put("task", p(w.task)); put("label", p(w.label)); put("session", p(w.sessionId))
                    put("target", p(w.target)); put("status", p(w.status.name)); put("doing", p(w.doing)); put("hint", p(w.hint))
                })
            }
        })
    }.toString()

    fun fromJson(text: String): Squad {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val ws = (o["workers"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
            val w = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            Worker(str(w, "idx").toIntOrNull() ?: 0,
                Persona(str(w, "name"), str(w, "role"), str(w, "face").ifBlank { "bot" }, str(w, "color").toLongOrNull() ?: 0xFF7C5CFF, emptyList()),
                str(w, "task"), str(w, "label"), str(w, "session"), str(w, "target"),
                runCatching { St.valueOf(str(w, "status")) }.getOrDefault(St.FAILED), str(w, "doing"), str(w, "hint"))
        }
        val preview = str(o, "preview").takeIf { it.isNotBlank() && it != "null" }
        return Squad(str(o, "id"), str(o, "parent"), str(o, "goal"), preview, ws,
            (o["log"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() },
            str(o, "running") == "true", str(o, "notice"))
    }
    fun editWorker(id: String, idx: Int, f: (Worker) -> Worker) = edit(id) { s -> s.copy(workers = s.workers.map { if (it.idx == idx) f(it) else it }) }
    /** Worker (chat) → (squad, índice). */
    fun byWorkerSession(sessionId: String): Pair<Squad, Worker>? =
        _squads.value.values.firstNotNullOfOrNull { s -> s.workers.firstOrNull { it.sessionId == sessionId }?.let { s to it } }

    /** Rótulo curto da tarefa para a lista e para a etiqueta sobre o elemento. */
    fun shortLabel(task: String): String = task.lineSequence().first().trim()
        .replace(Regex("^(crie|criar|faça|faca|fazer|escreva|escrever|ajuste|ajustar|adicione|adicionar|implemente|implementar|monte|montar)\\s+(o|a|os|as|um|uma)?\\s*", RegexOption.IGNORE_CASE), "")
        .take(40).trim().replaceFirstChar { it.uppercase() }

    /**
     * Para onde o cursor vai quando o agente edita: o primeiro id (#x) ou classe (.y) que aparece no trecho
     * novo. Sem nada reconhecível, mantém o alvo anterior.
     */
    fun selectorFromEdit(text: String): String? {
        Regex("""id\s*=\s*["']([A-Za-z][\w-]{0,60})["']""").find(text)?.let { return "#" + it.groupValues[1] }
        Regex("""(?:class|className)\s*=\s*\{?\s*["'`]([A-Za-z][\w-]{0,60})""").find(text)?.let { return "." + it.groupValues[1] }
        Regex("""(?m)^\s*([#.][A-Za-z][\w-]{0,60})\s*[{,]""").find(text)?.let { return it.groupValues[1] }
        return null
    }

    private val GENERIC = setOf("app", "main", "index", "style", "styles", "global", "globals", "theme", "utils", "util", "constants", "data", "config", "vite.config", "package")
    /** src/components/PricingTable.tsx → "pricing-table"; arquivos genéricos (App, main, styles…) não dão pista. */
    fun hintFromPath(path: String): String {
        val base = path.substringAfterLast('/').substringBefore('.')
        if (base.isBlank() || base.lowercase() in GENERIC) return ""
        return base.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").replace('_', '-').lowercase()
    }

    /** Estado para o overlay do browser: só quem está trabalhando (e quem acabou de terminar, com ✓). */
    fun overlayJson(s: Squad): String = kotlinx.serialization.json.buildJsonObject {
        put("w", kotlinx.serialization.json.buildJsonArray {
            s.workers.filter { it.status == St.RUNNING || it.status == St.DONE }.forEach { w ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("n", kotlinx.serialization.json.JsonPrimitive(w.persona.name))
                    put("c", kotlinx.serialization.json.JsonPrimitive(String.format("#%06X", w.persona.color and 0xFFFFFF)))
                    put("s", kotlinx.serialization.json.JsonPrimitive(w.target))
                    put("l", kotlinx.serialization.json.JsonPrimitive(w.label))
                    put("d", kotlinx.serialization.json.JsonPrimitive(w.status == St.DONE))
                    put("i", kotlinx.serialization.json.JsonPrimitive(w.idx))
                    put("f", kotlinx.serialization.json.JsonPrimitive(w.hint))
                })
            }
        })
    }.toString()

    /**
     * Script do overlay: cursores coloridos com o nome do agente, caixa tracejada e etiqueta sobre o elemento-alvo.
     * Não intercepta toques (pointer-events: none) e se reposiciona a cada quadro (rolagem, recarga, layout).
     */
    fun overlayScript(json: String?): String {
        val data = json ?: "null"
        return """(function(){try{
var D=$data;var w=window;
if(!w.__fluwySquad){
 var root=document.createElement('div');root.id='__fluwy_squad';
 root.style.cssText='position:fixed;left:0;top:0;width:0;height:0;z-index:2147483647;pointer-events:none';
 var st=document.createElement('style');st.textContent='#__fluwy_squad .fs-c{position:fixed;left:0;top:0;will-change:transform}'+
 '#__fluwy_squad .fs-p{display:inline-flex;align-items:center;gap:4px;margin:14px 0 0 12px;padding:3px 9px 3px 4px;border-radius:999px;color:#fff;font:600 12px/1.2 system-ui,sans-serif;box-shadow:0 2px 6px rgba(0,0,0,.25);white-space:nowrap}'+
 '#__fluwy_squad .fs-f{width:16px;height:16px;border-radius:50%;background:rgba(255,255,255,.9);color:#222;font:700 10px/16px system-ui;text-align:center}'+
 '#__fluwy_squad .fs-b{position:fixed;border:2px solid;border-radius:6px;box-sizing:border-box}'+
 '#__fluwy_squad .fs-t{position:absolute;left:-2px;top:-18px;max-width:70vw;overflow:hidden;text-overflow:ellipsis;padding:1px 6px;border-radius:4px 4px 4px 0;color:#fff;font:600 10px/14px system-ui,sans-serif;white-space:nowrap}'+
 '@keyframes fsBuild{0%{opacity:0;transform:translateY(8px) scale(.98);outline:2px solid var(--fs,#7c5cff)}60%{opacity:1;outline:2px solid var(--fs,#7c5cff)}100%{opacity:1;transform:none;outline:2px solid transparent}}'+
 '.fs-new{animation:fsBuild .7s ease-out both;outline-offset:2px}';
 (document.head||document.documentElement).appendChild(st);(document.body||document.documentElement).appendChild(root);
 var S=w.__fluwySquad={root:root,data:null,els:{},recent:[],update:function(d){this.data=d;this.idleAt=0;if(!this.loop&&!this.stop){var me=this;me.loop=true;requestAnimationFrame(function(){me.frame();});}},
  find:function(x){var e=null;if(x.s){try{e=document.querySelector(x.s);}catch(_){}}
   if(!e&&x.f){var f=x.f,c=f.replace(/-/g,'');var q=['#'+f,'.'+f,'#'+c,'.'+c,'[class*="'+f+'" i]','[id*="'+f+'" i]','[class*="'+c+'" i]',f.split('-')[0]];
    for(var k=0;k<q.length&&!e;k++){try{e=document.querySelector(q[k]);}catch(_){}}}
   if(e&&e.closest&&e.closest('#__fluwy_squad'))e=null;return e;},
  pick:function(x){var e=this.find(x);if(e)return e;
   var r=this.recent;if(r.length){var o=r[(x.i)%r.length];if(o&&o.isConnected)return o;}
   var c=document.querySelectorAll('h1,h2,h3,p,button,a,img,li,section,header,footer,nav');return c.length?c[(x.i*7)%c.length]:null;},
  frame:function(){var self=this,d=self.data,seen={},now=Date.now();if(self.stop){self.loop=false;return;}
   var active=d&&d.w&&d.w.some(function(x){return !x.d;});if(active)self.idleAt=0;else if(!self.idleAt)self.idleAt=now;
   if(self.idleAt&&now-self.idleAt>3000){self.loop=false;Object.keys(self.els).forEach(function(k){var o=self.els[k];o.c.remove();o.b.remove();delete self.els[k];});return;}
   if(!document.getElementById('__fluwy_squad'))(document.body||document.documentElement).appendChild(self.root);
   if(d&&d.w){var t=now/1000;d.w.forEach(function(x){var k='k'+x.i;var o=self.els[k];
    if(x.d){if(!o)return;if(!o.doneAt)o.doneAt=now;if(now-o.doneAt>2500)return;}
    seen[k]=1;
    if(!o){o={c:document.createElement('div'),b:document.createElement('div'),x:-1,y:-1};o.c.className='fs-c';o.b.className='fs-b';
     o.c.innerHTML='<svg width="18" height="20" viewBox="0 0 18 20" style="position:absolute;left:0;top:0"><path d="M1 1l15 8-7 2-3 7z" fill="'+x.c+'" stroke="#fff" stroke-width="1.5" stroke-linejoin="round"/></svg>'+
      '<div class="fs-p" style="background:'+x.c+'"><span class="fs-f">'+x.n.charAt(0)+'</span><span class="fs-n"></span></div>';
     o.b.innerHTML='<div class="fs-t" style="background:'+x.c+'"></div>';o.b.style.borderColor=x.c;self.root.appendChild(o.b);self.root.appendChild(o.c);self.els[k]=o;}
    o.c.querySelector('.fs-n').textContent=x.d?(x.n+' ✓'):x.n;o.b.firstChild.textContent=x.n+(x.l?' · '+x.l:'');
    if(!o.el||!o.el.isConnected||now-(o.at||0)>800){o.el=self.pick(x);o.at=now;}var e=o.el,r=e?e.getBoundingClientRect():{left:40+(x.i*53)%240,top:80+(x.i*97)%400,width:120,height:30};
    var tx=r.left+Math.min(r.width*0.6,Math.max(r.width-8,0))+(x.d?0:Math.sin(t*2.2+x.i)*5),ty=r.top+Math.min(r.height*0.55,60)+(x.d?0:Math.cos(t*1.8+x.i)*3);
    if(o.x<0){o.x=tx;o.y=ty;}o.x+=(tx-o.x)*0.5;o.y+=(ty-o.y)*0.5;
    o.c.style.transform='translate('+o.x.toFixed(1)+'px,'+o.y.toFixed(1)+'px)';o.c.style.opacity=x.d?'.6':'1';
    var bt=Math.max(r.top-3,2);o.b.style.left=Math.max(r.left-3,2)+'px';o.b.style.top=bt+'px';o.b.style.width=Math.min(r.width+6,innerWidth-4)+'px';o.b.style.height=Math.max(Math.min(r.height+6,innerHeight-bt-2),12)+'px';
    var huge=r.width>innerWidth*0.9&&r.height>innerHeight*0.8;
    o.b.firstChild.style.top=(bt<20?'2px':'-18px');o.b.style.display=(x.d||!e||huge)?'none':'block';});}
   Object.keys(self.els).forEach(function(k){if(!seen[k]){var o=self.els[k];o.c.remove();o.b.remove();delete self.els[k];}});
   self.loop=true;setTimeout(function(){requestAnimationFrame(function(){self.frame();});},80);}};
 // Elemento sendo construído: o que entra ou muda no DOM aparece com animação e atrai o cursor de quem está trabalhando.
 try{S.mo=new MutationObserver(function(ms){var add=[];ms.forEach(function(m){
   (m.type==='childList'?m.addedNodes:[m.target]).forEach(function(n){var el=n.nodeType===1?n:n.parentElement;
    if(!el||!el.isConnected||el.id==='__fluwy_squad'||(el.closest&&el.closest('#__fluwy_squad'))||el.tagName==='STYLE'||el.tagName==='SCRIPT')return;add.push(el);});});
  if(!add.length)return;S.lastMut=Date.now();var nw=S.lastMut;
  // Página que muda sozinha (ticker, relógio, carrossel): sem animação, senão o renderer do celular cai.
  S.bursts=(S.bursts||[]).filter(function(t){return nw-t<5000;});S.bursts.push(nw);if(S.bursts.length>12||S.calm){S.calm=S.bursts.length>6;return;}
  if(nw-(S.lastAnim||0)<500)return;S.lastAnim=nw;
  if(!(S.data&&S.data.w&&S.data.w.some(function(x){return!x.d;})))return;var col=(S.data&&S.data.w&&S.data.w.filter(function(x){return!x.d;})[0]||{}).c||'#7c5cff';
  add.slice(0,4).forEach(function(el){if(el===document.body||el===document.documentElement)return;var rr=el.getBoundingClientRect();if(rr.width*rr.height>innerWidth*innerHeight*0.6)return;el.style.setProperty('--fs',col);el.classList.remove('fs-new');void el.offsetWidth;el.classList.add('fs-new');
   setTimeout(function(){el.classList.remove('fs-new');},800);});
  S.recent=add.concat(S.recent).filter(function(e){return e.isConnected&&e!==document.body;}).slice(0,12);
 }).observe(document.body||document.documentElement,{childList:true,subtree:true});}catch(_){}
 requestAnimationFrame(function(){S.frame();});
}
w.__fluwySquad.update(D);
}catch(e){}})();"""
    }
}
