package com.fernandogodinho.teclado.models

import org.json.JSONArray
import org.json.JSONObject

/** What a model is used for. */
enum class ModelKind { SPEECH, TEXT }

/**
 * A downloadable model. Every file is pinned to an exact repository revision and verified
 * against its SHA-256 after download, so the app only ever runs the exact bytes listed here.
 */
data class ModelSpec(
    val id: String,
    val kind: ModelKind,
    val name: String,
    val badge: String,
    val description: String,
    val repo: String,
    val revision: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    /** Minimum device RAM (GB) for a comfortable experience. */
    val minRamGb: Int,
    /** Relative quality and speed on a 1..5 scale, shown as bars in the UI. */
    val quality: Int,
    val speed: Int,
    /** Older model ids this one replaces (offered as an update to people who have them). */
    val supersedes: List<String> = emptyList(),
    val builtIn: Boolean = true,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$revision/$fileName"
    val sourcePage: String get() = "https://huggingface.co/$repo"
    val localFileName: String get() = "$id.${fileName.substringAfterLast('.')}"
}

object ModelCatalog {

    private const val WHISPER_REPO = "ggerganov/whisper.cpp"
    private const val WHISPER_REV = "5359861c739e955e79d9a303bcbc70fb988958b1"

    private val builtInSpeech: List<ModelSpec> = listOf(
        ModelSpec(
            id = "whisper-large-v3-turbo-q5",
            kind = ModelKind.SPEECH,
            name = "Whisper Large v3 Turbo",
            badge = "Máxima precisão",
            description = "O modelo de voz mais preciso, excelente em português do Brasil, termos em inglês e nomes. Ideal para celulares topo de linha.",
            repo = WHISPER_REPO,
            revision = WHISPER_REV,
            fileName = "ggml-large-v3-turbo-q5_0.bin",
            sizeBytes = 574_041_195L,
            sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
            license = "MIT (OpenAI Whisper)",
            minRamGb = 8,
            quality = 5,
            speed = 3,
        ),
        ModelSpec(
            id = "whisper-small-q8",
            kind = ModelKind.SPEECH,
            name = "Whisper Small",
            badge = "Equilibrado",
            description = "Boa precisão em português com resposta rápida. Recomendado para a maioria dos celulares.",
            repo = WHISPER_REPO,
            revision = WHISPER_REV,
            fileName = "ggml-small-q8_0.bin",
            sizeBytes = 264_464_607L,
            sha256 = "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f",
            license = "MIT (OpenAI Whisper)",
            minRamGb = 4,
            quality = 4,
            speed = 4,
        ),
        ModelSpec(
            id = "whisper-base-q8",
            kind = ModelKind.SPEECH,
            name = "Whisper Base",
            badge = "Ultrarrápido",
            description = "Muito leve e veloz, ideal para celulares simples. Comete mais erros; a revisão por IA compensa parte deles.",
            repo = WHISPER_REPO,
            revision = WHISPER_REV,
            fileName = "ggml-base-q8_0.bin",
            sizeBytes = 81_768_585L,
            sha256 = "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9",
            license = "MIT (OpenAI Whisper)",
            minRamGb = 2,
            quality = 2,
            speed = 5,
        ),
    )

