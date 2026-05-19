// Placeholder for Speex AEC JNI bindings
#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_meshtalk_app_audio_SpeexAec_getVersion(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF("speex_aec_jni placeholder");
}
