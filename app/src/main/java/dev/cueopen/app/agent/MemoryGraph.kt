package dev.cueopen.app.agent

import dev.cueopen.app.data.MemoryItem
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Segundo cérebro (estilo Obsidian): cada memória é uma nota; ligações vêm de [[wikilinks]] explícitos,
 * de `links` salvos pela IA e de ligações automáticas por tags/palavras em comum. Lógica pura (testável na JVM).
 */
object MemoryGraph {
    /** Categorias (grupos de cor no grafo), na ordem de exibição. */
    val KINDS = listOf("pessoa", "projeto", "trabalho", "preferencia", "ferramenta", "lugar", "rotina", "outro")
    const val MAX_LINKS = 8

    private val WIKI = Regex("\\[\\[([^\\[\\]]{1,80})\\]\\]")

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()

    /** Alvos de [[wikilinks]] no texto. */
    fun wikiTargets(text: String): List<String> = WIKI.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()

    /** Texto sem a marcação [[ ]] (para exibição/prompt). */
    fun plain(text: String): String = WIKI.replace(text) { it.groupValues[1] }

    /** Título da nota: o explícito, ou as primeiras palavras do fato. */
    fun titleOf(m: MemoryItem): String = m.title.ifBlank {
        val p = plain(m.text).trim()
        val w = p.split(Regex("\\s+")).take(5).joinToString(" ")
        if (w.length < p.length) w.trimEnd(',', '.', ':', ';') + "…" else w.trimEnd('.')
    }

    private val KIND_HINTS = linkedMapOf(
        "pessoa" to listOf("esposa", "marido", "filho", "filha", "mae", "pai", "irmao", "irma", "amigo", "amiga", "chefe", "colega", "cliente", "socio", "namorad", "se chama", "nome dele", "nome dela"),
        "projeto" to listOf("projeto", "app", "aplicativo", "repo", "repositorio", "github", "site", "produto", "lancamento", "versao"),
        "trabalho" to listOf("trabalha", "empresa", "cargo", "emprego", "reuniao", "equipe", "time de", "vendas", "loja", "negocio"),
        "preferencia" to listOf("prefere", "gosta", "nao gosta", "odeia", "sempre", "nunca", "favorit", "quer que", "respostas curtas", "estilo"),
        "ferramenta" to listOf("usa o", "usa a", "ferramenta", "conta no", "conta de", "gmail", "notion", "slack", "whatsapp", "telegram", "planilha", "api", "modelo"),
        "lugar" to listOf("mora", "cidade", "endereco", "bairro", "estado de", "pais", "viagem", "viaja"),
        "rotina" to listOf("todo dia", "toda semana", "diariamente", "semanal", "horario", "acorda", "agenda", "rotina", "manha", "noite"),
    )

    /** Categoria: a salva, uma tag que seja categoria, ou inferida por palavras-chave. */
    fun kindOf(m: MemoryItem): String {
        norm(m.kind).takeIf { it in KINDS }?.let { return it }
        m.tags.map { norm(it) }.firstOrNull { it in KINDS }?.let { return it }
        val t = " " + norm(plain(m.text)) + " "
        return KIND_HINTS.entries.firstOrNull { (_, hints) -> hints.any { t.contains(it) } }?.key ?: "outro"
    }

    fun words(text: String): Set<String> = norm(plain(text)).split(Regex("[^a-z0-9]+")).filter { it.length > 3 && it !in STOP }.toSet()

    private val STOP = setOf("para", "como", "mais", "isso", "esse", "essa", "este", "esta", "pelo", "pela", "quando", "onde", "sobre", "entre", "depois", "antes", "muito", "porque", "tambem", "usuario", "sempre", "ainda", "todos", "todas", "cada", "qual", "quer")

    data class Edge(val a: String, val b: String, val explicit: Boolean)

