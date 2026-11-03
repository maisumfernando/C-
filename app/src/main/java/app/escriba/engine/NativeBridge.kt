package app.escriba.engine

import android.content.Context

/** Receives generated text incrementally from native code. Return false to stop. */
fun interface TokenCallback {
    fun onToken(piece: String): Boolean
}

/**
 * Thin JNI surface over whisper.cpp and llama.cpp (see app/src/main/cpp).
 * Handles are opaque native pointers; 0 means "not loaded".
 */
object NativeBridge {

    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            System.loadLibrary("escriba")
            nativeInit(context.applicationInfo.nativeLibraryDir)
            initialized = true
        }
    }

    private external fun nativeInit(nativeLibDir: String)
    external fun systemInfo(): String

    // Speech recognition -------------------------------------------------------------------
    external fun whisperLoad(path: String, flashAttn: Boolean): Long
    external fun whisperFree(handle: Long)
    external fun whisperAbort(handle: Long)
    external fun whisperTranscribe(
        handle: Long,
        samples: FloatArray,
        offset: Int,
        length: Int,
        language: String,
        prompt: String,
        threads: Int,
        beamSize: Int,
        audioCtx: Int,
        vadModelPath: String,
        noSpeechThreshold: Float,
    ): Array<String>?

    // Voice activity detection -------------------------------------------------------------
    external fun vadLoad(path: String): Long
    external fun vadFree(handle: Long)
    external fun vadReset(handle: Long)
    external fun vadProcess(handle: Long, samples: FloatArray, length: Int): FloatArray

    // Language model -----------------------------------------------------------------------
    external fun llmLoad(path: String, contextSize: Int, threads: Int): Long
    external fun llmFree(handle: Long)
    external fun llmAbort(handle: Long)
    external fun llmCountTokens(handle: Long, text: String): Int
    external fun llmWarmup(handle: Long, roles: Array<String>, contents: Array<String>): Boolean
    external fun llmGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        callback: TokenCallback?,
    ): String?
}
