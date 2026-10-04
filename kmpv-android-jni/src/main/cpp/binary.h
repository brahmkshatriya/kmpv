#pragma once

#include "mpv_abi.h"

#include <jni.h>

jbyteArray kmpv_code_result(JNIEnv *env, int code);
jbyteArray kmpv_node_result(JNIEnv *env, int code, const mpv_node *node);
jbyteArray kmpv_event_result(JNIEnv *env, const mpv_event *event);
