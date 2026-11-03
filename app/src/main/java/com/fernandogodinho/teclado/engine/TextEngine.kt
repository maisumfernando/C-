package com.fernandogodinho.teclado.engine

import com.fernandogodinho.teclado.models.ModelSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun system(text: String) = ChatMessage("system", text)
        fun user(text: String) = ChatMessage("user", text)
        fun assistant(text: String) = ChatMessage("assistant", text)
    }
}

/** A loaded instruction-tuned LLM. */
class TextEngine(
    val handle: Long,
    val spec: ModelSpec,
    private val dispatcher: CoroutineDispatcher,
) {
    @Volatile
    private var aborted = false

    fun abort() {
        aborted = true
        NativeBridge.llmAbort(handle)
    }

    suspend fun warmup(messages: List<ChatMessage>): Boolean = withContext(dispatcher) {
        if (aborted) return@withContext false
        NativeBridge.llmWarmup(
            handle,
            messages.map { it.role }.toTypedArray(),
            messages.map { it.content }.toTypedArray(),
        )
    }

    suspend fun countTokens(text: String): Int = withContext(dispatcher) {
        if (aborted) -1 else NativeBridge.llmCountTokens(handle, text)
    }

    /** Generates a reply, streaming pieces to [onToken]. Cancelling the coroutine stops generation. */
    suspend fun generate(messages: List<ChatMessage>, maxTokens: Int, onToken: (String) -> Unit): String? {
        val job = coroutineContext[Job]
        val registration = job?.invokeOnCompletion { cause -> if (cause != null) NativeBridge.llmAbort(handle) }
        try {
            return withContext(dispatcher) {
                if (aborted) return@withContext null
                NativeBridge.llmGenerate(
                    handle,
                    messages.map { it.role }.toTypedArray(),
                    messages.map { it.content }.toTypedArray(),
                    maxTokens,
                ) { piece ->
                    if (job?.isActive == false) {
                        false
                    } else {
                        onToken(piece)
                        true
                    }
                }
            }
        } finally {
            registration?.dispose()
        }
    }

    companion object {
        const val CONTEXT_SIZE = 4096
    }
}
