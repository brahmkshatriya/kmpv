#include "jni_internal.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct NodeReader {
    const uint8_t *data;
    size_t size;
    size_t offset;
    int error;
} NodeReader;

static int reader_take(NodeReader *reader, size_t size, const uint8_t **out) {
    if (reader->error || size > reader->size - reader->offset) {
        reader->error = MPV_ERROR_INVALID_PARAMETER;
        return 0;
    }
    *out = reader->data + reader->offset;
    reader->offset += size;
    return 1;
}

static uint8_t reader_u8(NodeReader *reader) {
    const uint8_t *data = NULL;
    return reader_take(reader, 1, &data) ? data[0] : 0;
}

static int32_t reader_i32(NodeReader *reader) {
    const uint8_t *data = NULL;
    if (!reader_take(reader, 4, &data)) return 0;
    uint32_t value = 0;
    for (int index = 0; index < 4; ++index) value |= (uint32_t)data[index] << (index * 8);
    return (int32_t)value;
}

static int64_t reader_i64(NodeReader *reader) {
    const uint8_t *data = NULL;
    if (!reader_take(reader, 8, &data)) return 0;
    uint64_t value = 0;
    for (int index = 0; index < 8; ++index) value |= (uint64_t)data[index] << (index * 8);
    return (int64_t)value;
}

static char *reader_string(NodeReader *reader) {
    int32_t length = reader_i32(reader);
    if (reader->error || length < 0) {
        reader->error = MPV_ERROR_INVALID_PARAMETER;
        return NULL;
    }
    const uint8_t *data = NULL;
    if (!reader_take(reader, (size_t)length, &data)) return NULL;
    char *value = (char *)malloc((size_t)length + 1);
    if (!value) {
        reader->error = MPV_ERROR_NOMEM;
        return NULL;
    }
    if (length) memcpy(value, data, (size_t)length);
    value[length] = '\0';
    return value;
}

static void parsed_node_clear(mpv_node *node) {
    if (!node) return;
    switch (node->format) {
        case MPV_FORMAT_STRING:
        case MPV_FORMAT_OSD_STRING:
            free(node->u.string);
            break;
        case MPV_FORMAT_NODE_ARRAY:
        case MPV_FORMAT_NODE_MAP: {
            mpv_node_list *list = node->u.list;
            if (list) {
                if (list->values) {
                    for (int index = 0; index < list->num; ++index) parsed_node_clear(&list->values[index]);
                    free(list->values);
                }
                if (list->keys) {
                    for (int index = 0; index < list->num; ++index) free(list->keys[index]);
                    free(list->keys);
                }
                free(list);
            }
            break;
        }
        case MPV_FORMAT_BYTE_ARRAY:
            if (node->u.ba) {
                free(node->u.ba->data);
                free(node->u.ba);
            }
            break;
        default:
            break;
    }
    memset(node, 0, sizeof(*node));
}

