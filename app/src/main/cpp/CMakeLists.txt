cmake_minimum_required(VERSION 3.22.1)
project("meshtalk_native")

# ---------------------------------------------------------------------------
# Opus JNI wrapper
# ---------------------------------------------------------------------------
# Note: libopus headers expected in <sysroot>/usr/include/opus/ or a custom
# include path. The prebuilt libopus.so must be placed in
# app/src/main/jniLibs/<abi>/ before building.
#
# Uncomment and adjust the following when libopus.so is available:
# add_library(opus SHARED IMPORTED)
# set_target_properties(opus PROPERTIES
#     IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopus.so)

add_library(opus_jni SHARED opus_jni.cpp)
target_include_directories(opus_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/include   # local headers fallback
)
target_link_libraries(opus_jni
    log
    # opus  # uncomment when libopus.so is available
)

# ---------------------------------------------------------------------------
# Speex AEC JNI wrapper
# ---------------------------------------------------------------------------
# Note: libspeexdsp headers expected in <sysroot>/usr/include/speex/ or a
# custom include path. The prebuilt libspeexdsp.so must be placed in
# app/src/main/jniLibs/<abi>/ before building.
#
# Uncomment and adjust the following when libspeexdsp.so is available:
# add_library(speexdsp SHARED IMPORTED)
# set_target_properties(speexdsp PROPERTIES
#     IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libspeexdsp.so)

add_library(speex_aec_jni SHARED speex_aec_jni.cpp)
target_include_directories(speex_aec_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/include   # local headers fallback
)
target_link_libraries(speex_aec_jni
    log
    # speexdsp  # uncomment when libspeexdsp.so is available
)
