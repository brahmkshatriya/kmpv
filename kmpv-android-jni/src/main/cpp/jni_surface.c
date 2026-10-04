#include "jni_internal.h"

#include <stdint.h>

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeAttachSurface(
    JNIEnv *env,
    jclass clazz,
    jlong raw_handle,
    jobject surface
) {
    (void)clazz;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    if (!handle || !surface) return MPV_ERROR_INVALID_PARAMETER;

    pthread_mutex_lock(&handle->api_mutex);
    if (handle->surface) {
        pthread_mutex_unlock(&handle->api_mutex);
        return MPV_ERROR_INVALID_PARAMETER;
    }

    jobject global_surface = (*env)->NewGlobalRef(env, surface);
    if (!global_surface) {
        pthread_mutex_unlock(&handle->api_mutex);
        return MPV_ERROR_NOMEM;
    }

    int64_t wid = (int64_t)(intptr_t)global_surface;
    int result = handle->api->set_option(handle->mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result >= 0) {
        handle->surface = global_surface;
    } else {
        (*env)->DeleteGlobalRef(env, global_surface);
    }
    pthread_mutex_unlock(&handle->api_mutex);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeDetachSurface(
    JNIEnv *env,
    jclass clazz,
    jlong raw_handle
) {
    (void)clazz;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    if (!handle) return MPV_ERROR_INVALID_PARAMETER;

    pthread_mutex_lock(&handle->api_mutex);
    int64_t wid = 0;
    int result = handle->api->set_option(handle->mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (handle->surface) {
        (*env)->DeleteGlobalRef(env, handle->surface);
        handle->surface = NULL;
    }
    pthread_mutex_unlock(&handle->api_mutex);
    return result;
}
