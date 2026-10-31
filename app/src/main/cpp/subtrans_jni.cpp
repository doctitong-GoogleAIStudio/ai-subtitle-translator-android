// JNI bridge between Kotlin (com.aivicventures.subtitletranslator.asr.WhisperNative) and
// whisper.cpp. One Whisper context per loaded model; calls on one context are serialised
// by the Kotlin side. Text is returned as raw UTF-8 bytes because a Whisper segment can end
// inside a multi-byte character, which JNI's NewStringUTF would reject.

#include <jni.h>
#include <android/log.h>
#include <sched.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "whisper.h"

#define TAG "SubtransNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Handle {
    whisper_context *ctx = nullptr;
    whisper_vad_context *vad = nullptr;  // Silero VAD, loaded on first use
    std::atomic<bool> abort{false};
};

double ms_since(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, "whisper.cpp", "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, "whisper.cpp", "%s", text);
    }
}

bool abort_callback(void *user_data) {
    return static_cast<Handle *>(user_data)->abort.load();
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jbyteArray bytes(JNIEnv *env, const char *text) {
    const auto n = static_cast<jsize>(text ? strlen(text) : 0);
    jbyteArray arr = env->NewByteArray(n);
    if (n > 0) env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte *>(text));
    return arr;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_aivicventures_subtitletranslator_asr_WhisperNative_loadBackends(JNIEnv *env, jobject, jstring dir) {
    whisper_log_set(log_callback, nullptr);
    const std::string path = jstring_to_std(env, dir);
    ggml_backend_load_all_from_path(path.c_str());
    LOGI("ggml backends loaded from %s: %zu device(s)", path.c_str(), ggml_backend_dev_count());
}

JNIEXPORT jstring JNICALL
Java_com_aivicventures_subtitletranslator_asr_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_aivicventures_subtitletranslator_asr_WhisperNative_initContext(JNIEnv *env, jobject, jstring model_path) {
    const std::string path = jstring_to_std(env, model_path);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = true;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("failed to load model %s", path.c_str());
        return 0;
    }
    auto *h = new Handle();
    h->ctx = ctx;
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_aivicventures_subtitletranslator_asr_WhisperNative_freeContext(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h == nullptr) return;
    whisper_free(h->ctx);
    if (h->vad) whisper_vad_free(h->vad);
    delete h;
}

JNIEXPORT void JNICALL
Java_com_aivicventures_subtitletranslator_asr_WhisperNative_requestAbort(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h != nullptr) h->abort.store(true);
}

JNIEXPORT void JNICALL
Java_com_aivicventures_subtitletranslator_asr_WhisperNative_clearAbort(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h != nullptr) h->abort.store(false);
}

/**
 * Silero VAD over 16 kHz mono samples. Returns speech regions as [t0, t1, t0, t1, ...] in
 * seconds, or null if the VAD model cannot be loaded. Single-threaded: the model is tiny
 * and runs ~30 small graphs per second of audio, where thread start-up would dominate.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_aivicventures_subtitletranslator_asr_WhisperNative_detectSpeech(
        JNIEnv *env, jobject, jlong ptr, jstring vad_model, jfloatArray samples) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h == nullptr) return nullptr;
    if (h->vad == nullptr) {
        const std::string path = jstring_to_std(env, vad_model);
        whisper_vad_context_params vp = whisper_vad_default_context_params();
        vp.n_threads = 1;
        vp.use_gpu = false;
        h->vad = whisper_vad_init_from_file_with_params(path.c_str(), vp);
        if (h->vad == nullptr) {
            LOGE("failed to load VAD model %s", path.c_str());
            return nullptr;
        }
    }
    const jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    whisper_vad_params vp = whisper_vad_default_params();
    vp.threshold = 0.5f;
    vp.min_speech_duration_ms = 250;
    vp.min_silence_duration_ms = 300;
    vp.speech_pad_ms = 200;
    whisper_vad_segments *segs = whisper_vad_segments_from_samples(h->vad, vp, pcm.data(), static_cast<int>(pcm.size()));
    if (segs == nullptr) return env->NewFloatArray(0);
    const int count = whisper_vad_segments_n_segments(segs);
    std::vector<jfloat> out(static_cast<size_t>(count) * 2);
    for (int i = 0; i < count; ++i) {
        // whisper_vad_segments_get_segment_t0/t1 are in centiseconds.
        out[2 * i] = whisper_vad_segments_get_segment_t0(segs, i) / 100.0f;
        out[2 * i + 1] = whisper_vad_segments_get_segment_t1(segs, i) / 100.0f;
    }
    whisper_vad_free_segments(segs);
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(out.size()));
    env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

/**
 * Runs Whisper on 16 kHz mono samples. Returns a NativeResult or null on failure/abort.
 *
 * language: Whisper code ("ko") or "auto"; translate: Whisper's X->English task;
 * fallback: retry at higher temperatures when the output looks broken (slower);
 * vadModel: path of the Silero VAD model, or null to decode everything.
 */
