#include "text_runtime.h"
#include <jni.h>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
// JNI's modified UTF-8 would corrupt supplementary characters in source posts.
std::string utf8(JNIEnv * env, jstring value) {
    if (!value) throw std::runtime_error("Missing text argument");
    const jsize n = env->GetStringLength(value);
    if (n > 524288) throw std::runtime_error("Text argument is too large");
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (!chars) throw std::runtime_error("Unable to read source text");
    std::string out;
    for (jsize i = 0; i < n; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < n && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff)
            c = 0x10000 + ((c - 0xd800) << 10) + (chars[++i] - 0xdc00);
        else if (c >= 0xd800 && c <= 0xdfff) c = 0xfffd;
        if (c < 0x80) out.push_back(static_cast<char>(c));
        else if (c < 0x800) { out.push_back(static_cast<char>(0xc0 | (c >> 6))); out.push_back(static_cast<char>(0x80 | (c & 63))); }
        else if (c < 0x10000) { out.push_back(static_cast<char>(0xe0 | (c >> 12))); out.push_back(static_cast<char>(0x80 | ((c >> 6) & 63))); out.push_back(static_cast<char>(0x80 | (c & 63))); }
        else { out.push_back(static_cast<char>(0xf0 | (c >> 18))); out.push_back(static_cast<char>(0x80 | ((c >> 12) & 63))); out.push_back(static_cast<char>(0x80 | ((c >> 6) & 63))); out.push_back(static_cast<char>(0x80 | (c & 63))); }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}
jstring string(JNIEnv * env, const std::string & text) {
    std::vector<jchar> out;
    for (size_t i = 0; i < text.size();) {
        unsigned char first = static_cast<unsigned char>(text[i++]);
        uint32_t c = first;
        int remaining = 0;
        if (first >= 0xf0 && first <= 0xf4) { c = first & 7; remaining = 3; }
        else if (first >= 0xe0 && first <= 0xef) { c = first & 15; remaining = 2; }
        else if (first >= 0xc2 && first <= 0xdf) { c = first & 31; remaining = 1; }
        else if (first > 0x7f) c = 0xfffd;
        bool valid = i + static_cast<size_t>(remaining) <= text.size();
        for (int j = 0; j < remaining && valid; ++j) {
            unsigned char next = static_cast<unsigned char>(text[i]);
            if ((next & 0xc0) != 0x80) { valid = false; break; }
            ++i; c = (c << 6) | (next & 63);
        }
        if (!valid || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff) ||
            (remaining == 1 && c < 0x80) || (remaining == 2 && c < 0x800) || (remaining == 3 && c < 0x10000)) c = 0xfffd;
        if (c < 0x10000) out.push_back(static_cast<jchar>(c));
        else { c -= 0x10000; out.push_back(static_cast<jchar>(0xd800 | (c >> 10))); out.push_back(static_cast<jchar>(0xdc00 | (c & 1023))); }
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}
void error(JNIEnv * env, const std::exception & e) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
}
extern "C" JNIEXPORT jstring JNICALL Java_app_antisocial_android_LocalTextNative_capabilities(JNIEnv * env, jclass) {
    try { return string(env, antisocial_ai::capabilities()); } catch (const std::exception & e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jlong JNICALL Java_app_antisocial_android_LocalTextNative_open(JNIEnv * env, jclass, jstring path, jstring backend, jint context, jint threads, jstring cache_profile) {
    try { return antisocial_ai::open(utf8(env, path), utf8(env, backend), context, threads, utf8(env, cache_profile)); } catch (const std::exception & e) { error(env, e); return 0; }
}
extern "C" JNIEXPORT jstring JNICALL Java_app_antisocial_android_LocalTextNative_generate(JNIEnv * env, jclass, jlong handle, jstring system, jstring user, jint maximum, jint seed, jboolean json_mode, jint instruction_cache_bytes) {
    try { return string(env, antisocial_ai::generate(handle, utf8(env, system), utf8(env, user), maximum, static_cast<uint32_t>(seed), json_mode == JNI_TRUE, instruction_cache_bytes)); } catch (const std::exception & e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT jint JNICALL Java_app_antisocial_android_LocalTextNative_countTokens(JNIEnv * env, jclass, jlong handle, jstring system, jstring user) {
    try { return antisocial_ai::count_tokens(handle, utf8(env, system), utf8(env, user)); } catch (const std::exception & e) { error(env, e); return 0; }
}
extern "C" JNIEXPORT jstring JNICALL Java_app_antisocial_android_LocalTextNative_diagnostics(JNIEnv * env, jclass, jlong handle) {
    try { return string(env, antisocial_ai::diagnostics(handle)); } catch (const std::exception & e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_app_antisocial_android_LocalTextNative_cancel(JNIEnv * env, jclass, jlong handle) {
    try { antisocial_ai::cancel(handle); } catch (const std::exception & e) { error(env, e); }
}
extern "C" JNIEXPORT void JNICALL Java_app_antisocial_android_LocalTextNative_reset(JNIEnv * env, jclass, jlong handle) {
    try { antisocial_ai::reset(handle); } catch (const std::exception & e) { error(env, e); }
}
extern "C" JNIEXPORT void JNICALL Java_app_antisocial_android_LocalTextNative_close(JNIEnv * env, jclass, jlong handle) {
    try { antisocial_ai::close(handle); } catch (const std::exception & e) { error(env, e); }
}
