cmake_minimum_required(VERSION 3.22.1)
project(kmpv_jni C)

add_library(
    kmpv_jni
    SHARED
    binary.c
    jni_commands.c
    jni_core.c
    jni_internal.c
    jni_properties.c
    jni_surface.c
    mpv_dynamic.c
)

target_compile_features(kmpv_jni PRIVATE c_std_11)
target_compile_options(kmpv_jni PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(kmpv_jni PRIVATE dl log)
