#include "kmpv_render_gl.h"
#include "kmpv_render_mpv_abi.h"

#include <stdatomic.h>
#include <stdlib.h>

#ifdef _WIN32
#include <windows.h>
typedef CRITICAL_SECTION KmpvRenderMutex;
#else
#include <pthread.h>
typedef pthread_mutex_t KmpvRenderMutex;
#endif

static void kmpv_render_mutex_init(KmpvRenderMutex *mutex) {
#ifdef _WIN32
    InitializeCriticalSection(mutex);
#else
    pthread_mutex_init(mutex, NULL);
#endif
}

static void kmpv_render_mutex_destroy(KmpvRenderMutex *mutex) {
#ifdef _WIN32
    DeleteCriticalSection(mutex);
#else
    pthread_mutex_destroy(mutex);
#endif
}

static void kmpv_render_mutex_lock(KmpvRenderMutex *mutex) {
#ifdef _WIN32
    EnterCriticalSection(mutex);
#else
    pthread_mutex_lock(mutex);
#endif
}

static void kmpv_render_mutex_unlock(KmpvRenderMutex *mutex) {
#ifdef _WIN32
    LeaveCriticalSection(mutex);
#else
    pthread_mutex_unlock(mutex);
#endif
}

struct KmpvOpenGlRenderer {
    mpv_handle *mpv;
    mpv_render_context *context;
    KmpvOpenGlGetProcAddress get_proc_address;
    void *get_proc_address_context;
    int native_display_type;
    void *native_display;
    atomic_int update_pending;
    KmpvRenderMutex callback_mutex;
    KmpvRenderUpdateCallback callback;
    void *callback_context;
    int last_framebuffer;
    int last_width;
    int last_height;
    int last_internal_format;
    int last_flip_y;
};

static void kmpv_on_render_update(void *context) {
    KmpvOpenGlRenderer *renderer = (KmpvOpenGlRenderer *)context;
    if (!renderer) return;

    const int already_pending =
        atomic_exchange_explicit(&renderer->update_pending, 1, memory_order_acq_rel);
    if (already_pending) return;

    kmpv_render_mutex_lock(&renderer->callback_mutex);
    if (renderer->callback) renderer->callback(renderer->callback_context);
    kmpv_render_mutex_unlock(&renderer->callback_mutex);
}

static void *kmpv_forward_get_proc_address(void *context, const char *name) {
    KmpvOpenGlRenderer *renderer = (KmpvOpenGlRenderer *)context;
    if (!renderer || !renderer->get_proc_address || !name) return NULL;
    return renderer->get_proc_address(
        renderer->get_proc_address_context,
        name
    );
}

static int kmpv_opengl_renderer_initialize(KmpvOpenGlRenderer *renderer) {
    if (renderer->context) return 0;
    if (!renderer->mpv) return MPV_ERROR_INVALID_PARAMETER;

    /*
     * This function is deliberately called from kmpv_opengl_renderer_render(),
     * not from kmpv_opengl_renderer_create(). libmpv requires the OpenGL
     * context used for mpv_render_context_create() to be current and to be the
     * same context used for subsequent render calls. NativeInteropView only
     * makes that context current while invoking its renderer callback.
     */
    if (!renderer->get_proc_address) return MPV_ERROR_INVALID_PARAMETER;
    mpv_opengl_init_params gl_init = {
        kmpv_forward_get_proc_address,
        renderer,
    };

    void *native_display = renderer->native_display;
    int native_display_type = renderer->native_display_type;

    mpv_render_param params[4];
    int param_count = 0;
    params[param_count++] = (mpv_render_param){
        MPV_RENDER_PARAM_API_TYPE, (void *)MPV_RENDER_API_TYPE_OPENGL
    };
    params[param_count++] = (mpv_render_param){
        MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init
    };
    if (native_display) {
        mpv_render_param_type display_type = MPV_RENDER_PARAM_INVALID;
        if (native_display_type == KMPV_NATIVE_DISPLAY_X11) {
            display_type = MPV_RENDER_PARAM_X11_DISPLAY;
        } else if (native_display_type == KMPV_NATIVE_DISPLAY_WAYLAND) {
            display_type = MPV_RENDER_PARAM_WL_DISPLAY;
        }
        if (display_type != MPV_RENDER_PARAM_INVALID) {
            params[param_count++] = (mpv_render_param){
                display_type, native_display
            };
        }
    }
    params[param_count] = (mpv_render_param){MPV_RENDER_PARAM_INVALID, NULL};

    const int result = mpv_render_context_create(&renderer->context, renderer->mpv, params);
    if (result < 0) return result;

    atomic_store_explicit(&renderer->update_pending, 1, memory_order_release);
    mpv_render_context_set_update_callback(renderer->context, kmpv_on_render_update, renderer);
    return 0;
}

