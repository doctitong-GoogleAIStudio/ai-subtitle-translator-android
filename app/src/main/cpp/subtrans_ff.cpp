// Audio decoding for recognition with the optional LGPL FFmpeg build (AC-3, E-AC-3, DTS,
// TrueHD, MPEG audio) when the phone has no MediaCodec decoder for the movie's audio.
// Packets come from Media3's demuxer; frames go back as mono float at the codec's rate,
// downmixed with the dialogue weighting of AudioWindowReader.downmixWeights.
// Playback uses Media3's own FFmpeg extension (libffmpegJNI) with the same libraries.

#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
}

#define TAG "FfmpegAudio"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Decoder {
    AVCodecContext *ctx = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    int64_t firstPts = AV_NOPTS_VALUE;  // of the first frame returned by the last decode call
};

float sampleAt(const AVFrame *f, int ch, int i, int channels) {
    const auto fmt = static_cast<AVSampleFormat>(f->format);
    const bool planar = av_sample_fmt_is_planar(fmt);
    const uint8_t *data = planar ? f->extended_data[ch] : f->extended_data[0];
    const int idx = planar ? i : i * channels + ch;
    switch (av_get_packed_sample_fmt(fmt)) {
        case AV_SAMPLE_FMT_FLT: return reinterpret_cast<const float *>(data)[idx];
        case AV_SAMPLE_FMT_DBL: return static_cast<float>(reinterpret_cast<const double *>(data)[idx]);
        case AV_SAMPLE_FMT_S16: return reinterpret_cast<const int16_t *>(data)[idx] / 32768.0f;
        case AV_SAMPLE_FMT_S32: return reinterpret_cast<const int32_t *>(data)[idx] / 2147483648.0f;
        case AV_SAMPLE_FMT_U8: return (data[idx] - 128) / 128.0f;
        default: return 0.0f;
    }
}

/** Dialogue-weighted mono downmix, as AudioWindowReader.downmixWeights: centre 2, LFE 0,
 *  front 1, every other (surround) channel 0.5, normalised to sum 1. */
void downmixWeights(const AVChannelLayout &layout, int channels, std::vector<float> &w) {
    w.assign(channels, 1.0f);
    if (channels > 2) {
        for (int c = 0; c < channels; c++) {
            switch (av_channel_layout_channel_from_index(&layout, c)) {
                case AV_CHAN_FRONT_LEFT: case AV_CHAN_FRONT_RIGHT: w[c] = 1.0f; break;
                case AV_CHAN_FRONT_CENTER: w[c] = 2.0f; break;
                case AV_CHAN_LOW_FREQUENCY: case AV_CHAN_LOW_FREQUENCY_2: w[c] = 0.0f; break;
                default: w[c] = 0.5f; break;
            }
        }
    }
    float total = 0.0f;
    for (float v : w) total += v;
    if (total <= 0.0f) { w.assign(channels, 1.0f / channels); return; }
    for (float &v : w) v /= total;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_aivicventures_subtitletranslator_audio_FfmpegAudio_license(JNIEnv *env, jobject) {
    return env->NewStringUTF(avcodec_license());
}

JNIEXPORT jstring JNICALL
Java_com_aivicventures_subtitletranslator_audio_FfmpegAudio_version(JNIEnv *env, jobject) {
    return env->NewStringUTF(av_version_info());
}

JNIEXPORT jboolean JNICALL
Java_com_aivicventures_subtitletranslator_audio_FfmpegAudio_hasDecoder(JNIEnv *env, jobject, jstring name) {
    const char *n = env->GetStringUTFChars(name, nullptr);
    const bool ok = avcodec_find_decoder_by_name(n) != nullptr;
    env->ReleaseStringUTFChars(name, n);
    return ok;
}

JNIEXPORT jlong JNICALL
Java_com_aivicventures_subtitletranslator_audio_FfmpegAudio_open(
        JNIEnv *env, jobject, jstring name, jbyteArray extradata, jint sampleRate, jint channels) {
    const char *n = env->GetStringUTFChars(name, nullptr);
    const AVCodec *codec = avcodec_find_decoder_by_name(n);
    env->ReleaseStringUTFChars(name, n);
    if (!codec) return 0;
    auto *d = new Decoder();
    d->ctx = avcodec_alloc_context3(codec);
    d->ctx->sample_rate = sampleRate;
    if (channels > 0) av_channel_layout_default(&d->ctx->ch_layout, channels);
    d->ctx->pkt_timebase = AVRational{1, 1000000};  // packet times are in microseconds
    d->ctx->request_sample_fmt = AV_SAMPLE_FMT_FLT;
    if (extradata) {
        const jsize size = env->GetArrayLength(extradata);
        d->ctx->extradata = static_cast<uint8_t *>(av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE));
        env->GetByteArrayRegion(extradata, 0, size, reinterpret_cast<jbyte *>(d->ctx->extradata));
        d->ctx->extradata_size = size;
    }
    d->packet = av_packet_alloc();
    d->frame = av_frame_alloc();
    const int err = avcodec_open2(d->ctx, codec, nullptr);
    if (err < 0) {
        LOGW("avcodec_open2 failed: %d", err);
        avcodec_free_context(&d->ctx);
        av_packet_free(&d->packet);
        av_frame_free(&d->frame);
        delete d;
        return 0;
    }
    return reinterpret_cast<jlong>(d);
}

