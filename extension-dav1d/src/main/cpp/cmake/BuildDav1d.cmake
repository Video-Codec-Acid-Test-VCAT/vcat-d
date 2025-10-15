# cmake/BuildDav1d.cmake
#
# Produces:
#   - ExternalProject: ext_dav1d_<abi>
#   - IMPORTED STATIC target: dav1d_static_<abi>
#   - Alias target: dav1d::dav1d  (stable name your JNI target links against)

include(ExternalProject)

# ---- Sanity checks -----------------------------------------------------------
if(NOT ANDROID)
  message(FATAL_ERROR "BuildDav1d.cmake must be used in an Android NDK build.")
endif()

if(NOT DEFINED DAV1D_GIT_REPO)
  set(DAV1D_GIT_REPO "https://code.videolan.org/videolan/dav1d.git")
endif()
if(NOT DEFINED DAV1D_GIT_TAG)
  set(DAV1D_GIT_TAG "1.5.1")
endif()

# ---- Locate NDK toolchain bin dir (portable) --------------------------------
set(_toolchain_bin "")

if(DEFINED ANDROID_NDK_TOOLCHAIN_ROOT AND EXISTS "${ANDROID_NDK_TOOLCHAIN_ROOT}/bin/clang")
  set(_toolchain_bin "${ANDROID_NDK_TOOLCHAIN_ROOT}/bin")
elseif(CMAKE_C_COMPILER)
  get_filename_component(_maybe_bin "${CMAKE_C_COMPILER}" DIRECTORY)
  if(EXISTS "${_maybe_bin}/clang")
    set(_toolchain_bin "${_maybe_bin}")
  endif()
elseif(DEFINED ANDROID_NDK AND EXISTS "${ANDROID_NDK}/toolchains/llvm/prebuilt")
  file(GLOB _ndk_hosts "${ANDROID_NDK}/toolchains/llvm/prebuilt/*")
  foreach(_host_dir IN LISTS _ndk_hosts)
    if(EXISTS "${_host_dir}/bin/clang")
      set(_toolchain_bin "${_host_dir}/bin")
      break()
    endif()
  endforeach()
endif()

if(NOT _toolchain_bin OR NOT EXISTS "${_toolchain_bin}/clang")
  message(FATAL_ERROR
    "Could not locate NDK toolchain bin dir. Tried:\n"
    "  ANDROID_NDK_TOOLCHAIN_ROOT/bin,\n"
    "  directory of CMAKE_C_COMPILER,\n"
    "  and ${ANDROID_NDK}/toolchains/llvm/prebuilt/*/bin")
endif()

set(TOOLCHAIN_BIN "${_toolchain_bin}")
message(STATUS "NDK toolchain bin: ${TOOLCHAIN_BIN}")

# ---- Per-ABI settings --------------------------------------------------------
set(_abi "${CMAKE_ANDROID_ARCH_ABI}")
if(NOT _abi)
  message(FATAL_ERROR "CMAKE_ANDROID_ARCH_ABI not set.")
endif()

# ---- Android API level detection (robust) ------------------------------------
# Prefer Android toolchain variables, then fall back to ANDROID_PLATFORM, then CMake.
set(_api "")

# 1) Newer NDK toolchain exports one of these:
if(DEFINED CMAKE_ANDROID_API AND CMAKE_ANDROID_API)
  set(_api "${CMAKE_ANDROID_API}")
elseif(DEFINED ANDROID_PLATFORM_LEVEL AND ANDROID_PLATFORM_LEVEL)
  set(_api "${ANDROID_PLATFORM_LEVEL}")
endif()

# 2) Parse from ANDROID_PLATFORM like "android-29"
if(NOT _api AND DEFINED ANDROID_PLATFORM)
  string(REGEX MATCH "([0-9]+)" _api "${ANDROID_PLATFORM}")
endif()

# 3) Fall back to CMake's idea (ensure all digits, not just one)
if(NOT _api AND CMAKE_SYSTEM_VERSION)
  string(REGEX MATCH "([0-9]+)" _api "${CMAKE_SYSTEM_VERSION}")
endif()

# Final fallback
if(NOT _api)
  set(_api 21)
endif()

message(STATUS "Android API level detected: ${_api}")


if(_abi STREQUAL "arm64-v8a")
  set(_triple "aarch64-linux-android")
  set(_cpu_family "aarch64")
  set(_cpu "armv8")
  set(_c_name   "${TOOLCHAIN_BIN}/${_triple}${_api}-clang")
  set(_cxx_name "${TOOLCHAIN_BIN}/${_triple}${_api}-clang++")
elseif(_abi STREQUAL "armeabi-v7a")
  set(_triple "armv7a-linux-androideabi")
  set(_cpu_family "arm")
  set(_cpu "armv7")
  set(_c_name   "${TOOLCHAIN_BIN}/${_triple}${_api}-clang")
  set(_cxx_name "${TOOLCHAIN_BIN}/${_triple}${_api}-clang++")
