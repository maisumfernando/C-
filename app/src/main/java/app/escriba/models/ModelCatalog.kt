package app.escriba.models

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
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$revision/$fileName"
    val sourcePage: String get() = "https://huggingface.co/$repo"
    val localFileName: String get() = "$id.${fileName.substringAfterLast('.')}"
}

object ModelCatalog {

    private const val WHISPER_REPO = "ggerganov/whisper.cpp"
    private const val WHISPER_REV = "5359861c739e955e79d9a303bcbc70fb988958b1"

    val speechModels: List<ModelSpec> = listOf(
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
            description = "Muito leve e veloz. Comete mais erros; a correção por IA compensa parte deles.",
            repo = WHISPER_REPO,
            revision = WHISPER_REV,
            fileName = "ggml-base-q8_0.bin",
            sizeBytes = 81_768_585L,
            sha256 = "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9",
            license = "MIT (OpenAI Whisper)",
            minRamGb = 3,
            quality = 2,
            speed = 5,
        ),
    )

    val textModels: List<ModelSpec> = listOf(
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
    )

    val all: List<ModelSpec> = speechModels + textModels

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }

    /** Picks the best models for the device's memory. */
    fun recommended(totalRamGb: Double): Pair<ModelSpec, ModelSpec> {
        val speech = when {
            totalRamGb >= 7.5 -> speechModels.first { it.id == "whisper-large-v3-turbo-q5" }
            totalRamGb >= 3.5 -> speechModels.first { it.id == "whisper-small-q8" }
            else -> speechModels.first { it.id == "whisper-base-q8" }
        }
        val text = when {
            totalRamGb >= 5.5 -> textModels.first { it.id == "gemma-4-e2b-q4" }
            else -> textModels.first { it.id == "qwen3.5-2b-q4" }
        }
        return speech to text
    }
}
