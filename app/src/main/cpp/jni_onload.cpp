// Library entry points: backend initialisation, logging and string helpers.

#include "jni_common.h"

#include "ggml-backend.h"
#include "llama.h"
#include "whisper.h"

#include <mutex>
#include <sstream>
#include <vector>

namespace escriba {

static JavaVM * g_vm = nullptr;

JavaVM * java_vm() { return g_vm; }

std::string to_utf8(JNIEnv * env, jstring str) {
    if (str == nullptr) {
        return {};
    }
    const jsize len = env->GetStringLength(str);
    const jchar * chars = env->GetStringChars(str, nullptr);
    std::string out;
    out.reserve(static_cast<size_t>(len) * 2);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t lo = chars[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                ++i;
            }
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(str, chars);
    return out;
}

jstring to_jstring(JNIEnv * env, const std::string & s) {
    std::vector<jchar> out;
    out.reserve(s.size());
    const auto * p = reinterpret_cast<const unsigned char *>(s.data());
    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        uint32_t cp;
        size_t extra;
        const unsigned char c = p[i];
        if (c < 0x80) {
            cp = c; extra = 0;
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1F; extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0F; extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07; extra = 3;
        } else {
            ++i; // invalid lead byte
            continue;
        }
        bool ok = true;
        for (size_t k = 1; k <= extra; ++k) {
            if (i + k >= n || (p[i + k] & 0xC0) != 0x80) {
                ok = false;
                break;
            }
            cp = (cp << 6) | (p[i + k] & 0x3F);
        }
        if (!ok) {
            ++i;
            continue;
        }
        i += extra + 1;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<jchar>(cp));
        }
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

size_t incomplete_utf8_tail(const std::string & s) {
    // Look back at most 3 bytes for a lead byte whose sequence is not complete.
    const size_t n = s.size();
    for (size_t back = 1; back <= 3 && back <= n; ++back) {
        const auto c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) {
            continue; // continuation byte, keep looking for the lead byte
        }
        size_t need = 0;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else return 0; // ASCII or invalid: nothing pending
        return back < need ? back : 0;
    }
    return 0;
}

static void android_log_cb(ggml_log_level level, const char * text, void * /*user*/) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: __android_log_write(ANDROID_LOG_ERROR, ESC_TAG, text); break;
        case GGML_LOG_LEVEL_WARN:  __android_log_write(ANDROID_LOG_WARN,  ESC_TAG, text); break;
        default: break; // info/debug output is extremely chatty during streaming; keep logcat clean
    }
}

} // namespace escriba

using namespace escriba;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void * /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static std::once_flag g_init_once;

extern "C" JNIEXPORT void JNICALL
Java_app_escriba_engine_NativeBridge_nativeInit(JNIEnv * env, jobject /*thiz*/, jstring jlib_dir) {
    const std::string lib_dir = to_utf8(env, jlib_dir);
    std::call_once(g_init_once, [&]() {
        llama_log_set(android_log_cb, nullptr);
        whisper_log_set(android_log_cb, nullptr);
        // Loads the CPU backend variant that best matches this phone's CPU features.
        ggml_backend_load_all_from_path(lib_dir.c_str());
        llama_backend_init();
        LOGI("backends ready (%zu registered)", ggml_backend_reg_count());
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_escriba_engine_NativeBridge_systemInfo(JNIEnv * env, jobject /*thiz*/) {
    std::ostringstream ss;
    ss << "whisper.cpp " << whisper_version() << "\n";
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        ss << "device: " << ggml_backend_dev_name(dev) << " - " << ggml_backend_dev_description(dev) << "\n";
    }
    ss << llama_print_system_info();
    return to_jstring(env, ss.str());
}