    /** Resolve um alvo (id, prefixo de id ou título) para o id de uma nota. */
    fun resolve(target: String, notes: List<MemoryItem>): String? {
        val t = target.trim(); if (t.isEmpty()) return null
        notes.firstOrNull { it.id == t }?.let { return it.id }
        val n = norm(t)
        notes.firstOrNull { norm(titleOf(it).trimEnd('…')) == n || norm(it.title) == n }?.let { return it.id }
        if (t.length >= 6) notes.firstOrNull { it.id.startsWith(t) }?.let { return it.id }
        return notes.firstOrNull { n.length >= 4 && norm(plain(it.text)).contains(n) }?.id
    }

    /** Ligações explícitas (links + [[wikilinks]]) e automáticas (tags/palavras em comum), sem duplicatas. */
    fun edges(notes: List<MemoryItem>, auto: Boolean = true): List<Edge> {
        val ids = notes.map { it.id }.toSet()
        val out = LinkedHashMap<Pair<String, String>, Edge>()
        fun add(a: String, b: String, explicit: Boolean) {
            if (a == b || a !in ids || b !in ids) return
            val k = if (a < b) a to b else b to a
            val prev = out[k]
            if (prev == null || (explicit && !prev.explicit)) out[k] = Edge(k.first, k.second, explicit)
        }
        for (m in notes) {
            m.links.forEach { add(m.id, it, true) }
            wikiTargets(m.text).forEach { t -> resolve(t, notes)?.let { add(m.id, it, true) } }
        }
        if (auto) {
            val ws = notes.associate { it.id to words(it.text) }
            val tags = notes.associate { it.id to it.tags.map { t -> norm(t) }.filter { t -> t !in KINDS }.toSet() }
            for (m in notes) {
                val cands = notes.asSequence().filter { it.id != m.id }.map { o ->
                    val sharedTags = tags[m.id]!!.intersect(tags[o.id]!!).size
                    val a = ws[m.id]!!; val b = ws[o.id]!!
                    val shared = a.intersect(b).size
                    val jac = if (a.isEmpty() || b.isEmpty()) 0.0 else shared.toDouble() / a.union(b).size
                    o.id to (sharedTags * 1.0 + if (shared >= 2) jac * 2 else 0.0)
                }.filter { it.second >= 0.5 }.sortedByDescending { it.second }.take(3)
                cands.forEach { add(m.id, it.first, false) }
            }
        }
        return out.values.toList()
    }

    fun neighbors(id: String, edges: List<Edge>): Set<String> =
        edges.mapNotNullTo(LinkedHashSet()) { if (it.a == id) it.b else if (it.b == id) it.a else null }

    fun degrees(edges: List<Edge>): Map<String, Int> {
        val d = HashMap<String, Int>()
        edges.forEach { d[it.a] = (d[it.a] ?: 0) + 1; d[it.b] = (d[it.b] ?: 0) + 1 }
        return d
    }

    /** Ids explícitos para salvar a partir de alvos citados pela IA (ids, títulos ou [[ ]]). */
    fun linkIds(targets: List<String>, notes: List<MemoryItem>, selfId: String? = null): List<String> =
        targets.flatMap { t -> wikiTargets(t).ifEmpty { listOf(t) } }.mapNotNull { resolve(it, notes) }
            .filter { it != selfId }.distinct().take(MAX_LINKS)

    /** Expande as memórias escolhidas com vizinhos diretos (ligações explícitas primeiro), até `extra` notas. */
    fun expand(picked: List<MemoryItem>, all: List<MemoryItem>, extra: Int): List<MemoryItem> {
        if (picked.isEmpty() || extra <= 0) return picked
        val e = edges(all)
        val have = picked.map { it.id }.toMutableSet()
        val byId = all.associateBy { it.id }
        val add = mutableListOf<MemoryItem>()
        for (p in picked) {
            val ns = e.filter { it.a == p.id || it.b == p.id }.sortedByDescending { it.explicit }
                .map { if (it.a == p.id) it.b else it.a }
            for (n in ns) {
                if (add.size >= extra) break
                if (n !in have) { byId[n]?.let { add += it; have += n } }
            }
        }
        return picked + add
    }

