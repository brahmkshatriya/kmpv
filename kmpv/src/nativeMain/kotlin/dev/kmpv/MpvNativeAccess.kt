@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package dev.kmpv

import dev.kmpv.internal.NativeMpvBackend
import kotlinx.cinterop.COpaquePointer
import kotlin.concurrent.atomics.AtomicInt

/**
 * Internal integration surface for optional native kmpv modules.
 *
 * Regular applications should not use this API. It exists so separately
 * published renderer/platform modules can attach to the same libmpv core
 * without exposing the backend implementation itself.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is reserved for kmpv native integration modules and may change without notice.",
)
public annotation class KmpvNativeApi

@KmpvNativeApi
public fun <T> Mpv.withNativeHandle(block: (COpaquePointer) -> T): T {
    check(!isClosed) { "this Mpv instance is closed" }
    val nativeBackend = backend as? NativeMpvBackend
        ?: error("native libmpv access is unavailable for this backend")
    return nativeBackend.withHandle(block)
}

@KmpvNativeApi
public class MpvNativeHandleLease internal constructor(
    private val nativeBackend: NativeMpvBackend,
    public val handle: COpaquePointer,
) : AutoCloseable {
    private val released: AtomicInt = AtomicInt(0)

    override fun close() {
        if (released.compareAndSet(0, 1)) nativeBackend.releaseNativeResource()
    }
}

@KmpvNativeApi
public fun Mpv.acquireNativeHandleLease(): MpvNativeHandleLease {
    check(!isClosed) { "this Mpv instance is closed" }
    val nativeBackend = backend as? NativeMpvBackend
        ?: error("native libmpv access is unavailable for this backend")
    return MpvNativeHandleLease(nativeBackend, nativeBackend.acquireNativeResource())
}

@KmpvNativeApi
public fun Mpv.nativeError(code: Int, context: String? = null): MpvError {
    val nativeBackend = backend as? NativeMpvBackend
        ?: return MpvError(code, "mpv error $code", context)
    return nativeBackend.errorOf(code, context)
}
