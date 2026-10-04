package dev.kmpv

import dev.kmpv.internal.AndroidMpvBackend

/** Integration surface reserved for optional Android kmpv modules. */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is reserved for kmpv Android integration modules and may change without notice.",
)
public annotation class KmpvAndroidNativeApi

@KmpvAndroidNativeApi
public fun <T> Mpv.withAndroidNativeHandle(block: (Long) -> T): T {
    check(!isClosed) { "this Mpv instance is closed" }
    val androidBackend = backend as? AndroidMpvBackend
        ?: error("Android libmpv access is unavailable for this backend")
    return androidBackend.withHandle(block)
}

@KmpvAndroidNativeApi
public fun Mpv.androidNativeError(code: Int, context: String? = null): MpvError {
    val androidBackend = backend as? AndroidMpvBackend
        ?: return MpvError(code, "mpv error $code", context)
    return androidBackend.errorOf(code, context)
}
