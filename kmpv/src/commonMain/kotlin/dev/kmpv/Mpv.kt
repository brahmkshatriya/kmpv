@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.kmpv

import dev.kmpv.internal.MpvBackend
import dev.kmpv.internal.createMpvBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicInt

public class Mpv private constructor(internal val backend: MpvBackend) : AutoCloseable {
    private val requestIdMutex: Mutex = Mutex()
    private var nextRequestId: Long = 1L
    private val initializationState: AtomicInt = AtomicInt(InitializationNew)
    private val closeState: AtomicInt = AtomicInt(CloseOpen)

    public val clientName: String get() = backend.clientName
    public val clientId: Long get() = backend.clientId
    /**
     * Raw libmpv events for diagnostics and advanced integrations.
     *
     * Delivery is isolated from the private request/reply lane used by async
     * commands, async properties, observations, and [MpvPlayer]. If a consumer
     * falls far enough behind, old public events may be dropped instead of
     * delaying playback/control work or growing memory without bound.
     */
    public val events: SharedFlow<MpvEvent> get() = backend.events
    internal val internalEvents: SharedFlow<MpvEvent> get() = backend.internalEvents
    public val isInitialized: Boolean get() = initializationState.load() == InitializationReady
    public val isClosed: Boolean get() = closeState.load() != CloseOpen

    public fun initialize(): MpvResult<Unit> {
        checkOpen()
        check(initializationState.compareAndSet(InitializationNew, InitializationRunning)) {
            "Mpv.initialize() may only be called once"
        }
        return try {
            backend.initialize().also { result ->
                if (result is MpvResult.Success) {
                    initializationState.compareAndSet(InitializationRunning, InitializationReady)
                } else {
                    initializationState.compareAndSet(InitializationRunning, InitializationNew)
                }
            }
        } catch (failure: Throwable) {
            initializationState.compareAndSet(InitializationRunning, InitializationNew)
            throw failure
        }
    }

    public fun <T> setOption(option: MpvOption<T>, value: T): MpvResult<Unit> =
        setOption(option.name, option.encode(value))

    public fun setOption(name: String, value: String): MpvResult<Unit> {
        checkOpen()
        check(initializationState.load() == InitializationNew) { "mpv options must be set before initialize()" }
        return backend.setOption(name, value)
    }

    public fun loadConfigFile(path: String): MpvResult<Unit> {
        checkOpen()
        check(initializationState.load() == InitializationNew) { "mpv config files must be loaded before initialize()" }
        return backend.loadConfigFile(path)
    }

    public fun requestLogMessages(minLevel: String): MpvResult<Unit> {
        checkReady()
        return backend.requestLogMessages(minLevel)
    }

    public operator fun <T> get(property: MpvProperty<T>): MpvResult<T> {
        checkReady()
        val rawResult: MpvResult<MpvValue> = if (property.format == MpvFormat.Node) {
            backend.getPropertyNode(property.name).map(MpvValue::NodeValue)
        } else {
            backend.getProperty(property.name, property.format)
        }
        return when (val result = rawResult) {
            is MpvResult.Success -> property.decode(result.value)?.let { MpvResult.Success(it) }
                ?: MpvResult.Failure(MpvError(-1, "property had an unexpected value type", property.name))
            is MpvResult.Failure -> result
        }
    }

