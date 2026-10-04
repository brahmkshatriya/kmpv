#include "include/kmpv_bridge_internal.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#ifndef _WIN32
#include <sched.h>
#endif

#define KMPV_ERROR_EVENT_THREAD (-10000)

static void kmpv_mutex_init(KmpvMutex *mutex) {
#ifdef _WIN32
    InitializeCriticalSection(mutex);
#else
    pthread_mutex_init(mutex, NULL);
#endif
}

static void kmpv_mutex_destroy(KmpvMutex *mutex) {
#ifdef _WIN32
    DeleteCriticalSection(mutex);
#else
    pthread_mutex_destroy(mutex);
#endif
}

static void kmpv_mutex_lock(KmpvMutex *mutex) {
#ifdef _WIN32
    EnterCriticalSection(mutex);
#else
    pthread_mutex_lock(mutex);
#endif
}

static void kmpv_mutex_unlock(KmpvMutex *mutex) {
#ifdef _WIN32
    LeaveCriticalSection(mutex);
#else
    pthread_mutex_unlock(mutex);
#endif
}

void kmpv_thread_yield(void) {
#ifdef _WIN32
    if (!SwitchToThread()) Sleep(0);
#else
    sched_yield();
#endif
}


static void kmpv_node_clear(KmpvNode *node);
static void kmpv_mpv_node_clear(mpv_node *node);

static char *kmpv_strdup(const char *value) {
    if (!value) return NULL;
    size_t length = strlen(value) + 1;
    char *copy = (char *)malloc(length);
    if (copy) memcpy(copy, value, length);
    return copy;
}

static int kmpv_copy_node(const mpv_node *source, KmpvNode *target) {
    memset(target, 0, sizeof(*target));
    target->format = (int)source->format;

    switch (source->format) {
        case MPV_FORMAT_NONE:
            return 0;
        case MPV_FORMAT_STRING:
            target->string_value = kmpv_strdup(source->u.string);
            return source->u.string && !target->string_value ? MPV_ERROR_NOMEM : 0;
        case MPV_FORMAT_FLAG:
            target->flag_value = source->u.flag;
            return 0;
        case MPV_FORMAT_INT64:
            target->int64_value = source->u.int64;
            return 0;
        case MPV_FORMAT_DOUBLE:
            target->double_value = source->u.double_;
            return 0;
        case MPV_FORMAT_NODE_ARRAY:
        case MPV_FORMAT_NODE_MAP: {
            const mpv_node_list *list = source->u.list;
            if (!list || list->num <= 0) return 0;
            target->count = list->num;
            target->values = (KmpvNode *)calloc((size_t)list->num, sizeof(KmpvNode));
            if (!target->values) return MPV_ERROR_NOMEM;
            if (source->format == MPV_FORMAT_NODE_MAP) {
                target->keys = (char **)calloc((size_t)list->num, sizeof(char *));
                if (!target->keys) {
                    kmpv_node_clear(target);
                    return MPV_ERROR_NOMEM;
                }
            }
            for (int i = 0; i < list->num; ++i) {
                int result = kmpv_copy_node(&list->values[i], &target->values[i]);
                if (result < 0) {
                    kmpv_node_clear(target);
                    return result;
                }
                if (target->keys) {
                    target->keys[i] = kmpv_strdup(list->keys[i]);
                    if (list->keys[i] && !target->keys[i]) {
                        kmpv_node_clear(target);
                        return MPV_ERROR_NOMEM;
                    }
                }
            }
            return 0;
        }
        case MPV_FORMAT_BYTE_ARRAY: {
            const mpv_byte_array *bytes = source->u.ba;
            if (!bytes || bytes->size == 0) return 0;
            target->byte_count = bytes->size;
            target->bytes = (uint8_t *)malloc(bytes->size);
            if (!target->bytes) return MPV_ERROR_NOMEM;
            memcpy(target->bytes, bytes->data, bytes->size);
            return 0;
        }
        default:
            target->format = KMPV_FORMAT_NONE;
            return 0;
    }
}

static void kmpv_node_clear(KmpvNode *node) {
    if (!node) return;
    free(node->string_value);
    free(node->bytes);
    if (node->values) {
        for (int i = 0; i < node->count; ++i) kmpv_node_clear(&node->values[i]);
        free(node->values);
    }
    if (node->keys) {
        for (int i = 0; i < node->count; ++i) free(node->keys[i]);
        free(node->keys);
    }
    memset(node, 0, sizeof(*node));
}

