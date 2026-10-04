@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, dev.kmpv.KmpvNativeApi::class)

package dev.kmpv

import cnames.structs.KmpvOpenGlRenderer
import dev.kmpv.render.bridge.kmpv_opengl_renderer_create
import dev.kmpv.render.bridge.kmpv_opengl_renderer_destroy
import dev.kmpv.render.bridge.kmpv_opengl_renderer_render
import dev.kmpv.render.bridge.kmpv_opengl_renderer_report_swap
import dev.kmpv.render.bridge.kmpv_opengl_renderer_set_update_callback
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value

private fun renderUpdateCallback(context: COpaquePointer?) {
    context?.asStableRef<MpvOpenGlRenderer>()?.get()?.dispatchUpdate()
}

private class OpenGlProcResolver(
    val resolve: (String) -> COpaquePointer?,
)

private fun renderGetProcAddress(
    context: COpaquePointer?,
    name: CPointer<ByteVar>?,
): COpaquePointer? {
    val resolver = context?.asStableRef<OpenGlProcResolver>()?.get() ?: return null
    val symbol = name?.toKString() ?: return null
    return resolver.resolve(symbol)
}

public enum class MpvOpenGlNativeDisplayType(internal val bridgeValue: Int) {
    None(0),
    X11(1),
    Wayland(2),
}

public data class MpvOpenGlNativeDisplay(
    public val type: MpvOpenGlNativeDisplayType,
    public val pointer: COpaquePointer?,
) {
    public companion object {
        public val None: MpvOpenGlNativeDisplay =
            MpvOpenGlNativeDisplay(MpvOpenGlNativeDisplayType.None, null)
    }
}

public data class MpvOpenGlRenderTarget(
    val framebuffer: Int,
    val width: Int,
    val height: Int,
    val internalFormat: Int = 0,
)

public data class MpvOpenGlRenderOptions(
    val flipY: Boolean = true,
)

/**
 * Kotlin/Native OpenGL/OpenGL ES renderer for libmpv's render API.
 *
 * The native render context is created lazily during the first [render] call,
 * when the host graphics context is current. Configure mpv with `vo=libmpv`
 * before calling [Mpv.initialize]. The resolver supplied to
 * [Mpv.createOpenGlRenderer] must resolve functions for that same context.
 *
 * Calls that touch the render context, including [render] and [close], must be
 * made on the graphics thread with that context current. The update callback
 * can be invoked by libmpv from another thread and should only schedule work on
 * the graphics/UI thread. On Linux, pass the current X11/Wayland display only
 * when direct hardware-decoder interop requires it; copy-back hwdec modes do
 * not require a native display handle.
 */
