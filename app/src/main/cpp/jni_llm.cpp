// Local LLM (llama.cpp) used to turn raw dictation into polished written text.
//
// The polishing prompt (instructions + examples) is identical on every call, so the
// evaluated prompt is kept in the KV cache and only the new transcript has to be
// processed. For hybrid/recurrent models that cannot truncate their memory, a
// snapshot of the warmed-up prefix is restored instead.

#include "jni_common.h"

#include "chat.h"
#include "common.h"
#include "llama.h"

#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

using namespace escriba;

namespace {

struct LlmHandle {
    llama_model *             model = nullptr;
    llama_context *           ctx   = nullptr;
    common_chat_templates_ptr tmpls;
    int                       n_ctx   = 0;
    int                       n_batch = 0;

    std::vector<llama_token> cached;          // tokens currently held in sequence 0
    std::vector<llama_token> snapshot_tokens; // tokens covered by `snapshot`
    std::vector<uint8_t>     snapshot;        // serialized state of the warmed-up prefix

    std::atomic<bool> abort{false};
    std::mutex        mutex;
};

bool abort_cb(void * user_data) {
    return static_cast<std::atomic<bool> *>(user_data)->load(std::memory_order_relaxed);
}

struct Message {
    std::string role;
    std::string content;
};

std::vector<Message> read_messages(JNIEnv * env, jobjectArray jroles, jobjectArray jcontents) {
    std::vector<Message> msgs;
    const jsize n = std::min(env->GetArrayLength(jroles), env->GetArrayLength(jcontents));
    msgs.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        auto jr = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
        auto jc = static_cast<jstring>(env->GetObjectArrayElement(jcontents, i));
        msgs.push_back({to_utf8(env, jr), to_utf8(env, jc)});
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    return msgs;
}

// Renders the conversation with the model's own chat template and tokenizes it.
// Template text may contain special tokens; user-provided text never does.
bool render_and_tokenize(LlmHandle * h, const std::vector<Message> & msgs, bool add_generation_prompt,
                         std::vector<llama_token> & out, std::vector<std::string> & stops) {
    common_chat_templates_inputs inputs;
    for (const auto & m : msgs) {
        common_chat_msg cm;
        cm.role    = m.role;
        cm.content = m.content;
        inputs.messages.push_back(std::move(cm));
    }
    inputs.add_generation_prompt = add_generation_prompt;
    inputs.enable_thinking       = false;
    inputs.add_bos               = true; // BOS is added by the tokenizer instead of the template text
    inputs.add_eos               = false;

    common_chat_params params;
    bool rendered = false;
    for (int attempt = 0; attempt < 2 && !rendered; ++attempt) {
        inputs.use_jinja = attempt == 0;
        try {
            params   = common_chat_templates_apply(h->tmpls.get(), inputs);
            rendered = true;
        } catch (const std::exception & e) {
            LOGW("chat template (jinja=%d) failed: %s", (int) inputs.use_jinja, e.what());
        }
    }
    if (!rendered) {
        return false;
    }
    stops = params.additional_stops;

    const llama_vocab * vocab = llama_model_get_vocab(h->model);
    out.clear();
    bool first = true;
    for (const auto & part : params.prompt.parts) {
        if (part.text.empty()) continue;
        auto toks = common_tokenize(vocab, part.text, /*add_special=*/first, /*parse_special=*/!part.is_input);
        out.insert(out.end(), toks.begin(), toks.end());
        first = false;
    }
    return !out.empty();
}

// Makes sequence 0 hold a prefix of `tokens` and returns how many tokens are already evaluated.
size_t reuse_cache(LlmHandle * h, const std::vector<llama_token> & tokens) {
    llama_memory_t mem = llama_get_memory(h->ctx);

    size_t lcp = 0;
    while (lcp < h->cached.size() && lcp < tokens.size() && h->cached[lcp] == tokens[lcp]) {
        ++lcp;
    }
    // At least the last prompt token must be evaluated again to obtain fresh logits.
    if (lcp >= tokens.size()) {
        lcp = tokens.size() - 1;
    }
    if (lcp == h->cached.size()) {
        return lcp;
    }
    if (lcp > 0 && llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(lcp), -1)) {
        h->cached.resize(lcp);
        return lcp;
    }