static int kmpv_build_mpv_node(const KmpvNode *source, mpv_node *target) {
    if (!source || !target) return MPV_ERROR_INVALID_PARAMETER;
    memset(target, 0, sizeof(*target));

    switch (source->format) {
        case KMPV_FORMAT_NONE:
            target->format = MPV_FORMAT_NONE;
            return 0;
        case KMPV_FORMAT_STRING:
            target->format = MPV_FORMAT_STRING;
            target->u.string = kmpv_strdup(source->string_value ? source->string_value : "");
            return target->u.string ? 0 : MPV_ERROR_NOMEM;
        case KMPV_FORMAT_FLAG:
            target->format = MPV_FORMAT_FLAG;
            target->u.flag = source->flag_value;
            return 0;
        case KMPV_FORMAT_INT64:
            target->format = MPV_FORMAT_INT64;
            target->u.int64 = source->int64_value;
            return 0;
        case KMPV_FORMAT_DOUBLE:
            target->format = MPV_FORMAT_DOUBLE;
            target->u.double_ = source->double_value;
            return 0;
        case KMPV_FORMAT_NODE_ARRAY:
        case KMPV_FORMAT_NODE_MAP: {
            target->format = source->format == KMPV_FORMAT_NODE_MAP
                ? MPV_FORMAT_NODE_MAP
                : MPV_FORMAT_NODE_ARRAY;
            mpv_node_list *list = (mpv_node_list *)calloc(1, sizeof(mpv_node_list));
            if (!list) return MPV_ERROR_NOMEM;
            target->u.list = list;
            list->num = source->count;
            if (source->count <= 0) return 0;
            if (!source->values) {
                kmpv_mpv_node_clear(target);
                return MPV_ERROR_INVALID_PARAMETER;
            }
            list->values = (mpv_node *)calloc((size_t)source->count, sizeof(mpv_node));
            if (!list->values) {
                kmpv_mpv_node_clear(target);
                return MPV_ERROR_NOMEM;
            }
            if (target->format == MPV_FORMAT_NODE_MAP) {
                if (!source->keys) {
                    kmpv_mpv_node_clear(target);
                    return MPV_ERROR_INVALID_PARAMETER;
                }
                list->keys = (char **)calloc((size_t)source->count, sizeof(char *));
                if (!list->keys) {
                    kmpv_mpv_node_clear(target);
                    return MPV_ERROR_NOMEM;
                }
            }
            for (int index = 0; index < source->count; ++index) {
                int result = kmpv_build_mpv_node(&source->values[index], &list->values[index]);
                if (result < 0) {
                    kmpv_mpv_node_clear(target);
                    return result;
                }
                if (list->keys) {
                    list->keys[index] = kmpv_strdup(source->keys[index] ? source->keys[index] : "");
                    if (!list->keys[index]) {
                        kmpv_mpv_node_clear(target);
                        return MPV_ERROR_NOMEM;
                    }
                }
            }
            return 0;
        }
        case KMPV_FORMAT_BYTE_ARRAY: {
            target->format = MPV_FORMAT_BYTE_ARRAY;
            mpv_byte_array *bytes = (mpv_byte_array *)calloc(1, sizeof(mpv_byte_array));
            if (!bytes) return MPV_ERROR_NOMEM;
            target->u.ba = bytes;
            bytes->size = source->byte_count;
            if (bytes->size == 0) return 0;
            if (!source->bytes) {
                kmpv_mpv_node_clear(target);
                return MPV_ERROR_INVALID_PARAMETER;
            }
            bytes->data = malloc(bytes->size);
            if (!bytes->data) {
                kmpv_mpv_node_clear(target);
                return MPV_ERROR_NOMEM;
            }
            memcpy(bytes->data, source->bytes, bytes->size);
            return 0;
        }
        default:
            return MPV_ERROR_INVALID_PARAMETER;
    }
}

static void kmpv_mpv_node_clear(mpv_node *node) {
    if (!node) return;
    if (node->format == MPV_FORMAT_STRING || node->format == MPV_FORMAT_OSD_STRING) {
        free(node->u.string);
    } else if (node->format == MPV_FORMAT_NODE_ARRAY || node->format == MPV_FORMAT_NODE_MAP) {
        mpv_node_list *list = node->u.list;
        if (list) {
            if (list->values) {
                for (int index = 0; index < list->num; ++index) {
                    kmpv_mpv_node_clear(&list->values[index]);
                }
                free(list->values);
            }
            if (list->keys) {
                for (int index = 0; index < list->num; ++index) free(list->keys[index]);
                free(list->keys);
            }
            free(list);
        }
    } else if (node->format == MPV_FORMAT_BYTE_ARRAY) {
        if (node->u.ba) {
            free(node->u.ba->data);
            free(node->u.ba);
        }
    }
    memset(node, 0, sizeof(*node));
}

