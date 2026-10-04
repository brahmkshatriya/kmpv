@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpv.internal

import cnames.structs.KmpvHandle
import dev.kmpv.MpvEndFileReason
import dev.kmpv.MpvError
import dev.kmpv.MpvEvent
import dev.kmpv.MpvFormat
import dev.kmpv.MpvNode
import dev.kmpv.MpvResult
import dev.kmpv.MpvValue
import dev.kmpv.bridge.KmpvEvent
import dev.kmpv.bridge.KmpvNode
import dev.kmpv.bridge.kmpv_abort_async_command
import dev.kmpv.bridge.kmpv_client_id
import dev.kmpv.bridge.kmpv_client_name
import dev.kmpv.bridge.kmpv_command
import dev.kmpv.bridge.kmpv_command_async
import dev.kmpv.bridge.kmpv_command_result
import dev.kmpv.bridge.kmpv_create
import dev.kmpv.bridge.kmpv_destroy
import dev.kmpv.bridge.kmpv_error_string
import dev.kmpv.bridge.kmpv_free
import dev.kmpv.bridge.kmpv_get_property_async
import dev.kmpv.bridge.kmpv_get_property_double
import dev.kmpv.bridge.kmpv_get_property_flag
import dev.kmpv.bridge.kmpv_get_property_int64
import dev.kmpv.bridge.kmpv_get_property_node
import dev.kmpv.bridge.kmpv_get_property_string
import dev.kmpv.bridge.kmpv_initialize
import dev.kmpv.bridge.kmpv_load_config_file
import dev.kmpv.bridge.kmpv_node_free
import dev.kmpv.bridge.kmpv_node_byte_at
import dev.kmpv.bridge.kmpv_node_byte_count
import dev.kmpv.bridge.kmpv_node_count
import dev.kmpv.bridge.kmpv_node_double
import dev.kmpv.bridge.kmpv_node_flag
import dev.kmpv.bridge.kmpv_node_format
import dev.kmpv.bridge.kmpv_node_int64
import dev.kmpv.bridge.kmpv_node_key_at
import dev.kmpv.bridge.kmpv_node_string
import dev.kmpv.bridge.kmpv_node_value_at
import dev.kmpv.bridge.kmpv_observe_property
import dev.kmpv.bridge.kmpv_request_log_messages
import dev.kmpv.bridge.kmpv_raw_mpv_handle
import dev.kmpv.bridge.kmpv_set_event_callback
import dev.kmpv.bridge.kmpv_set_option_string
import dev.kmpv.bridge.kmpv_set_property_double
import dev.kmpv.bridge.kmpv_set_property_double_async
import dev.kmpv.bridge.kmpv_set_property_flag
import dev.kmpv.bridge.kmpv_set_property_flag_async
import dev.kmpv.bridge.kmpv_set_property_int64
import dev.kmpv.bridge.kmpv_set_property_int64_async
import dev.kmpv.bridge.kmpv_set_property_node
import dev.kmpv.bridge.kmpv_set_property_node_async
import dev.kmpv.bridge.kmpv_set_property_string
import dev.kmpv.bridge.kmpv_set_property_string_async
import dev.kmpv.bridge.kmpv_unobserve_property
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
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

private const val MPV_EVENT_SHUTDOWN = 1
private const val MPV_EVENT_LOG_MESSAGE = 2
private const val MPV_EVENT_GET_PROPERTY_REPLY = 3
private const val MPV_EVENT_SET_PROPERTY_REPLY = 4
private const val MPV_EVENT_COMMAND_REPLY = 5
private const val MPV_EVENT_START_FILE = 6
private const val MPV_EVENT_END_FILE = 7
private const val MPV_EVENT_FILE_LOADED = 8
private const val MPV_EVENT_SEEK = 20
private const val MPV_EVENT_PLAYBACK_RESTART = 21
private const val MPV_EVENT_PROPERTY_CHANGE = 22
private const val MPV_EVENT_QUEUE_OVERFLOW = 24

private fun nativeEventCallback(context: COpaquePointer?, event: CPointer<KmpvEvent>?) {
    val backend = context?.asStableRef<NativeMpvBackend>()?.get() ?: return
    event?.let(backend::onNativeEvent)
}