JNIEXPORT jobject JNICALL
Java_com_aivicventures_subtitletranslator_asr_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong ptr, jfloatArray samples, jstring language, jboolean translate,
        jint threads, jboolean fallback, jstring vad_model, jstring initial_prompt) {
    auto *h = reinterpret_cast<Handle *>(ptr);
    if (h == nullptr || h->abort.load()) return nullptr;

    const std::string lang = jstring_to_std(env, language);
    const std::string vad_path = jstring_to_std(env, vad_model);
    const std::string prompt = jstring_to_std(env, initial_prompt);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.translate = translate;
    p.language = lang.empty() ? "auto" : lang.c_str();
    p.detect_language = false;
    p.no_context = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.greedy.best_of = fallback ? 3 : 1;
    p.temperature_inc = fallback ? 0.2f : 0.0f;
    p.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
    p.abort_callback = abort_callback;
    p.abort_callback_user_data = h;
    if (!vad_path.empty()) {
        p.vad = true;
        p.vad_model_path = vad_path.c_str();
        p.vad_params = whisper_vad_default_params();
        p.vad_params.threshold = 0.5f;
        p.vad_params.min_speech_duration_ms = 250;
        p.vad_params.min_silence_duration_ms = 300;
        p.vad_params.speech_pad_ms = 200;
    }

    const jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    const auto t_full = std::chrono::steady_clock::now();
    whisper_reset_timings(h->ctx);
    const int rc = whisper_full(h->ctx, p, pcm.data(), static_cast<int>(pcm.size()));
    if (const whisper_timings *tm = whisper_get_timings(h->ctx)) {
        // Per-run averages (the encoder usually runs twice per window, the decoder per token).
        LOGI("whisper_full: %.0f ms wall; %.1f s audio, %d threads; per run: encode %.0f ms, decode %.0f ms, batchd %.0f ms, prompt %.0f ms; sample %.0f ms",
             ms_since(t_full), pcm.size() / 16000.0, threads, tm->encode_ms, tm->decode_ms, tm->batchd_ms, tm->prompt_ms, tm->sample_ms);
    }
    if (rc != 0 || h->abort.load()) {
        if (!h->abort.load()) LOGW("whisper_full failed: %d", rc);
        return nullptr;
    }

    const int n_seg = whisper_full_n_segments(h->ctx);
    const whisper_token eot = whisper_token_eot(h->ctx);

    jclass bytes_cls = env->FindClass("[B");
    jobjectArray texts = env->NewObjectArray(n_seg, bytes_cls, nullptr);
    std::vector<jlong> t0(n_seg), t1(n_seg);
    std::vector<jfloat> no_speech(n_seg), avg_logprob(n_seg);
    std::vector<jint> n_tokens(n_seg);

    for (int i = 0; i < n_seg; ++i) {
        jbyteArray b = bytes(env, whisper_full_get_segment_text(h->ctx, i));
        env->SetObjectArrayElement(texts, i, b);
        env->DeleteLocalRef(b);
        t0[i] = whisper_full_get_segment_t0(h->ctx, i) * 10;  // centiseconds -> ms
        t1[i] = whisper_full_get_segment_t1(h->ctx, i) * 10;
        no_speech[i] = whisper_full_get_segment_no_speech_prob(h->ctx, i);
        double sum = 0;
        int count = 0;
        const int nt = whisper_full_n_tokens(h->ctx, i);
        for (int j = 0; j < nt; ++j) {
            const whisper_token_data d = whisper_full_get_token_data(h->ctx, i, j);
            if (d.id >= eot) continue;  // special and timestamp tokens
            sum += d.plog;
            ++count;
        }
        avg_logprob[i] = count > 0 ? static_cast<float>(sum / count) : -10.0f;
        n_tokens[i] = count;
    }

    jlongArray jt0 = env->NewLongArray(n_seg);
    jlongArray jt1 = env->NewLongArray(n_seg);
    jfloatArray jns = env->NewFloatArray(n_seg);
    jfloatArray jlp = env->NewFloatArray(n_seg);
    jintArray jnt = env->NewIntArray(n_seg);
    env->SetLongArrayRegion(jt0, 0, n_seg, t0.data());
    env->SetLongArrayRegion(jt1, 0, n_seg, t1.data());
    env->SetFloatArrayRegion(jns, 0, n_seg, no_speech.data());
    env->SetFloatArrayRegion(jlp, 0, n_seg, avg_logprob.data());
    env->SetIntArrayRegion(jnt, 0, n_seg, n_tokens.data());

    const int lang_id = whisper_full_lang_id(h->ctx);
    const char *lang_str = lang_id >= 0 ? whisper_lang_str(lang_id) : "";
    jstring jlang = env->NewStringUTF(lang_str ? lang_str : "");

    jclass cls = env->FindClass("com/aivicventures/subtitletranslator/asr/NativeResult");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;[[B[J[J[F[F[I)V");
    return env->NewObject(cls, ctor, jlang, texts, jt0, jt1, jns, jlp, jnt);
}

// CPU affinity of the calling thread only (pid 0 = this thread on Linux). Threads it creates
// afterwards inherit the mask, so pinning the engine thread pins whisper.cpp's workers too.
JNIEXPORT jboolean JNICALL
Java_com_aivicventures_subtitletranslator_asr_WhisperNative_setThreadAffinity(JNIEnv *env, jobject, jintArray cpus) {
    cpu_set_t set;
    CPU_ZERO(&set);
    const jsize n = env->GetArrayLength(cpus);
    std::vector<jint> ids(n);
    env->GetIntArrayRegion(cpus, 0, n, ids.data());
    for (jint id : ids) if (id >= 0 && id < CPU_SETSIZE) CPU_SET(id, &set);
    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        LOGW("sched_setaffinity failed: %s", strerror(errno));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_com_aivicventures_subtitletranslator_asr_WhisperNative_getThreadAffinity(JNIEnv *env, jobject) {
    cpu_set_t set;
    CPU_ZERO(&set);
    std::vector<jint> ids;
    if (sched_getaffinity(0, sizeof(set), &set) == 0) {
        for (int i = 0; i < CPU_SETSIZE; i++) if (CPU_ISSET(i, &set)) ids.push_back(i);
    }
    jintArray out = env->NewIntArray(static_cast<jsize>(ids.size()));
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(ids.size()), ids.data());
    return out;
}

}  // extern "C"