    /** Linha compacta para o prompt/ferramentas: título, categoria, fato e ligações. */
    fun describe(m: MemoryItem, notes: List<MemoryItem>, edges: List<Edge>): String {
        val byId = notes.associateBy { it.id }
        val ns = neighbors(m.id, edges).mapNotNull { byId[it] }.take(5).joinToString(", ") { "[[${titleOf(it)}]]" }
        return "[${m.id.take(8)}] ${titleOf(m)} (${kindOf(m)}): ${plain(m.text)}" + if (ns.isNotEmpty()) " → $ns" else ""
    }

    // ---------- Layout de forças (Fruchterman-Reingold simplificado), determinístico ----------

    data class P(var x: Float, var y: Float)

    /**
     * Posiciona os nós em [-1,1]². Grupos (categorias) começam em setores do círculo, o que forma
     * aglomerados coloridos como no grafo do Obsidian. `seed` deixa o resultado estável entre aberturas.
     */
    fun layout(ids: List<String>, groupOf: Map<String, String>, edges: List<Edge>, iterations: Int = 220, seed: Long = 7): Map<String, P> {
        val n = ids.size
        if (n == 0) return emptyMap()
        val rnd = java.util.Random(seed)
        val groups = ids.map { groupOf[it] ?: "outro" }.distinct()
        val pos = ids.associateWith { id ->
            val gi = groups.indexOf(groupOf[id] ?: "outro")
            val ang = (gi.toDouble() / max(1, groups.size)) * 2 * Math.PI + rnd.nextGaussian() * 0.5
            val r = 0.25 + rnd.nextDouble() * 0.6
            P((Math.cos(ang) * r).toFloat(), (Math.sin(ang) * r).toFloat())
        }
        if (n == 1) return mapOf(ids[0] to P(0f, 0f))
        val idx = ids.withIndex().associate { it.value to it.index }
        val k = sqrt(4.0 / n).toFloat() * 0.9f
        val disp = Array(n) { FloatArray(2) }
        val arr = ids.map { pos[it]!! }
        var temp = 0.12f
        val sample = n > 400
        repeat(iterations) {
            for (d in disp) { d[0] = 0f; d[1] = 0f }
            for (i in 0 until n) {
                val pi = arr[i]
                var j = if (sample) rnd.nextInt(n) else i + 1
                val end = if (sample) 60 else n
                var c = 0
                while (if (sample) c < end else j < n) {
                    if (j != i) {
                        val pj = arr[j]
                        val dx = pi.x - pj.x; val dy = pi.y - pj.y
                        val d2 = max(dx * dx + dy * dy, 1e-4f)
                        val f = k * k / d2 * (if (sample) n / 60f / 2f else 1f)
                        disp[i][0] += dx * f; disp[i][1] += dy * f
                        if (!sample) { disp[j][0] -= dx * f; disp[j][1] -= dy * f }
                    }
                    if (sample) { j = rnd.nextInt(n); c++ } else j++
                }
            }
            for (e in edges) {
                val a = idx[e.a] ?: continue; val b = idx[e.b] ?: continue
                val pa = arr[a]; val pb = arr[b]
                val dx = pa.x - pb.x; val dy = pa.y - pb.y
                val d = max(sqrt(dx * dx + dy * dy), 1e-3f)
                val f = d * d / k * (if (e.explicit) 1.2f else 0.6f) / d
                disp[a][0] -= dx * f; disp[a][1] -= dy * f
                disp[b][0] += dx * f; disp[b][1] += dy * f
            }
            for (i in 0 until n) {
                val p = arr[i]
                // gravidade leve para o centro mantém o "cérebro" redondo
                disp[i][0] -= p.x * 0.6f; disp[i][1] -= p.y * 0.6f
                val dl = max(sqrt(disp[i][0] * disp[i][0] + disp[i][1] * disp[i][1]), 1e-6f)
                val m = min(dl, temp)
                p.x += disp[i][0] / dl * m; p.y += disp[i][1] / dl * m
            }
            temp = max(0.004f, temp * 0.975f)
        }
        // normaliza para [-1,1]
        val mx = arr.maxOf { max(kotlin.math.abs(it.x), kotlin.math.abs(it.y)) }.coerceAtLeast(1e-3f)
        arr.forEach { it.x /= mx; it.y /= mx }
        return pos
    }
}
