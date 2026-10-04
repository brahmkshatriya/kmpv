#pragma once

#include "kmpv_bridge.h"
#include "kmpv_mpv_abi.h"

#include <stdatomic.h>

#ifdef _WIN32
#include <windows.h>
typedef HANDLE KmpvThread;
typedef CRITICAL_SECTION KmpvMutex;
#else
#include <pthread.h>
typedef pthread_t KmpvThread;
typedef pthread_mutex_t KmpvMutex;
#endif

struct KmpvHandle {
    mpv_handle *mpv;
    KmpvThread event_thread;
    int event_thread_started;
    atomic_int stopping;
    KmpvMutex callback_mutex;
    KmpvEventCallback callback;
    void *callback_context;
};
