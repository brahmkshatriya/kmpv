@file:OptIn(KmpvAndroidNativeApi::class)

package dev.kmpv

import android.view.Surface
import dev.kmpv.internal.AndroidMpvNative

public enum class MpvAndroidVideoMode(
    internal val vo: String,
    internal val hwdec: String,
) {
    /** GPU rendering through mpv's Android/EGL context. */
    Gpu(
        vo = "gpu",
        hwdec = "mediacodec-copy",
    ),

    /** Direct MediaCodec rendering into the Android Surface. */
    MediaCodecEmbed(
        vo = "mediacodec_embed",
        hwdec = "mediacodec",
    ),
}

/**
 * Applies Android video-output options before [Mpv.initialize].
 *
 * [MpvAndroidVideoMode.Gpu] uses `vo=gpu`, `gpu-context=android`, OpenGL ES,
 * and `mediacodec-copy`. [MpvAndroidVideoMode.MediaCodecEmbed] uses direct
 * MediaCodec surface output instead.
 */
public fun Mpv.configureAndroidVideoOutput(
    mode: MpvAndroidVideoMode = MpvAndroidVideoMode.Gpu,
): MpvResult<Unit> {
    check(!isInitialized) { "configure Android video output before initialize()" }
    val options = buildList {
        add("vo" to mode.vo)
        add("hwdec" to mode.hwdec)
        if (mode == MpvAndroidVideoMode.Gpu) {
            add("gpu-context" to "android")
            add("opengl-es" to "yes")
        }
    }
    for ((name, value) in options) {
        when (val result = setOption(name, value)) {
            is MpvResult.Success -> Unit
            is MpvResult.Failure -> return result
        }
    }
    return MpvResult.Success(Unit)
}

/** Android `Surface` output attached to an initialized [Mpv] instance. */
public class MpvAndroidSurfaceOutput internal constructor(
    private val mpv: Mpv,
    private val mode: MpvAndroidVideoMode,
) : AutoCloseable {
    private var attached: Boolean = false

    public fun attach(surface: Surface): MpvResult<Unit> {
        check(!mpv.isClosed) { "this Mpv instance is closed" }
        check(!attached) { "an Android Surface is already attached" }
        return mpv.withAndroidNativeHandle { handle ->
            val code = AndroidMpvNative.nativeAttachSurface(handle, surface)
            if (code < 0) {
                return@withAndroidNativeHandle MpvResult.Failure(
                    mpv.androidNativeError(code, "android-surface-attach"),
                )
            }
            attached = true
            MpvResult.Success(Unit)
        }
    }

    public fun resize(width: Int, height: Int): MpvResult<Unit> {
        require(width > 0 && height > 0) { "surface dimensions must be positive" }
        check(attached) { "attach a Surface before reporting its size" }
        if (mode != MpvAndroidVideoMode.Gpu) return MpvResult.Success(Unit)
        return mpv.setProperty(AndroidSurfaceSize, "${width}x$height")
    }

    public fun detach(): MpvResult<Unit> {
        if (!attached) return MpvResult.Success(Unit)
        if (mpv.isClosed) {
            attached = false
            return MpvResult.Success(Unit)
        }

        return mpv.withAndroidNativeHandle { handle ->
            val code = AndroidMpvNative.nativeDetachSurface(handle)
            attached = false

            if (code < 0) {
                return@withAndroidNativeHandle MpvResult.Failure(
                    mpv.androidNativeError(code, "android-surface-detach"),
                )
            }
            MpvResult.Success(Unit)
        }
    }

    override fun close() {
        if (!mpv.isClosed) detach()
    }

    private companion object {
        val AndroidSurfaceSize: MpvProperty<String> = MpvProperty.string("android-surface-size")
    }
}

public fun Mpv.createAndroidSurfaceOutput(
    mode: MpvAndroidVideoMode = MpvAndroidVideoMode.Gpu,
): MpvAndroidSurfaceOutput {
    check(isInitialized) { "initialize mpv before creating Android Surface output" }
    return MpvAndroidSurfaceOutput(
        mpv = this,
        mode = mode,
    )
}
