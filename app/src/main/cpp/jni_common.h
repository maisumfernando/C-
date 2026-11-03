// Shared helpers for the JNI bridge.
#pragma once

#include <android/log.h>
#include <jni.h>

#include <string>

#define ESC_TAG "TecladoNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, ESC_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, ESC_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ESC_TAG, __VA_ARGS__)

namespace teclado {

// Converts a Java string to UTF-8 without relying on JNI's "modified UTF-8",
// which breaks characters outside the BMP (emoji) and embedded NULs.
std::string to_utf8(JNIEnv * env, jstring str);

// Creates a Java string from (possibly partial) UTF-8 bytes. Invalid sequences
// are dropped instead of crashing the VM like NewStringUTF would.
jstring to_jstring(JNIEnv * env, const std::string & utf8);

// Returns the number of bytes at the end of `s` that form an incomplete UTF-8
// sequence (0 when the string ends on a character boundary).
size_t incomplete_utf8_tail(const std::string & s);

JavaVM * java_vm();

} // namespace teclado