else()
  message(FATAL_ERROR "Unsupported ABI '${_abi}' in BuildDav1d.cmake")
endif()

# Common bin tools
set(_ar     "${TOOLCHAIN_BIN}/llvm-ar")
set(_strip  "${TOOLCHAIN_BIN}/llvm-strip")
set(_ranlib "${TOOLCHAIN_BIN}/llvm-ranlib")

# ---- Meson & Ninja -----------------------------------------------------------
if(NOT CMAKE_MAKE_PROGRAM)
  message(FATAL_ERROR "CMAKE_MAKE_PROGRAM (ninja) not found.")
endif()

find_program(MESON_EXECUTABLE meson)
set(_meson_cmd "")
if(MESON_EXECUTABLE)
  set(_meson_cmd "${MESON_EXECUTABLE}")
else()
  find_package(Python3 COMPONENTS Interpreter QUIET)
  if(Python3_Interpreter_FOUND)
    set(_meson_cmd "${Python3_EXECUTABLE} -m mesonbuild.mesonmain")
  else()
    message(FATAL_ERROR "Meson not found. Install 'meson' or ensure 'python3 -m mesonbuild.mesonmain' works.")
  endif()
endif()

# ---- Paths for build/install -------------------------------------------------
set(_root_dir  "${CMAKE_CURRENT_BINARY_DIR}/_dav1d/${_abi}")
set(_src_dir   "${_root_dir}/src")
set(_build_dir "${_root_dir}/build")
set(_prefix    "${_root_dir}/prefix")

# Make sure IMPORTED interface paths exist at configure time (AGP is strict).
file(MAKE_DIRECTORY "${_prefix}/include")
file(MAKE_DIRECTORY "${_prefix}/lib")

file(MAKE_DIRECTORY "${_root_dir}")

# Flags – keep minimal; NDK compilers already encode target+API
set(_c_args   "-fPIC")
set(_cxx_args "-fPIC")
set(_ld_args  "")

# Meson cross file
set(_cross_file "${_root_dir}/meson-cross.ini")
file(WRITE "${_cross_file}" "
[binaries]
c = '${_c_name}'
cpp = '${_cxx_name}'
ar = '${_ar}'
strip = '${_strip}'
ranlib = '${_ranlib}'
pkgconfig = 'false'

[properties]
needs_exe_wrapper = true

[host_machine]
system = 'android'
cpu_family = '${_cpu_family}'
cpu = '${_cpu}'
endian = 'little'

[built-in options]
c_args = ['${_c_args}']
cpp_args = ['${_cxx_args}']
c_link_args = ['${_ld_args}']
cpp_link_args = ['${_ld_args}']
default_library = 'static'
")

# ---- ExternalProject: fetch, build, install ---------------------------------
set(_byproduct_lib "${_prefix}/lib/libdav1d.a")
set(_byproduct_hdr "${_prefix}/include/dav1d/dav1d.h")  # representative header

ExternalProject_Add(ext_dav1d_${_abi}
  GIT_REPOSITORY     ${DAV1D_GIT_REPO}
  GIT_TAG            ${DAV1D_GIT_TAG}
  GIT_SHALLOW        1
  SOURCE_DIR         "${_src_dir}"
  BINARY_DIR         "${_build_dir}"

  CONFIGURE_COMMAND  ${_meson_cmd} setup
                     "${_build_dir}" "${_src_dir}"
                     --cross-file "${_cross_file}"
                     --prefix "${_prefix}"
                     --default-library static
                     -Denable_tests=false
                     -Denable_tools=false

  BUILD_COMMAND      ${CMAKE_MAKE_PROGRAM} -C "${_build_dir}"
  INSTALL_COMMAND    ${CMAKE_MAKE_PROGRAM} -C "${_build_dir}" install

  BUILD_BYPRODUCTS   "${_byproduct_lib}" "${_byproduct_hdr}"
  USES_TERMINAL_BUILD 1
  USES_TERMINAL_INSTALL 1
)

# ---- Imported target + alias -------------------------------------------------
set(_imp_target "dav1d_static_${_abi}")
add_library(${_imp_target} STATIC IMPORTED GLOBAL)

# (fixed) close the brace on IMPORTED_LOCATION and set includes, too
set_target_properties(${_imp_target} PROPERTIES
  IMPORTED_LOCATION             "${_byproduct_lib}"
  INTERFACE_INCLUDE_DIRECTORIES "${_prefix}/include"
)

add_dependencies(${_imp_target} ext_dav1d_${_abi})

# Stable alias for consumers
add_library(dav1d::dav1d ALIAS ${_imp_target})

# (Optional) export a couple of vars
set(DAV1D_IMPORTED_TARGET ${_imp_target} CACHE INTERNAL "")
set(DAV1D_PREFIX          ${_prefix}     CACHE INTERNAL "")
set(DAV1D_LIB             ${_byproduct_lib} CACHE INTERNAL "")