    /** Reads a property through libmpv's asynchronous request/reply API. */
    public suspend fun <T> getAsync(property: MpvProperty<T>): MpvResult<T> = coroutineScope {
        checkReady()
        val requestId = nextId()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            internalEvents.filterIsInstance<MpvEvent.GetPropertyReply>()
                .first { it.requestId == requestId }
        }
        when (val submitted = backend.getPropertyAsync(requestId, property.name, property.format)) {
            is MpvResult.Failure -> {
                waiter.cancel()
                submitted
            }
            is MpvResult.Success -> {
                val reply = waiter.await()
                reply.error?.let { MpvResult.Failure(it) }
                    ?: reply.value?.let(property.decode)?.let { MpvResult.Success(it) }
                    ?: MpvResult.Failure(
                        MpvError(-1, "property had an unexpected value type", property.name),
                    )
            }
        }
    }

    public operator fun <T> set(property: MpvProperty<T>, value: T) {
        setProperty(property, value).getOrThrow()
    }

    public fun <T> setProperty(property: MpvProperty<T>, value: T): MpvResult<Unit> {
        checkReady()
        return when (val encoded = property.encode(value)) {
            is MpvValue.NodeValue -> backend.setPropertyNode(property.name, encoded.value)
            else -> backend.setProperty(property.name, encoded)
        }
    }

    /** Sets a property through libmpv's asynchronous request/reply API. */
    public suspend fun <T> setAsync(property: MpvProperty<T>, value: T): MpvResult<Unit> = coroutineScope {
        checkReady()
        val requestId = nextId()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            internalEvents.filterIsInstance<MpvEvent.SetPropertyReply>()
                .first { it.requestId == requestId }
        }
        when (val submitted = backend.setPropertyAsync(requestId, property.name, property.encode(value))) {
            is MpvResult.Failure -> {
                waiter.cancel()
                submitted
            }
            is MpvResult.Success -> {
                val reply = waiter.await()
                reply.error?.let { MpvResult.Failure(it) } ?: MpvResult.Success(Unit)
            }
        }
    }

    public fun getNodeProperty(name: String): MpvResult<MpvNode> {
        checkReady()
        require(name.isNotBlank()) { "An mpv property name must not be blank" }
        return backend.getPropertyNode(name)
    }

    public suspend fun getNodePropertyAsync(name: String): MpvResult<MpvNode> =
        getAsync(MpvProperty.node(name))

    public fun setNodeProperty(name: String, value: MpvNode): MpvResult<Unit> {
        checkReady()
        require(name.isNotBlank()) { "An mpv property name must not be blank" }
        return backend.setPropertyNode(name, value)
    }

    public suspend fun setNodePropertyAsync(name: String, value: MpvNode): MpvResult<Unit> =
        setAsync(MpvProperty.node(name), value)

    public fun observeNode(name: String): Flow<MpvNode?> = observe(MpvProperty.node(name))

    public fun command(command: MpvCommand): MpvResult<Unit> = command(*command.args.toTypedArray())

    public fun command(vararg args: String): MpvResult<Unit> {
        checkReady()
        require(args.isNotEmpty()) { "An mpv command must have at least one argument" }
        return backend.command(args.toList())
    }

    public fun commandResult(command: MpvCommand): MpvResult<MpvNode> =
        commandResult(*command.args.toTypedArray())

    public fun commandResult(vararg args: String): MpvResult<MpvNode> {
        checkReady()
        require(args.isNotEmpty()) { "An mpv command must have at least one argument" }
        return backend.commandResult(args.toList())
    }

    public suspend fun commandAsync(command: MpvCommand): MpvResult<Unit> = commandAsync(*command.args.toTypedArray())

    public suspend fun commandAsync(vararg args: String): MpvResult<Unit> =
        when (val result = submitAsyncCommand(args.toList())) {
            is MpvResult.Success -> MpvResult.Success(Unit)
            is MpvResult.Failure -> result
        }

    public suspend fun commandResultAsync(command: MpvCommand): MpvResult<MpvNode> =
        commandResultAsync(*command.args.toTypedArray())

    public suspend fun commandResultAsync(vararg args: String): MpvResult<MpvNode> =
        when (val result = submitAsyncCommand(args.toList())) {
            is MpvResult.Success -> MpvResult.Success(result.value.result ?: MpvNode.None)
            is MpvResult.Failure -> result
        }

    private suspend fun submitAsyncCommand(args: List<String>): MpvResult<MpvEvent.CommandReply> = coroutineScope {
        checkReady()
        require(args.isNotEmpty()) { "An mpv command must have at least one argument" }

        val requestId = nextId()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            internalEvents.filterIsInstance<MpvEvent.CommandReply>().first { it.requestId == requestId }
        }

        when (val submitted = backend.commandAsync(requestId, args)) {
            is MpvResult.Failure -> {
                waiter.cancel()
                submitted
            }
            is MpvResult.Success -> {
                try {
                    val reply = waiter.await()
                    reply.error?.let { MpvResult.Failure(it) } ?: MpvResult.Success(reply)
                } catch (cancelled: CancellationException) {
                    backend.abortAsyncCommand(requestId)
                    throw cancelled
                }
            }
        }
    }

    public fun <T> observe(property: MpvProperty<T>): Flow<T?> = callbackFlow {
        checkReady()
        val observerId = nextId()
        val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
            internalEvents.filterIsInstance<MpvEvent.PropertyChange>().collect { event ->
                if (event.observerId == observerId) {
                    trySend(event.value?.let(property.decode))
                }
            }
        }

        when (val result = backend.observeProperty(observerId, property.name, property.format)) {
            is MpvResult.Success -> Unit
            is MpvResult.Failure -> {
                eventJob.cancel()
                close(MpvException(result.error))
            }
        }

        awaitClose {
            eventJob.cancel()
            if (!isClosed) backend.unobserveProperty(observerId)
        }
    }

    override fun close() {
        if (!closeState.compareAndSet(CloseOpen, CloseRunning)) return
        try {
            backend.close()
            check(closeState.compareAndSet(CloseRunning, CloseClosed)) { "Mpv close state changed unexpectedly" }
        } catch (failure: Throwable) {
            closeState.compareAndSet(CloseRunning, CloseOpen)
            throw failure
        }
    }

    private suspend fun nextId(): Long = requestIdMutex.withLock { nextRequestId++ }

    private fun checkOpen() {
        check(closeState.load() == CloseOpen) { "this Mpv instance is closing or closed" }
    }

    private fun checkReady() {
        checkOpen()
        check(initializationState.load() == InitializationReady) { "call initialize() before using the mpv client API" }
    }

    public companion object {
        private const val InitializationNew: Int = 0
        private const val InitializationRunning: Int = 1
        private const val InitializationReady: Int = 2

        private const val CloseOpen: Int = 0
        private const val CloseRunning: Int = 1
        private const val CloseClosed: Int = 2

        public fun create(): Mpv = Mpv(createMpvBackend())
    }
}
