@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.kmpv.internal

import dev.kmpv.bridge.kmpv_thread_yield
import kotlin.concurrent.atomics.AtomicInt

/**
 * Pins the native wrapper while synchronous calls are in flight and tracks
 * longer-lived native children such as libmpv render contexts.
 */
internal class NativeCallGate {
    private val state: AtomicInt = AtomicInt(0)
    private val resources: AtomicInt = AtomicInt(0)
    private val destroyed: AtomicInt = AtomicInt(0)

    fun enter() {
        check(tryEnter()) { "this Mpv backend is closing or closed" }
    }

    fun tryEnter(): Boolean {
        while (true) {
            if (destroyed.load() != 0) return false
            val current = state.load()
            if (current and ClosingBit != 0) return false
            val active = current and CountMask
            check(active < CountMask) { "too many concurrent native mpv calls" }
            if (state.compareAndSet(current, current + 1)) return true
        }
    }

    fun exit() {
        while (true) {
            val current = state.load()
            check(current and CountMask > 0) { "native mpv call gate underflow" }
            if (state.compareAndSet(current, current - 1)) return
        }
    }

    fun acquireResource() {
        enter()
        try {
            updateResources(+1)
        } finally {
            exit()
        }
    }

    fun releaseResource() {
        updateResources(-1)
    }

    /** Returns false if another close already completed. */
    fun beginClose(): Boolean {
        while (true) {
            if (destroyed.load() != 0) return false
            val current = state.load()
            if (current and ClosingBit != 0) {
                while (true) {
                    if (destroyed.load() != 0) return false
                    if (state.load() and ClosingBit == 0) break
                    kmpv_thread_yield()
                }
                continue
            }
            if (state.compareAndSet(current, current or ClosingBit)) break
        }

        while (state.load() and CountMask != 0) kmpv_thread_yield()

        val openResources = resources.load()
        if (openResources != 0) {
            check(state.compareAndSet(ClosingBit, 0)) { "native mpv close gate changed unexpectedly" }
            error(
                "close native mpv child resources before closing Mpv " +
                    "($openResources resource${if (openResources == 1) "" else "s"} still open)",
            )
        }
        return true
    }

    fun markDestroyed() {
        check(destroyed.compareAndSet(0, 1)) { "native mpv backend was destroyed more than once" }
    }

    private fun updateResources(delta: Int) {
        while (true) {
            val current = resources.load()
            val next = current + delta
            check(next >= 0) { "native mpv resource gate underflow" }
            if (resources.compareAndSet(current, next)) return
        }
    }

    private companion object {
        const val ClosingBit: Int = Int.MIN_VALUE
        const val CountMask: Int = Int.MAX_VALUE
    }
}
