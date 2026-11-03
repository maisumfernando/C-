package com.fernandogodinho.teclado

import com.fernandogodinho.teclado.ime.suggest.Dictionary
import com.fernandogodinho.teclado.ime.suggest.SwipeDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Simulates swipes over the real keyboard geometry (QWERTY + ç, 411 dp wide) with a shaky
 * finger, and checks that the intended words come out.
 */
class SwipeDecoderTest {

    companion object {
        private lateinit var decoder: SwipeDecoder
        private val centers = HashMap<Char, FloatArray>()
        private const val KEY = 40f // key width in px for this test
        private const val ROW = 55f

        @BeforeClass
        @JvmStatic
        fun load() {
            val dir = listOf("src/main/assets/dict", "app/src/main/assets/dict").map(::File).first { it.isDirectory }
            val dict = Dictionary.build(File(dir, "pt_br.txt").readLines(), File(dir, "en.txt").readLines())
            decoder = SwipeDecoder(dict.swipeEntries())
            // Same proportions as Layouts.letters(): 10 keys, 10 keys (with ç), shift(1.5) + 7 + backspace(1.5).
            "qwertyuiop".forEachIndexed { i, c -> centers[c] = floatArrayOf(KEY * (i + 0.5f), ROW * 0.5f) }
            "asdfghjklç".forEachIndexed { i, c -> centers[c] = floatArrayOf(KEY * (i + 0.5f), ROW * 1.5f) }
            "zxcvbnm".forEachIndexed { i, c -> centers[c] = floatArrayOf(KEY * (1.5f + i + 0.5f), ROW * 2.5f) }
        }

        /** A wobbly path through the word's keys, with noise and corner cutting like a real thumb. */
        fun swipe(word: String, rnd: Random, noise: Float = 0.22f): Pair<FloatArray, FloatArray> {
            val keys = SwipeDecoder.keySequence(word)!!
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            for (i in keys.indices) {
                val c = centers.getValue(keys[i])
                val tx = c[0] + (rnd.nextFloat() - 0.5f) * 2 * noise * KEY
                val ty = c[1] + (rnd.nextFloat() - 0.5f) * 2 * noise * ROW
                if (xs.isEmpty()) {
                    xs += tx; ys += ty
                    continue
                }
                val sx = xs.last(); val sy = ys.last()
                val steps = 6
                for (s in 1..steps) {
                    val t = s / steps.toFloat()
                    xs += sx + (tx - sx) * t + (rnd.nextFloat() - 0.5f) * 4f
                    ys += sy + (ty - sy) * t + (rnd.nextFloat() - 0.5f) * 4f
                }
            }
            return xs.toFloatArray() to ys.toFloatArray()
        }
    }

    private fun decode(word: String, seed: Int = 1): List<String> {
        val (xs, ys) = swipe(word, Random(seed))
        return decoder.decode(xs, ys, centers, KEY)
    }

    @Test
    fun keySequenceKeepsCedillaAndDropsAccents() {
        assertEquals("voce", SwipeDecoder.keySequence("você"))
        assertEquals("coraçao", SwipeDecoder.keySequence("coração"))
        assertEquals("caro", SwipeDecoder.keySequence("carro"))
        assertEquals(null, SwipeDecoder.keySequence("covid19"))
    }

    @Test
    fun decodesCommonWords() {
        listOf("você", "obrigado", "amanhã", "reunião", "trabalho", "coração", "também", "porque", "quando", "depois").forEach { w ->
            val result = decode(w)
            assertTrue("$w -> $result", w in result.take(3))
        }
    }

    @Test
    fun accuracyOnFrequentWords() {
        val words = listOf(
            "que", "não", "para", "com", "uma", "mais", "como", "mas", "foi", "ele", "das", "tem", "seu", "sua", "ser",
            "quando", "muito", "nos", "já", "está", "também", "pelo", "pela", "até", "isso", "ela", "entre", "era", "depois",
            "sem", "mesmo", "aos", "ter", "seus", "quem", "nas", "estão", "você", "tinha", "foram", "essa", "num", "nem",
            "suas", "meu", "minha", "têm", "numa", "pelos", "elas", "havia", "seja", "qual", "será", "nós", "tenho", "lhe",
            "deles", "essas", "esses", "pelas", "este", "fosse", "dele", "tu", "te", "vocês", "vos", "lhes", "meus", "minhas",
            "obrigado", "amanhã", "hoje", "ontem", "trabalho", "casa", "tudo", "bem", "bom", "dia", "noite", "tarde", "beijo",
            "saudade", "agora", "sempre", "nunca", "aqui", "onde", "porque", "então", "vamos", "pode", "fazer", "falar",
        )
        var hits = 0
        var top3 = 0
        for ((i, w) in words.withIndex()) {
            val result = decode(w, seed = i)
            if (result.firstOrNull() == w) hits++
            if (w in result.take(3)) top3++
        }
        val acc = hits.toFloat() / words.size
        val acc3 = top3.toFloat() / words.size
        println("swipe accuracy top1=$acc top3=$acc3")
        assertTrue("top-1 accuracy $acc", acc >= 0.75f)
        assertTrue("top-3 accuracy $acc3", acc3 >= 0.9f)
    }
}
