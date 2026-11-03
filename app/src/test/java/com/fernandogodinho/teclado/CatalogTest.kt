package com.fernandogodinho.teclado

import com.fernandogodinho.teclado.models.ModelCatalog
import com.fernandogodinho.teclado.updates.UpdateChecker
import com.fernandogodinho.teclado.core.SecureHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogTest {

    private fun model(org: String = "ggml-org", file: String = "x.gguf", sha: String = "a".repeat(64), rev: String = "b".repeat(40)) = """
        {"id":"new-model","kind":"text","name":"Novo","badge":"Novo","description":"d","repo":"$org/repo",
         "revision":"$rev","file":"$file","size":123456789,"sha256":"$sha","license":"Apache 2.0",
         "minRamGb":4,"quality":4,"speed":4,"supersedes":["qwen3.5-2b-q4"]}
    """.trimIndent()

    private fun catalog(vararg models: String) = """{"schema":1,"catalogVersion":2,"models":[${models.joinToString(",")}]}"""

    @Test
    fun acceptsWellFormedCatalog() {
        val parsed = ModelCatalog.parseRemote(catalog(model()))
        assertEquals(2, parsed.version)
        assertEquals("new-model", parsed.models.single().id)
        assertTrue(SecureHttp.isAllowedModelUrl(parsed.models.single().url))
    }

    @Test
    fun rejectsUntrustedOrMalformedEntries() {
        fun rejected(json: String) = runCatching { ModelCatalog.parseRemote(json) }.isFailure
        assertTrue(rejected(catalog(model(org = "random-user"))))
        assertTrue(rejected(catalog(model(file = "../../evil.gguf"))))
        assertTrue(rejected(catalog(model(file = "model.exe"))))
        assertTrue(rejected(catalog(model(sha = "abc"))))
        assertTrue(rejected(catalog(model(rev = "main"))))
        assertTrue(rejected("""{"schema":2,"catalogVersion":1,"models":[]}"""))
        assertTrue(rejected("x".repeat(70_000)))
    }

    @Test
    fun publishedCatalogIsValid() {
        val file = listOf("catalog/models.json", "../catalog/models.json").map(::File).firstOrNull { it.isFile } ?: return
        ModelCatalog.parseRemote(file.readText())
    }

    @Test
    fun updatesFollowSupersedes() {
        ModelCatalog.setRemote(ModelCatalog.parseRemote(catalog(model())).models)
        val updates = ModelCatalog.updatesFor(setOf("qwen3.5-2b-q4"))
        assertEquals("new-model", updates.single().second.id)
        ModelCatalog.setRemote(emptyList())
    }

    @Test
    fun networkAllowlist() {
        assertTrue(SecureHttp.isAllowedModelUrl("https://huggingface.co/ggml-org/x/resolve/abc/model.gguf"))
        assertTrue(SecureHttp.isAllowedModelUrl("https://us.aws.cdn.hf.co/xet-bridge-us/abc"))
        assertFalse(SecureHttp.isAllowedModelUrl("http://huggingface.co/x"))
        assertFalse(SecureHttp.isAllowedModelUrl("https://huggingface.co.evil.com/x"))
        assertFalse(SecureHttp.isAllowedModelUrl("https://evilhuggingface.co/x"))
        assertFalse(SecureHttp.isAllowedModelUrl("https://user@huggingface.co/x"))
    }

    @Test
    fun releaseInfoValidation() {
        val r = UpdateChecker.parseRelease("""{"versionName":"1.1.0","versionCode":10100,"notes":"Novidades"}""")
        assertEquals(10100, r.versionCode)
        assertTrue(runCatching { UpdateChecker.parseRelease("""{"versionName":"<script>","versionCode":1}""") }.isFailure)
    }
}
