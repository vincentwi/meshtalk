/**
 * Opus JNI bindings for MeshTalk
 *
 * Wraps libopus encoder/decoder for 16kHz mono voice.
 * Requires libopus.so to be present in jniLibs at build time.
 */
#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <android/log.h>

// Opus header — will resolve once libopus is added to the build
#include <opus/opus.h>

#define TAG "OpusJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

// Max encoded packet size (Opus recommends 4000 bytes for safety)
#define MAX_PACKET_SIZE 4000

// Global encoder/decoder state (single-instance design)
static OpusEncoder *encoder = nullptr;
static OpusDecoder *decoder = nullptr;
static int g_channels = 1;

extern "C" {

/**
 * Initialize Opus encoder and decoder.
 * Returns 0 on success, negative Opus error code on failure.
 */
JNIEXPORT jint JNICALL
Java_com_meshtalk_app_audio_OpusCodec_nativeInit(
        JNIEnv *env, jobject /* this */,
        jint sampleRate, jint channels) {

    int err;

    // Clean up any previous state
    if (encoder) { opus_encoder_destroy(encoder); encoder = nullptr; }
    if (decoder) { opus_decoder_destroy(decoder); decoder = nullptr; }

    g_channels = channels;

    // Create encoder — VOIP application, optimised for speech
    encoder = opus_encoder_create(sampleRate, channels, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || !encoder) {
        LOGE("opus_encoder_create failed: %s", opus_strerror(err));
        return err;
    }

    // Encoder tuning for low-bandwidth walkie-talkie use
    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(16000));          // 16 kbps
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(5));           // balanced CPU/quality
    opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(encoder, OPUS_SET_DTX(1));                  // discontinuous transmission
    opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(1));           // forward error correction
    opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(15));    // expect ~15% loss over mesh

    // Create decoder
    decoder = opus_decoder_create(sampleRate, channels, &err);
    if (err != OPUS_OK || !decoder) {
        LOGE("opus_decoder_create failed: %s", opus_strerror(err));
        opus_encoder_destroy(encoder);
        encoder = nullptr;
        return err;
    }

    LOGI("Opus initialized: %dHz, %dch, 16kbps VOIP", sampleRate, channels);
    return 0;
}

/**
 * Encode PCM samples to an Opus packet.
 * Returns a byte[] with the compressed data, or null on error.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_meshtalk_app_audio_OpusCodec_nativeEncode(
        JNIEnv *env, jobject /* this */,
        jshortArray pcmData, jint frameSize) {

    if (!encoder) {
        LOGE("encode called but encoder is null");
        return nullptr;
    }

    jshort *pcm = env->GetShortArrayElements(pcmData, nullptr);
    if (!pcm) return nullptr;

    unsigned char packet[MAX_PACKET_SIZE];
    int nbBytes = opus_encode(encoder, pcm, frameSize, packet, MAX_PACKET_SIZE);

    env->ReleaseShortArrayElements(pcmData, pcm, JNI_ABORT);

    if (nbBytes < 0) {
        LOGE("opus_encode error: %s", opus_strerror(nbBytes));
        return nullptr;
    }

    // Wrap encoded bytes in a Java byte[]
    jbyteArray result = env->NewByteArray(nbBytes);
    if (result) {
        env->SetByteArrayRegion(result, 0, nbBytes, reinterpret_cast<jbyte *>(packet));
    }
    return result;
}

/**
 * Decode an Opus packet to PCM samples.
 * Returns a short[] with the decoded audio, or null on error.
 */
JNIEXPORT jshortArray JNICALL
Java_com_meshtalk_app_audio_OpusCodec_nativeDecode(
        JNIEnv *env, jobject /* this */,
        jbyteArray opusData, jint frameSize) {

    if (!decoder) {
        LOGE("decode called but decoder is null");
        return nullptr;
    }

    jbyte *data = env->GetByteArrayElements(opusData, nullptr);
    jint dataLen = env->GetArrayLength(opusData);
    if (!data) return nullptr;

    // Allocate output PCM buffer
    int pcmSamples = frameSize * g_channels;
    auto *pcm = new opus_int16[pcmSamples];

    int decodedSamples = opus_decode(
            decoder,
            reinterpret_cast<const unsigned char *>(data),
            dataLen,
            pcm,
            frameSize,
            0  // no FEC decode
    );

    env->ReleaseByteArrayElements(opusData, data, JNI_ABORT);

    if (decodedSamples < 0) {
        LOGE("opus_decode error: %s", opus_strerror(decodedSamples));
        delete[] pcm;
        return nullptr;
    }

    int totalSamples = decodedSamples * g_channels;
    jshortArray result = env->NewShortArray(totalSamples);
    if (result) {
        env->SetShortArrayRegion(result, 0, totalSamples, pcm);
    }

    delete[] pcm;
    return result;
}

/**
 * Release encoder and decoder resources.
 */
JNIEXPORT void JNICALL
Java_com_meshtalk_app_audio_OpusCodec_nativeRelease(
        JNIEnv *env, jobject /* this */) {

    if (encoder) {
        opus_encoder_destroy(encoder);
        encoder = nullptr;
    }
    if (decoder) {
        opus_decoder_destroy(decoder);
        decoder = nullptr;
    }
    LOGI("Opus released");
}

} // extern "C"
