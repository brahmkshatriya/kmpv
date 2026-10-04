#pragma once

#ifdef __cplusplus
extern "C" {
#endif

typedef struct KmpvOpenGlRenderer KmpvOpenGlRenderer;
typedef void (*KmpvRenderUpdateCallback)(void *context);
typedef void *(*KmpvOpenGlGetProcAddress)(void *context, const char *name);

enum KmpvNativeDisplayType {
    KMPV_NATIVE_DISPLAY_NONE = 0,
    KMPV_NATIVE_DISPLAY_X11 = 1,
    KMPV_NATIVE_DISPLAY_WAYLAND = 2,
};

KmpvOpenGlRenderer *kmpv_opengl_renderer_create(
    void *mpv_handle,
    KmpvOpenGlGetProcAddress get_proc_address,
    void *get_proc_address_context,
    int native_display_type,
    void *native_display,
    int *out_error
);
void kmpv_opengl_renderer_destroy(KmpvOpenGlRenderer *renderer);

/* Returns 1 when rendered, 0 when no redraw is needed, or a negative mpv error. */
int kmpv_opengl_renderer_render(
    KmpvOpenGlRenderer *renderer,
    int framebuffer,
    int width,
    int height,
    int internal_format,
    int flip_y
);

/* Reports a completed buffer swap to libmpv. */
int kmpv_opengl_renderer_report_swap(KmpvOpenGlRenderer *renderer);

void kmpv_opengl_renderer_set_update_callback(
    KmpvOpenGlRenderer *renderer,
    KmpvRenderUpdateCallback callback,
    void *context
);

#ifdef __cplusplus
}
#endif
