/**
 * Speex AEC (Acoustic Echo Cancellation) JNI bindings for MeshTalk
 *
 * Wraps the SpeexDSP echo cancellation module for hands-free
 * walkie-talkie on AR glasses (speaker close to mic).
 * Requires libspeexdsp.so to be present in jniLibs at build time.
 */
#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <android/log.h>

// SpeexDSP headers — will resolve once libspeexdsp is added to the build
#include <speex/speex_echo.h>
#include <speex/speex_preprocess.h>

#define TAG "SpeexAecJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

// Global AEC state (single-instance design)
static SpeexEchoState      *echoState      = nullptr;
static SpeexPreprocessState *preprocessState = nullptr;
static int g_frameSize = 0;

extern "C" {

/**
 * Initialize Speex echo canceller and preprocessor.
 * Returns 0 on success, -1 on failure.
 */
JNIEXPORT jint JNICALL
Java_com_openclaw_app_audio_SpeexAec_nativeInit(
        JNIEnv *env, jobject /* this */,
        jint frameSize, jint filterLength, jint sampleRate) {

    // Clean up previous state
    if (echoState) { speex_echo_state_destroy(echoState); echoState = nullptr; }
    if (preprocessState) { speex_preprocess_state_destroy(preprocessState); preprocessState = nullptr; }

    g_frameSize = frameSize;

    // Create echo canceller
    echoState = speex_echo_state_init(frameSize, filterLength);
    if (!echoState) {
        LOGE("speex_echo_state_init failed");
        return -1;
    }

    // Set sample rate on the echo canceller
    spx_int32_t rate = sampleRate;
    speex_echo_ctl(echoState, SPEEX_ECHO_SET_SAMPLING_RATE, &rate);

    // Create preprocessor for noise suppression + AGC alongside AEC
    preprocessState = speex_preprocess_state_init(frameSize, sampleRate);
    if (!preprocessState) {
        LOGE("speex_preprocess_state_init failed");
        speex_echo_state_destroy(echoState);
        echoState = nullptr;
        return -1;
    }

    // Link preprocessor to echo canceller
    speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_ECHO_STATE, echoState);

    // Enable noise suppression (moderate, -30 dB)
    spx_int32_t noiseSuppress = -30;
    spx_int32_t denoise = 1;
    speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_DENOISE, &denoise);
    speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &noiseSuppress);

    // Enable AGC
    spx_int32_t agc = 1;
    spx_int32_t agcLevel = 24000;
    speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_AGC, &agc);
    speex_preprocess_ctl(preprocessState, SPEEX_PREPROCESS_SET_AGC_LEVEL, &agcLevel);

    LOGI("Speex AEC initialized: frame=%d, filter=%d, rate=%d",
         frameSize, filterLength, sampleRate);
    return 0;
}

/**
 * Process a mic frame through echo cancellation + preprocessing.
 * micData   = captured microphone audio (contains echo)
 * speakerData = reference signal (what was played to the speaker)
 * Returns a short[] with the cleaned audio, or null on error.
 */
JNIEXPORT jshortArray JNICALL
Java_com_openclaw_app_audio_SpeexAec_nativeProcess(
        JNIEnv *env, jobject /* this */,
        jshortArray micData, jshortArray speakerData) {

    if (!echoState || !preprocessState) {
        LOGE("process called but AEC not initialized");
        return nullptr;
    }

    jshort *mic     = env->GetShortArrayElements(micData, nullptr);
    jshort *speaker = env->GetShortArrayElements(speakerData, nullptr);
    if (!mic || !speaker) {
        if (mic)     env->ReleaseShortArrayElements(micData, mic, JNI_ABORT);
        if (speaker) env->ReleaseShortArrayElements(speakerData, speaker, JNI_ABORT);
        return nullptr;
    }

    // Output buffer for echo-cancelled audio
    auto *out = new spx_int16_t[g_frameSize];

    // Run echo cancellation: out = mic - echo(speaker)
    speex_echo_cancellation(echoState, mic, speaker, out);

    // Run preprocessor (noise suppression + AGC) in-place
    speex_preprocess_run(preprocessState, out);

    env->ReleaseShortArrayElements(micData, mic, JNI_ABORT);
    env->ReleaseShortArrayElements(speakerData, speaker, JNI_ABORT);

    // Wrap output in Java short[]
    jshortArray result = env->NewShortArray(g_frameSize);
    if (result) {
        env->SetShortArrayRegion(result, 0, g_frameSize, out);
    }

    delete[] out;
    return result;
}

/**
 * Release all Speex AEC resources.
 */
JNIEXPORT void JNICALL
Java_com_openclaw_app_audio_SpeexAec_nativeRelease(
        JNIEnv *env, jobject /* this */) {

    if (preprocessState) {
        speex_preprocess_state_destroy(preprocessState);
        preprocessState = nullptr;
    }
    if (echoState) {
        speex_echo_state_destroy(echoState);
        echoState = nullptr;
    }
    LOGI("Speex AEC released");
}

} // extern "C"