    private val builtInText: List<ModelSpec> = listOf(
        ModelSpec(
            id = "gemma-4-e2b-q4",
            kind = ModelKind.TEXT,
            name = "Gemma 4 E2B",
            badge = "Recomendado",
            description = "Modelo do Google com ótimo domínio do português. Revisa gramática, pontuação e estilo com naturalidade.",
            repo = "ggml-org/gemma-4-E2B-it-GGUF",
            revision = "6938c58e698502037401a5f19f6905f2fb2f7dee",
            fileName = "gemma-4-E2B-it-Q4_0.gguf",
            sizeBytes = 2_841_481_184L,
            sha256 = "8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52",
            license = "Apache 2.0",
            minRamGb = 6,
            quality = 4,
            speed = 4,
        ),
        ModelSpec(
            id = "qwen3.5-4b-q4",
            kind = ModelKind.TEXT,
            name = "Qwen 3.5 4B",
            badge = "Máxima qualidade",
            description = "O revisor mais capaz: entende contexto, autocorreções e termos técnicos. Mais lento e exige mais memória.",
            repo = "unsloth/Qwen3.5-4B-GGUF",
            revision = "e87f176479d0855a907a41277aca2f8ee7a09523",
            fileName = "Qwen3.5-4B-Q4_K_M.gguf",
            sizeBytes = 2_740_937_888L,
            sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
            license = "Apache 2.0",
            minRamGb = 8,
            quality = 5,
            speed = 3,
        ),
        ModelSpec(
            id = "qwen3.5-2b-q4",
            kind = ModelKind.TEXT,
            name = "Qwen 3.5 2B",
            badge = "Leve e rápido",
            description = "Compacto e veloz. Ótimo para remover vícios de linguagem e pontuar; revisões mais simples.",
            repo = "unsloth/Qwen3.5-2B-GGUF",
            revision = "f6d5376be1edb4d416d56da11e5397a961aca8ae",
            fileName = "Qwen3.5-2B-Q4_K_M.gguf",
            sizeBytes = 1_280_835_840L,
            sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
            license = "Apache 2.0",
            minRamGb = 4,
            quality = 3,
            speed = 5,
        ),
        ModelSpec(
            id = "qwen3.5-0.8b-q8",
            kind = ModelKind.TEXT,
            name = "Qwen 3.5 0.8B",
            badge = "Para celulares simples",
            description = "O menor revisor: tira muletas e pontua bem, com correções mais básicas. Roda em aparelhos com pouca memória.",
            repo = "ggml-org/Qwen3.5-0.8B-GGUF",
            revision = "807dfd9a6fadbf703207aac3bfb0b2b3040d077c",
            fileName = "Qwen3.5-0.8B-Q8_0.gguf",
            sizeBytes = 833_592_096L,
            sha256 = "37ae482d336108d23516fa35e8e0c4126688d81018b87178a18d752a1357814f",
            license = "Apache 2.0",
            minRamGb = 3,
            quality = 2,
            speed = 5,
        ),
    )

    /** Extra models announced by the validated online catalog (see [parseRemote]). */
    @Volatile
    private var remote: List<ModelSpec> = emptyList()