/** Decodes one packet; returns mono samples (possibly none) at [rate], or null on a hard error. */
JNIEXPORT jfloatArray JNICALL
Java_com_aivicventures_subtitletranslator_audio_FfmpegAudio_decode(
        JNIEnv *env, jobject, jlong ptr, jobject buffer, jint size, jlong ptsUs) {
    auto *d = reinterpret_cast<Decoder *>(ptr);
    auto *src = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (!src) return nullptr;
    if (av_new_packet(d->packet, size) < 0) return nullptr;
    memcpy(d->packet->data, src, size);
    d->packet->pts = ptsUs;
    int err = avcodec_send_packet(d->ctx, d->packet);
    av_packet_unref(d->packet);
    std::vector<float> mono, weights;
    d->firstPts = AV_NOPTS_VALUE;
    if (err < 0 && err != AVERROR(EAGAIN)) {
        // A damaged packet: skip it like a player would, keep decoding the next ones.
        return env->NewFloatArray(0);
    }
    while ((err = avcodec_receive_frame(d->ctx, d->frame)) >= 0) {
        const int channels = d->frame->ch_layout.nb_channels;
        if (d->firstPts == AV_NOPTS_VALUE) d->firstPts = d->frame->best_effort_timestamp;
        if (static_cast<int>(weights.size()) != channels) downmixWeights(d->frame->ch_layout, channels, weights);
        const size_t base = mono.size();
        mono.resize(base + d->frame->nb_samples);
        for (int i = 0; i < d->frame->nb_samples; i++) {
            float sum = 0.0f;
            for (int c = 0; c < channels; c++) sum += weights[c] * sampleAt(d->frame, c, i, channels);
            mono[base + i] = sum;
        }
        av_frame_unref(d->frame);
    }
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(mono.size()));
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(mono.size()), mono.data());
    return out;
}

JNIEXPORT jint JNICALL
Java_com_aivicventures_subtitletranslator_audio_FfmpegAudio_sampleRate(JNIEnv *, jobject, jlong ptr) {
    return reinterpret_cast<Decoder *>(ptr)->ctx->sample_rate;
}

/** Start time (µs) of the first frame the last decode() returned; Long.MIN_VALUE if unknown. */
JNIEXPORT jlong JNICALL
Java_com_aivicventures_subtitletranslator_audio_FfmpegAudio_lastPts(JNIEnv *, jobject, jlong ptr) {
    const int64_t pts = reinterpret_cast<Decoder *>(ptr)->firstPts;
    return pts == AV_NOPTS_VALUE ? INT64_MIN : pts;
}

JNIEXPORT void JNICALL
Java_com_aivicventures_subtitletranslator_audio_FfmpegAudio_flush(JNIEnv *, jobject, jlong ptr) {
    avcodec_flush_buffers(reinterpret_cast<Decoder *>(ptr)->ctx);
}

JNIEXPORT void JNICALL
Java_com_aivicventures_subtitletranslator_audio_FfmpegAudio_close(JNIEnv *, jobject, jlong ptr) {
    auto *d = reinterpret_cast<Decoder *>(ptr);
    if (!d) return;
    avcodec_free_context(&d->ctx);
    av_packet_free(&d->packet);
    av_frame_free(&d->frame);
    delete d;
}

}  // extern "C"