static KmpvNode *kmpv_node_copy_alloc(const mpv_node *source, int *out_error) {
    KmpvNode *node = (KmpvNode *)calloc(1, sizeof(KmpvNode));
    if (!node) {
        if (out_error) *out_error = MPV_ERROR_NOMEM;
        return NULL;
    }
    int result = kmpv_copy_node(source, node);
    if (result < 0) {
        kmpv_node_clear(node);
        free(node);
        if (out_error) *out_error = result;
        return NULL;
    }
    if (out_error) *out_error = 0;
    return node;
}

static mpv_format kmpv_to_mpv_format(int format) {
    switch (format) {
        case KMPV_FORMAT_STRING: return MPV_FORMAT_STRING;
        case KMPV_FORMAT_FLAG: return MPV_FORMAT_FLAG;
        case KMPV_FORMAT_INT64: return MPV_FORMAT_INT64;
        case KMPV_FORMAT_DOUBLE: return MPV_FORMAT_DOUBLE;
        case KMPV_FORMAT_NODE: return MPV_FORMAT_NODE;
        default: return MPV_FORMAT_NONE;
    }
}

static void kmpv_deliver_event(KmpvHandle *handle, const mpv_event *event) {
    KmpvEvent out;
    memset(&out, 0, sizeof(out));
    out.event_id = (int)event->event_id;
    out.reply_userdata = event->reply_userdata;
    out.error = event->error;

    KmpvNode property_node_storage;
    int has_property_node = 0;
    memset(&property_node_storage, 0, sizeof(property_node_storage));

    if ((event->event_id == MPV_EVENT_PROPERTY_CHANGE ||
         event->event_id == MPV_EVENT_GET_PROPERTY_REPLY) && event->data) {
        const mpv_event_property *property = (const mpv_event_property *)event->data;
        out.property_name = property->name;
        out.property_format = (int)property->format;
        out.property_available = property->data != NULL && property->format != MPV_FORMAT_NONE;

        if (out.property_available) {
            switch (property->format) {
                case MPV_FORMAT_STRING:
                    out.string_value = *(char **)property->data;
                    break;
                case MPV_FORMAT_FLAG:
                    out.flag_value = *(int *)property->data;
                    break;
                case MPV_FORMAT_INT64:
                    out.int64_value = *(int64_t *)property->data;
                    break;
                case MPV_FORMAT_DOUBLE:
                    out.double_value = *(double *)property->data;
                    break;
                case MPV_FORMAT_NODE:
                    if (kmpv_copy_node((const mpv_node *)property->data, &property_node_storage) >= 0) {
                        out.property_node = &property_node_storage;
                        has_property_node = 1;
                    }
                    break;
                default:
                    break;
            }
        }
    } else if (event->event_id == MPV_EVENT_END_FILE && event->data) {
        const mpv_event_end_file *end_file = (const mpv_event_end_file *)event->data;
        out.end_file_reason = (int)end_file->reason;
        out.end_file_error = end_file->error;
    } else if (event->event_id == MPV_EVENT_LOG_MESSAGE && event->data) {
        const mpv_event_log_message *log = (const mpv_event_log_message *)event->data;
        out.log_prefix = log->prefix;
        out.log_level = log->level;
        out.log_text = log->text;
    }

    KmpvNode command_result_storage;
    int has_command_result = 0;
    memset(&command_result_storage, 0, sizeof(command_result_storage));
    if (event->event_id == MPV_EVENT_COMMAND_REPLY && event->data && event->error >= 0) {
        const mpv_event_command *command = (const mpv_event_command *)event->data;
        if (command->result.format != MPV_FORMAT_NONE &&
            kmpv_copy_node(&command->result, &command_result_storage) >= 0) {
            out.command_result = &command_result_storage;
            has_command_result = 1;
        }
    }

    kmpv_mutex_lock(&handle->callback_mutex);
    if (handle->callback) {
        handle->callback(handle->callback_context, &out);
    }
    kmpv_mutex_unlock(&handle->callback_mutex);
    if (has_property_node) kmpv_node_clear(&property_node_storage);
    if (has_command_result) kmpv_node_clear(&command_result_storage);
}