    val builtIn: List<ModelSpec> get() = builtInSpeech + builtInText
    val all: List<ModelSpec> get() = builtIn + remote
    val speechModels: List<ModelSpec> get() = all.filter { it.kind == ModelKind.SPEECH }
    val textModels: List<ModelSpec> get() = all.filter { it.kind == ModelKind.TEXT }

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }

    fun setRemote(models: List<ModelSpec>) {
        val builtInIds = builtIn.map { it.id }.toSet()
        remote = models.filter { it.id !in builtInIds }
    }

    /** Newer models that replace one the user already has installed. */
    fun updatesFor(installed: Set<String>): List<Pair<ModelSpec, ModelSpec>> =
        all.filter { it.id !in installed }.flatMap { newer ->
            newer.supersedes.filter { it in installed }.mapNotNull { oldId -> byId(oldId)?.let { it to newer } }
        }

    /** Picks the best models for the device's memory. */
    fun recommended(totalRamGb: Double): Pair<ModelSpec, ModelSpec> {
        fun latest(id: String): ModelSpec {
            val base = all.first { it.id == id }
            // Follow "supersedes" links so recommendations move to newer models automatically.
            return all.firstOrNull { base.id in it.supersedes } ?: base
        }
        val speech = when {
            totalRamGb >= 7.5 -> latest("whisper-large-v3-turbo-q5")
            totalRamGb >= 3.5 -> latest("whisper-small-q8")
            else -> latest("whisper-base-q8")
        }
        val text = when {
            totalRamGb >= 5.5 -> latest("gemma-4-e2b-q4")
            totalRamGb >= 3.5 -> latest("qwen3.5-2b-q4")
            else -> latest("qwen3.5-0.8b-q8")
        }
        return speech to text
    }

    // -------------------------------------------------------------------------------------------
    // Online catalog validation. The catalog can only *add* models, and every field is checked:
    // files must come from trusted Hugging Face organisations, be pinned to a full commit hash
    // and carry a SHA-256 that the downloader enforces. Anything malformed is rejected whole.
    // -------------------------------------------------------------------------------------------

    private val trustedOrgs = setOf("ggerganov", "ggml-org", "unsloth", "Qwen", "google", "openai")
    private val idPattern = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val repoPattern = Regex("^([A-Za-z0-9-]{1,40})/[A-Za-z0-9._-]{1,96}$")
    private val revisionPattern = Regex("^[0-9a-f]{40}$")
    private val shaPattern = Regex("^[0-9a-f]{64}$")
    private val filePattern = Regex("^[A-Za-z0-9._-]{1,120}\\.(bin|gguf)$")

    data class RemoteCatalog(val version: Int, val models: List<ModelSpec>)

    /** Parses and validates the online catalog. Throws [IllegalArgumentException] if anything is off. */
    fun parseRemote(json: String): RemoteCatalog {
        require(json.length <= 64 * 1024) { "catálogo grande demais" }
        val root = JSONObject(json)
        require(root.getInt("schema") == 1) { "versão de catálogo não suportada" }
        val version = root.getInt("catalogVersion")
        val array: JSONArray = root.getJSONArray("models")
        require(array.length() <= 40) { "modelos demais" }
        val models = (0 until array.length()).map { i -> parseModel(array.getJSONObject(i)) }
        require(models.map { it.id }.toSet().size == models.size) { "ids duplicados" }
        return RemoteCatalog(version, models)
    }

    private fun parseModel(o: JSONObject): ModelSpec {
        fun text(key: String, max: Int): String = o.getString(key).trim().also {
            require(it.isNotEmpty() && it.length <= max && it.none { c -> c.isISOControl() }) { "campo inválido: $key" }
        }
        val id = o.getString("id")
        require(idPattern.matches(id)) { "id inválido" }
        val kind = when (o.getString("kind")) {
            "speech" -> ModelKind.SPEECH
            "text" -> ModelKind.TEXT
            else -> throw IllegalArgumentException("tipo inválido")
        }
        val repo = o.getString("repo")
        val org = repoPattern.matchEntire(repo)?.groupValues?.get(1) ?: throw IllegalArgumentException("repo inválido")
        require(org in trustedOrgs) { "origem não confiável" }
        val revision = o.getString("revision")
        require(revisionPattern.matches(revision)) { "revisão inválida" }
        val file = o.getString("file")
        require(filePattern.matches(file) && !file.contains("..")) { "arquivo inválido" }
        require((kind == ModelKind.SPEECH) == file.endsWith(".bin")) { "formato inválido" }
        val size = o.getLong("size")
        require(size in 1_000_000L..8_000_000_000L) { "tamanho inválido" }
        val sha = o.getString("sha256")
        require(shaPattern.matches(sha)) { "sha256 inválido" }
        val supersedes = o.optJSONArray("supersedes")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.onEach { require(idPattern.matches(it)) { "supersedes inválido" } }
        }.orEmpty()
        return ModelSpec(
            id = id,
            kind = kind,
            name = text("name", 60),
            badge = text("badge", 30),
            description = text("description", 400),
            repo = repo,
            revision = revision,
            fileName = file,
            sizeBytes = size,
            sha256 = sha,
            license = text("license", 60),
            minRamGb = o.getInt("minRamGb").coerceIn(1, 32),
            quality = o.getInt("quality").coerceIn(1, 5),
            speed = o.getInt("speed").coerceIn(1, 5),
            supersedes = supersedes,
            builtIn = false,
        )
    }
}
