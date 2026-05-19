cmake_minimum_required(VERSION 3.22.1)
project("meshtalk_native")

# ---------------------------------------------------------------------------
# Import prebuilt libopus (cross-compiled for Android arm64-v8a)
# ---------------------------------------------------------------------------
add_library(opus SHARED IMPORTED)
set_target_properties(opus PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopus.so)

# ---------------------------------------------------------------------------
# Opus JNI wrapper
# ---------------------------------------------------------------------------
add_library(opus_jni SHARED opus_jni.cpp)
target_include_directories(opus_jni PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(opus_jni opus log)

# ---------------------------------------------------------------------------
# Import prebuilt libspeexdsp (cross-compiled for Android arm64-v8a)
# ---------------------------------------------------------------------------
add_library(speexdsp SHARED IMPORTED)
set_target_properties(speexdsp PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libspeexdsp.so)

# ---------------------------------------------------------------------------
# Speex AEC JNI wrapper
# ---------------------------------------------------------------------------
add_library(speex_aec_jni SHARED speex_aec_jni.cpp)
target_include_directories(speex_aec_jni PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(speex_aec_jni speexdsp log)
