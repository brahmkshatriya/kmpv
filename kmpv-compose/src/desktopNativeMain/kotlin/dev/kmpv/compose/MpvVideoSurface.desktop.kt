@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.kmpv.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.NativeInteropView
import androidx.compose.ui.viewinterop.NativeView
import androidx.compose.ui.viewinterop.OpenGlInteropRenderTarget
import androidx.compose.ui.viewinterop.isOpenGlInteropAvailable
import dev.kmpv.Mpv
import dev.kmpv.MpvError
import dev.kmpv.MpvOpenGlRenderer
import dev.kmpv.MpvResult
import dev.kmpv.createOpenGlRenderer
import dev.kmpv.getOrThrow

@Composable
internal actual fun PlatformMpvVideoSurface(
    mpv: Mpv,
    modifier: Modifier,
    onError: (MpvError) -> Unit,
    onReady: () -> Unit,
) {
    val currentOnReady = rememberUpdatedState(onReady)
    val currentOnError = rememberUpdatedState(onError)
    if (!isOpenGlInteropAvailable()) {
        LaunchedEffect(mpv) {
            currentOnError.value(
                MpvError(
                    code = -1,
                    message = "Compose Native OpenGL interop is unavailable in this window",
                    context = "video-surface",
                ),
            )
        }
        Box(modifier)
        return
    }

    key(mpv) {
        val holder = remember(mpv) { DesktopVideoSurfaceHolder(mpv) }
        SideEffect {
            holder.onReady = currentOnReady.value
            holder.onError = currentOnError.value
        }
        NativeView(
            factory = { holder.nativeView },
            modifier = modifier,
        )
    }
}

private class DesktopVideoSurfaceHolder(mpv: Mpv) : AutoCloseable {
    private val renderer: MpvOpenGlRenderer = mpv.createOpenGlRenderer(
        getProcAddress = ::sdlOpenGlProcAddress,
        nativeDisplay = platformOpenGlNativeDisplay(),
    ).getOrThrow()
    private var closed: Boolean = false
    private var readyReported: Boolean = false

    var onReady: () -> Unit = {}
    var onError: (MpvError) -> Unit = {}

    val nativeView: NativeInteropView = NativeInteropView.openGl(
        renderer = ::render,
        continuousRendering = false,
        releaser = ::close,
    )

    init {
        renderer.setUpdateCallback { nativeView.requestRender() }
    }

    private fun render(target: OpenGlInteropRenderTarget): Boolean =
        when (val result = renderer.render(target.framebuffer, target.width, target.height)) {
            is MpvResult.Success -> {
                if (!readyReported) {
                    readyReported = true
                    onReady()
                }
                result.value
            }
            is MpvResult.Failure -> {
                onError(result.error)
                false
            }
        }

    override fun close() {
        if (closed) return
        closed = true
        renderer.setUpdateCallback(null)
        renderer.close()
    }
}
