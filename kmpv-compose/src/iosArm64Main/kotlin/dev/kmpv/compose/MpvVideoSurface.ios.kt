@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpv.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import dev.kmpv.Mpv
import dev.kmpv.MpvError
import dev.kmpv.MpvOpenGlRenderer
import dev.kmpv.MpvResult
import dev.kmpv.createOpenGlRenderer
import dev.kmpv.getOrThrow
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.value
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectZero
import platform.EAGL.EAGLContext
import platform.EAGL.kEAGLRenderingAPIOpenGLES2
import platform.GLKit.GLKView
import platform.GLKit.GLKViewDelegateProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.gles2.GL_FRAMEBUFFER_BINDING
import platform.gles2.glGetIntegerv
import platform.posix.RTLD_DEFAULT
import platform.posix.dlsym

@Composable
internal actual fun PlatformMpvVideoSurface(
    mpv: Mpv,
    modifier: Modifier,
    onError: (MpvError) -> Unit,
    onReady: () -> Unit,
) {
    val currentOnReady = rememberUpdatedState(onReady)
    val currentOnError = rememberUpdatedState(onError)
    UIKitView(
        factory = {
            IosMpvVideoView.create(mpv).also {
                it.onReady = currentOnReady.value
                it.onError = currentOnError.value
            }
        },
        modifier = modifier,
        update = {
            it.onReady = currentOnReady.value
            it.onError = currentOnError.value
        },
        onRelease = {
            it.close()
        },
    )
}

private class IosMpvVideoView private constructor(
    private val eaglContext: EAGLContext,
    private val renderer: MpvOpenGlRenderer,
) : GLKView(CGRectZero.readValue(), eaglContext), GLKViewDelegateProtocol {
    private var closed: Boolean = false
    private var readyReported: Boolean = false

    var onReady: () -> Unit = {}
    var onError: (MpvError) -> Unit = {}

    init {
        delegate = this
        enableSetNeedsDisplay = true
        renderer.setUpdateCallback {
            dispatch_async(dispatch_get_main_queue()) {
                if (!closed) setNeedsDisplay()
            }
        }
    }

    override fun glkView(view: GLKView, drawInRect: CValue<CGRect>) {
        if (closed) return
        if (!EAGLContext.setCurrentContext(eaglContext)) {
            onError(
                MpvError(
                    code = -1,
                    message = "Could not make the EAGL context current",
                    context = "ios-opengl-render",
                ),
            )
            return
        }

        view.bindDrawable()
        if (view.drawableWidth <= 0 || view.drawableHeight <= 0) return
        memScoped {
            val framebuffer = alloc<IntVar>()
            glGetIntegerv(GL_FRAMEBUFFER_BINDING.toUInt(), framebuffer.ptr)
            when (
                val result = renderer.render(
                    framebuffer = framebuffer.value,
                    width = view.drawableWidth.toInt(),
                    height = view.drawableHeight.toInt(),
                )
            ) {
                is MpvResult.Success -> {
                    if (!readyReported) {
                        readyReported = true
                        onReady()
                    }
                }
                is MpvResult.Failure -> onError(result.error)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        EAGLContext.setCurrentContext(eaglContext)
        renderer.setUpdateCallback(null)
        renderer.close()
        delegate = null
        deleteDrawable()
        if (EAGLContext.currentContext() == eaglContext) {
            EAGLContext.setCurrentContext(null)
        }
    }

    companion object {
        fun create(mpv: Mpv): IosMpvVideoView {
            val context = EAGLContext(kEAGLRenderingAPIOpenGLES2)
            val renderer = mpv.createOpenGlRenderer(
                getProcAddress = ::resolveOpenGlSymbol,
            ).getOrThrow()
            return IosMpvVideoView(context, renderer)
        }
    }
}

private fun resolveOpenGlSymbol(name: String): COpaquePointer? = dlsym(RTLD_DEFAULT, name)
