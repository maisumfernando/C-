package com.fernandogodinho.teclado

import com.fernandogodinho.teclado.engine.SpokenText
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenTextTest {

    @Test
    fun verificationCodesFromWordsOrDigits() {
        assertEquals("482913", SpokenText.code("quatro oito dois, nove um três", allowLetters = false))
        assertEquals("482913", SpokenText.code("482 913.", allowLetters = false))
        assertEquals("482913", SpokenText.code("4, 8, 2, 9, 1, 3", allowLetters = false))
        assertEquals("606", SpokenText.code("meia zero meia", allowLetters = false))
        assertEquals("123456", SpokenText.code("O código é 123-456", allowLetters = false))
        assertEquals("G58A2", SpokenText.code("G 58A2", allowLetters = true))
    }

    @Test
    fun spelledPasswords() {
        assertEquals("Abc12@", SpokenText.secret("A maiúsculo, b, c, um, dois, arroba"))
        assertEquals("cachorro#2025", SpokenText.secret("Cachorro cerquilha 2025"))
        assertEquals("Kx_9!", SpokenText.secret("maiúsculo cá xis underline nove ponto de exclamação"))
        assertEquals("Senha123", SpokenText.secret("Senha123"))
    }

    @Test
    fun emailAndLinks() {
        assertEquals("fernando.godinho@gmail.com", SpokenText.address("fernando ponto godinho arroba gmail ponto com"))
        assertEquals("fernando@gmail.com", SpokenText.address("Fernando@gmail.com."))
        assertEquals("site.com.br/contato", SpokenText.address("site ponto com ponto br barra contato"))
    }
}
