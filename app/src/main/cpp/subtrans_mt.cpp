// JNI bridge to CTranslate2 (M2M100 text translation) for
// com.aivicventures.subtitletranslator.translate.TranslatorNative.
// Tokens cross the boundary as UTF-8 bytes joined by single spaces: SentencePiece pieces
// never contain a space (it is written as U+2581), and Java's modified UTF-8 would mangle
// emoji and other characters outside the BMP.

#include <jni.h>
#include <android/log.h>

#include <chrono>
#include <memory>
#include <string>
#include <vector>

#include <ctranslate2/translator.h>

// Internal CTranslate2 headers, only for logging which kernels were selected.
#include "cpu/backend.h"
#include "cpu/cpu_isa.h"

#define TAG "SubtransMt"

namespace {

struct Handle {
    std::unique_ptr<ctranslate2::Translator> translator;
};

void throw_java(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, message);
}

std::vector<std::string> split_tokens(JNIEnv *env, jbyteArray bytes) {
    std::vector<std::string> out;
    const jsize n = env->GetArrayLength(bytes);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    size_t start = 0;
    while (start <= s.size()) {
        size_t end = s.find(' ', start);
        if (end == std::string::npos) end = s.size();
        if (end > start) out.emplace_back(s.substr(start, end - start));
        start = end + 1;
    }
    return out;
}

jbyteArray join_tokens(JNIEnv *env, const std::vector<std::string> &tokens) {
    std::string s;
    for (const auto &t : tokens) {
        if (!s.empty()) s.push_back(' ');
        s += t;
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aivicventures_subtitletranslator_translate_TranslatorNative_init(JNIEnv *env, jobject, jstring modelDir, jint threads) {
    const char *path = env->GetStringUTFChars(modelDir, nullptr);
    std::string dir(path);
    env->ReleaseStringUTFChars(modelDir, path);
    try {
        ctranslate2::ReplicaPoolConfig config;
        config.num_threads_per_replica = static_cast<size_t>(threads > 0 ? threads : 1);
        auto t0 = std::chrono::steady_clock::now();
        auto handle = new Handle{std::make_unique<ctranslate2::Translator>(
            dir, ctranslate2::Device::CPU, ctranslate2::ComputeType::INT8, std::vector<int>{0}, false, config)};
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
        __android_log_print(ANDROID_LOG_INFO, TAG, "loaded %s in %lld ms, %d threads, isa=%s int8 gemm=%s", dir.c_str(),
                            static_cast<long long>(ms), threads, ctranslate2::cpu::isa_to_str(ctranslate2::cpu::get_cpu_isa()).c_str(),
                            ctranslate2::cpu::gemm_backend_to_str(ctranslate2::cpu::get_gemm_backend(ctranslate2::ComputeType::INT8)).c_str());
        return reinterpret_cast<jlong>(handle);
    } catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "load failed: %s", e.what());
        throw_java(env, e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_aivicventures_subtitletranslator_translate_TranslatorNative_free(JNIEnv *, jobject, jlong ptr) {
    delete reinterpret_cast<Handle *>(ptr);
}

/** sources: one byte[] per sentence (space-joined tokens); returns the target tokens the same way. */
JNIEXPORT jobjectArray JNICALL
Java_com_aivicventures_subtitletranslator_translate_TranslatorNative_translate(
    JNIEnv *env, jobject, jlong ptr, jobjectArray sources, jstring targetPrefix, jint beam, jint maxLength) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    const jsize n = env->GetArrayLength(sources);
    std::vector<std::vector<std::string>> batch;
    batch.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(sources, i));
        batch.push_back(split_tokens(env, bytes));
        env->DeleteLocalRef(bytes);
    }
    const char *p = env->GetStringUTFChars(targetPrefix, nullptr);
    std::vector<std::vector<std::string>> prefix(batch.size(), std::vector<std::string>{std::string(p)});
    env->ReleaseStringUTFChars(targetPrefix, p);

    ctranslate2::TranslationOptions options;
    options.beam_size = static_cast<size_t>(beam);
    options.max_decoding_length = static_cast<size_t>(maxLength);
    options.max_input_length = 256;
    options.repetition_penalty = 1.1f;  // same decoding settings as the Windows app
    options.no_repeat_ngram_size = 4;
    std::vector<ctranslate2::TranslationResult> results;
    try {
        results = h->translator->translate_batch(batch, prefix, options, 8);
    } catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "translate failed: %s", e.what());
        throw_java(env, e.what());
        return nullptr;
    }
    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray out = env->NewObjectArray(n, byteArrayClass, nullptr);
    for (jsize i = 0; i < n; i++) {
        std::vector<std::string> tokens;
        if (!results[i].hypotheses.empty()) {
            for (const auto &t : results[i].hypotheses[0])
                if (t != prefix[i][0]) tokens.push_back(t);
        }
        jbyteArray arr = join_tokens(env, tokens);
        env->SetObjectArrayElement(out, i, arr);
        env->DeleteLocalRef(arr);
    }
    return out;
}

}  // extern "C"
