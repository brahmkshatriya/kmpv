#pragma once

#include "binary.h"
#include "mpv_dynamic.h"

#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>

typedef struct AndroidMpvHandle {
    const MpvApi *api;
    mpv_handle *mpv;
    pthread_mutex_t api_mutex;
    pthread_mutex_t wait_mutex;
    atomic_int stopping;
    atomic_int initialized;
    jobject surface;
} AndroidMpvHandle;

typedef struct JniArgs {
    JNIEnv *env;
    jsize count;
    jstring *strings;
    const char **utf;
    const char **argv;
} JniArgs;

AndroidMpvHandle *kmpv_from_handle(jlong value);
jlong kmpv_to_handle(AndroidMpvHandle *value);
int kmpv_jni_args_init(JNIEnv *env, jobjectArray array, JniArgs *args);
void kmpv_jni_args_destroy(JniArgs *args);
int kmpv_jni_string(JNIEnv *env, jstring value, const char **utf);
void kmpv_jni_string_release(JNIEnv *env, jstring value, const char *utf);
