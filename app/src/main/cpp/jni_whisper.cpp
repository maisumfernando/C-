// Speech recognition (whisper.cpp) and streaming voice-activity detection (Silero VAD).

#include "jni_common.h"

#include "whisper.h"

#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

using namespace teclado;

namespace {

struct WhisperHandle {
    whisper_context * ctx = nullptr;
    std::atomic<bool> abort{false};
    std::mutex        mutex; // whisper contexts are not thread-safe
};

struct VadHandle {
    whisper_vad_context * ctx = nullptr;
    std::mutex            mutex;
};

// 16 kHz mono: at most 11 minutes per call, 30 s per VAD call.
constexpr jint kMaxSamples    = 16000 * 60 * 11;
constexpr jint kMaxVadSamples = 16000 * 30;

bool abort_cb(void * user_data) {
    return static_cast<std::atomic<bool> *>(user_data)->load(std::memory_order_relaxed);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_fernandogodinho_teclado_engine_NativeBridge_whisperLoad(JNIEnv * env, jobject /*thiz*/, jstring jpath, jboolean flash_attn) {
    const std::string path = to_utf8(env, jpath);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu    = false;
    cparams.flash_attn = flash_attn;
    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("whisper: failed to load %s", path.c_str());
        return 0;
    }
    auto * h = new WhisperHandle();
    h->ctx = ctx;
    LOGI("whisper: loaded %s (multilingual=%d)", path.c_str(), whisper_is_multilingual(ctx));
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fernandogodinho_teclado_engine_NativeBridge_whisperFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * h = reinterpret_cast<WhisperHandle *>(handle);
    if (h == nullptr) return;
    h->abort = true;
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        whisper_free(h->ctx);
        h->ctx = nullptr;
    }
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fernandogodinho_teclado_engine_NativeBridge_whisperAbort(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * h = reinterpret_cast<WhisperHandle *>(handle);
    if (h != nullptr) h->abort = true;
}

/**
 * Transcribes 16 kHz mono float PCM.
 * Returns {text, detected_language} or null on failure. Aborted runs return an empty text.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_fernandogodinho_teclado_engine_NativeBridge_whisperTranscribe(
        JNIEnv * env, jobject /*thiz*/, jlong handle,
        jfloatArray jsamples, jint offset, jint length,
        jstring jlanguage, jstring jprompt,
        jint n_threads, jint beam_size, jint audio_ctx,
        jstring jvad_model, jfloat no_speech_thold) {
    auto * h = reinterpret_cast<WhisperHandle *>(handle);
    if (h == nullptr || h->ctx == nullptr || jsamples == nullptr) {
        return nullptr;
    }
    // Validate the requested window before touching memory.
    const jsize available = env->GetArrayLength(jsamples);
    if (offset < 0 || length <= 0 || length > kMaxSamples || offset > available - length) {
        LOGE("whisper: invalid audio window");
        return nullptr;
    }

    std::vector<float> pcm(static_cast<size_t>(length));
    env->GetFloatArrayRegion(jsamples, offset, length, pcm.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    const std::string language  = to_utf8(env, jlanguage);
    const std::string prompt    = to_utf8(env, jprompt);
    const std::string vad_model = to_utf8(env, jvad_model);

    std::lock_guard<std::mutex> lock(h->mutex);
    h->abort = false;

    const bool use_beam = beam_size > 1;
    whisper_full_params p = whisper_full_default_params(use_beam ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads        = n_threads > 0 ? n_threads : 4;
    p.translate        = false;
    p.no_context       = true;   // context is provided explicitly through the prompt
    p.no_timestamps    = true;
    p.single_segment   = false;
    p.print_special    = false;
    p.print_progress   = false;
    p.print_realtime   = false;
    p.print_timestamps = false;
    p.suppress_blank   = true;
    p.suppress_nst     = true;
    p.temperature      = 0.0f;
    p.temperature_inc  = 0.2f;   // fall back to sampling only when greedy decoding degenerates
    p.no_speech_thold  = no_speech_thold;
    p.audio_ctx        = audio_ctx;
    p.language         = language.empty() ? "auto" : language.c_str();
    p.detect_language  = false;
    p.initial_prompt   = prompt.empty() ? nullptr : prompt.c_str();
    p.carry_initial_prompt = true;
    p.greedy.best_of        = 2;
    p.beam_search.beam_size = use_beam ? beam_size : 1;
    p.abort_callback           = abort_cb;
    p.abort_callback_user_data = &h->abort;

    if (!vad_model.empty()) {
        p.vad            = true;
        p.vad_model_path = vad_model.c_str();
        p.vad_params     = whisper_vad_default_params();
        p.vad_params.threshold               = 0.45f;
        p.vad_params.min_speech_duration_ms  = 200;
        p.vad_params.min_silence_duration_ms = 600;
        p.vad_params.speech_pad_ms           = 250;
        p.vad_params.samples_overlap         = 0.1f;
    }

    const int rc = whisper_full(h->ctx, p, pcm.data(), static_cast<int>(pcm.size()));
    const bool aborted = h->abort.load();
    if (rc != 0 && !aborted) {
        LOGE("whisper_full failed: %d", rc);
        return nullptr;
    }

    std::string text;
    if (!aborted) {
        const int n = whisper_full_n_segments(h->ctx);
        for (int i = 0; i < n; ++i) {
            const float nsp = whisper_full_get_segment_no_speech_prob(h->ctx, i);
            const char * seg = whisper_full_get_segment_text(h->ctx, i);
            if (seg == nullptr) continue;
            if (nsp > 0.85f && std::strlen(seg) < 40) {
                continue; // almost certainly a hallucination over silence
            }
            text += seg;
        }
    }

    const int lang_id = whisper_full_lang_id(h->ctx);
    const char * lang = lang_id >= 0 ? whisper_lang_str(lang_id) : "";

    jclass string_cls = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(2, string_cls, nullptr);
    env->SetObjectArrayElement(result, 0, to_jstring(env, text));
    env->SetObjectArrayElement(result, 1, to_jstring(env, lang ? lang : ""));
    return result;
}

// ---------------------------------------------------------------------------
// Streaming Silero VAD
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_fernandogodinho_teclado_engine_NativeBridge_vadLoad(JNIEnv * env, jobject /*thiz*/, jstring jpath) {
    const std::string path = to_utf8(env, jpath);
    whisper_vad_context_params vp = whisper_vad_default_context_params();
    vp.n_threads = 1;
    vp.use_gpu   = false;
    whisper_vad_context * ctx = whisper_vad_init_from_file_with_params(path.c_str(), vp);
    if (ctx == nullptr) {
        LOGE("vad: failed to load %s", path.c_str());
        return 0;
    }
    auto * h = new VadHandle();
    h->ctx = ctx;
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fernandogodinho_teclado_engine_NativeBridge_vadFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * h = reinterpret_cast<VadHandle *>(handle);
    if (h == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        whisper_vad_free(h->ctx);
        h->ctx = nullptr;
    }
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fernandogodinho_teclado_engine_NativeBridge_vadReset(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * h = reinterpret_cast<VadHandle *>(handle);
    if (h == nullptr) return;
    std::lock_guard<std::mutex> lock(h->mutex);
    whisper_vad_reset_state(h->ctx);
}

/** Returns one speech probability per 512-sample (32 ms) window. */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_fernandogodinho_teclado_engine_NativeBridge_vadProcess(JNIEnv * env, jobject /*thiz*/, jlong handle,
                                                jfloatArray jsamples, jint length) {
    auto * h = reinterpret_cast<VadHandle *>(handle);
    if (h == nullptr || h->ctx == nullptr || jsamples == nullptr || length <= 0 ||
        length > env->GetArrayLength(jsamples) || length > kMaxVadSamples) {
        return env->NewFloatArray(0);
    }
    std::vector<float> pcm(static_cast<size_t>(length));
    env->GetFloatArrayRegion(jsamples, 0, length, pcm.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!whisper_vad_detect_speech_no_reset(h->ctx, pcm.data(), length)) {
        return env->NewFloatArray(0);
    }
    const int n = whisper_vad_n_probs(h->ctx);
    const float * probs = whisper_vad_probs(h->ctx);
    jfloatArray out = env->NewFloatArray(n);
    env->SetFloatArrayRegion(out, 0, n, probs);
    return out;
}
