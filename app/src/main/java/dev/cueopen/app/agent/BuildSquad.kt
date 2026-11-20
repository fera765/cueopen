package dev.cueopen.app.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
    )
    data class Squad(
        val id: String, val parentSessionId: String, val goal: String, val preview: String?,
        val workers: List<Worker>, val log: List<String> = emptyList(), val running: Boolean = true,
    ) {
        val active get() = workers.count { it.status == St.RUNNING }
        val done get() = workers.count { it.status == St.DONE }
    }

    private val _squads = MutableStateFlow<Map<String, Squad>>(emptyMap())
    val squads: StateFlow<Map<String, Squad>> = _squads
    fun get(id: String) = _squads.value[id]
    fun put(s: Squad) = _squads.update { it + (s.id to s) }
    fun edit(id: String, f: (Squad) -> Squad) = _squads.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }
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
        Regex("""class\s*=\s*["']([A-Za-z][\w-]{0,60})""").find(text)?.let { return "." + it.groupValues[1] }
        Regex("""(?m)^\s*([#.][A-Za-z][\w-]{0,60})\s*[{,]""").find(text)?.let { return it.groupValues[1] }
        return null
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
 var st=document.createElement('style');st.textContent='#__fluwy_squad .fs-c{position:fixed;left:0;top:0;transition:transform .9s cubic-bezier(.2,.8,.2,1);will-change:transform}'+
 '#__fluwy_squad .fs-p{display:inline-flex;align-items:center;gap:4px;margin:14px 0 0 12px;padding:3px 9px 3px 4px;border-radius:999px;color:#fff;font:600 12px/1.2 system-ui,sans-serif;box-shadow:0 2px 6px rgba(0,0,0,.25);white-space:nowrap}'+
 '#__fluwy_squad .fs-f{width:16px;height:16px;border-radius:50%;background:rgba(255,255,255,.9);color:#222;font:700 10px/16px system-ui;text-align:center}'+
 '#__fluwy_squad .fs-b{position:fixed;border:2px solid;border-radius:6px;transition:all .6s ease;box-sizing:border-box}'+
 '#__fluwy_squad .fs-t{position:absolute;left:-2px;top:-18px;padding:1px 6px;border-radius:4px 4px 4px 0;color:#fff;font:600 10px/14px system-ui,sans-serif;white-space:nowrap}';
 (document.head||document.documentElement).appendChild(st);(document.body||document.documentElement).appendChild(root);
 w.__fluwySquad={root:root,data:null,els:{},update:function(d){this.data=d;},
  pick:function(x,i){var e=null;if(x.s){try{e=document.querySelector(x.s);}catch(_){}}
   if(!e){var c=document.querySelectorAll('h1,h2,h3,p,button,a,img,li,section > *');if(c.length)e=c[(i*7)%c.length];}return e;},
  frame:function(){var self=this,d=self.data,seen={};if(!document.getElementById('__fluwy_squad'))(document.body||document.documentElement).appendChild(self.root);
   if(d&&d.w){var t=Date.now()/1000;d.w.forEach(function(x){var k='k'+x.i;seen[k]=1;var o=self.els[k];
    if(!o){o={c:document.createElement('div'),b:document.createElement('div')};o.c.className='fs-c';o.b.className='fs-b';
     o.c.innerHTML='<svg width="18" height="20" viewBox="0 0 18 20" style="position:absolute;left:0;top:0"><path d="M1 1l15 8-7 2-3 7z" fill="'+x.c+'" stroke="#fff" stroke-width="1.5" stroke-linejoin="round"/></svg>'+
      '<div class="fs-p" style="background:'+x.c+'"><span class="fs-f">'+x.n.charAt(0)+'</span><span class="fs-n"></span></div>';
     o.b.innerHTML='<div class="fs-t" style="background:'+x.c+'"></div>';o.b.style.borderColor=x.c;self.root.appendChild(o.b);self.root.appendChild(o.c);self.els[k]=o;}
    o.c.querySelector('.fs-n').textContent=x.d?(x.n+' ✓'):x.n;o.b.firstChild.textContent=x.n+(x.l?' · '+x.l:'');
    var e=self.pick(x,x.i),r=e?e.getBoundingClientRect():{left:40+(x.i*53)%240,top:80+(x.i*97)%400,width:120,height:30};
    var wob=x.d?0:Math.sin(t*1.7+x.i)*6;var cx=r.left+Math.min(r.width*0.6,r.width-8)+wob,cy=r.top+r.height*0.55+Math.cos(t*1.3+x.i)*4;
    o.c.style.transform='translate('+cx+'px,'+cy+'px)';o.c.style.opacity=x.d?'.55':'1';
    o.b.style.left=r.left-3+'px';o.b.style.top=r.top-3+'px';o.b.style.width=r.width+6+'px';o.b.style.height=r.height+6+'px';o.b.style.display=x.d?'none':'block';});}
   Object.keys(self.els).forEach(function(k){if(!seen[k]){var o=self.els[k];o.c.remove();o.b.remove();delete self.els[k];}});
   requestAnimationFrame(function(){self.frame();});}};
 setTimeout(function(){w.__fluwySquad.frame();},50);
}
w.__fluwySquad.update(D);
}catch(e){}})();"""
    }
}