    // Partial removal is not possible (e.g. recurrent layers): restore the prefix snapshot.
    llama_memory_clear(mem, true);
    h->cached.clear();
    const auto & snap = h->snapshot_tokens;
    if (!h->snapshot.empty() && snap.size() < tokens.size() &&
        std::equal(snap.begin(), snap.end(), tokens.begin())) {
        if (llama_state_seq_set_data(h->ctx, h->snapshot.data(), h->snapshot.size(), 0) > 0) {
            h->cached = snap;
            return snap.size();
        }
        llama_memory_clear(mem, true);
    }
    return 0;
}

// Evaluates tokens[from..] in batches. Logits are requested for the final token only.
bool eval_tokens(LlmHandle * h, const std::vector<llama_token> & tokens, size_t from) {
    common_batch batch(h->ctx);
    for (size_t i = from; i < tokens.size(); i += h->n_batch) {
        if (h->abort.load()) return false;
        const size_t end = std::min(tokens.size(), i + static_cast<size_t>(h->n_batch));
        batch.clear();
        for (size_t j = i; j < end; ++j) {
            batch.add(tokens[j], static_cast<llama_pos>(j), 0, j == tokens.size() - 1);
        }
        const int rc = llama_process(h->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (rc != 0) {
            LOGW("llama_process failed during prompt eval: %d", rc);
            return false;
        }
        h->cached.insert(h->cached.end(), tokens.begin() + i, tokens.begin() + end);
    }
    return true;
}

void reset_cache(LlmHandle * h) {
    llama_memory_clear(llama_get_memory(h->ctx), true);
    h->cached.clear();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_escriba_engine_NativeBridge_llmLoad(JNIEnv * env, jobject /*thiz*/, jstring jpath, jint n_ctx, jint n_threads) {
    const std::string path = to_utf8(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) {
        LOGE("llm: failed to load %s", path.c_str());
        return 0;
    }

    const int ctx_train = llama_model_n_ctx_train(model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(ctx_train > 0 ? std::min<int>(n_ctx, ctx_train) : n_ctx);
    cp.n_batch         = 512;
    cp.n_ubatch        = 512;
    cp.n_seq_max       = 1;
    cp.n_threads       = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf         = true;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    llama_context * ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LOGE("llm: failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto * h = new LlmHandle();
    h->model   = model;
    h->ctx     = ctx;
    h->n_ctx   = static_cast<int>(llama_n_ctx(ctx));
    h->n_batch = static_cast<int>(cp.n_batch);
    try {
        h->tmpls = common_chat_templates_init(model, "");
    } catch (const std::exception & e) {
        LOGE("llm: chat template init failed: %s", e.what());
        llama_free(ctx);
        llama_model_free(model);
        delete h;
        return 0;
    }
    llama_set_abort_callback(ctx, abort_cb, &h->abort);
    LOGI("llm: loaded %s (n_ctx=%d, threads=%d)", path.c_str(), h->n_ctx, n_threads);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_app_escriba_engine_NativeBridge_llmFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h == nullptr) return;
    h->abort = true;
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        h->tmpls.reset();
        llama_free(h->ctx);
        llama_model_free(h->model);
        h->ctx   = nullptr;
        h->model = nullptr;
    }
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_app_escriba_engine_NativeBridge_llmAbort(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h != nullptr) h->abort = true;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_escriba_engine_NativeBridge_llmCountTokens(JNIEnv * env, jobject /*thiz*/, jlong handle, jstring jtext) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h == nullptr || h->model == nullptr) return -1;
    const std::string text = to_utf8(env, jtext);
    return static_cast<jint>(common_tokenize(llama_model_get_vocab(h->model), text, false, false).size());
}

/** Evaluates the fixed part of the prompt ahead of time so the first real request is fast. */
extern "C" JNIEXPORT jboolean JNICALL
Java_app_escriba_engine_NativeBridge_llmWarmup(JNIEnv * env, jobject /*thiz*/, jlong handle,
                                               jobjectArray jroles, jobjectArray jcontents) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h == nullptr || h->ctx == nullptr) return JNI_FALSE;
    const auto msgs = read_messages(env, jroles, jcontents);

    std::lock_guard<std::mutex> lock(h->mutex);
    h->abort = false;

    std::vector<llama_token> tokens;
    std::vector<std::string> stops;
    if (!render_and_tokenize(h, msgs, false, tokens, stops)) return JNI_FALSE;
    if (static_cast<int>(tokens.size()) >= h->n_ctx / 2) return JNI_FALSE;
    if (h->snapshot_tokens == tokens && !h->snapshot.empty()) return JNI_TRUE;

    const size_t from = reuse_cache(h, tokens);
    if (!eval_tokens(h, tokens, from)) {
        reset_cache(h);
        return JNI_FALSE;
    }

    const size_t size = llama_state_seq_get_size(h->ctx, 0);
    std::vector<uint8_t> state(size);
    if (size > 0 && llama_state_seq_get_data(h->ctx, state.data(), size, 0) == size) {
        h->snapshot        = std::move(state);
        h->snapshot_tokens = tokens;
    }
    LOGI("llm: warmed up %zu prompt tokens (snapshot %zu bytes)", tokens.size(), h->snapshot.size());
    return JNI_TRUE;
}