static void kmpv_event_loop(KmpvHandle *handle) {
    while (!atomic_load_explicit(&handle->stopping, memory_order_acquire)) {
        mpv_event *event = mpv_wait_event(handle->mpv, -1.0);
        if (atomic_load_explicit(&handle->stopping, memory_order_acquire)) break;
        if (!event || event->event_id == MPV_EVENT_NONE) continue;
        kmpv_deliver_event(handle, event);
        if (event->event_id == MPV_EVENT_SHUTDOWN) break;
    }
}

#ifdef _WIN32
static DWORD WINAPI kmpv_event_thread_main(LPVOID context) {
    kmpv_event_loop((KmpvHandle *)context);
    return 0;
}

static int kmpv_start_event_thread(KmpvHandle *handle) {
    handle->event_thread = CreateThread(NULL, 0, kmpv_event_thread_main, handle, 0, NULL);
    return handle->event_thread ? 0 : -1;
}

static void kmpv_join_event_thread(KmpvHandle *handle) {
    WaitForSingleObject(handle->event_thread, INFINITE);
    CloseHandle(handle->event_thread);
    handle->event_thread = NULL;
}
#else
static void *kmpv_event_thread_main(void *context) {
    kmpv_event_loop((KmpvHandle *)context);
    return NULL;
}

static int kmpv_start_event_thread(KmpvHandle *handle) {
    return pthread_create(&handle->event_thread, NULL, kmpv_event_thread_main, handle);
}

static void kmpv_join_event_thread(KmpvHandle *handle) {
    pthread_join(handle->event_thread, NULL);
}
#endif

KmpvHandle *kmpv_create(void) {
    KmpvHandle *handle = (KmpvHandle *)calloc(1, sizeof(KmpvHandle));
    if (!handle) return NULL;

    handle->mpv = mpv_create();
    if (!handle->mpv) {
        free(handle);
        return NULL;
    }

    atomic_init(&handle->stopping, 0);
    kmpv_mutex_init(&handle->callback_mutex);
    return handle;
}

void kmpv_destroy(KmpvHandle *handle) {
    if (!handle) return;

    kmpv_set_event_callback(handle, NULL, NULL);
    if (handle->event_thread_started) {
        atomic_store_explicit(&handle->stopping, 1, memory_order_release);
        mpv_wakeup(handle->mpv);
        kmpv_join_event_thread(handle);
    }

    if (handle->mpv) mpv_terminate_destroy(handle->mpv);
    kmpv_mutex_destroy(&handle->callback_mutex);
    free(handle);
}

int kmpv_initialize(KmpvHandle *handle) {
    if (!handle || !handle->mpv) return MPV_ERROR_INVALID_PARAMETER;
    int result = mpv_initialize(handle->mpv);
    if (result < 0) return result;

    if (kmpv_start_event_thread(handle) != 0) {
        return KMPV_ERROR_EVENT_THREAD;
    }
    handle->event_thread_started = 1;
    return 0;
}

void *kmpv_raw_mpv_handle(KmpvHandle *handle) {
    return handle ? handle->mpv : NULL;
}

const char *kmpv_client_name(KmpvHandle *handle) {
    return handle && handle->mpv ? mpv_client_name(handle->mpv) : NULL;
}

int64_t kmpv_client_id(KmpvHandle *handle) {
    return handle && handle->mpv ? mpv_client_id(handle->mpv) : 0;
}

const char *kmpv_error_string(int error) {
    if (error == KMPV_ERROR_EVENT_THREAD) return "could not start mpv event thread";
    return mpv_error_string(error);
}

int kmpv_set_option_string(KmpvHandle *handle, const char *name, const char *value) {
    return mpv_set_option_string(handle->mpv, name, value);
}

int kmpv_load_config_file(KmpvHandle *handle, const char *path) {
    return mpv_load_config_file(handle->mpv, path);
}

int kmpv_request_log_messages(KmpvHandle *handle, const char *min_level) {
    return mpv_request_log_messages(handle->mpv, min_level);
}

int kmpv_get_property_string(KmpvHandle *handle, const char *name, char **out_value) {
    return mpv_get_property(handle->mpv, name, MPV_FORMAT_STRING, out_value);
}

int kmpv_get_property_flag(KmpvHandle *handle, const char *name, int *out_value) {
    return mpv_get_property(handle->mpv, name, MPV_FORMAT_FLAG, out_value);
}

