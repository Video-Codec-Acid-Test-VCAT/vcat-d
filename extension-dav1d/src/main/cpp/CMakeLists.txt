cmake_minimum_required(VERSION 3.18)
project(extension_dav1d LANGUAGES C CXX)

# Guard: this project is meant for Android NDK builds.
if(NOT ANDROID)
  message(FATAL_ERROR "This project must be built with the Android NDK toolchain.")
endif()

# Toolchain / build basics
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# -----------------------------------------------------------------------------
# dav1d: fetch & build (Meson/Ninja inside ExternalProject)
# Gradle may override with -DDAV1D_GIT_TAG / -DDAV1D_GIT_REPO
# -----------------------------------------------------------------------------
set(DAV1D_GIT_REPO "https://code.videolan.org/videolan/dav1d.git" CACHE STRING "dav1d repo")
set(DAV1D_GIT_TAG  "1.5.1" CACHE STRING "dav1d tag")

# Expects: src/main/cpp/cmake/BuildDav1d.cmake
# Provides imported per-ABI target 'dav1d_static_${ANDROID_ABI}' and alias 'dav1d::dav1d'
include(${CMAKE_CURRENT_LIST_DIR}/cmake/BuildDav1d.cmake)

# -----------------------------------------------------------------------------
# Your JNI shared library
# -----------------------------------------------------------------------------
add_library(dav1d_jni SHARED
  dav1d_jni.cc
)

# Android system libs
find_library(ANDROID_LIB android)
find_library(LOG_LIB     log)
find_library(M_LIB       m)        # may be empty on newer NDKs

# Baseline links (same for all ABIs)
target_link_libraries(dav1d_jni
  PRIVATE
    dav1d::dav1d
    ${ANDROID_LIB}
    ${LOG_LIB}
    ${M_LIB}
)

# 32-bit ABIs sometimes need libatomic for __atomic_* helpers.
# (BuildDav1d.cmake adds atomic to dav1d’s interface on 32-bit already,
# but we also add it here for JNI if your code ever uses atomics directly.)
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "x86")
  find_library(ATOMIC_LIB atomic)
  if(ATOMIC_LIB)
    target_link_libraries(dav1d_jni PRIVATE ${ATOMIC_LIB})
  endif()
endif()

# Warnings for JNI sources
target_compile_options(dav1d_jni PRIVATE
  $<$<COMPILE_LANGUAGE:C>:-Wall -Wextra -Wno-unused-parameter>
  $<$<COMPILE_LANGUAGE:CXX>:-Wall -Wextra -Wno-unused-parameter>
)
