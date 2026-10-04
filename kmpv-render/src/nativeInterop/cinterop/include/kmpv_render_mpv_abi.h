#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct mpv_handle mpv_handle;
typedef struct mpv_render_context mpv_render_context;

typedef enum mpv_render_param_type {
    MPV_RENDER_PARAM_INVALID = 0,
    MPV_RENDER_PARAM_API_TYPE = 1,
    MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2,
    MPV_RENDER_PARAM_OPENGL_FBO = 3,
    MPV_RENDER_PARAM_FLIP_Y = 4,
    MPV_RENDER_PARAM_X11_DISPLAY = 8,
    MPV_RENDER_PARAM_WL_DISPLAY = 9,
} mpv_render_param_type;

typedef struct mpv_render_param {
    mpv_render_param_type type;
    void *data;
} mpv_render_param;

typedef struct mpv_opengl_init_params {
    void *(*get_proc_address)(void *ctx, const char *name);
    void *get_proc_address_ctx;
} mpv_opengl_init_params;

typedef struct mpv_opengl_fbo {
    int fbo;
    int w;
    int h;
    int internal_format;
} mpv_opengl_fbo;

typedef void (*mpv_render_update_fn)(void *cb_ctx);

#define MPV_RENDER_API_TYPE_OPENGL "opengl"
#define MPV_RENDER_UPDATE_FRAME (1u << 0)

int mpv_render_context_create(
    mpv_render_context **res,
    mpv_handle *mpv,
    mpv_render_param *params
);
void mpv_render_context_free(mpv_render_context *ctx);
void mpv_render_context_set_update_callback(
    mpv_render_context *ctx,
    mpv_render_update_fn callback,
    void *callback_ctx
);
uint64_t mpv_render_context_update(mpv_render_context *ctx);
int mpv_render_context_render(mpv_render_context *ctx, mpv_render_param *params);
void mpv_render_context_report_swap(mpv_render_context *ctx);

enum {
    MPV_ERROR_NOMEM = -1,
    MPV_ERROR_INVALID_PARAMETER = -4,
};

#ifdef __cplusplus
}
#endif
