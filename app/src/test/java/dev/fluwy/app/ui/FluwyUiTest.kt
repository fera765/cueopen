package dev.fluwy.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FluwyUiTest {
    @Test fun `parse cada tipo de elemento`() {
        assertTrue(FluwyUi.parse("""{"type":"buttons","items":[{"label":"Sim","send":"sim"},{"label":"Abrir","url":"https://x.com"}]}""", true) is FluwyWidget.Buttons)
        val ch = FluwyUi.parse("""{"type":"chart","style":"line","series":[{"name":"A","values":[1,2,3]}]}""", true) as FluwyWidget.Chart
        assertEquals(listOf(1.0, 2.0, 3.0), ch.series[0].values)
        val calc = FluwyUi.parse("""{"type":"calculator","expr":"a+b","vars":[{"id":"a","default":2},{"id":"b","default":3}]}""", true) as FluwyWidget.Calculator
        assertEquals("5", FluwyUi.evalCalc(calc.expr, mapOf("a" to 2.0, "b" to 3.0)))
        assertTrue(FluwyUi.parse("""{"type":"table","columns":["A"],"rows":[["1"]]}""", true) is FluwyWidget.Table)
        assertTrue(FluwyUi.parse("""{"type":"stats","items":[{"label":"X","value":"9"}]}""", true) is FluwyWidget.Stats)
        assertTrue(FluwyUi.parse("""{"type":"steps","items":["um","dois"]}""", true) is FluwyWidget.Steps)
        assertTrue(FluwyUi.parse("""{"type":"progress","items":[{"label":"p","value":40}]}""", true) is FluwyWidget.Progress)
        assertTrue(FluwyUi.parse("""{"type":"callout","tone":"good","text":"ok"}""", true) is FluwyWidget.Callout)
        assertTrue(FluwyUi.parse("""{"type":"keyvalue","items":[{"key":"k","value":"v"}]}""", true) is FluwyWidget.KeyValue)
    }

    @Test fun `aceita campos alternativos e enum`() {
        val b = FluwyUi.parse("""{"type":"choices","options":[{"text":"A","message":"a"}]}""", true) as FluwyWidget.Buttons
        assertEquals("A", b.items[0].label); assertEquals("a", b.items[0].send)
        val b2 = FluwyUi.parse("""{"type":"buttons","items":["X","Y"]}""", true) as FluwyWidget.Buttons
        assertEquals(2, b2.items.size); assertEquals("X", b2.items[0].send)
    }

    /** BACKTEST DE STREAM: cada prefixo crescente de um elemento NUNCA quebra e nunca vira JSON cru. */
    @Test fun `stream progressivo nunca falha`() {
        val samples = listOf(
            """{"type":"buttons","items":[{"label":"Comprar","send":"comprar"},{"label":"Vender","send":"vender"}]}""",
            """{"type":"chart","style":"bar","labels":["seg","ter"],"series":[{"name":"vendas","values":[10,20,30]}]}""",
            """{"type":"calculator","title":"Lucro","expr":"preco*qtd-custo","vars":[{"id":"preco","default":10},{"id":"qtd","default":3},{"id":"custo","default":5}]}""",
            """{"type":"stats","items":[{"label":"Win","value":"62%","tone":"good"},{"label":"Loss","value":"38%","tone":"bad"}]}""",
            """{"type":"table","columns":["Ativo","Sinal"],"rows":[["EURUSD","compra"],["GBPUSD","venda"]]}""",
        )
        for (full in samples) {
            for (i in 1..full.length) {
                val prefix = full.substring(0, i)
                // durante o stream o bloco está aberto (complete=false)
                val w = FluwyUi.parse(prefix, complete = false)
                assertTrue("prefixo '$prefix' não deve ser nulo", w is FluwyWidget)
                // o final completo tem de resolver no tipo certo
            }
            val done = FluwyUi.parse(full, complete = true)
            assertTrue(done !is FluwyWidget.Skeleton)
        }
    }

    @Test fun `json truncado no meio vira esqueleto e nao estoura`() {
        val truncs = listOf("""{"type":"chart","series":[{"values":[1,2""", """{"type":"table","rows":[["a","b"],["c""", """{"type":"calc","expr":"a+""", """{"typ""")
        truncs.forEach { assertTrue(FluwyUi.parse(it, false) is FluwyWidget) }
    }

    @Test fun `painel de conector`() {
        val w = FluwyUi.parse("""{"type":"connector","id":"iqoption","title":"Operações","collapsed":true}""", true) as FluwyWidget.ConnectorPanel
        assertEquals("iqoption", w.id); assertEquals(true, w.collapsed)
    }

    @Test fun `tipo desconhecido completo vira aviso, nao JSON cru`() {
        assertTrue(FluwyUi.parse("""{"type":"foobar","x":1}""", true) is FluwyWidget.Callout)
        assertTrue(FluwyUi.parse("texto que nao e json", true) is FluwyWidget.Callout)
    }

    @Test fun `calculadora usa o motor Compute com funcoes`() {
        assertEquals("100", FluwyUi.evalCalc("pow(base, exp)", mapOf("base" to 10.0, "exp" to 2.0)))
        assertEquals("7.5", FluwyUi.evalCalc("(a+b)/2", mapOf("a" to 5.0, "b" to 10.0)))
        assertEquals("—", FluwyUi.evalCalc("1/0 == null ? funcao_inexistente() : 0", emptyMap())) // erro → "—", nunca crash
    }
}