KmpvOpenGlRenderer *kmpv_opengl_renderer_create(
    void *native_handle,
    KmpvOpenGlGetProcAddress get_proc_address,
    void *get_proc_address_context,
    int native_display_type,
    void *native_display,
    int *out_error
) {
    if (out_error) *out_error = 0;
    if (!native_handle || !get_proc_address) {
        if (out_error) *out_error = MPV_ERROR_INVALID_PARAMETER;
        return NULL;
    }

    KmpvOpenGlRenderer *renderer =
        (KmpvOpenGlRenderer *)calloc(1, sizeof(KmpvOpenGlRenderer));
    if (!renderer) {
        if (out_error) *out_error = MPV_ERROR_NOMEM;
        return NULL;
    }

    atomic_init(&renderer->update_pending, 1);
    kmpv_render_mutex_init(&renderer->callback_mutex);
    renderer->mpv = (mpv_handle *)native_handle;
    renderer->get_proc_address = get_proc_address;
    renderer->get_proc_address_context = get_proc_address_context;
    renderer->native_display_type = native_display_type;
    renderer->native_display = native_display;
    renderer->last_framebuffer = -1;
    renderer->last_internal_format = -1;
    renderer->last_flip_y = -1;
    return renderer;
}

void kmpv_opengl_renderer_destroy(KmpvOpenGlRenderer *renderer) {
    if (!renderer) return;
    if (renderer->context) {
        mpv_render_context_set_update_callback(renderer->context, NULL, NULL);
    }
    kmpv_render_mutex_lock(&renderer->callback_mutex);
    renderer->callback = NULL;
    renderer->callback_context = NULL;
    kmpv_render_mutex_unlock(&renderer->callback_mutex);
    if (renderer->context) mpv_render_context_free(renderer->context);
    kmpv_render_mutex_destroy(&renderer->callback_mutex);
    free(renderer);
}

int kmpv_opengl_renderer_render(
    KmpvOpenGlRenderer *renderer,
    int framebuffer,
    int width,
    int height,
    int internal_format,
    int flip_y
) {
    if (!renderer || width <= 0 || height <= 0) {
        return MPV_ERROR_INVALID_PARAMETER;
    }

    const int init_result = kmpv_opengl_renderer_initialize(renderer);
    if (init_result < 0) return init_result;

    const int target_changed =
        framebuffer != renderer->last_framebuffer ||
        width != renderer->last_width ||
        height != renderer->last_height ||
        internal_format != renderer->last_internal_format ||
        !!flip_y != renderer->last_flip_y;
    const int update_pending =
        atomic_exchange_explicit(&renderer->update_pending, 0, memory_order_acq_rel);
    if (!target_changed && !update_pending) return 0;

    const uint64_t update_flags =
        update_pending ? mpv_render_context_update(renderer->context) : 0;
    if (!target_changed && !(update_flags & MPV_RENDER_UPDATE_FRAME)) return 0;

    mpv_opengl_fbo fbo = {framebuffer, width, height, internal_format};
    int flip_y_value = flip_y ? 1 : 0;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flip_y_value},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    const int result = mpv_render_context_render(renderer->context, params);
    if (result < 0) return result;

    renderer->last_framebuffer = framebuffer;
    renderer->last_width = width;
    renderer->last_height = height;
    renderer->last_internal_format = internal_format;
    renderer->last_flip_y = !!flip_y;
    return 1;
}

int kmpv_opengl_renderer_report_swap(KmpvOpenGlRenderer *renderer) {
    if (!renderer || !renderer->context) return MPV_ERROR_INVALID_PARAMETER;
    mpv_render_context_report_swap(renderer->context);
    return 0;
}

void kmpv_opengl_renderer_set_update_callback(
    KmpvOpenGlRenderer *renderer,
    KmpvRenderUpdateCallback callback,
    void *context
) {
    if (!renderer) return;
    kmpv_render_mutex_lock(&renderer->callback_mutex);
    renderer->callback = callback;
    renderer->callback_context = context;
    kmpv_render_mutex_unlock(&renderer->callback_mutex);

    if (renderer->context) {
        mpv_render_context_set_update_callback(
            renderer->context,
            callback ? kmpv_on_render_update : NULL,
            callback ? renderer : NULL
        );
    }
}
