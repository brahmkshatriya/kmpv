#include "jni_internal.h"

#include <stdlib.h>

static JavaVM *g_java_vm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_java_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeCreate(JNIEnv *env, jobject self) {
    (void)env;
    (void)self;
    const MpvApi *api = kmpv_mpv_api();
    if (!api) return 0;
    if (!kmpv_register_java_vm(g_java_vm)) return 0;

    AndroidMpvHandle *handle = (AndroidMpvHandle *)calloc(1, sizeof(AndroidMpvHandle));
    if (!handle) return 0;
    handle->api = api;
    handle->mpv = api->create();
    if (!handle->mpv) {
        free(handle);
        return 0;
    }
    pthread_mutex_init(&handle->api_mutex, NULL);
    pthread_mutex_init(&handle->wait_mutex, NULL);
    atomic_init(&handle->stopping, 0);
    atomic_init(&handle->initialized, 0);
    return kmpv_to_handle(handle);
}

JNIEXPORT jstring JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeLastLoadError(JNIEnv *env, jobject self) {
    (void)self;
    return (*env)->NewStringUTF(env, kmpv_mpv_load_error());
}

JNIEXPORT void JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeDestroy(JNIEnv *env, jobject self, jlong raw_handle) {
    (void)env;
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    if (!handle) return;

    if (atomic_load_explicit(&handle->initialized, memory_order_acquire)) {
        atomic_store_explicit(&handle->stopping, 1, memory_order_release);
        handle->api->wakeup(handle->mpv);
        pthread_mutex_lock(&handle->wait_mutex);
        pthread_mutex_unlock(&handle->wait_mutex);
    }

    pthread_mutex_lock(&handle->api_mutex);
    handle->api->terminate_destroy(handle->mpv);
    handle->mpv = NULL;
    if (handle->surface) {
        (*env)->DeleteGlobalRef(env, handle->surface);
        handle->surface = NULL;
    }
    pthread_mutex_unlock(&handle->api_mutex);

    pthread_mutex_destroy(&handle->wait_mutex);
    pthread_mutex_destroy(&handle->api_mutex);
    free(handle);
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeInitialize(JNIEnv *env, jobject self, jlong raw_handle) {
    (void)env;
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    if (!handle) return MPV_ERROR_INVALID_PARAMETER;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->initialize(handle->mpv);
    if (result >= 0) {
        atomic_store_explicit(&handle->stopping, 0, memory_order_release);
        atomic_store_explicit(&handle->initialized, 1, memory_order_release);
    }
    pthread_mutex_unlock(&handle->api_mutex);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeClientName(JNIEnv *env, jobject self, jlong raw_handle) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    if (!handle) return NULL;
    pthread_mutex_lock(&handle->api_mutex);
    const char *name = handle->api->client_name(handle->mpv);
    jstring result = name ? (*env)->NewStringUTF(env, name) : NULL;
    pthread_mutex_unlock(&handle->api_mutex);
    return result;
}

JNIEXPORT jlong JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeClientId(JNIEnv *env, jobject self, jlong raw_handle) {
    (void)env;
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    if (!handle) return 0;
    pthread_mutex_lock(&handle->api_mutex);
    int64_t result = handle->api->client_id(handle->mpv);
    pthread_mutex_unlock(&handle->api_mutex);
    return (jlong)result;
}

JNIEXPORT jstring JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeErrorString(JNIEnv *env, jobject self, jint code) {
    (void)self;
    const MpvApi *api = kmpv_mpv_api();
    if (!api) return (*env)->NewStringUTF(env, kmpv_mpv_load_error());
    const char *message = api->error_string(code);
    return (*env)->NewStringUTF(env, message ? message : "unknown mpv error");
}
