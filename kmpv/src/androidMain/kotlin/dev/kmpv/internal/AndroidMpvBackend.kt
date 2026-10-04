package dev.kmpv.internal

import dev.kmpv.MpvError
import dev.kmpv.MpvEvent
import dev.kmpv.MpvFormat
import dev.kmpv.MpvNode
import dev.kmpv.MpvResult
import dev.kmpv.MpvValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

internal class AndroidMpvBackend : MpvBackend {
    private var handle: Long = AndroidMpvNative.nativeCreate().also {
        check(it != 0L) {
            "Android libmpv runtime is unavailable: ${AndroidMpvNative.nativeLastLoadError()}"
        }
    }
    private val internalInbound: Channel<MpvEvent> = Channel(Channel.UNLIMITED)
    private val publicInbound: Channel<MpvEvent> = Channel(
        capacity = PublicEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableInternalEvents: MutableSharedFlow<MpvEvent> = MutableSharedFlow()
    private val mutableEvents: MutableSharedFlow<MpvEvent> = MutableSharedFlow()
    private val lifecycleLock = ReentrantReadWriteLock()
    private var eventThread: Thread? = null
    @Volatile private var closed: Boolean = false
    @Volatile private var initialized: Boolean = false

    private companion object {
        const val PublicEventBufferCapacity: Int = 256
    }

    override val clientName: String
        get() = withHandle { AndroidMpvNative.nativeClientName(it).orEmpty() }

    override val clientId: Long
        get() = withHandle(AndroidMpvNative::nativeClientId)

    override val internalEvents: SharedFlow<MpvEvent> = mutableInternalEvents.asSharedFlow()
    override val events: SharedFlow<MpvEvent> = mutableEvents.asSharedFlow()

    init {
        scope.launch {
            for (event in internalInbound) mutableInternalEvents.emit(event)
        }
        scope.launch {
            for (event in publicInbound) mutableEvents.emit(event)
        }
    }

    override fun initialize(): MpvResult<Unit> {
        return withHandle { current ->
            val result = unitResult(AndroidMpvNative.nativeInitialize(current), "initialize")
            if (result is MpvResult.Success) {
                initialized = true
                startEventThread(current)
            }
            result
        }
    }

    override fun setOption(name: String, value: String): MpvResult<Unit> =
        withHandle { unitResult(AndroidMpvNative.nativeSetOptionString(it, name, value), "option:$name") }

    override fun loadConfigFile(path: String): MpvResult<Unit> =
        withHandle { unitResult(AndroidMpvNative.nativeLoadConfigFile(it, path), "config:$path") }

    override fun requestLogMessages(minLevel: String): MpvResult<Unit> =
        withHandle { unitResult(AndroidMpvNative.nativeRequestLogMessages(it, minLevel), "logs:$minLevel") }

    override fun getProperty(name: String, format: MpvFormat): MpvResult<MpvValue> = withHandle { current ->
        val decoded = decodeNodeResult(AndroidMpvNative.nativeGetProperty(current, name, format.bridgeValue))
        if (decoded.code < 0) return@withHandle failure(decoded.code, name)
        val value = decoded.value?.toMpvValue()
            ?: return@withHandle MpvResult.Failure(
                MpvError(-1, "property had an unexpected value type", name),
            )
        MpvResult.Success(value)
    }

    override fun getPropertyNode(name: String): MpvResult<MpvNode> = withHandle { current ->
        val decoded = decodeNodeResult(AndroidMpvNative.nativeGetProperty(current, name, 6))
        if (decoded.code < 0) {
            failure(decoded.code, name)
        } else {
            MpvResult.Success(decoded.value ?: MpvNode.None)
        }
    }

    override fun setProperty(name: String, value: MpvValue): MpvResult<Unit> = withHandle { current ->
        val code = when (value) {
            is MpvValue.StringValue -> AndroidMpvNative.nativeSetPropertyString(current, name, value.value)
            is MpvValue.FlagValue -> AndroidMpvNative.nativeSetPropertyFlag(current, name, value.value)
            is MpvValue.Int64Value -> AndroidMpvNative.nativeSetPropertyInt64(current, name, value.value)
            is MpvValue.DoubleValue -> AndroidMpvNative.nativeSetPropertyDouble(current, name, value.value)
            is MpvValue.NodeValue -> AndroidMpvNative.nativeSetPropertyNode(current, name, encodeNode(value.value))
        }
        unitResult(code, name)
    }

    override fun setPropertyNode(name: String, value: MpvNode): MpvResult<Unit> = withHandle { current ->
        unitResult(
            AndroidMpvNative.nativeSetPropertyNode(current, name, encodeNode(value)),
            name,
        )
    }

    override fun getPropertyAsync(
        requestId: Long,
        name: String,
        format: MpvFormat,
    ): MpvResult<Unit> = withHandle { current ->
        unitResult(
            AndroidMpvNative.nativeGetPropertyAsync(current, requestId, name, format.bridgeValue),
            "get-async:$name",
        )
    }

    override fun setPropertyAsync(
        requestId: Long,
        name: String,
        value: MpvValue,
    ): MpvResult<Unit> = withHandle { current ->
        val code = when (value) {
            is MpvValue.StringValue ->
                AndroidMpvNative.nativeSetPropertyStringAsync(current, requestId, name, value.value)
            is MpvValue.FlagValue ->
                AndroidMpvNative.nativeSetPropertyFlagAsync(current, requestId, name, value.value)
            is MpvValue.Int64Value ->
                AndroidMpvNative.nativeSetPropertyInt64Async(current, requestId, name, value.value)
            is MpvValue.DoubleValue ->
                AndroidMpvNative.nativeSetPropertyDoubleAsync(current, requestId, name, value.value)
            is MpvValue.NodeValue ->
                AndroidMpvNative.nativeSetPropertyNodeAsync(current, requestId, name, encodeNode(value.value))
        }
        unitResult(code, "set-async:$name")
    }

    override fun command(args: List<String>): MpvResult<Unit> =
        withHandle { unitResult(AndroidMpvNative.nativeCommand(it, args.toTypedArray()), args.firstOrNull()) }

    override fun commandResult(args: List<String>): MpvResult<MpvNode> = withHandle { current ->
        val decoded = decodeNodeResult(AndroidMpvNative.nativeCommandResult(current, args.toTypedArray()))
        if (decoded.code < 0) {
            failure(decoded.code, args.firstOrNull())
        } else {
            MpvResult.Success(decoded.value ?: MpvNode.None)
        }
    }

    override fun commandAsync(requestId: Long, args: List<String>): MpvResult<Unit> = withHandle {
        unitResult(
            AndroidMpvNative.nativeCommandAsync(it, requestId, args.toTypedArray()),
            args.firstOrNull(),
        )
    }

    override fun abortAsyncCommand(requestId: Long) {
        withHandleIfOpen { AndroidMpvNative.nativeAbortAsyncCommand(it, requestId) }
    }

    override fun observeProperty(
        observerId: Long,
        name: String,
        format: MpvFormat,
    ): MpvResult<Unit> = withHandle {
        unitResult(
            AndroidMpvNative.nativeObserveProperty(it, observerId, name, format.bridgeValue),
            "observe:$name",
        )
    }

    override fun unobserveProperty(observerId: Long): MpvResult<Unit> =
        withHandleIfOpen {
            unitResult(
                AndroidMpvNative.nativeUnobserveProperty(it, observerId),
                "unobserve:$observerId",
            )
        } ?: MpvResult.Success(Unit)

    override fun close() {
        lifecycleLock.writeLock().withLock {
            if (closed) return
            closed = true
            val current = handle
            if (current == 0L) return

            if (initialized) AndroidMpvNative.nativeWakeup(current)
            val thread = eventThread
            if (thread != null && thread !== Thread.currentThread()) {
                try {
                    thread.join()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            AndroidMpvNative.nativeDestroy(current)
            handle = 0L
            eventThread = null
            internalInbound.close()
            publicInbound.close()
            scope.cancel()
        }
    }

    private fun startEventThread(current: Long) {
        check(eventThread == null) { "Android mpv event thread already started" }
        eventThread = Thread({
            while (!closed) {
                val payload = AndroidMpvNative.nativeWaitEvent(current)
                if (payload == null) {
                    if (closed) break
                    continue
                }
                val event = decodeEvent(payload, ::errorOf)
                internalInbound.trySend(event)
                publicInbound.trySend(event)
            }
        }, "kmpv-mpv-events").apply {
            isDaemon = true
            start()
        }
    }

    internal fun <T> withHandle(block: (Long) -> T): T = lifecycleLock.readLock().withLock {
        check(handle != 0L && !closed) { "this Mpv backend is closed" }
        block(handle)
    }

    private fun <T> withHandleIfOpen(block: (Long) -> T): T? = lifecycleLock.readLock().withLock {
        if (handle == 0L || closed) null else block(handle)
    }

    private fun unitResult(code: Int, context: String? = null): MpvResult<Unit> =
        if (code >= 0) MpvResult.Success(Unit) else failure(code, context)

    private fun <T> failure(code: Int, context: String? = null): MpvResult<T> =
        MpvResult.Failure(errorOf(code, context))

    internal fun errorOf(code: Int, context: String? = null): MpvError = MpvError(
        code = code,
        message = AndroidMpvNative.nativeErrorString(code),
        context = context,
    )
}

internal actual fun createMpvBackend(): MpvBackend = AndroidMpvBackend()
