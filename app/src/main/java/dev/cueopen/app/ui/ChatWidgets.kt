package dev.cueopen.app.ui

import dev.cueopen.app.uiconnector.custom.Compute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * CueUI — SDK de elementos interativos DENTRO da resposta do agente.
 *
 * O agente escreve um bloco ```cueui com um objeto JSON; o chat renderiza um elemento nativo
 * (botões, gráfico, calculadora, tabela, estatísticas, passos, barra de progresso, aviso, chave→valor).
 * Tudo é tolerante a STREAM: enquanto o JSON chega incompleto, nunca aparece o JSON cru nem quebra a tela —
 * mostra um esqueleto e, assim que fecha, vira o elemento final. Formato 100% próprio do app.
 */

sealed class CueWidget {
    data class Buttons(val items: List<Btn>) : CueWidget()
    data class Chart(val title: String, val style: String, val series: List<Ser>, val labels: List<String>) : CueWidget()
    data class Calculator(val title: String, val expr: String, val vars: List<CalcVar>, val unit: String) : CueWidget()
    data class Table(val title: String, val columns: List<String>, val rows: List<List<String>>) : CueWidget()
    data class Stats(val title: String, val items: List<Stat>) : CueWidget()
    data class Steps(val title: String, val items: List<String>) : CueWidget()
    data class Progress(val title: String, val items: List<Prog>) : CueWidget()
    data class Callout(val tone: String, val title: String, val text: String) : CueWidget()
    data class KeyValue(val title: String, val items: List<Pair<String, String>>) : CueWidget()
    data class ConnectorPanel(val id: String, val title: String, val collapsed: Boolean) : CueWidget()
    object Skeleton : CueWidget() // JSON ainda chegando (stream)

    data class Btn(val label: String, val send: String, val copy: String, val url: String, val tone: String)
    data class Ser(val name: String, val values: List<Double>, val color: String)
    data class CalcVar(val id: String, val label: String, val default: Double, val min: Double?, val max: Double?, val step: Double)
    data class Stat(val label: String, val value: String, val tone: String)
    data class Prog(val label: String, val value: Double, val tone: String)
}

object CueUi {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Tipos conhecidos — basta ver o "type" para mostrar o esqueleto certo durante o stream. */
    val TYPES = setOf("buttons", "choices", "chart", "calculator", "calc", "table", "stats", "steps", "progress", "callout", "note", "keyvalue", "kv", "connector", "painel")

    private fun JsonObject.str(vararg k: String, def: String = "") = k.firstNotNullOfOrNull { this[it] as? JsonPrimitive }?.contentOrNull ?: def
    private fun JsonObject.num(vararg k: String): Double? = k.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() } }
    private fun JsonObject.arr(vararg k: String): JsonArray? = k.firstNotNullOfOrNull { this[it] as? JsonArray }
    private fun strList(a: JsonArray?): List<String> = a?.map { (it as? JsonPrimitive)?.contentOrNull ?: "" }.orEmpty()
    private fun numList(a: JsonArray?): List<Double> = a?.mapNotNull { (it as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() } }.orEmpty()

    /**
     * Converte o conteúdo de um bloco cueui em widget. [complete] = o bloco já fechou (```).
     * Durante o stream (incompleto), tenta mesmo assim; se ainda não dá, devolve Skeleton (nunca null/JSON cru).
     */
    fun parse(raw: String, complete: Boolean): CueWidget {
        val text = raw.trim().removePrefix("```cueui").removePrefix("```").removeSuffix("```").trim()
        if (text.isBlank()) return CueWidget.Skeleton
        val o = runCatching { json.parseToJsonElement(repair(text)) as? JsonObject }.getOrNull()
            ?: return if (complete) errorCallout() else CueWidget.Skeleton
        val type = o.str("type").lowercase()
        if (type.isEmpty()) return CueWidget.Skeleton
        if (type !in TYPES) return if (complete) errorCallout() else CueWidget.Skeleton
        return runCatching { build(type, o) }.getOrElse { if (complete) errorCallout() else CueWidget.Skeleton }
    }

    private fun errorCallout() = CueWidget.Callout("warn", "Elemento inválido", "O agente enviou um elemento que não pôde ser exibido.")

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

    private fun build(type: String, o: JsonObject): CueWidget = when (type) {
        "buttons", "choices" -> CueWidget.Buttons(
            (o.arr("items", "buttons", "options")?.map { e ->
                when (e) {
                    is JsonObject -> CueWidget.Btn(e.str("label", "text", def = "—"), e.str("send", "message", "value"), e.str("copy"), e.str("url", "link"), e.str("tone", def = "neutral"))
                    is JsonPrimitive -> e.contentOrNull.orEmpty().let { CueWidget.Btn(it, it, "", "", "neutral") }
                    else -> CueWidget.Btn("", "", "", "", "neutral")
                }
            } ?: emptyList()).filter { it.label.isNotBlank() })
        "chart" -> {
            val series = o.arr("series")?.mapNotNull { it as? JsonObject }?.map {
                CueWidget.Ser(it.str("name"), numList(it.arr("values", "data", "points")), it.str("color"))
            } ?: o.arr("values", "data")?.let { listOf(CueWidget.Ser("", numList(it), "")) } ?: emptyList()
            CueWidget.Chart(o.str("title"), o.str("style", "kind", def = "bar").lowercase(), series, strList(o.arr("labels", "x")))
        }
        "calculator", "calc" -> CueWidget.Calculator(o.str("title", def = "Calculadora"), o.str("expr", "formula", "expression"),
            o.arr("vars", "inputs", "fields")?.mapNotNull { it as? JsonObject }?.map {
                CueWidget.CalcVar(it.str("id", "name", def = "x"), it.str("label", def = it.str("id", "name", def = "x")),
                    it.num("default", "value") ?: 0.0, it.num("min"), it.num("max"), it.num("step") ?: 1.0)
            }.orEmpty(), o.str("unit", "suffix"))
        "table" -> CueWidget.Table(o.str("title"), strList(o.arr("columns", "headers", "cols")),
            o.arr("rows")?.map { r -> (r as? JsonArray)?.let { strList(it) } ?: ((r as? JsonObject)?.let { ro -> strList(o.arr("columns")).map { ro.str(it) } } ?: emptyList()) }?.filter { it.isNotEmpty() }.orEmpty())
        "stats" -> CueWidget.Stats(o.str("title"), o.arr("items", "stats")?.mapNotNull { it as? JsonObject }?.map {
            CueWidget.Stat(it.str("label", "name", def = "—"), it.str("value", def = "—"), it.str("tone", def = "neutral")) }.orEmpty())
        "steps" -> CueWidget.Steps(o.str("title"), o.arr("items", "steps")?.let { a ->
            a.map { e -> (e as? JsonPrimitive)?.contentOrNull ?: (e as? JsonObject)?.str("title", "text", "label") ?: "" } }.orEmpty().filter { it.isNotBlank() })
        "progress" -> CueWidget.Progress(o.str("title"), o.arr("items", "bars")?.mapNotNull { it as? JsonObject }?.map {
            CueWidget.Prog(it.str("label", def = ""), (it.num("value", "percent", "pct") ?: 0.0).coerceIn(0.0, 100.0), it.str("tone", def = "neutral")) }
            ?: o.num("value", "percent")?.let { listOf(CueWidget.Prog(o.str("label"), it.coerceIn(0.0, 100.0), o.str("tone", def = "neutral"))) }.orEmpty())
        "callout", "note" -> CueWidget.Callout(o.str("tone", def = "info"), o.str("title"), o.str("text", "message", "body"))
        "connector", "painel" -> CueWidget.ConnectorPanel(o.str("id", "connector", "conector"), o.str("title"), o.str("collapsed", "collapsado", def = "false").equals("true", true))
        "keyvalue", "kv" -> CueWidget.KeyValue(o.str("title"), o.arr("items", "pairs")?.mapNotNull { it as? JsonObject }?.map {
            it.str("key", "label", "name", def = "—") to it.str("value", "val", def = "—") }
            ?: (o["data"] as? JsonObject)?.entries?.map { it.key to ((it.value as? JsonPrimitive)?.contentOrNull ?: "") }.orEmpty())
        else -> CueWidget.Skeleton
    }

    /** Avalia a expressão da calculadora com os valores atuais (reaproveita o motor Compute, isolado e seguro). */
    fun evalCalc(expr: String, values: Map<String, Double>): String = runCatching {
        val r = Compute.eval(expr, values.mapValues { it.value as Any? }, null)
        Compute.str(r)
    }.getOrElse { "—" }
}