int kmpv_get_property_int64(KmpvHandle *handle, const char *name, int64_t *out_value) {
    return mpv_get_property(handle->mpv, name, MPV_FORMAT_INT64, out_value);
}

int kmpv_get_property_double(KmpvHandle *handle, const char *name, double *out_value) {
    return mpv_get_property(handle->mpv, name, MPV_FORMAT_DOUBLE, out_value);
}


int kmpv_get_property_node(KmpvHandle *handle, const char *name, KmpvNode **out_value) {
    if (!handle || !handle->mpv || !out_value) return MPV_ERROR_INVALID_PARAMETER;
    *out_value = NULL;
    mpv_node source;
    memset(&source, 0, sizeof(source));
    int result = mpv_get_property(handle->mpv, name, MPV_FORMAT_NODE, &source);
    if (result < 0) {
        mpv_free_node_contents(&source);
        return result;
    }
    int copy_error = 0;
    KmpvNode *copy = kmpv_node_copy_alloc(&source, &copy_error);
    mpv_free_node_contents(&source);
    if (!copy) return copy_error;
    *out_value = copy;
    return 0;
}

int kmpv_set_property_string(KmpvHandle *handle, const char *name, const char *value) {
    return mpv_set_property_string(handle->mpv, name, value);
}

int kmpv_set_property_flag(KmpvHandle *handle, const char *name, int value) {
    return mpv_set_property(handle->mpv, name, MPV_FORMAT_FLAG, &value);
}

int kmpv_set_property_int64(KmpvHandle *handle, const char *name, int64_t value) {
    return mpv_set_property(handle->mpv, name, MPV_FORMAT_INT64, &value);
}

int kmpv_set_property_double(KmpvHandle *handle, const char *name, double value) {
    return mpv_set_property(handle->mpv, name, MPV_FORMAT_DOUBLE, &value);
}

int kmpv_set_property_node(KmpvHandle *handle, const char *name, const KmpvNode *value) {
    if (!handle || !handle->mpv || !name || !value) return MPV_ERROR_INVALID_PARAMETER;
    mpv_node native;
    int converted = kmpv_build_mpv_node(value, &native);
    if (converted < 0) return converted;
    int result = mpv_set_property(handle->mpv, name, MPV_FORMAT_NODE, &native);
    kmpv_mpv_node_clear(&native);
    return result;
}

int kmpv_get_property_async(
    KmpvHandle *handle,
    uint64_t request_id,
    const char *name,
    int format
) {
    if (!handle || !handle->mpv || !name) return MPV_ERROR_INVALID_PARAMETER;
    mpv_format native_format = kmpv_to_mpv_format(format);
    if (native_format == MPV_FORMAT_NONE) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_get_property_async(handle->mpv, request_id, name, native_format);
}

int kmpv_set_property_string_async(
    KmpvHandle *handle,
    uint64_t request_id,
    const char *name,
    const char *value
) {
    if (!handle || !handle->mpv || !name || !value) return MPV_ERROR_INVALID_PARAMETER;
    char *native_value = (char *)value;
    return mpv_set_property_async(
        handle->mpv,
        request_id,
        name,
        MPV_FORMAT_STRING,
        &native_value
    );
}

int kmpv_set_property_flag_async(
    KmpvHandle *handle,
    uint64_t request_id,
    const char *name,
    int value
) {
    if (!handle || !handle->mpv || !name) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_set_property_async(handle->mpv, request_id, name, MPV_FORMAT_FLAG, &value);
}

int kmpv_set_property_int64_async(
    KmpvHandle *handle,
    uint64_t request_id,
    const char *name,
    int64_t value
) {
    if (!handle || !handle->mpv || !name) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_set_property_async(handle->mpv, request_id, name, MPV_FORMAT_INT64, &value);
}

int kmpv_set_property_double_async(
    KmpvHandle *handle,
    uint64_t request_id,
    const char *name,
    double value
) {
    if (!handle || !handle->mpv || !name) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_set_property_async(handle->mpv, request_id, name, MPV_FORMAT_DOUBLE, &value);
}

int kmpv_set_property_node_async(
    KmpvHandle *handle,
    uint64_t request_id,
    const char *name,
    const KmpvNode *value
) {
    if (!handle || !handle->mpv || !name || !value) return MPV_ERROR_INVALID_PARAMETER;
    mpv_node native;
    int converted = kmpv_build_mpv_node(value, &native);
    if (converted < 0) return converted;
    int result = mpv_set_property_async(handle->mpv, request_id, name, MPV_FORMAT_NODE, &native);
    kmpv_mpv_node_clear(&native);
    return result;
}

