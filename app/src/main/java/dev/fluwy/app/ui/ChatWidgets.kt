package dev.fluwy.app.ui

import dev.fluwy.app.uiconnector.custom.Compute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * FluwyUI — SDK de elementos interativos DENTRO da resposta do agente.
 *
 * O agente escreve um bloco ```fluwyui com um objeto JSON; o chat renderiza um elemento nativo
 * (botões, gráfico, calculadora, tabela, estatísticas, passos, barra de progresso, aviso, chave→valor).
 * Tudo é tolerante a STREAM: enquanto o JSON chega incompleto, nunca aparece o JSON cru nem quebra a tela —
 * mostra um esqueleto e, assim que fecha, vira o elemento final. Formato 100% próprio do app.
 */

sealed class FluwyWidget {
    data class Buttons(val items: List<Btn>) : FluwyWidget()
    data class Chart(val title: String, val style: String, val series: List<Ser>, val labels: List<String>) : FluwyWidget()
    data class Calculator(val title: String, val expr: String, val vars: List<CalcVar>, val unit: String) : FluwyWidget()
    data class Table(val title: String, val columns: List<String>, val rows: List<List<String>>) : FluwyWidget()
    data class Stats(val title: String, val items: List<Stat>) : FluwyWidget()
    data class Steps(val title: String, val items: List<String>) : FluwyWidget()
    data class Progress(val title: String, val items: List<Prog>) : FluwyWidget()
    data class Callout(val tone: String, val title: String, val text: String) : FluwyWidget()
    data class KeyValue(val title: String, val items: List<Pair<String, String>>) : FluwyWidget()
    data class ConnectorPanel(val id: String, val title: String, val collapsed: Boolean) : FluwyWidget()
    data class Checklist(val title: String, val items: List<Pair<String, Boolean>>) : FluwyWidget()
    data class Timeline(val title: String, val items: List<Triple<String, String, String>>) : FluwyWidget()
    data class Badges(val items: List<Stat>) : FluwyWidget()
    data class Compare(val title: String, val options: List<Opt>) : FluwyWidget()
    data class Files(val title: String, val items: List<Pair<String, String>>) : FluwyWidget()
    data class Accordion(val items: List<Pair<String, String>>) : FluwyWidget()
    data class Tabs(val tabs: List<Pair<String, String>>) : FluwyWidget()
    data class Summary(val title: String, val text: String, val bullets: List<String>) : FluwyWidget()
    data class Divider(val label: String) : FluwyWidget()
    /** Cards com FOTO (produtos, notícias, lugares…): item sem foto válida não aparece. */
    data class Cards(val title: String, val items: List<CardItem>) : FluwyWidget()
    data class CardItem(val title: String, val subtitle: String, val image: String, val url: String, val price: String, val source: String, val badge: String)
    /** Composição: vários componentes num só bloco (relatório final). */
    data class Group(val title: String, val blocks: List<FluwyWidget>) : FluwyWidget()
    data class Opt(val name: String, val items: List<String>, val tone: String)
    object Skeleton : FluwyWidget() // JSON ainda chegando (stream)

    data class Btn(val label: String, val send: String, val copy: String, val url: String, val tone: String)
    data class Ser(val name: String, val values: List<Double>, val color: String)
    data class CalcVar(val id: String, val label: String, val default: Double, val min: Double?, val max: Double?, val step: Double)
    data class Stat(val label: String, val value: String, val tone: String)
    data class Prog(val label: String, val value: Double, val tone: String)
}

object FluwyUi {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Tipos conhecidos — basta ver o "type" para mostrar o esqueleto certo durante o stream. */
    val TYPES = setOf("buttons", "choices", "chart", "calculator", "calc", "table", "stats", "steps", "progress", "callout", "note", "keyvalue", "kv", "connector", "painel",
        "checklist", "timeline", "badges", "compare", "files", "accordion", "tabs", "summary", "divider", "report", "group", "cards", "products", "news", "produtos", "noticias")

    private fun JsonObject.str(vararg k: String, def: String = "") = k.firstNotNullOfOrNull { this[it] as? JsonPrimitive }?.contentOrNull ?: def
    private fun JsonObject.num(vararg k: String): Double? = k.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() } }
    private fun JsonObject.arr(vararg k: String): JsonArray? = k.firstNotNullOfOrNull { this[it] as? JsonArray }
    private fun strList(a: JsonArray?): List<String> = a?.map { (it as? JsonPrimitive)?.contentOrNull ?: "" }.orEmpty()
    private fun numList(a: JsonArray?): List<Double> = a?.mapNotNull { (it as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() } }.orEmpty()

    /**
     * Converte o conteúdo de um bloco fluwyui em widget. [complete] = o bloco já fechou (```).
     * Durante o stream (incompleto), tenta mesmo assim; se ainda não dá, devolve Skeleton (nunca null/JSON cru).
     */
    fun parse(raw: String, complete: Boolean): FluwyWidget {
        val text = raw.trim().removePrefix("```fluwyui").removePrefix("```").removeSuffix("```").trim()
        if (text.isBlank()) return FluwyWidget.Skeleton
        val o = runCatching { json.parseToJsonElement(repair(text)) as? JsonObject }.getOrNull()
            ?: return if (complete) errorCallout() else FluwyWidget.Skeleton
        val type = o.str("type").lowercase()
        if (type.isEmpty()) return FluwyWidget.Skeleton
        if (type !in TYPES) return if (complete) errorCallout() else FluwyWidget.Skeleton
        return runCatching { build(type, o) }.getOrElse { if (complete) errorCallout() else FluwyWidget.Skeleton }
    }

    private fun errorCallout() = FluwyWidget.Callout("warn", "Elemento inválido", "O agente enviou um elemento que não pôde ser exibido.")

    /** Tenta fechar chaves/colchetes abertos de um JSON truncado pelo stream, para render progressivo. */
    private fun repair(t: String): String {
        var s = t
        // remove vírgula/dois-pontos pendente no fim
        s = s.trimEnd().trimEnd(',', ':')
        var inStr = false; var esc = false
        val stack = ArrayDeque<Char>()
        for (ch in s) {
            if (esc) { esc = false; continue }
            when {
                ch == '\\' && inStr -> esc = true
                ch == '"' -> inStr = !inStr
                !inStr && (ch == '{' || ch == '[') -> stack.addLast(ch)
                !inStr && (ch == '}' || ch == ']') -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        val sb = StringBuilder(s)
        if (inStr) sb.append('"')
        while (stack.isNotEmpty()) sb.append(if (stack.removeLast() == '{') '}' else ']')
        return sb.toString()
    }

    private fun build(type: String, o: JsonObject): FluwyWidget = when (type) {
        "buttons", "choices" -> FluwyWidget.Buttons(
            (o.arr("items", "buttons", "options")?.map { e ->
                when (e) {
                    is JsonObject -> FluwyWidget.Btn(e.str("label", "text", def = "—"), e.str("send", "message", "value"), e.str("copy"), e.str("url", "link"), e.str("tone", def = "neutral"))
                    is JsonPrimitive -> e.contentOrNull.orEmpty().let { FluwyWidget.Btn(it, it, "", "", "neutral") }
                    else -> FluwyWidget.Btn("", "", "", "", "neutral")
                }
            } ?: emptyList()).filter { it.label.isNotBlank() })
        "chart" -> {
            val series = o.arr("series")?.mapNotNull { it as? JsonObject }?.map {
                FluwyWidget.Ser(it.str("name"), numList(it.arr("values", "data", "points")), it.str("color"))
            } ?: o.arr("values", "data")?.let { listOf(FluwyWidget.Ser("", numList(it), "")) } ?: emptyList()
            FluwyWidget.Chart(o.str("title"), o.str("style", "kind", def = "bar").lowercase(), series, strList(o.arr("labels", "x")))
        }
        "calculator", "calc" -> FluwyWidget.Calculator(o.str("title", def = "Calculadora"), o.str("expr", "formula", "expression"),
            o.arr("vars", "inputs", "fields")?.mapNotNull { it as? JsonObject }?.map {
                FluwyWidget.CalcVar(it.str("id", "name", def = "x"), it.str("label", def = it.str("id", "name", def = "x")),
                    it.num("default", "value") ?: 0.0, it.num("min"), it.num("max"), it.num("step") ?: 1.0)
            }.orEmpty(), o.str("unit", "suffix"))
        "table" -> FluwyWidget.Table(o.str("title"), strList(o.arr("columns", "headers", "cols")),
            o.arr("rows")?.map { r -> (r as? JsonArray)?.let { strList(it) } ?: ((r as? JsonObject)?.let { ro -> strList(o.arr("columns")).map { ro.str(it) } } ?: emptyList()) }?.filter { it.isNotEmpty() }.orEmpty())
        "stats" -> FluwyWidget.Stats(o.str("title"), o.arr("items", "stats")?.mapNotNull { it as? JsonObject }?.map {
            FluwyWidget.Stat(it.str("label", "name", def = "—"), it.str("value", def = "—"), it.str("tone", def = "neutral")) }.orEmpty())
        "steps" -> FluwyWidget.Steps(o.str("title"), o.arr("items", "steps")?.let { a ->
            a.map { e -> (e as? JsonPrimitive)?.contentOrNull ?: (e as? JsonObject)?.str("title", "text", "label") ?: "" } }.orEmpty().filter { it.isNotBlank() })
        "progress" -> FluwyWidget.Progress(o.str("title"), o.arr("items", "bars")?.mapNotNull { it as? JsonObject }?.map {
            FluwyWidget.Prog(it.str("label", def = ""), (it.num("value", "percent", "pct") ?: 0.0).coerceIn(0.0, 100.0), it.str("tone", def = "neutral")) }
            ?: o.num("value", "percent")?.let { listOf(FluwyWidget.Prog(o.str("label"), it.coerceIn(0.0, 100.0), o.str("tone", def = "neutral"))) }.orEmpty())
        "callout", "note" -> FluwyWidget.Callout(o.str("tone", def = "info"), o.str("title"), o.str("text", "message", "body"))
        "connector", "painel" -> FluwyWidget.ConnectorPanel(o.str("id", "connector", "conector"), o.str("title"), o.str("collapsed", "collapsado", def = "false").equals("true", true))
        "keyvalue", "kv" -> FluwyWidget.KeyValue(o.str("title"), o.arr("items", "pairs")?.mapNotNull { it as? JsonObject }?.map {
            it.str("key", "label", "name", def = "—") to it.str("value", "val", def = "—") }
            ?: (o["data"] as? JsonObject)?.entries?.map { it.key to ((it.value as? JsonPrimitive)?.contentOrNull ?: "") }.orEmpty())
        "checklist" -> FluwyWidget.Checklist(o.str("title"), o.arr("items")?.map { e ->
            when (e) { is JsonObject -> e.str("text", "label", "title") to e.str("done", "checked", def = "false").equals("true", true); else -> ((e as? JsonPrimitive)?.contentOrNull ?: "") to false }
        }.orEmpty().filter { it.first.isNotBlank() })
        "timeline" -> FluwyWidget.Timeline(o.str("title"), o.arr("items", "events")?.mapNotNull { it as? JsonObject }?.map {
            Triple(it.str("when", "time", "date"), it.str("title", "label"), it.str("text", "description")) }.orEmpty())
        "badges" -> FluwyWidget.Badges(o.arr("items")?.map { e ->
            when (e) { is JsonObject -> FluwyWidget.Stat(e.str("label", "text", def = ""), "", e.str("tone", def = "neutral")); else -> FluwyWidget.Stat((e as? JsonPrimitive)?.contentOrNull ?: "", "", "neutral") }
        }.orEmpty().filter { it.label.isNotBlank() })
        "compare" -> FluwyWidget.Compare(o.str("title"), o.arr("options", "items")?.mapNotNull { it as? JsonObject }?.map {
            FluwyWidget.Opt(it.str("name", "title", def = "—"), strList(it.arr("items", "points")).filter { p -> p.isNotBlank() }, it.str("tone", def = "neutral")) }.orEmpty())
        "files" -> FluwyWidget.Files(o.str("title"), o.arr("items", "files")?.map { e ->
            when (e) { is JsonObject -> e.str("path", "name") to e.str("note", "description"); else -> ((e as? JsonPrimitive)?.contentOrNull ?: "") to "" }
        }.orEmpty().filter { it.first.isNotBlank() })
        "accordion" -> FluwyWidget.Accordion(o.arr("items", "sections")?.mapNotNull { it as? JsonObject }?.map { it.str("title", "label", def = "—") to it.str("text", "body", "content") }.orEmpty())
        "tabs" -> FluwyWidget.Tabs(o.arr("tabs", "items")?.mapNotNull { it as? JsonObject }?.map { it.str("title", "label", def = "—") to it.str("text", "body", "content") }.orEmpty())
        "summary" -> FluwyWidget.Summary(o.str("title", def = "Resumo executivo"), o.str("text", "body"), strList(o.arr("bullets", "items")).filter { it.isNotBlank() })
        "divider" -> FluwyWidget.Divider(o.str("label", "text"))
        "cards", "products", "news", "produtos", "noticias" -> FluwyWidget.Cards(o.str("title", "titulo"), o.arr("items", "cards", "products", "news", "results")?.mapNotNull { it as? JsonObject }?.map {
            FluwyWidget.CardItem(it.str("title", "name", "titulo", "nome"), it.str("subtitle", "description", "snippet", "descricao", "resumo"),
                it.str("image", "img", "thumbnail", "photo", "foto", "imagem"), it.str("url", "link", "href"), it.str("price", "preco", "preço"),
                it.str("source", "site", "fonte", "store", "loja"), it.str("badge", "tag", "date", "data"))
        }?.filter { it.title.isNotBlank() && it.url.startsWith("http") }?.take(20).orEmpty())
        "report", "group" -> FluwyWidget.Group(o.str("title"), o.arr("blocks", "items")?.mapNotNull { it as? JsonObject }?.take(12)?.mapNotNull { b ->
            val t = b.str("type").lowercase()
            // Sem recursão de grupos: um relatório não contém outro (limita profundidade e tamanho).
            if (t in TYPES && t != "report" && t != "group") runCatching { build(t, b) }.getOrNull() else null
        }.orEmpty())
        else -> FluwyWidget.Skeleton
    }

    /** Avalia a expressão da calculadora com os valores atuais (reaproveita o motor Compute, isolado e seguro). */
    fun evalCalc(expr: String, values: Map<String, Double>): String = runCatching {
        val r = Compute.eval(expr, values.mapValues { it.value as Any? }, null)
        Compute.str(r)
    }.getOrElse { "—" }
}
