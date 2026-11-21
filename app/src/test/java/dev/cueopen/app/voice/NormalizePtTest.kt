package dev.cueopen.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizePtTest {
    @Test fun dinheiroHorasDatas() {
        assertEquals("Sua reunião é às dez horas e trinta com o doutor Paulo.", NormalizePt.normalize("Sua reunião é às 10h30 com o Dr. Paulo."))
        assertEquals("O total ficou mil duzentos e cinquenta reais e noventa centavos (vinte e cinco por cento de desconto) no dia quinze de dezembro de dois mil e vinte e seis.",
            NormalizePt.normalize("O total ficou R$ 1.250,90 (25% de desconto) no dia 15/12/2026."))
        assertEquals("Faltam duas horas, vinte e um minutos e um milhão de passos!", NormalizePt.normalize("Faltam 2 horas, 21 min e 1.000.000 de passos!"))
        assertEquals("Chegaram três i-meils; o segundo é do cliente", NormalizePt.normalize("Chegaram 3 e-mails; o 2º é do cliente"))
    }
}
