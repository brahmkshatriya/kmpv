#include "mpv_dynamic.h"

#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>

static MpvApi api;
static pthread_mutex_t load_mutex = PTHREAD_MUTEX_INITIALIZER;
static int attempted = 0;
static void *avcodec_library = NULL;
static int java_vm_registered = 0;
static char load_error[256] = "libmpv.so has not been loaded";

static int symbol(void **target, const char *name) {
    *target = dlsym(api.library, name);
    if (*target) return 1;
    const char *error = dlerror();
    snprintf(load_error, sizeof(load_error), "missing libmpv symbol %s: %s", name,
             error ? error : "unknown dlsym error");
    return 0;
}

#define LOAD(field, name) do { if (!symbol((void **)&api.field, name)) goto failed; } while (0)

const MpvApi *kmpv_mpv_api(void) {
    pthread_mutex_lock(&load_mutex);
    if (attempted) {
        const MpvApi *result = api.library ? &api : NULL;
        pthread_mutex_unlock(&load_mutex);
        return result;
    }
    attempted = 1;
    api.library = dlopen("libmpv.so", RTLD_NOW | RTLD_LOCAL);
    if (!api.library) {
        const char *error = dlerror();
        snprintf(load_error, sizeof(load_error),
                 "could not load libmpv.so: %s. Supply libmpv.so for the device ABI.",
                 error ? error : "unknown dlopen error");
        pthread_mutex_unlock(&load_mutex);
        return NULL;
    }

    LOAD(create, "mpv_create");
    LOAD(terminate_destroy, "mpv_terminate_destroy");
    LOAD(initialize, "mpv_initialize");
    LOAD(client_name, "mpv_client_name");
    LOAD(client_id, "mpv_client_id");
    LOAD(error_string, "mpv_error_string");
    LOAD(set_option, "mpv_set_option");
    LOAD(set_option_string, "mpv_set_option_string");
    LOAD(load_config_file, "mpv_load_config_file");
    LOAD(request_log_messages, "mpv_request_log_messages");
    LOAD(get_property, "mpv_get_property");
    LOAD(set_property_string, "mpv_set_property_string");
    LOAD(set_property, "mpv_set_property");
    LOAD(get_property_async, "mpv_get_property_async");
    LOAD(set_property_async, "mpv_set_property_async");
    LOAD(command, "mpv_command");
    LOAD(command_ret, "mpv_command_ret");
    LOAD(command_async, "mpv_command_async");
    LOAD(abort_async_command, "mpv_abort_async_command");
    LOAD(observe_property, "mpv_observe_property");
    LOAD(unobserve_property, "mpv_unobserve_property");
    LOAD(wait_event, "mpv_wait_event");
    LOAD(wakeup, "mpv_wakeup");
    LOAD(free_value, "mpv_free");
    LOAD(free_node_contents, "mpv_free_node_contents");
    pthread_mutex_unlock(&load_mutex);
    return &api;

failed:
    dlclose(api.library);
    memset(&api, 0, sizeof(api));
    pthread_mutex_unlock(&load_mutex);
    return NULL;
}

const char *kmpv_mpv_load_error(void) {
    return load_error;
}

int kmpv_register_java_vm(JavaVM *vm) {
    if (!vm) return 0;

    pthread_mutex_lock(&load_mutex);
    if (java_vm_registered) {
        pthread_mutex_unlock(&load_mutex);
        return 1;
    }

    if (!api.library) {
        pthread_mutex_unlock(&load_mutex);
        return 0;
    }

    avcodec_library = dlopen("libavcodec.so", RTLD_NOW | RTLD_LOCAL);
    if (!avcodec_library) {
        const char *error = dlerror();
        snprintf(
            load_error,
            sizeof(load_error),
            "could not load libavcodec.so for Android JNI registration: %s",
            error ? error : "unknown dlopen error"
        );
        pthread_mutex_unlock(&load_mutex);
        return 0;
    }

    typedef int (*AvJniSetJavaVm)(void *, void *);
    dlerror();
    AvJniSetJavaVm set_java_vm = (AvJniSetJavaVm)dlsym(avcodec_library, "av_jni_set_java_vm");
    const char *error = dlerror();
    if (!set_java_vm || error) {
        snprintf(
            load_error,
            sizeof(load_error),
            "missing av_jni_set_java_vm in libavcodec.so: %s",
            error ? error : "symbol not found"
        );
        pthread_mutex_unlock(&load_mutex);
        return 0;
    }

    if (set_java_vm((void *)vm, NULL) < 0) {
        snprintf(load_error, sizeof(load_error), "av_jni_set_java_vm rejected the Android JavaVM");
        pthread_mutex_unlock(&load_mutex);
        return 0;
    }

    java_vm_registered = 1;
    pthread_mutex_unlock(&load_mutex);
    return 1;
}