internal class NativeMpvBackend : MpvBackend {
    private var handle: CPointer<KmpvHandle>? = kmpv_create()
        ?: error("mpv_create() failed")
    private val callbackRef: StableRef<NativeMpvBackend> = StableRef.create(this)
    private val internalInbound: Channel<MpvEvent> = Channel(Channel.UNLIMITED)
    private val publicInbound: Channel<MpvEvent> = Channel(
        capacity = PublicEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableInternalEvents: MutableSharedFlow<MpvEvent> = MutableSharedFlow()
    private val mutableEvents: MutableSharedFlow<MpvEvent> = MutableSharedFlow()
    private val callGate: NativeCallGate = NativeCallGate()

    private companion object {
        const val PublicEventBufferCapacity: Int = 256
    }

    override val clientName: String
        get() = withHandle { kmpv_client_name(it)?.toKString().orEmpty() }

    override val clientId: Long
        get() = withHandle(::kmpv_client_id)

    override val internalEvents: SharedFlow<MpvEvent> = mutableInternalEvents.asSharedFlow()
    override val events: SharedFlow<MpvEvent> = mutableEvents.asSharedFlow()

    init {
        withHandle {
            kmpv_set_event_callback(
                it,
                staticCFunction(::nativeEventCallback),
                callbackRef.asCPointer(),
            )
        }
        scope.launch {
            for (event in internalInbound) mutableInternalEvents.emit(event)
        }
        scope.launch {
            for (event in publicInbound) mutableEvents.emit(event)
        }
    }

    override fun initialize(): MpvResult<Unit> =
        withHandle { unitResult(kmpv_initialize(it), "initialize") }

    override fun setOption(name: String, value: String): MpvResult<Unit> =
        withHandle { unitResult(kmpv_set_option_string(it, name, value), "option:$name") }

    override fun loadConfigFile(path: String): MpvResult<Unit> =
        withHandle { unitResult(kmpv_load_config_file(it, path), "config:$path") }

    override fun requestLogMessages(minLevel: String): MpvResult<Unit> =
        withHandle { unitResult(kmpv_request_log_messages(it, minLevel), "logs:$minLevel") }

    override fun getProperty(name: String, format: MpvFormat): MpvResult<MpvValue> = withHandle { current ->
        memScoped {
            when (format) {
                MpvFormat.String -> {
                    val out = alloc<CPointerVar<ByteVar>>()
                    out.value = null
                    val code = kmpv_get_property_string(current, name, out.ptr)
                    if (code < 0) return@memScoped failure(code, name)
                    val pointer = out.value ?: return@memScoped MpvResult.Success(MpvValue.StringValue(""))
                    val value = pointer.toKString()
                    kmpv_free(pointer)
                    MpvResult.Success(MpvValue.StringValue(value))
                }
                MpvFormat.Flag -> {
                    val out = alloc<IntVar>()
                    val code = kmpv_get_property_flag(current, name, out.ptr)
                    if (code < 0) failure(code, name) else MpvResult.Success(MpvValue.FlagValue(out.value != 0))
                }
                MpvFormat.Int64 -> {
                    val out = alloc<LongVar>()
                    val code = kmpv_get_property_int64(current, name, out.ptr)
                    if (code < 0) failure(code, name) else MpvResult.Success(MpvValue.Int64Value(out.value))
                }
                MpvFormat.Double -> {
                    val out = alloc<DoubleVar>()
                    val code = kmpv_get_property_double(current, name, out.ptr)
                    if (code < 0) failure(code, name) else MpvResult.Success(MpvValue.DoubleValue(out.value))
                }
                MpvFormat.Node -> error("node properties use getPropertyNode")
            }
        }
    }

    override fun getPropertyNode(name: String): MpvResult<MpvNode> = withHandle { current ->
        memScoped {
            val out = alloc<CPointerVar<KmpvNode>>()
            out.value = null
            val code = kmpv_get_property_node(current, name, out.ptr)
            if (code < 0) return@memScoped failure(code, name)
            val pointer = out.value ?: return@memScoped MpvResult.Success(MpvNode.None)
            try {
                MpvResult.Success(decodeNode(pointer))
            } finally {
                kmpv_node_free(pointer)
            }
        }
    }

    override fun setProperty(name: String, value: MpvValue): MpvResult<Unit> = withHandle { current ->
        val code = when (value) {
            is MpvValue.StringValue -> kmpv_set_property_string(current, name, value.value)
            is MpvValue.FlagValue -> kmpv_set_property_flag(current, name, if (value.value) 1 else 0)
            is MpvValue.Int64Value -> kmpv_set_property_int64(current, name, value.value)
            is MpvValue.DoubleValue -> kmpv_set_property_double(current, name, value.value)
            is MpvValue.NodeValue -> error("node values use setPropertyNode")
        }
        unitResult(code, name)
    }

    override fun setPropertyNode(name: String, value: MpvNode): MpvResult<Unit> =
        withHandle { current -> setPropertyNode(current, name, value) }

    private fun setPropertyNode(
        current: CPointer<KmpvHandle>,
        name: String,
        value: MpvNode,
    ): MpvResult<Unit> = memScoped {
        val encoded = alloc<KmpvNode>()
        encodeNode(value, encoded.ptr)
        unitResult(kmpv_set_property_node(current, name, encoded.ptr), name)
    }

    override fun getPropertyAsync(
        requestId: Long,
        name: String,
        format: MpvFormat,
    ): MpvResult<Unit> = withHandle { current ->
        unitResult(
            kmpv_get_property_async(current, requestId.toULong(), name, format.bridgeValue),
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
                kmpv_set_property_string_async(current, requestId.toULong(), name, value.value)
            is MpvValue.FlagValue ->
                kmpv_set_property_flag_async(current, requestId.toULong(), name, if (value.value) 1 else 0)
            is MpvValue.Int64Value ->
                kmpv_set_property_int64_async(current, requestId.toULong(), name, value.value)
            is MpvValue.DoubleValue ->
                kmpv_set_property_double_async(current, requestId.toULong(), name, value.value)
            is MpvValue.NodeValue -> memScoped {
                val encoded = alloc<KmpvNode>()
                encodeNode(value.value, encoded.ptr)
                kmpv_set_property_node_async(current, requestId.toULong(), name, encoded.ptr)
            }
        }
        unitResult(code, "set-async:$name")
    }

    override fun command(args: List<String>): MpvResult<Unit> = withHandle { current ->
        withArgv(args) { argv ->
            unitResult(kmpv_command(current, argv, args.size), args.firstOrNull())
        }
    }

    override fun commandResult(args: List<String>): MpvResult<MpvNode> = withHandle { current ->
        withArgv(args) { argv ->
            memScoped {
                val out = alloc<CPointerVar<KmpvNode>>()
                out.value = null
                val code = kmpv_command_result(current, argv, args.size, out.ptr)
                if (code < 0) return@memScoped failure(code, args.firstOrNull())
                val pointer = out.value ?: return@memScoped MpvResult.Success(MpvNode.None)
                try {
                    MpvResult.Success(decodeNode(pointer))
                } finally {
                    kmpv_node_free(pointer)
                }
            }
        }
    }

    override fun commandAsync(requestId: Long, args: List<String>): MpvResult<Unit> = withHandle { current ->
        withArgv(args) { argv ->
            unitResult(
                kmpv_command_async(current, requestId.toULong(), argv, args.size),
                args.firstOrNull(),
            )
        }
    }

    override fun abortAsyncCommand(requestId: Long) {
        withHandleIfOpen { kmpv_abort_async_command(it, requestId.toULong()) }
    }

    override fun observeProperty(
        observerId: Long,
        name: String,
        format: MpvFormat,
    ): MpvResult<Unit> = withHandle {
        unitResult(
            kmpv_observe_property(it, observerId.toULong(), name, format.bridgeValue),
            "observe:$name",
        )
    }

    override fun unobserveProperty(observerId: Long): MpvResult<Unit> =
        withHandleIfOpen {
            unitResult(kmpv_unobserve_property(it, observerId.toULong()), "unobserve:$observerId")
        } ?: MpvResult.Success(Unit)

    override fun close() {
        if (!callGate.beginClose()) return
        val current = checkNotNull(handle) { "native mpv handle disappeared during close" }
        kmpv_set_event_callback(current, null, null)
        kmpv_destroy(current)
        handle = null
        callbackRef.dispose()
        internalInbound.close()
        publicInbound.close()
        scope.cancel()
        callGate.markDestroyed()
    }

    internal fun onNativeEvent(eventPointer: CPointer<KmpvEvent>) {
        val event = eventPointer.pointed
        val mapped = when (event.event_id) {
            MPV_EVENT_SHUTDOWN -> MpvEvent.Shutdown
            MPV_EVENT_GET_PROPERTY_REPLY -> MpvEvent.GetPropertyReply(
                requestId = event.reply_userdata.toLong(),
                name = event.property_name?.toKString().orEmpty(),
                error = event.error.takeIf { it < 0 }?.let { errorOf(it, "get-property") },
                value = decodePropertyEventValue(event),
            )
            MPV_EVENT_SET_PROPERTY_REPLY -> MpvEvent.SetPropertyReply(
                requestId = event.reply_userdata.toLong(),
                error = event.error.takeIf { it < 0 }?.let { errorOf(it, "set-property") },
            )
            MPV_EVENT_START_FILE -> MpvEvent.StartFile
            MPV_EVENT_FILE_LOADED -> MpvEvent.FileLoaded
            MPV_EVENT_SEEK -> MpvEvent.Seek
            MPV_EVENT_PLAYBACK_RESTART -> MpvEvent.PlaybackRestart
            MPV_EVENT_QUEUE_OVERFLOW -> MpvEvent.QueueOverflow
            MPV_EVENT_COMMAND_REPLY -> MpvEvent.CommandReply(
                requestId = event.reply_userdata.toLong(),
                error = event.error.takeIf { it < 0 }?.let { errorOf(it, "command") },
                result = event.command_result?.let(::decodeNode),
            )
            MPV_EVENT_END_FILE -> MpvEvent.EndFile(
                reason = when (event.end_file_reason) {
                    0 -> MpvEndFileReason.Eof
                    2 -> MpvEndFileReason.Stop
                    3 -> MpvEndFileReason.Quit
                    4 -> MpvEndFileReason.Error
                    5 -> MpvEndFileReason.Redirect
                    else -> MpvEndFileReason.Unknown
                },
                error = event.end_file_error.takeIf { it < 0 }?.let { errorOf(it, "end-file") },
            )
            MPV_EVENT_PROPERTY_CHANGE -> MpvEvent.PropertyChange(
                observerId = event.reply_userdata.toLong(),
                name = event.property_name?.toKString().orEmpty(),
                value = decodePropertyEventValue(event),
            )
            MPV_EVENT_LOG_MESSAGE -> MpvEvent.LogMessage(
                prefix = event.log_prefix?.toKString().orEmpty(),
                level = event.log_level?.toKString().orEmpty(),
                text = event.log_text?.toKString().orEmpty(),
            )
            else -> MpvEvent.Unknown(
                eventId = event.event_id,
                replyUserdata = event.reply_userdata.toLong(),
                error = event.error.takeIf { it < 0 }?.let { errorOf(it, "event:${event.event_id}") },
            )
        }
        internalInbound.trySend(mapped)
        publicInbound.trySend(mapped)
    }

    private fun decodePropertyEventValue(event: KmpvEvent): MpvValue? =
        if (event.property_available == 0) null else when (event.property_format) {
            1 -> MpvValue.StringValue(event.string_value?.toKString().orEmpty())
            3 -> MpvValue.FlagValue(event.flag_value != 0)
            4 -> MpvValue.Int64Value(event.int64_value)
            5 -> MpvValue.DoubleValue(event.double_value)
            6 -> MpvValue.NodeValue(event.property_node?.let(::decodeNode) ?: MpvNode.None)
            else -> null
        }

    internal fun <T> withHandle(block: (CPointer<KmpvHandle>) -> T): T {
        callGate.enter()
        try {
            return block(checkNotNull(handle) { "this Mpv backend is closed" })
        } finally {
            callGate.exit()
        }
    }

    private fun <T> withHandleIfOpen(block: (CPointer<KmpvHandle>) -> T): T? {
        if (!callGate.tryEnter()) return null
        try {
            val current = handle ?: return null
            return block(current)
        } finally {
            callGate.exit()
        }
    }

    internal fun acquireNativeResource(): COpaquePointer {
        callGate.acquireResource()
        return try {
            val wrapper = checkNotNull(handle) { "this Mpv backend is closed" }
            checkNotNull(kmpv_raw_mpv_handle(wrapper)) { "native mpv handle is unavailable" }
        } catch (failure: Throwable) {
            callGate.releaseResource()
            throw failure
        }
    }

    internal fun releaseNativeResource() {
        callGate.releaseResource()
    }

    private fun unitResult(code: Int, context: String? = null): MpvResult<Unit> =
        if (code >= 0) MpvResult.Success(Unit) else failure(code, context)

    private fun <T> failure(code: Int, context: String? = null): MpvResult<T> =
        MpvResult.Failure(errorOf(code, context))

    internal fun errorOf(code: Int, context: String? = null): MpvError = MpvError(
        code = code,
        message = kmpv_error_string(code)?.toKString() ?: "mpv error $code",
        context = context,
    )

    private fun decodeNode(pointer: CPointer<KmpvNode>): MpvNode {
        return when (kmpv_node_format(pointer)) {
            0 -> MpvNode.None
            1 -> MpvNode.StringValue(kmpv_node_string(pointer)?.toKString().orEmpty())
            3 -> MpvNode.FlagValue(kmpv_node_flag(pointer) != 0)
            4 -> MpvNode.Int64Value(kmpv_node_int64(pointer))
            5 -> MpvNode.DoubleValue(kmpv_node_double(pointer))
            7 -> {
                val count = kmpv_node_count(pointer).coerceAtLeast(0)
                MpvNode.ArrayValue(
                    List(count) { index ->
                        kmpv_node_value_at(pointer, index)?.let(::decodeNode) ?: MpvNode.None
                    },
                )
            }
            8 -> {
                val count = kmpv_node_count(pointer).coerceAtLeast(0)
                val result = LinkedHashMap<String, MpvNode>(count)
                repeat(count) { index ->
                    val key = kmpv_node_key_at(pointer, index)?.toKString().orEmpty()
                    val value = kmpv_node_value_at(pointer, index)?.let(::decodeNode) ?: MpvNode.None
                    result[key] = value
                }
                MpvNode.MapValue(result)
            }
            9 -> {
                val size = kmpv_node_byte_count(pointer).toInt().coerceAtLeast(0)
                MpvNode.BytesValue(
                    ByteArray(size) { index -> kmpv_node_byte_at(pointer, index.toULong()).toByte() },
                )
            }
            else -> MpvNode.None
        }
    }

    private fun MemScope.encodeNode(value: MpvNode, target: CPointer<KmpvNode>) {
        val node = target.pointed
        when (value) {
            MpvNode.None -> node.format = 0
            is MpvNode.StringValue -> {
                node.format = 1
                node.string_value = value.value.cstr.getPointer(this)
            }
            is MpvNode.FlagValue -> {
                node.format = 3
                node.flag_value = if (value.value) 1 else 0
            }
            is MpvNode.Int64Value -> {
                node.format = 4
                node.int64_value = value.value
            }
            is MpvNode.DoubleValue -> {
                node.format = 5
                node.double_value = value.value
            }
            is MpvNode.ArrayValue -> {
                node.format = 7
                node.count = value.values.size
                if (value.values.isNotEmpty()) {
                    val values = allocArray<KmpvNode>(value.values.size)
                    node.values = values
                    value.values.forEachIndexed { index, child ->
                        encodeNode(child, values[index].ptr)
                    }
                }
            }
            is MpvNode.MapValue -> {
                node.format = 8
                node.count = value.values.size
                if (value.values.isNotEmpty()) {
                    val values = allocArray<KmpvNode>(value.values.size)
                    val keys = allocArray<CPointerVar<ByteVar>>(value.values.size)
                    node.values = values
                    node.keys = keys
                    value.values.entries.forEachIndexed { index, (key, child) ->
                        keys[index] = key.cstr.getPointer(this)
                        encodeNode(child, values[index].ptr)
                    }
                }
            }
            is MpvNode.BytesValue -> {
                node.format = 9
                node.byte_count = value.value.size.convert()
                if (value.value.isNotEmpty()) {
                    val bytes = allocArray<UByteVar>(value.value.size)
                    value.value.forEachIndexed { index, byte -> bytes[index] = byte.toUByte() }
                    node.bytes = bytes
                }
            }
        }
    }

    private inline fun <R> withArgv(args: List<String>, block: (CPointer<CPointerVar<ByteVar>>) -> R): R = memScoped {
        val argv = allocArray<CPointerVar<ByteVar>>(args.size)
        args.forEachIndexed { index, value ->
            argv[index] = value.cstr.getPointer(this)
        }
        block(argv)
    }
}

internal actual fun createMpvBackend(): MpvBackend = NativeMpvBackend()
