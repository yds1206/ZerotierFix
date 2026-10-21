cmake_minimum_required(VERSION 3.22.1)

project(ZeroTierOneJNI LANGUAGES C CXX)

# Path to the ZeroTierOne core submodule (externals/core).
set(ZT_CORE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../externals/core")

if(NOT EXISTS "${ZT_CORE_DIR}/include/ZeroTierOne.h")
    message(FATAL_ERROR "ZeroTierOne core not found at ${ZT_CORE_DIR}. Run: git submodule update --init --recursive")
endif()

file(GLOB ZT_CORE_SOURCES "${ZT_CORE_DIR}/node/*.cpp")

set(ZT_JNI_SOURCES
    "${ZT_CORE_DIR}/osdep/OSUtils.cpp"
    "${ZT_CORE_DIR}/java/jni/com_zerotierone_sdk_Node.cpp"
    "${ZT_CORE_DIR}/java/jni/ZT_jniutils.cpp"
    "${ZT_CORE_DIR}/java/jni/ZT_jnicache.cpp")

add_library(ZeroTierOneJNI SHARED ${ZT_CORE_SOURCES} ${ZT_JNI_SOURCES})

target_include_directories(ZeroTierOneJNI PRIVATE
    "${ZT_CORE_DIR}/include"
    "${ZT_CORE_DIR}/node"
    "${ZT_CORE_DIR}/ext"
    "${ZT_CORE_DIR}/ext/prometheus-cpp-lite-1.0/core/include"
    "${ZT_CORE_DIR}/ext/prometheus-cpp-lite-1.0/simpleapi/include"
    "${ZT_CORE_DIR}/java/jni")

target_compile_features(ZeroTierOneJNI PRIVATE cxx_std_17)

target_compile_options(ZeroTierOneJNI PRIVATE
    -fexceptions
    -frtti
    -fPIC
    -O3
    -Wall
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-deprecated-declarations
    -Wno-sign-compare
    -Wno-format)

target_link_libraries(ZeroTierOneJNI PRIVATE log)

# The JNI bindings are C++ but the JNI entry points must keep C linkage.
set_target_properties(ZeroTierOneJNI PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF)