public class MpvOpenGlRenderer internal constructor(
    private val owner: Mpv,
    private val nativeLease: MpvNativeHandleLease,
    getProcAddress: ((String) -> COpaquePointer?)?,
    private val nativeDisplay: MpvOpenGlNativeDisplay,
) : AutoCloseable {
    private val nativeHandle: COpaquePointer get() = nativeLease.handle
    private val procResolverRef: StableRef<OpenGlProcResolver>? =
        getProcAddress?.let { StableRef.create(OpenGlProcResolver(it)) }
    private var native: CPointer<KmpvOpenGlRenderer>? = null
    private var callbackRef: StableRef<MpvOpenGlRenderer>? = null
    private var updateCallback: (() -> Unit)? = null
    private var closed: Boolean = false

    public fun setUpdateCallback(callback: (() -> Unit)?) {
        check(!closed) { "this MpvOpenGlRenderer is closed" }
        updateCallback = callback
        native?.let { configureNativeCallback(it) }
    }

    public fun render(
        framebuffer: Int,
        width: Int,
        height: Int,
    ): MpvResult<Boolean> = render(
        target = MpvOpenGlRenderTarget(framebuffer, width, height),
    )

    public fun render(
        target: MpvOpenGlRenderTarget,
        options: MpvOpenGlRenderOptions = MpvOpenGlRenderOptions(),
    ): MpvResult<Boolean> {
        check(!closed) { "this MpvOpenGlRenderer is closed" }
        require(target.width > 0 && target.height > 0) { "render target dimensions must be positive" }
        val renderer = when (val result = ensureNative()) {
            is MpvResult.Success -> result.value
            is MpvResult.Failure -> return result
        }
        val code = kmpv_opengl_renderer_render(
            renderer,
            target.framebuffer,
            target.width,
            target.height,
            target.internalFormat,
            if (options.flipY) 1 else 0,
        )
        return if (code < 0) {
            MpvResult.Failure(owner.nativeError(code, "opengl-render"))
        } else {
            MpvResult.Success(code > 0)
        }
    }

    /** Reports that the host presented/swapped the most recently rendered frame. */
    public fun reportSwap(): MpvResult<Unit> {
        check(!closed) { "this MpvOpenGlRenderer is closed" }
        val renderer = native
            ?: return MpvResult.Failure(owner.nativeError(-4, "opengl-report-swap"))
        val code = kmpv_opengl_renderer_report_swap(renderer)
        return if (code < 0) {
            MpvResult.Failure(owner.nativeError(code, "opengl-report-swap"))
        } else {
            MpvResult.Success(Unit)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            native?.let {
                kmpv_opengl_renderer_set_update_callback(it, null, null)
                kmpv_opengl_renderer_destroy(it)
            }
        } finally {
            native = null
            callbackRef?.dispose()
            callbackRef = null
            updateCallback = null
            procResolverRef?.dispose()
            nativeLease.close()
        }
    }

    internal fun dispatchUpdate() {
        updateCallback?.invoke()
    }

    private fun ensureNative(): MpvResult<CPointer<KmpvOpenGlRenderer>> {
        native?.let { return MpvResult.Success(it) }
        return memScoped {
            val error = alloc<IntVar>()
            error.value = 0
            val created = kmpv_opengl_renderer_create(
                nativeHandle,
                procResolverRef?.let { staticCFunction(::renderGetProcAddress) },
                procResolverRef?.asCPointer(),
                nativeDisplay.type.bridgeValue,
                nativeDisplay.pointer,
                error.ptr,
            )
            if (created == null) {
                MpvResult.Failure(owner.nativeError(error.value, "opengl-render-context"))
            } else {
                native = created
                configureNativeCallback(created)
                MpvResult.Success(created)
            }
        }
    }

    private fun configureNativeCallback(renderer: CPointer<KmpvOpenGlRenderer>) {
        if (updateCallback == null) {
            kmpv_opengl_renderer_set_update_callback(renderer, null, null)
            callbackRef?.dispose()
            callbackRef = null
            return
        }
        val ref = callbackRef ?: StableRef.create(this).also { callbackRef = it }
        kmpv_opengl_renderer_set_update_callback(
            renderer,
            staticCFunction(::renderUpdateCallback),
            ref.asCPointer(),
        )
    }
}

/**
 * Creates a renderer bound to the OpenGL/OpenGL ES context represented by
 * [getProcAddress]. The actual libmpv render context is initialized lazily on
 * the first [MpvOpenGlRenderer.render] call while that context is current.
 */
public fun Mpv.createOpenGlRenderer(
    getProcAddress: (String) -> COpaquePointer?,
    nativeDisplay: MpvOpenGlNativeDisplay = MpvOpenGlNativeDisplay.None,
): MpvResult<MpvOpenGlRenderer> {
    check(isInitialized) { "initialize mpv before creating an OpenGL renderer" }
    return MpvResult.Success(
        MpvOpenGlRenderer(
            owner = this,
            nativeLease = acquireNativeHandleLease(),
            getProcAddress = getProcAddress,
            nativeDisplay = nativeDisplay,
        ),
    )
}
