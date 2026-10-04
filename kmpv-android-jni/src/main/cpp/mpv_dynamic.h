#pragma once

#include "mpv_abi.h"

#include <jni.h>

typedef struct MpvApi {
    void *library;
    mpv_handle *(*create)(void);
    void (*terminate_destroy)(mpv_handle *);
    int (*initialize)(mpv_handle *);
    const char *(*client_name)(mpv_handle *);
    int64_t (*client_id)(mpv_handle *);
    const char *(*error_string)(int);
    int (*set_option)(mpv_handle *, const char *, mpv_format, void *);
    int (*set_option_string)(mpv_handle *, const char *, const char *);
    int (*load_config_file)(mpv_handle *, const char *);
    int (*request_log_messages)(mpv_handle *, const char *);
    int (*get_property)(mpv_handle *, const char *, mpv_format, void *);
    int (*set_property_string)(mpv_handle *, const char *, const char *);
    int (*set_property)(mpv_handle *, const char *, mpv_format, void *);
    int (*get_property_async)(mpv_handle *, uint64_t, const char *, mpv_format);
    int (*set_property_async)(mpv_handle *, uint64_t, const char *, mpv_format, void *);
    int (*command)(mpv_handle *, const char **);
    int (*command_ret)(mpv_handle *, const char **, mpv_node *);
    int (*command_async)(mpv_handle *, uint64_t, const char **);
    void (*abort_async_command)(mpv_handle *, uint64_t);
    int (*observe_property)(mpv_handle *, uint64_t, const char *, mpv_format);
    int (*unobserve_property)(mpv_handle *, uint64_t);
    mpv_event *(*wait_event)(mpv_handle *, double);
    void (*wakeup)(mpv_handle *);
    void (*free_value)(void *);
    void (*free_node_contents)(mpv_node *);
} MpvApi;

const MpvApi *kmpv_mpv_api(void);
const char *kmpv_mpv_load_error(void);
int kmpv_register_java_vm(JavaVM *vm);
