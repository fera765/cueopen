package dev.cueopen.app.uiconnector.custom

import kotlinx.serialization.json.Json

/**
 * Suíte de análise técnica (padrão TradingView) que NÃO busca dados: recebe velas de qualquer fonte
 * (IQ Option, Binance, CSV, texto) e devolve todos os indicadores com sinal de compra/venda e o resumo.
 * Roda direto na biblioteca nativa do motor [Compute] — sem interpretar expressões, em milissegundos.
 */
object TechnicalSuite {
    data class Row(val name: String, val value: Double?, val signal: String, val group: String)
    data class Result(val rows: List<Row>, val buys: Int, val sells: Int, val neutrals: Int, val summary: String, val price: Double?,
                      val maBuys: Int, val maSells: Int, val oscBuys: Int, val oscSells: Int, val candles: Int)

    class Candles(val t: List<Any?>, val o: List<Double?>, val h: List<Double?>, val l: List<Double?>, val c: List<Double?>, val v: List<Double?>) {
        val size get() = c.size
        fun toMap(): Map<String, Any?> = linkedMapOf("t" to t, "o" to o, "h" to h, "l" to l, "c" to c, "v" to v)
    }

    private val parser = Json { isLenient = true }
    private fun n(x: Any?) = Compute.numOrNull(x)

    /**
     * Normaliza velas em QUALQUER formato comum:
     * - lista de listas: [t, o, h, l, c, v, …] (Binance) ou [o, h, l, c, (v)];
     * - lista de objetos: open/o, high/h/max, low/l/min, close/c, volume/v/vol, time/t/from/timestamp;
     * - objeto de colunas: {open:[…], high:[…], low:[…], close:[…], volume:[…]} (ou o/h/l/c/v);
     * - texto (JSON, CSV ou linhas como a saída de get_candles): por linha, os últimos 5 números = o h l c v (ou 4 = o h l c).
     */
    fun parse(input: Any?): Candles {
        val data: Any? = if (input is String) {
            val t = input.trim()
            if (t.startsWith("[") || t.startsWith("{")) runCatching { Compute.fromJson(parser.parseToJsonElement(t)) }.getOrElse { t } else t
        } else input
        when (data) {
            is Map<*, *> -> {
                fun col(vararg k: String): List<Any?>? = k.firstNotNullOfOrNull { data[it] as? List<*> }
                val c = col("close", "c", "closes") ?: (data["candles"] ?: data["data"] ?: data["items"])?.let { return parse(it) }
                    ?: throw Compute.ComputeError("velas: objeto sem coluna close/c")
                val o = col("open", "o") ?: c; val h = col("high", "h", "max") ?: c; val l = col("low", "l", "min") ?: c
                val v = col("volume", "v", "vol") ?: List(c.size) { 0.0 }; val t = col("time", "t", "from", "timestamp") ?: List(c.size) { it.toDouble() }
                return Candles(t, o.map { n(it) }, h.map { n(it) }, l.map { n(it) }, c.map { n(it) }, v.map { n(it) ?: 0.0 })
            }
            is List<*> -> {
                val t = ArrayList<Any?>(); val o = ArrayList<Double?>(); val h = ArrayList<Double?>(); val l = ArrayList<Double?>(); val c = ArrayList<Double?>(); val v = ArrayList<Double?>()
                data.forEachIndexed { i, e ->
                    when (e) {
                        is List<*> -> if (e.size >= 6) { t += e[0]; o += n(e[1]); h += n(e[2]); l += n(e[3]); c += n(e[4]); v += n(e[5]) }
                            else if (e.size >= 4) { t += i.toDouble(); o += n(e[0]); h += n(e[1]); l += n(e[2]); c += n(e[3]); v += n(e.getOrNull(4)) ?: 0.0 }
                        is Map<*, *> -> {
                            fun g(vararg k: String) = k.firstNotNullOfOrNull { e[it]?.let { x -> n(x) } }
                            val cc = g("close", "c") ?: return@forEachIndexed
                            t += k(e, "time", "t", "from", "timestamp", "date") ?: i.toDouble()
                            o += g("open", "o") ?: cc; h += g("high", "h", "max") ?: cc; l += g("low", "l", "min") ?: cc; c += cc; v += g("volume", "v", "vol") ?: 0.0
                        }
                        is Double -> { t += i.toDouble(); o += e; h += e; l += e; c += e; v += 0.0 } // só fechamentos
                        else -> {}
                    }
                }
                if (c.isEmpty()) throw Compute.ComputeError("velas: lista sem velas reconhecíveis")
                return Candles(t, o, h, l, c, v)
            }
            is String -> {
                val t = ArrayList<Any?>(); val o = ArrayList<Double?>(); val h = ArrayList<Double?>(); val l = ArrayList<Double?>(); val c = ArrayList<Double?>(); val v = ArrayList<Double?>()
                data.lineSequence().forEachIndexed { i, line ->
                    val nums = line.split(Regex("[\\s,;|]+")).mapNotNull { it.trim().toDoubleOrNull() }
                    if (nums.size >= 5) { val x = nums.takeLast(5); t += i.toDouble(); o += x[0]; h += x[1]; l += x[2]; c += x[3]; v += x[4] }
                    else if (nums.size == 4) { t += i.toDouble(); o += nums[0]; h += nums[1]; l += nums[2]; c += nums[3]; v += 0.0 }
                }
                if (c.size < 2) throw Compute.ComputeError("velas: texto sem linhas com open high low close (volume)")
                return Candles(t, o, h, l, c, v)
            }
            else -> throw Compute.ComputeError("velas: formato não reconhecido")
        }
    }

