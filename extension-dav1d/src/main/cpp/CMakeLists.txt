cmake_minimum_required(VERSION 3.18)
project(dav1d_jni LANGUAGES C CXX)

add_library(dav1d_jni SHARED dav1d_jni.cc)

# --- dav1d headers ---
target_include_directories(dav1d_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/dav1d/include
)

# --- Prebuilt dav1d .so per ABI ---
add_library(dav1d SHARED IMPORTED GLOBAL)
set_target_properties(dav1d PROPERTIES
        IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libdav1d.so"
)

# --- libyuv ---
# Point CMake at the libyuv repo and build it as a subproject.
get_filename_component(LIBYUV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libyuv" ABSOLUTE)
add_subdirectory(${LIBYUV_DIR} ${CMAKE_CURRENT_BINARY_DIR}/libyuv_build EXCLUDE_FROM_ALL)

# Tell our JNI target where the libyuv headers are.
target_include_directories(dav1d_jni PRIVATE ${LIBYUV_DIR}/include)

# --- NDK libs (ANativeWindow + log) ---
find_library(log-lib     log)
find_library(android-lib android)

# --- Link everything ---
target_link_libraries(dav1d_jni
        PRIVATE
        dav1d
        yuv             # libyuv target from add_subdirectory
        ${android-lib}  # ANativeWindow_*
        ${log-lib}
        atomic
        m
)
