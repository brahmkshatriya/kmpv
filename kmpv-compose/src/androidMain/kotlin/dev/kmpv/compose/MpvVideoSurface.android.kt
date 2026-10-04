package dev.kmpv.compose

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.kmpv.Mpv
import dev.kmpv.MpvAndroidSurfaceOutput
import dev.kmpv.MpvAndroidVideoMode
import dev.kmpv.MpvError
import dev.kmpv.MpvPlayer
import dev.kmpv.MpvResult
import dev.kmpv.createAndroidSurfaceOutput

@Composable
internal actual fun PlatformMpvVideoSurface(
    mpv: Mpv,
    modifier: Modifier,
    onError: (MpvError) -> Unit,
    onReady: () -> Unit,
) {
    MpvAndroidVideoSurface(
        mpv = mpv,
        mode = MpvAndroidVideoMode.Gpu,
        modifier = modifier,
        onReady = onReady,
        onError = onError,
    )
}

/** Android-specific Compose surface with explicit video-output mode selection. */
@Composable
public fun MpvAndroidVideoSurface(
    mpv: Mpv,
    mode: MpvAndroidVideoMode = MpvAndroidVideoMode.Gpu,
    modifier: Modifier = Modifier,
    onError: (MpvError) -> Unit = {},
) {
    MpvAndroidVideoSurface(
        mpv = mpv,
        mode = mode,
        modifier = modifier,
        onReady = {},
        onError = onError,
    )
}

/** Android-specific Compose surface with an explicit readiness callback. */
@Composable
public fun MpvAndroidVideoSurface(
    mpv: Mpv,
    mode: MpvAndroidVideoMode = MpvAndroidVideoMode.Gpu,
    modifier: Modifier = Modifier,
    onReady: () -> Unit,
    onError: (MpvError) -> Unit = {},
) {
    check(mpv.isInitialized) { "initialize mpv before composing MpvAndroidVideoSurface" }
    val currentOnReady = rememberUpdatedState(onReady)
    val currentOnError = rememberUpdatedState(onError)

    key(mpv, mode) {
        val binding = remember(mpv, mode) { AndroidVideoSurfaceBinding(mpv, mode) }
        SideEffect {
            binding.onReady = currentOnReady.value
            binding.onError = currentOnError.value
        }
        DisposableEffect(binding) {
            onDispose { binding.close() }
        }
        AndroidView(
            factory = binding::create,
            modifier = modifier,
        )
    }
}

/** Android-specific overload for a hoisted [MpvPlayer]. */
@Composable
public fun MpvAndroidVideoSurface(
    player: MpvPlayer,
    mode: MpvAndroidVideoMode = MpvAndroidVideoMode.Gpu,
    modifier: Modifier = Modifier,
    onError: (MpvError) -> Unit = {},
) {
    MpvAndroidVideoSurface(player.raw, mode, modifier, onError)
}

/** Android player overload with an explicit readiness callback. */
@Composable
public fun MpvAndroidVideoSurface(
    player: MpvPlayer,
    mode: MpvAndroidVideoMode = MpvAndroidVideoMode.Gpu,
    modifier: Modifier = Modifier,
    onReady: () -> Unit,
    onError: (MpvError) -> Unit = {},
) {
    MpvAndroidVideoSurface(
        mpv = player.raw,
        mode = mode,
        modifier = modifier,
        onReady = onReady,
        onError = onError,
    )
}

private class AndroidVideoSurfaceBinding(
    mpv: Mpv,
    mode: MpvAndroidVideoMode,
) : SurfaceHolder.Callback, AutoCloseable {
    private val output: MpvAndroidSurfaceOutput = mpv.createAndroidSurfaceOutput(mode)
    private var view: SurfaceView? = null
    private var closed: Boolean = false
    private var readyReported: Boolean = false

    var onReady: () -> Unit = {}
    var onError: (MpvError) -> Unit = {}

    fun create(context: Context): SurfaceView = SurfaceView(context).also {
        view = it
        it.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        when (val result = output.attach(holder.surface)) {
            is MpvResult.Success -> {
                if (!readyReported) {
                    readyReported = true
                    onReady()
                }
            }
            is MpvResult.Failure -> onError(result.error)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        report(output.resize(width, height))
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        report(output.detach())
    }

    private fun report(result: MpvResult<Unit>) {
        if (result is MpvResult.Failure) onError(result.error)
    }

    override fun close() {
        if (closed) return
        closed = true
        view?.holder?.removeCallback(this)
        view = null
        report(output.detach())
        output.close()
    }
}