    private fun k(m: Map<*, *>, vararg keys: String): Any? = keys.firstNotNullOfOrNull { m[it] }

    private fun lastNum(x: Any?): Double? = (x as? List<*>)?.lastOrNull { it != null }?.let { n(it) } ?: n(x)
    private fun prevNum(x: Any?): Double? = (x as? List<*>)?.filterNotNull()?.let { if (it.size >= 2) n(it[it.size - 2]) else null }

    /** Calcula a suíte completa. Precisa de ~30 velas (médias de 200 só aparecem com 200+). */
    fun analyze(k: Candles): Result {
        val ctx = Compute.Ctx(emptyMap(), null)
        val o = k.o; val h = k.h; val l = k.l; val c = k.c; val v = k.v
        fun f(name: String, vararg a: Any?): Any? = runCatching { Compute.Lib.call(name, a.toList(), ctx) }.getOrNull()
        fun m(x: Any?, key: String) = (x as? Map<*, *>)?.get(key)
        val price = c.lastOrNull { it != null }
        val rows = ArrayList<Row>()
        fun sig(buy: Boolean, sell: Boolean) = if (buy) "compra" else if (sell) "venda" else "neutro"
        fun ma(name: String, value: Double?) {
            rows += Row(name, value, if (value == null || price == null) "neutro" else sig(price > value, price < value), "média")
        }
        for (p in listOf(10, 20, 30, 50, 100, 200)) {
            ma("SMA($p)", lastNum(f("sma", c, p.toDouble())))
            ma("EMA($p)", lastNum(f("ema", c, p.toDouble())))
        }
        ma("VWMA(20)", lastNum(f("vwma", c, v, 20.0)))
        ma("Hull MA(9)", lastNum(f("hma", c, 9.0)))
        ma("Ichimoku (base 26)", lastNum(m(f("ichimoku", h, l), "kijun")))
        ma("Parabolic SAR", lastNum(f("psar", h, l)))
        val sup = f("supertrend", h, l, c, 10.0, 3.0); val supd = lastNum(m(sup, "dir"))
        rows += Row("Supertrend(10,3)", lastNum(m(sup, "line")), sig(supd == 1.0, supd == -1.0), "média")

        fun osc(name: String, value: Double?, buy: Boolean, sell: Boolean) { rows += Row(name, value, if (value == null) "neutro" else sig(buy, sell), "oscilador") }
        val rsi = lastNum(f("rsi", c, 14.0)); osc("RSI(14)", rsi, (rsi ?: 50.0) < 30, (rsi ?: 50.0) > 70)
        val st = f("stoch", h, l, c, 14.0, 3.0, 3.0); val sk = lastNum(m(st, "k")); val sd = lastNum(m(st, "d"))
        osc("Estocástico %K(14,3,3)", sk, sk != null && sd != null && sk < 20 && sk > sd, sk != null && sd != null && sk > 80 && sk < sd)
        val sr = lastNum(m(f("stochrsi", c), "k")); osc("Stoch RSI(3,3,14,14)", sr, (sr ?: 50.0) < 20, (sr ?: 50.0) > 80)
        val mc = f("macd", c); val ml = lastNum(m(mc, "macd")); val ms = lastNum(m(mc, "signal"))
        osc("MACD(12,26,9)", ml, ml != null && ms != null && ml > ms, ml != null && ms != null && ml < ms)
        val cci = lastNum(f("cci", h, l, c, 20.0)); osc("CCI(20)", cci, (cci ?: 0.0) < -100, (cci ?: 0.0) > 100)
        val ax = f("adx", h, l, c, 14.0); val adx = lastNum(m(ax, "adx")); val pdi = lastNum(m(ax, "pdi")) ?: 0.0; val mdi = lastNum(m(ax, "mdi")) ?: 0.0
        osc("ADX(14)", adx, (adx ?: 0.0) > 20 && pdi > mdi, (adx ?: 0.0) > 20 && pdi < mdi)
        val wr = lastNum(f("willr", h, l, c, 14.0)); osc("Williams %R(14)", wr, (wr ?: -50.0) < -80, (wr ?: -50.0) > -20)
        val ao = f("ao", h, l); val aov = lastNum(ao); val aop = prevNum(ao)
        osc("Awesome Oscillator", aov, aov != null && aop != null && aov > 0 && aov > aop, aov != null && aop != null && aov < 0 && aov < aop)
        val mom = lastNum(f("mom", c, 10.0)); osc("Momentum(10)", mom, (mom ?: 0.0) > 0, (mom ?: 0.0) < 0)
        val uo = lastNum(f("uo", h, l, c)); osc("Ultimate Oscillator(7,14,28)", uo, (uo ?: 50.0) > 70, (uo ?: 50.0) < 30)
        val el = f("elder", h, l, c); val bbp = lastNum(m(el, "bull"))?.let { b -> lastNum(m(el, "bear"))?.let { b + it } }
        osc("Bull/Bear Power", bbp, (bbp ?: 0.0) > 0, (bbp ?: 0.0) < 0)
        val mfi = lastNum(f("mfi", h, l, c, v, 14.0)); osc("MFI(14)", mfi, (mfi ?: 50.0) < 20, (mfi ?: 50.0) > 80)
        val cmf = lastNum(f("cmf", h, l, c, v, 20.0)); osc("Chaikin Money Flow(20)", cmf, (cmf ?: 0.0) > 0.05, (cmf ?: 0.0) < -0.05)
        val bb = lastNum(m(f("bollinger", c, 20.0, 2.0), "pctb")); osc("Bollinger %B(20,2)", bb, (bb ?: 0.5) < 0, (bb ?: 0.5) > 1)
        val cmo = lastNum(f("cmo", c, 9.0)); osc("CMO(9)", cmo, (cmo ?: 0.0) < -50, (cmo ?: 0.0) > 50)
        val roc = lastNum(f("roc", c, 9.0)); osc("ROC(9)", roc, (roc ?: 0.0) > 0, (roc ?: 0.0) < 0)
        val trix = lastNum(f("trix", c, 18.0)); osc("TRIX(18)", trix, (trix ?: 0.0) > 0, (trix ?: 0.0) < 0)
        val ar = f("aroon", h, l, 14.0); val au = lastNum(m(ar, "up")); val ad = lastNum(m(ar, "down"))
        osc("Aroon(14)", au?.let { a -> ad?.let { a - it } }, au != null && ad != null && au > 70 && au > ad, au != null && ad != null && ad > 70 && ad > au)
        val atr = lastNum(f("atr", h, l, c, 14.0)); rows += Row("ATR(14)", atr, "neutro", "volatilidade")
        val chop = lastNum(f("chop", h, l, c, 14.0)); rows += Row("Choppiness(14)", chop, "neutro", "volatilidade")

        val valid = rows.filter { it.value != null && it.group != "volatilidade" }
        val buys = valid.count { it.signal == "compra" }; val sells = valid.count { it.signal == "venda" }
        val neutral = valid.size - buys - sells
        val score = if (valid.isEmpty()) 0.0 else (buys - sells).toDouble() / valid.size
        val summary = when { valid.isEmpty() -> "Dados insuficientes"; score >= 0.5 -> "Forte compra"; score >= 0.1 -> "Compra"; score <= -0.5 -> "Forte venda"; score <= -0.1 -> "Venda"; else -> "Neutro" }
        val mas = valid.filter { it.group == "média" }; val oscs = valid.filter { it.group == "oscilador" }
        return Result(rows, buys, sells, neutral, summary, price,
            mas.count { it.signal == "compra" }, mas.count { it.signal == "venda" }, oscs.count { it.signal == "compra" }, oscs.count { it.signal == "venda" }, k.size)
    }

