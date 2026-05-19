cmake_minimum_required(VERSION 3.22.1)
project("meshtalk_native")

# Opus JNI wrapper (placeholder)
add_library(opus_jni SHARED opus_jni.cpp)
target_link_libraries(opus_jni log)

# Speex AEC JNI wrapper (placeholder)
add_library(speex_aec_jni SHARED speex_aec_jni.cpp)
target_link_libraries(speex_aec_jni log)