/**
 * Runs the conversation and streams the answer through `callback.onToken(String): Boolean`
 * (returning false stops generation). Returns the full answer, or null on failure.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_app_escriba_engine_NativeBridge_llmGenerate(JNIEnv * env, jobject /*thiz*/, jlong handle,
                                                 jobjectArray jroles, jobjectArray jcontents,
                                                 jint max_tokens, jobject callback) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h == nullptr || h->ctx == nullptr) return nullptr;
    const auto msgs = read_messages(env, jroles, jcontents);

    jmethodID on_token = nullptr;
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(cls);
        if (on_token == nullptr) {
            env->ExceptionClear();
        }
    }

    std::lock_guard<std::mutex> lock(h->mutex);
    h->abort = false;

    std::vector<llama_token> tokens;
    std::vector<std::string> stops;
    if (!render_and_tokenize(h, msgs, true, tokens, stops)) {
        LOGE("llm: failed to build prompt");
        return nullptr;
    }
    const int room = h->n_ctx - static_cast<int>(tokens.size()) - 4;
    if (room < 16) {
        LOGE("llm: prompt too long (%zu tokens, n_ctx=%d)", tokens.size(), h->n_ctx);
        return nullptr;
    }
    const int budget = std::min<int>(max_tokens, room);

    const size_t from = reuse_cache(h, tokens);
    if (!eval_tokens(h, tokens, from)) {
        reset_cache(h);
        return h->abort.load() ? to_jstring(env, "") : nullptr;
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    const llama_vocab * vocab = llama_model_get_vocab(h->model);
    std::string output;
    std::string pending; // bytes not yet delivered to Java (incomplete UTF-8)
    common_batch batch(h->ctx);
    bool failed = false;

    for (int i = 0; i < budget; ++i) {
        if (h->abort.load()) break;
        const llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        const std::string piece = common_token_to_piece(h->ctx, tok, false);
        output += piece;
        pending += piece;

        bool stop_hit = false;
        for (const auto & s : stops) {
            if (!s.empty() && output.size() >= s.size() &&
                output.compare(output.size() - s.size(), s.size(), s) == 0) {
                output.resize(output.size() - s.size());
                pending.clear();
                stop_hit = true;
                break;
            }
        }
        if (stop_hit) break;

        const size_t tail = incomplete_utf8_tail(pending);
        if (on_token != nullptr && pending.size() > tail) {
            const std::string ready = pending.substr(0, pending.size() - tail);
            pending.erase(0, pending.size() - tail);
            jstring js = to_jstring(env, ready);
            const jboolean keep_going = env->CallBooleanMethod(callback, on_token, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
            if (!keep_going) break;
        }

        batch.clear();
        batch.add(tok, static_cast<llama_pos>(h->cached.size()), 0, true);
        if (llama_process(h->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            failed = true;
            break;
        }
        h->cached.push_back(tok);
    }
    llama_sampler_free(smpl);

    if (failed || h->abort.load()) {
        // The memory may be in an inconsistent state after an interrupted decode.
        reset_cache(h);
        if (failed && !h->abort.load()) {
            return nullptr;
        }
    }
    return to_jstring(env, output);
}