    fun toValue(r: Result): Map<String, Any?> = linkedMapOf(
        "rows" to r.rows.map { linkedMapOf("nome" to it.name, "valor" to it.value?.let { v -> Compute.str(round(v)) }.orEmpty(), "sinal" to it.signal, "grupo" to it.group) },
        "compras" to r.buys.toDouble(), "vendas" to r.sells.toDouble(), "neutros" to r.neutrals.toDouble(), "resumo" to r.summary,
        "preco" to r.price, "medias" to "${r.maBuys} compra · ${r.maSells} venda", "osciladores" to "${r.oscBuys} compra · ${r.oscSells} venda",
        "velas" to r.candles.toDouble(),
    )

    private fun round(v: Double): Double = if (kotlin.math.abs(v) >= 1000) Math.round(v * 100) / 100.0 else java.math.BigDecimal(v).round(java.math.MathContext(6)).toDouble()

    /** Texto para o agente. */
    fun report(r: Result, title: String): String = buildString {
        appendLine("$title — ${r.candles} velas — preço ${r.price?.let { Compute.str(it) } ?: "?"}")
        appendLine("RESUMO: ${r.summary} (${r.buys} compra, ${r.sells} venda, ${r.neutrals} neutro) · médias ${r.maBuys}/${r.maSells} · osciladores ${r.oscBuys}/${r.oscSells}")
        r.rows.forEach { appendLine("${it.name}: ${it.value?.let { v -> Compute.str(round(v)) } ?: "—"} → ${it.signal}") }
    }.trim()
}
