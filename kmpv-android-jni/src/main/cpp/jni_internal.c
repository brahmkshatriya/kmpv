#include "jni_internal.h"

#include <stdlib.h>
#include <string.h>

AndroidMpvHandle *kmpv_from_handle(jlong value) {
    return (AndroidMpvHandle *)(uintptr_t)value;
}

jlong kmpv_to_handle(AndroidMpvHandle *value) {
    return (jlong)(uintptr_t)value;
}

int kmpv_jni_args_init(JNIEnv *env, jobjectArray array, JniArgs *args) {
    memset(args, 0, sizeof(*args));
    args->env = env;
    if (!array) return 0;
    args->count = (*env)->GetArrayLength(env, array);
    if (args->count <= 0) return 0;
    args->strings = (jstring *)calloc((size_t)args->count, sizeof(jstring));
    args->utf = (const char **)calloc((size_t)args->count, sizeof(char *));
    args->argv = (const char **)calloc((size_t)args->count + 1, sizeof(char *));
    if (!args->strings || !args->utf || !args->argv) return 0;

    for (jsize index = 0; index < args->count; ++index) {
        args->strings[index] = (jstring)(*env)->GetObjectArrayElement(env, array, index);
        if (!args->strings[index]) return 0;
        args->utf[index] = (*env)->GetStringUTFChars(env, args->strings[index], NULL);
        if (!args->utf[index]) return 0;
        args->argv[index] = args->utf[index];
    }
    return 1;
}

void kmpv_jni_args_destroy(JniArgs *args) {
    if (!args || !args->env) return;
    for (jsize index = 0; index < args->count; ++index) {
        if (!args->strings || !args->strings[index]) continue;
        if (args->utf && args->utf[index]) {
            (*args->env)->ReleaseStringUTFChars(args->env, args->strings[index], args->utf[index]);
        }
        (*args->env)->DeleteLocalRef(args->env, args->strings[index]);
    }
    free(args->strings);
    free(args->utf);
    free(args->argv);
    memset(args, 0, sizeof(*args));
}

int kmpv_jni_string(JNIEnv *env, jstring value, const char **utf) {
    if (!value) return 0;
    *utf = (*env)->GetStringUTFChars(env, value, NULL);
    return *utf != NULL;
}

void kmpv_jni_string_release(JNIEnv *env, jstring value, const char *utf) {
    if (value && utf) (*env)->ReleaseStringUTFChars(env, value, utf);
}
