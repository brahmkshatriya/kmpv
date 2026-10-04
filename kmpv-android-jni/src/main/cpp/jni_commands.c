#include "jni_internal.h"

#include <string.h>

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeCommand(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jobjectArray arguments
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    JniArgs args;
    memset(&args, 0, sizeof(args));
    if (!handle || !kmpv_jni_args_init(env, arguments, &args)) {
        kmpv_jni_args_destroy(&args);
        return MPV_ERROR_INVALID_PARAMETER;
    }
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->command(handle->mpv, args.argv);
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_args_destroy(&args);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeCommandResult(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jobjectArray arguments
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    JniArgs args;
    memset(&args, 0, sizeof(args));
    if (!handle || !kmpv_jni_args_init(env, arguments, &args)) {
        kmpv_jni_args_destroy(&args);
        return kmpv_code_result(env, MPV_ERROR_INVALID_PARAMETER);
    }
    mpv_node node;
    memset(&node, 0, sizeof(node));
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->command_ret(handle->mpv, args.argv, &node);
    jbyteArray encoded = kmpv_node_result(env, result, &node);
    if (result >= 0) handle->api->free_node_contents(&node);
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_args_destroy(&args);
    return encoded;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeCommandAsync(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jlong request_id,
    jobjectArray arguments
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    JniArgs args;
    memset(&args, 0, sizeof(args));
    if (!handle || !kmpv_jni_args_init(env, arguments, &args)) {
        kmpv_jni_args_destroy(&args);
        return MPV_ERROR_INVALID_PARAMETER;
    }
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->command_async(handle->mpv, (uint64_t)request_id, args.argv);
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_args_destroy(&args);
    return result;
}

JNIEXPORT void JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeAbortAsyncCommand(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jlong request_id
) {
    (void)env;
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    if (!handle) return;
    pthread_mutex_lock(&handle->api_mutex);
    handle->api->abort_async_command(handle->mpv, (uint64_t)request_id);
    pthread_mutex_unlock(&handle->api_mutex);
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeObserveProperty(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jlong observer_id,
    jstring name,
    jint format
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    if (!handle || !kmpv_jni_string(env, name, &name_utf)) return MPV_ERROR_INVALID_PARAMETER;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->observe_property(
        handle->mpv,
        (uint64_t)observer_id,
        name_utf,
        (mpv_format)format
    );
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, name, name_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeUnobserveProperty(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jlong observer_id
) {
    (void)env;
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    if (!handle) return MPV_ERROR_INVALID_PARAMETER;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->unobserve_property(handle->mpv, (uint64_t)observer_id);
    pthread_mutex_unlock(&handle->api_mutex);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeWaitEvent(JNIEnv *env, jobject self, jlong raw_handle) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    if (!handle || atomic_load_explicit(&handle->stopping, memory_order_acquire)) return NULL;

    pthread_mutex_lock(&handle->wait_mutex);
    if (atomic_load_explicit(&handle->stopping, memory_order_acquire)) {
        pthread_mutex_unlock(&handle->wait_mutex);
        return NULL;
    }
    mpv_event *event = handle->api->wait_event(handle->mpv, -1.0);
    jbyteArray encoded = NULL;
    if (!atomic_load_explicit(&handle->stopping, memory_order_acquire)) {
        encoded = kmpv_event_result(env, event);
    }
    pthread_mutex_unlock(&handle->wait_mutex);
    return encoded;
}

JNIEXPORT void JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeWakeup(JNIEnv *env, jobject self, jlong raw_handle) {
    (void)env;
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    if (!handle) return;
    atomic_store_explicit(&handle->stopping, 1, memory_order_release);
    handle->api->wakeup(handle->mpv);
}
