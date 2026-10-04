#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct KmpvHandle KmpvHandle;

enum KmpvFormat {
    KMPV_FORMAT_NONE = 0,
    KMPV_FORMAT_STRING = 1,
    KMPV_FORMAT_FLAG = 3,
    KMPV_FORMAT_INT64 = 4,
    KMPV_FORMAT_DOUBLE = 5,
    KMPV_FORMAT_NODE = 6,
    KMPV_FORMAT_NODE_ARRAY = 7,
    KMPV_FORMAT_NODE_MAP = 8,
    KMPV_FORMAT_BYTE_ARRAY = 9,
};

typedef struct KmpvNode {
    int format;
    char *string_value;
    int flag_value;
    int64_t int64_value;
    double double_value;
    struct KmpvNode *values;
    char **keys;
    int count;
    uint8_t *bytes;
    size_t byte_count;
} KmpvNode;

typedef struct KmpvEvent {
    int event_id;
    uint64_t reply_userdata;
    int error;

    const char *property_name;
    int property_format;
    int property_available;
    int flag_value;
    int64_t int64_value;
    double double_value;
    const char *string_value;
    const KmpvNode *property_node;

    int end_file_reason;
    int end_file_error;

    const char *log_prefix;
    const char *log_level;
    const char *log_text;

    const KmpvNode *command_result;
} KmpvEvent;

typedef void (*KmpvEventCallback)(void *context, const KmpvEvent *event);

KmpvHandle *kmpv_create(void);
void kmpv_destroy(KmpvHandle *handle);
int kmpv_initialize(KmpvHandle *handle);
void *kmpv_raw_mpv_handle(KmpvHandle *handle);

const char *kmpv_client_name(KmpvHandle *handle);
int64_t kmpv_client_id(KmpvHandle *handle);
const char *kmpv_error_string(int error);

int kmpv_set_option_string(KmpvHandle *handle, const char *name, const char *value);
int kmpv_load_config_file(KmpvHandle *handle, const char *path);
int kmpv_request_log_messages(KmpvHandle *handle, const char *min_level);

int kmpv_get_property_string(KmpvHandle *handle, const char *name, char **out_value);
int kmpv_get_property_flag(KmpvHandle *handle, const char *name, int *out_value);
int kmpv_get_property_int64(KmpvHandle *handle, const char *name, int64_t *out_value);
int kmpv_get_property_double(KmpvHandle *handle, const char *name, double *out_value);
int kmpv_get_property_node(KmpvHandle *handle, const char *name, KmpvNode **out_value);

int kmpv_set_property_string(KmpvHandle *handle, const char *name, const char *value);
int kmpv_set_property_flag(KmpvHandle *handle, const char *name, int value);
int kmpv_set_property_int64(KmpvHandle *handle, const char *name, int64_t value);
int kmpv_set_property_double(KmpvHandle *handle, const char *name, double value);
int kmpv_set_property_node(KmpvHandle *handle, const char *name, const KmpvNode *value);
int kmpv_get_property_async(
    KmpvHandle *handle,
    uint64_t request_id,
    const char *name,
    int format
);
int kmpv_set_property_string_async(
    KmpvHandle *handle,
    uint64_t request_id,
    const char *name,
    const char *value
);
int kmpv_set_property_flag_async(
    KmpvHandle *handle,
    uint64_t request_id,
    const char *name,
    int value
);
int kmpv_set_property_int64_async(
    KmpvHandle *handle,
    uint64_t request_id,
    const char *name,
    int64_t value
);
int kmpv_set_property_double_async(
    KmpvHandle *handle,
    uint64_t request_id,
    const char *name,
    double value
);
int kmpv_set_property_node_async(
    KmpvHandle *handle,
    uint64_t request_id,
    const char *name,
    const KmpvNode *value
);

int kmpv_command(KmpvHandle *handle, const char *const *args, int count);
int kmpv_command_result(KmpvHandle *handle, const char *const *args, int count, KmpvNode **out_value);
int kmpv_command_async(KmpvHandle *handle, uint64_t request_id, const char *const *args, int count);
void kmpv_abort_async_command(KmpvHandle *handle, uint64_t request_id);

int kmpv_observe_property(KmpvHandle *handle, uint64_t observer_id, const char *name, int format);
int kmpv_unobserve_property(KmpvHandle *handle, uint64_t observer_id);

void kmpv_set_event_callback(KmpvHandle *handle, KmpvEventCallback callback, void *context);
void kmpv_free(void *value);
void kmpv_node_free(KmpvNode *node);
int kmpv_node_format(const KmpvNode *node);
const char *kmpv_node_string(const KmpvNode *node);
int kmpv_node_flag(const KmpvNode *node);
int64_t kmpv_node_int64(const KmpvNode *node);
double kmpv_node_double(const KmpvNode *node);
int kmpv_node_count(const KmpvNode *node);
const KmpvNode *kmpv_node_value_at(const KmpvNode *node, int index);
const char *kmpv_node_key_at(const KmpvNode *node, int index);
size_t kmpv_node_byte_count(const KmpvNode *node);
uint8_t kmpv_node_byte_at(const KmpvNode *node, size_t index);
void kmpv_thread_yield(void);

#ifdef __cplusplus
}
#endif