static const char **kmpv_make_argv(const char *const *args, int count) {
    const char **argv = (const char **)calloc((size_t)count + 1, sizeof(char *));
    if (!argv) return NULL;
    for (int i = 0; i < count; ++i) argv[i] = args[i];
    argv[count] = NULL;
    return argv;
}

int kmpv_command(KmpvHandle *handle, const char *const *args, int count) {
    const char **argv = kmpv_make_argv(args, count);
    if (!argv) return MPV_ERROR_NOMEM;
    int result = mpv_command(handle->mpv, argv);
    free(argv);
    return result;
}


int kmpv_command_result(KmpvHandle *handle, const char *const *args, int count, KmpvNode **out_value) {
    if (!handle || !handle->mpv || !args || count <= 0 || !out_value) {
        return MPV_ERROR_INVALID_PARAMETER;
    }
    *out_value = NULL;
    const char **argv = kmpv_make_argv(args, count);
    if (!argv) return MPV_ERROR_NOMEM;
    mpv_node source;
    memset(&source, 0, sizeof(source));
    int result = mpv_command_ret(handle->mpv, argv, &source);
    free(argv);
    if (result < 0) return result;
    int copy_error = 0;
    KmpvNode *copy = kmpv_node_copy_alloc(&source, &copy_error);
    mpv_free_node_contents(&source);
    if (!copy) return copy_error;
    *out_value = copy;
    return 0;
}

int kmpv_command_async(KmpvHandle *handle, uint64_t request_id, const char *const *args, int count) {
    const char **argv = kmpv_make_argv(args, count);
    if (!argv) return MPV_ERROR_NOMEM;
    int result = mpv_command_async(handle->mpv, request_id, argv);
    free(argv);
    return result;
}

void kmpv_abort_async_command(KmpvHandle *handle, uint64_t request_id) {
    mpv_abort_async_command(handle->mpv, request_id);
}

int kmpv_observe_property(KmpvHandle *handle, uint64_t observer_id, const char *name, int format) {
    mpv_format mpv_format_value = kmpv_to_mpv_format(format);
    if (mpv_format_value == MPV_FORMAT_NONE) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_observe_property(handle->mpv, observer_id, name, mpv_format_value);
}

int kmpv_unobserve_property(KmpvHandle *handle, uint64_t observer_id) {
    return mpv_unobserve_property(handle->mpv, observer_id);
}

void kmpv_set_event_callback(KmpvHandle *handle, KmpvEventCallback callback, void *context) {
    if (!handle) return;
    kmpv_mutex_lock(&handle->callback_mutex);
    handle->callback = callback;
    handle->callback_context = context;
    kmpv_mutex_unlock(&handle->callback_mutex);
}

void kmpv_free(void *value) {
    mpv_free(value);
}


void kmpv_node_free(KmpvNode *node) {
    if (!node) return;
    kmpv_node_clear(node);
    free(node);
}

int kmpv_node_format(const KmpvNode *node) {
    return node ? node->format : KMPV_FORMAT_NONE;
}

const char *kmpv_node_string(const KmpvNode *node) {
    return node ? node->string_value : NULL;
}

int kmpv_node_flag(const KmpvNode *node) {
    return node ? node->flag_value : 0;
}

int64_t kmpv_node_int64(const KmpvNode *node) {
    return node ? node->int64_value : 0;
}

double kmpv_node_double(const KmpvNode *node) {
    return node ? node->double_value : 0.0;
}

int kmpv_node_count(const KmpvNode *node) {
    return node ? node->count : 0;
}

const KmpvNode *kmpv_node_value_at(const KmpvNode *node, int index) {
    if (!node || !node->values || index < 0 || index >= node->count) return NULL;
    return &node->values[index];
}

const char *kmpv_node_key_at(const KmpvNode *node, int index) {
    if (!node || !node->keys || index < 0 || index >= node->count) return NULL;
    return node->keys[index];
}

size_t kmpv_node_byte_count(const KmpvNode *node) {
    return node ? node->byte_count : 0;
}

uint8_t kmpv_node_byte_at(const KmpvNode *node, size_t index) {
    if (!node || !node->bytes || index >= node->byte_count) return 0;
    return node->bytes[index];
}
