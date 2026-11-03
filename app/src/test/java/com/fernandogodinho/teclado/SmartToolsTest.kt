package com.fernandogodinho.teclado

import com.fernandogodinho.teclado.core.Snippet
import com.fernandogodinho.teclado.core.SnippetStore
import com.fernandogodinho.teclado.ime.smart.Calculator
import com.fernandogodinho.teclado.ime.smart.Reminders
import com.fernandogodinho.teclado.ime.smart.SensitiveData
import com.fernandogodinho.teclado.ime.smart.UnitConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SmartToolsTest {

    @Test
    fun calculatorEvaluatesWhatWasTyped() {
        assertEquals("40", Calculator.detect("quanto dá 12*3+4=")?.result)
        assertEquals("37,5", Calculator.detect("12,5 × 3 =")?.result)
        assertEquals("1.300,5", Calculator.detect("1.250,50 + 50 =")?.result)
        assertEquals("30", Calculator.detect("200 * 15% =")?.result)
        assertEquals("2,5", Calculator.detect("(2+3)/2=")?.result?.let { if (it == "2.5") "2,5" else it })
        assertEquals("1024", Calculator.detect("2^10=")?.result)
        assertEquals("6", Calculator.detect("max 2 x 3 =")?.result)
        assertNull(Calculator.detect("12*3+4"))      // no "=" yet
        assertNull(Calculator.detect("tudo = bom"))   // not a calculation
        assertNull(Calculator.detect("-5 ="))
        assertNull(Calculator.detect("10/0 ="))
    }

    @Test
    fun convertsUnits() {
        assertEquals("6,2137 mi", UnitConverter.detect("10 km em milhas")?.insert)
        assertEquals("86 °F", UnitConverter.detect("30 °C para fahrenheit")?.insert)
        assertEquals("5,5116 lb", UnitConverter.detect("2,5 kg em libras")?.insert)
        assertEquals("27,7778 m/s", UnitConverter.detect("100 km/h em m/s")?.insert)
        assertEquals("2 h", UnitConverter.detect("120 minutos em horas =")?.insert)
        assertNull(UnitConverter.detect("10 km em kg"))       // different quantities
        assertNull(UnitConverter.detect("comprei 10 maçãs"))
    }

    @Test
    fun detectsSensitiveData() {
        assertEquals(SensitiveData.Kind.CARD, SensitiveData.detect("meu cartão 4111 1111 1111 1111 ok"))
        assertEquals(SensitiveData.Kind.CPF, SensitiveData.detect("CPF 529.982.247-25"))
        assertEquals(SensitiveData.Kind.CNPJ, SensitiveData.detect("CNPJ 11.222.333/0001-81"))
        assertNull(SensitiveData.detect("ligue 11 98765-4321"))
        assertNull(SensitiveData.detect("CPF 111.111.111-11"))
        assertNull(SensitiveData.detect("pedido 1234567890123")) // fails Luhn
    }

    @Test
    fun parsesReminders() {
        val now = LocalDateTime.of(2026, 11, 3, 20, 0) // a Tuesday evening
        val r = Reminders.parse("lembrete amanhã às 8h ligar para o banco", now) as Reminders.Request.Event
        assertEquals(LocalDateTime.of(2026, 11, 4, 8, 0), r.at)
        assertEquals("Ligar para o banco", r.title)
        val pm = Reminders.parse("/lembrete sexta 3 da tarde reunião com a Ana", now) as Reminders.Request.Event
        assertEquals(LocalDateTime.of(2026, 11, 6, 15, 0), pm.at)
        assertTrue(pm.title.contains("Reunião com a Ana", ignoreCase = true))
        val t = Reminders.parse("/timer 10 minutos arroz", now) as Reminders.Request.Timer
        assertEquals(600, t.seconds)
        assertEquals("Arroz", t.title)
        val later = Reminders.parse("me lembre às 7h de tomar o remédio", now) as Reminders.Request.Event
        assertEquals(LocalDateTime.of(2026, 11, 4, 7, 0), later.at) // 7h already passed today
        assertNull(Reminders.parse("lembrete de que a vida é bela", now))
        assertNull(Reminders.parse("amanhã às 8h", now)) // no command word
    }

    @Test
    fun snippetShortcutsAreNormalisedAndRoundTrip() {
        assertEquals("endereco", SnippetStore.normalizeShortcut("/Endereço"))
        val list = listOf(Snippet(shortcut = "ass", title = "Assinatura", text = "Fernando\nGodinho", category = "Trabalho", sensitive = false))
        val back = SnippetStore.fromJson(SnippetStore.toJson(list))
        assertEquals(list, back)
    }
}