static void reader_node(NodeReader *reader, mpv_node *node) {
    memset(node, 0, sizeof(*node));
    if (reader->error) return;

    mpv_format format = (mpv_format)reader_u8(reader);
    node->format = format;
    switch (format) {
        case MPV_FORMAT_NONE:
            return;
        case MPV_FORMAT_STRING:
            node->u.string = reader_string(reader);
            return;
        case MPV_FORMAT_FLAG:
            node->u.flag = reader_u8(reader) ? 1 : 0;
            return;
        case MPV_FORMAT_INT64:
            node->u.int64 = reader_i64(reader);
            return;
        case MPV_FORMAT_DOUBLE: {
            int64_t bits = reader_i64(reader);
            memcpy(&node->u.double_, &bits, sizeof(bits));
            return;
        }
        case MPV_FORMAT_NODE_ARRAY:
        case MPV_FORMAT_NODE_MAP: {
            int32_t count = reader_i32(reader);
            if (reader->error || count < 0) {
                reader->error = MPV_ERROR_INVALID_PARAMETER;
                return;
            }
            mpv_node_list *list = (mpv_node_list *)calloc(1, sizeof(mpv_node_list));
            if (!list) {
                reader->error = MPV_ERROR_NOMEM;
                return;
            }
            node->u.list = list;
            list->num = count;
            if (count == 0) return;
            list->values = (mpv_node *)calloc((size_t)count, sizeof(mpv_node));
            if (!list->values) {
                reader->error = MPV_ERROR_NOMEM;
                return;
            }
            if (format == MPV_FORMAT_NODE_MAP) {
                list->keys = (char **)calloc((size_t)count, sizeof(char *));
                if (!list->keys) {
                    reader->error = MPV_ERROR_NOMEM;
                    return;
                }
            }
            for (int index = 0; index < count && !reader->error; ++index) {
                if (list->keys) list->keys[index] = reader_string(reader);
                reader_node(reader, &list->values[index]);
            }
            return;
        }
        case MPV_FORMAT_BYTE_ARRAY: {
            int32_t count = reader_i32(reader);
            if (reader->error || count < 0) {
                reader->error = MPV_ERROR_INVALID_PARAMETER;
                return;
            }
            mpv_byte_array *array = (mpv_byte_array *)calloc(1, sizeof(mpv_byte_array));
            if (!array) {
                reader->error = MPV_ERROR_NOMEM;
                return;
            }
            node->u.ba = array;
            array->size = (size_t)count;
            if (count == 0) return;
            const uint8_t *data = NULL;
            if (!reader_take(reader, (size_t)count, &data)) return;
            array->data = malloc((size_t)count);
            if (!array->data) {
                reader->error = MPV_ERROR_NOMEM;
                return;
            }
            memcpy(array->data, data, (size_t)count);
            return;
        }
        default:
            reader->error = MPV_ERROR_INVALID_PARAMETER;
            return;
    }
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeSetOptionString(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jstring name,
    jstring value
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    const char *value_utf = NULL;
    if (!handle || !kmpv_jni_string(env, name, &name_utf) ||
        !kmpv_jni_string(env, value, &value_utf)) {
        kmpv_jni_string_release(env, name, name_utf);
        kmpv_jni_string_release(env, value, value_utf);
        return MPV_ERROR_INVALID_PARAMETER;
    }
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->set_option_string(handle->mpv, name_utf, value_utf);
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, name, name_utf);
    kmpv_jni_string_release(env, value, value_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeLoadConfigFile(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jstring path
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *path_utf = NULL;
    if (!handle || !kmpv_jni_string(env, path, &path_utf)) return MPV_ERROR_INVALID_PARAMETER;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->load_config_file(handle->mpv, path_utf);
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, path, path_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeRequestLogMessages(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jstring level
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *level_utf = NULL;
    if (!handle || !kmpv_jni_string(env, level, &level_utf)) return MPV_ERROR_INVALID_PARAMETER;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->request_log_messages(handle->mpv, level_utf);
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, level, level_utf);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeGetProperty(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jstring name,
    jint requested_format
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    if (!handle || !kmpv_jni_string(env, name, &name_utf)) {
        return kmpv_code_result(env, MPV_ERROR_INVALID_PARAMETER);
    }

    mpv_format format = (mpv_format)requested_format;
    mpv_node node;
    memset(&node, 0, sizeof(node));
    int result = MPV_ERROR_INVALID_PARAMETER;

    pthread_mutex_lock(&handle->api_mutex);
    if (format == MPV_FORMAT_STRING) {
        char *value = NULL;
        result = handle->api->get_property(handle->mpv, name_utf, format, &value);
        node.format = format;
        node.u.string = value;
        jbyteArray encoded = kmpv_node_result(env, result, &node);
        if (value) handle->api->free_value(value);
        pthread_mutex_unlock(&handle->api_mutex);
        kmpv_jni_string_release(env, name, name_utf);
        return encoded;
    }
    if (format == MPV_FORMAT_FLAG) {
        int value = 0;
        result = handle->api->get_property(handle->mpv, name_utf, format, &value);
        node.format = format;
        node.u.flag = value;
    } else if (format == MPV_FORMAT_INT64) {
        int64_t value = 0;
        result = handle->api->get_property(handle->mpv, name_utf, format, &value);
        node.format = format;
        node.u.int64 = value;
    } else if (format == MPV_FORMAT_DOUBLE) {
        double value = 0.0;
        result = handle->api->get_property(handle->mpv, name_utf, format, &value);
        node.format = format;
        node.u.double_ = value;
    } else if (format == MPV_FORMAT_NODE) {
        result = handle->api->get_property(handle->mpv, name_utf, format, &node);
    }

    jbyteArray encoded = kmpv_node_result(env, result, &node);
    if (format == MPV_FORMAT_NODE && result >= 0) handle->api->free_node_contents(&node);
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, name, name_utf);
    return encoded;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeSetPropertyString(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jstring name,
    jstring value
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    const char *value_utf = NULL;
    if (!handle || !kmpv_jni_string(env, name, &name_utf) ||
        !kmpv_jni_string(env, value, &value_utf)) {
        kmpv_jni_string_release(env, name, name_utf);
        kmpv_jni_string_release(env, value, value_utf);
        return MPV_ERROR_INVALID_PARAMETER;
    }
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->set_property_string(handle->mpv, name_utf, value_utf);
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, name, name_utf);
    kmpv_jni_string_release(env, value, value_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeSetPropertyFlag(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jstring name,
    jboolean value
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    if (!handle || !kmpv_jni_string(env, name, &name_utf)) return MPV_ERROR_INVALID_PARAMETER;
    int native_value = value ? 1 : 0;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->set_property(handle->mpv, name_utf, MPV_FORMAT_FLAG, &native_value);
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, name, name_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeSetPropertyInt64(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jstring name,
    jlong value
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    if (!handle || !kmpv_jni_string(env, name, &name_utf)) return MPV_ERROR_INVALID_PARAMETER;
    int64_t native_value = (int64_t)value;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->set_property(handle->mpv, name_utf, MPV_FORMAT_INT64, &native_value);
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, name, name_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeSetPropertyDouble(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jstring name,
    jdouble value
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    if (!handle || !kmpv_jni_string(env, name, &name_utf)) return MPV_ERROR_INVALID_PARAMETER;
    double native_value = value;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->set_property(handle->mpv, name_utf, MPV_FORMAT_DOUBLE, &native_value);
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, name, name_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeSetPropertyNode(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jstring name,
    jbyteArray encoded
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    if (!handle || !encoded || !kmpv_jni_string(env, name, &name_utf)) {
        return MPV_ERROR_INVALID_PARAMETER;
    }

    jsize length = (*env)->GetArrayLength(env, encoded);
    jbyte *bytes = (*env)->GetByteArrayElements(env, encoded, NULL);
    if (!bytes) {
        kmpv_jni_string_release(env, name, name_utf);
        return MPV_ERROR_NOMEM;
    }

    NodeReader reader = {
        .data = (const uint8_t *)bytes,
        .size = (size_t)length,
        .offset = 0,
        .error = 0,
    };
    mpv_node node;
    reader_node(&reader, &node);
    if (!reader.error && reader.offset != reader.size) reader.error = MPV_ERROR_INVALID_PARAMETER;

    int result = reader.error;
    if (!result) {
        pthread_mutex_lock(&handle->api_mutex);
        result = handle->api->set_property(handle->mpv, name_utf, MPV_FORMAT_NODE, &node);
        pthread_mutex_unlock(&handle->api_mutex);
    }

    parsed_node_clear(&node);
    (*env)->ReleaseByteArrayElements(env, encoded, bytes, JNI_ABORT);
    kmpv_jni_string_release(env, name, name_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeGetPropertyAsync(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jlong request_id,
    jstring name,
    jint format
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    if (!handle || !kmpv_jni_string(env, name, &name_utf)) return MPV_ERROR_INVALID_PARAMETER;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->get_property_async(
        handle->mpv,
        (uint64_t)request_id,
        name_utf,
        (mpv_format)format
    );
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, name, name_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeSetPropertyStringAsync(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jlong request_id,
    jstring name,
    jstring value
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    const char *value_utf = NULL;
    if (!handle || !kmpv_jni_string(env, name, &name_utf) ||
        !kmpv_jni_string(env, value, &value_utf)) {
        kmpv_jni_string_release(env, name, name_utf);
        kmpv_jni_string_release(env, value, value_utf);
        return MPV_ERROR_INVALID_PARAMETER;
    }
    char *native_value = (char *)value_utf;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->set_property_async(
        handle->mpv,
        (uint64_t)request_id,
        name_utf,
        MPV_FORMAT_STRING,
        &native_value
    );
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, name, name_utf);
    kmpv_jni_string_release(env, value, value_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeSetPropertyFlagAsync(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jlong request_id,
    jstring name,
    jboolean value
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    if (!handle || !kmpv_jni_string(env, name, &name_utf)) return MPV_ERROR_INVALID_PARAMETER;
    int native_value = value ? 1 : 0;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->set_property_async(
        handle->mpv,
        (uint64_t)request_id,
        name_utf,
        MPV_FORMAT_FLAG,
        &native_value
    );
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, name, name_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeSetPropertyInt64Async(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jlong request_id,
    jstring name,
    jlong value
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    if (!handle || !kmpv_jni_string(env, name, &name_utf)) return MPV_ERROR_INVALID_PARAMETER;
    int64_t native_value = (int64_t)value;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->set_property_async(
        handle->mpv,
        (uint64_t)request_id,
        name_utf,
        MPV_FORMAT_INT64,
        &native_value
    );
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, name, name_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeSetPropertyDoubleAsync(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jlong request_id,
    jstring name,
    jdouble value
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    if (!handle || !kmpv_jni_string(env, name, &name_utf)) return MPV_ERROR_INVALID_PARAMETER;
    double native_value = value;
    pthread_mutex_lock(&handle->api_mutex);
    int result = handle->api->set_property_async(
        handle->mpv,
        (uint64_t)request_id,
        name_utf,
        MPV_FORMAT_DOUBLE,
        &native_value
    );
    pthread_mutex_unlock(&handle->api_mutex);
    kmpv_jni_string_release(env, name, name_utf);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_kmpv_internal_AndroidMpvNative_nativeSetPropertyNodeAsync(
    JNIEnv *env,
    jobject self,
    jlong raw_handle,
    jlong request_id,
    jstring name,
    jbyteArray encoded
) {
    (void)self;
    AndroidMpvHandle *handle = kmpv_from_handle(raw_handle);
    const char *name_utf = NULL;
    if (!handle || !encoded || !kmpv_jni_string(env, name, &name_utf)) {
        return MPV_ERROR_INVALID_PARAMETER;
    }

    jsize length = (*env)->GetArrayLength(env, encoded);
    jbyte *bytes = (*env)->GetByteArrayElements(env, encoded, NULL);
    if (!bytes) {
        kmpv_jni_string_release(env, name, name_utf);
        return MPV_ERROR_NOMEM;
    }
    NodeReader reader = {
        .data = (const uint8_t *)bytes,
        .size = (size_t)length,
        .offset = 0,
        .error = 0,
    };
    mpv_node node;
    reader_node(&reader, &node);
    if (!reader.error && reader.offset != reader.size) reader.error = MPV_ERROR_INVALID_PARAMETER;

    int result = reader.error;
    if (!result) {
        pthread_mutex_lock(&handle->api_mutex);
        result = handle->api->set_property_async(
            handle->mpv,
            (uint64_t)request_id,
            name_utf,
            MPV_FORMAT_NODE,
            &node
        );
        pthread_mutex_unlock(&handle->api_mutex);
    }

    parsed_node_clear(&node);
    (*env)->ReleaseByteArrayElements(env, encoded, bytes, JNI_ABORT);
    kmpv_jni_string_release(env, name, name_utf);
    return result;
}
