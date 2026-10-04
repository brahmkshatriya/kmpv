#include "binary.h"

#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct Buffer {
    uint8_t *data;
    size_t size;
    size_t capacity;
    int failed;
} Buffer;

static void reserve(Buffer *buffer, size_t extra) {
    if (buffer->failed || extra > SIZE_MAX - buffer->size) {
        buffer->failed = 1;
        return;
    }
    size_t required = buffer->size + extra;
    if (required <= buffer->capacity) return;
    size_t next = buffer->capacity ? buffer->capacity : 128;
    while (next < required) {
        if (next > SIZE_MAX / 2) {
            next = required;
            break;
        }
        next *= 2;
    }
    uint8_t *resized = (uint8_t *)realloc(buffer->data, next);
    if (!resized) {
        buffer->failed = 1;
        return;
    }
    buffer->data = resized;
    buffer->capacity = next;
}

static void bytes(Buffer *buffer, const void *data, size_t size) {
    reserve(buffer, size);
    if (buffer->failed) return;
    memcpy(buffer->data + buffer->size, data, size);
    buffer->size += size;
}

static void u8(Buffer *buffer, uint8_t value) {
    bytes(buffer, &value, 1);
}

static void i32(Buffer *buffer, int32_t value) {
    uint32_t bits = (uint32_t)value;
    uint8_t encoded[4];
    for (int i = 0; i < 4; ++i) encoded[i] = (uint8_t)(bits >> (i * 8));
    bytes(buffer, encoded, sizeof(encoded));
}

static void i64(Buffer *buffer, int64_t value) {
    uint64_t bits = (uint64_t)value;
    uint8_t encoded[8];
    for (int i = 0; i < 8; ++i) encoded[i] = (uint8_t)(bits >> (i * 8));
    bytes(buffer, encoded, sizeof(encoded));
}

static void f64(Buffer *buffer, double value) {
    uint64_t bits = 0;
    memcpy(&bits, &value, sizeof(bits));
    i64(buffer, (int64_t)bits);
}

static void string(Buffer *buffer, const char *value) {
    if (!value) {
        i32(buffer, -1);
        return;
    }
    size_t size = strlen(value);
    if (size > INT32_MAX) {
        buffer->failed = 1;
        return;
    }
    i32(buffer, (int32_t)size);
    bytes(buffer, value, size);
}

static void node(Buffer *buffer, const mpv_node *value) {
    mpv_format format = value ? value->format : MPV_FORMAT_NONE;
    u8(buffer, (uint8_t)format);
    switch (format) {
        case MPV_FORMAT_NONE:
            break;
        case MPV_FORMAT_STRING:
            string(buffer, value->u.string);
            break;
        case MPV_FORMAT_FLAG:
            u8(buffer, value->u.flag ? 1 : 0);
            break;
        case MPV_FORMAT_INT64:
            i64(buffer, value->u.int64);
            break;
        case MPV_FORMAT_DOUBLE:
            f64(buffer, value->u.double_);
            break;
        case MPV_FORMAT_NODE_ARRAY:
        case MPV_FORMAT_NODE_MAP: {
            const mpv_node_list *list = value->u.list;
            int count = list && list->num > 0 ? list->num : 0;
            i32(buffer, count);
            for (int index = 0; index < count && !buffer->failed; ++index) {
                if (format == MPV_FORMAT_NODE_MAP) {
                    string(buffer, list->keys ? list->keys[index] : NULL);
                }
                node(buffer, &list->values[index]);
            }
            break;
        }
        case MPV_FORMAT_BYTE_ARRAY: {
            const mpv_byte_array *array = value->u.ba;
            size_t size = array ? array->size : 0;
            if (size > INT32_MAX) {
                buffer->failed = 1;
                break;
            }
            i32(buffer, (int32_t)size);
            if (size) bytes(buffer, array->data, size);
            break;
        }
        default:
            buffer->failed = 1;
            break;
    }
}

static jbyteArray finish(JNIEnv *env, Buffer *buffer) {
    if (buffer->failed || buffer->size > INT32_MAX) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)buffer->size);
    if (result && buffer->size) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)buffer->size, (const jbyte *)buffer->data);
    }
    return result;
}

static mpv_node property_node(mpv_format format, void *data) {
    mpv_node result;
    memset(&result, 0, sizeof(result));
    result.format = data ? format : MPV_FORMAT_NONE;
    if (!data) return result;
    switch (format) {
        case MPV_FORMAT_STRING: result.u.string = *(char **)data; break;
        case MPV_FORMAT_FLAG: result.u.flag = *(int *)data; break;
        case MPV_FORMAT_INT64: result.u.int64 = *(int64_t *)data; break;
        case MPV_FORMAT_DOUBLE: result.u.double_ = *(double *)data; break;
        case MPV_FORMAT_NODE: result = *(mpv_node *)data; break;
        default: result.format = MPV_FORMAT_NONE; break;
    }
    return result;
}

jbyteArray kmpv_code_result(JNIEnv *env, int code) {
    Buffer buffer = {0};
    i32(&buffer, code);
    jbyteArray result = finish(env, &buffer);
    free(buffer.data);
    return result;
}

jbyteArray kmpv_node_result(JNIEnv *env, int code, const mpv_node *value) {
    Buffer buffer = {0};
    i32(&buffer, code);
    if (code >= 0) node(&buffer, value);
    jbyteArray result = finish(env, &buffer);
    free(buffer.data);
    return result;
}

jbyteArray kmpv_event_result(JNIEnv *env, const mpv_event *event) {
    if (!event || event->event_id == MPV_EVENT_NONE) return NULL;
    Buffer buffer = {0};
    i32(&buffer, event->event_id);
    i64(&buffer, (int64_t)event->reply_userdata);
    i32(&buffer, event->error);

    switch (event->event_id) {
        case MPV_EVENT_GET_PROPERTY_REPLY:
        case MPV_EVENT_PROPERTY_CHANGE: {
            mpv_event_property *property = (mpv_event_property *)event->data;
            string(&buffer, property ? property->name : NULL);
            i32(&buffer, property ? property->format : MPV_FORMAT_NONE);
            int available = property && property->data && property->format != MPV_FORMAT_NONE;
            u8(&buffer, available ? 1 : 0);
            if (available) {
                mpv_node value = property_node(property->format, property->data);
                node(&buffer, &value);
            }
            break;
        }
        case MPV_EVENT_END_FILE: {
            mpv_event_end_file *end = (mpv_event_end_file *)event->data;
            i32(&buffer, end ? end->reason : -1);
            i32(&buffer, end ? end->error : 0);
            break;
        }
        case MPV_EVENT_LOG_MESSAGE: {
            mpv_event_log_message *log = (mpv_event_log_message *)event->data;
            string(&buffer, log ? log->prefix : NULL);
            string(&buffer, log ? log->level : NULL);
            string(&buffer, log ? log->text : NULL);
            break;
        }
        case MPV_EVENT_COMMAND_REPLY: {
            mpv_event_command *command = (mpv_event_command *)event->data;
            if (event->error >= 0 && command) node(&buffer, &command->result);
            else {
                mpv_node none = {0};
                none.format = MPV_FORMAT_NONE;
                node(&buffer, &none);
            }
            break;
        }
        default:
            break;
    }

    jbyteArray result = finish(env, &buffer);
    free(buffer.data);
    return result;
}
