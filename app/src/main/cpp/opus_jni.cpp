// Placeholder for Opus JNI bindings
#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_meshtalk_app_audio_OpusCodec_getVersion(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF("opus_jni placeholder");
}
