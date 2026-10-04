package dev.kmpv.internal

import dev.kmpv.MpvEndFileReason
import dev.kmpv.MpvError
import dev.kmpv.MpvEvent
import dev.kmpv.MpvNode
import dev.kmpv.MpvValue

internal data class AndroidNodeResult(
    val code: Int,
    val value: MpvNode?,
)

internal fun decodeNodeResult(bytes: ByteArray): AndroidNodeResult {
    val reader = AndroidBinaryReader(bytes)
    val code = reader.readInt()
    return AndroidNodeResult(code, if (code >= 0) reader.readNode() else null)
}

internal fun decodeEvent(
    bytes: ByteArray,
    errorOf: (Int, String?) -> MpvError,
): MpvEvent {
    val reader = AndroidBinaryReader(bytes)
    val eventId = reader.readInt()
    val replyUserdata = reader.readLong()
    val error = reader.readInt()

    return when (eventId) {
        1 -> MpvEvent.Shutdown
        2 -> MpvEvent.LogMessage(
            prefix = reader.readString().orEmpty(),
            level = reader.readString().orEmpty(),
            text = reader.readString().orEmpty(),
        )
        3 -> {
            val name = reader.readString().orEmpty()
            val propertyFormat = reader.readInt()
            val available = reader.readByte() != 0
            val node = if (available) reader.readNode() else null
            MpvEvent.GetPropertyReply(
                requestId = replyUserdata,
                name = name,
                error = error.takeIf { it < 0 }?.let { errorOf(it, "get-property") },
                value = when {
                    node == null -> null
                    propertyFormat == 6 -> MpvValue.NodeValue(node)
                    else -> node.toMpvValue()
                },
            )
        }
        4 -> MpvEvent.SetPropertyReply(
            requestId = replyUserdata,
            error = error.takeIf { it < 0 }?.let { errorOf(it, "set-property") },
        )
        5 -> MpvEvent.CommandReply(
            requestId = replyUserdata,
            error = error.takeIf { it < 0 }?.let { errorOf(it, "command") },
            result = reader.readNode().takeUnless { it === MpvNode.None },
        )
        6 -> MpvEvent.StartFile
        7 -> {
            val reason = when (reader.readInt()) {
                0 -> MpvEndFileReason.Eof
                2 -> MpvEndFileReason.Stop
                3 -> MpvEndFileReason.Quit
                4 -> MpvEndFileReason.Error
                5 -> MpvEndFileReason.Redirect
                else -> MpvEndFileReason.Unknown
            }
            val endError = reader.readInt()
            MpvEvent.EndFile(
                reason = reason,
                error = endError.takeIf { it < 0 }?.let { errorOf(it, "end-file") },
            )
        }
        8 -> MpvEvent.FileLoaded
        20 -> MpvEvent.Seek
        21 -> MpvEvent.PlaybackRestart
        22 -> {
            val name = reader.readString().orEmpty()
            val propertyFormat = reader.readInt()
            val available = reader.readByte() != 0
            val node = if (available) reader.readNode() else null
            MpvEvent.PropertyChange(
                observerId = replyUserdata,
                name = name,
                value = when {
                    node == null -> null
                    propertyFormat == 6 -> MpvValue.NodeValue(node)
                    else -> node.toMpvValue()
                },
            )
        }
        24 -> MpvEvent.QueueOverflow
        else -> MpvEvent.Unknown(
            eventId = eventId,
            replyUserdata = replyUserdata,
            error = error.takeIf { it < 0 }?.let { errorOf(it, "event:$eventId") },
        )
    }
}

internal fun MpvNode.toMpvValue(): MpvValue? = when (this) {
    is MpvNode.StringValue -> MpvValue.StringValue(value)
    is MpvNode.FlagValue -> MpvValue.FlagValue(value)
    is MpvNode.Int64Value -> MpvValue.Int64Value(value)
    is MpvNode.DoubleValue -> MpvValue.DoubleValue(value)
    else -> MpvValue.NodeValue(this)
}

internal fun encodeNode(value: MpvNode): ByteArray = AndroidBinaryWriter().apply {
    writeNode(value)
}.toByteArray()

private class AndroidBinaryWriter {
    private var bytes: ByteArray = ByteArray(128)
    private var size: Int = 0

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    fun writeNode(value: MpvNode) {
        when (value) {
            MpvNode.None -> writeByte(0)
            is MpvNode.StringValue -> {
                writeByte(1)
                writeString(value.value)
            }
            is MpvNode.FlagValue -> {
                writeByte(3)
                writeByte(if (value.value) 1 else 0)
            }
            is MpvNode.Int64Value -> {
                writeByte(4)
                writeLong(value.value)
            }
            is MpvNode.DoubleValue -> {
                writeByte(5)
                writeLong(value.value.toBits())
            }
            is MpvNode.ArrayValue -> {
                writeByte(7)
                writeInt(value.values.size)
                value.values.forEach(::writeNode)
            }
            is MpvNode.MapValue -> {
                writeByte(8)
                writeInt(value.values.size)
                value.values.forEach { (key, child) ->
                    writeString(key)
                    writeNode(child)
                }
            }
            is MpvNode.BytesValue -> {
                writeByte(9)
                writeInt(value.value.size)
                writeBytes(value.value)
            }
        }
    }

    private fun writeString(value: String) {
        val encoded = value.encodeToByteArray()
        writeInt(encoded.size)
        writeBytes(encoded)
    }

    private fun writeByte(value: Int) {
        ensureCapacity(1)
        bytes[size++] = value.toByte()
    }

    private fun writeInt(value: Int) {
        ensureCapacity(4)
        repeat(4) { index -> bytes[size++] = (value ushr (index * 8)).toByte() }
    }

    private fun writeLong(value: Long) {
        ensureCapacity(8)
        repeat(8) { index -> bytes[size++] = (value ushr (index * 8)).toByte() }
    }

    private fun writeBytes(value: ByteArray) {
        ensureCapacity(value.size)
        value.copyInto(bytes, destinationOffset = size)
        size += value.size
    }

    private fun ensureCapacity(extra: Int) {
        require(extra >= 0 && size <= Int.MAX_VALUE - extra) { "encoded mpv node is too large" }
        val required = size + extra
        if (required <= bytes.size) return
        var next = bytes.size
        while (next < required) {
            next = if (next > Int.MAX_VALUE / 2) required else (next * 2).coerceAtLeast(required)
        }
        bytes = bytes.copyOf(next)
    }
}

private class AndroidBinaryReader(private val bytes: ByteArray) {
    private var offset: Int = 0

    fun readByte(): Int {
        requireAvailable(1)
        return bytes[offset++].toInt() and 0xff
    }

    fun readInt(): Int {
        requireAvailable(4)
        var value = 0
        repeat(4) { index ->
            value = value or ((bytes[offset + index].toInt() and 0xff) shl (index * 8))
        }
        offset += 4
        return value
    }

    fun readLong(): Long {
        requireAvailable(8)
        var value = 0L
        repeat(8) { index ->
            value = value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
        }
        offset += 8
        return value
    }

    fun readDouble(): Double = Double.fromBits(readLong())

    fun readString(): String? {
        val size = readInt()
        if (size < 0) return null
        requireAvailable(size)
        val value = String(bytes, offset, size, Charsets.UTF_8)
        offset += size
        return value
    }

    fun readNode(): MpvNode = when (val format = readByte()) {
        0 -> MpvNode.None
        1 -> MpvNode.StringValue(readString().orEmpty())
        3 -> MpvNode.FlagValue(readByte() != 0)
        4 -> MpvNode.Int64Value(readLong())
        5 -> MpvNode.DoubleValue(readDouble())
        7 -> {
            val count = readCount()
            MpvNode.ArrayValue(List(count) { readNode() })
        }
        8 -> {
            val count = readCount()
            val values = LinkedHashMap<String, MpvNode>(count)
            repeat(count) {
                values[readString().orEmpty()] = readNode()
            }
            MpvNode.MapValue(values)
        }
        9 -> {
            val count = readCount()
            requireAvailable(count)
            val value = bytes.copyOfRange(offset, offset + count)
            offset += count
            MpvNode.BytesValue(value)
        }
        else -> error("unsupported encoded mpv node format: $format")
    }

    private fun readCount(): Int {
        val count = readInt()
        require(count >= 0) { "negative encoded collection size: $count" }
        return count
    }

    private fun requireAvailable(size: Int) {
        require(size >= 0 && offset <= bytes.size - size) {
            "truncated Android mpv bridge payload"
        }
    }
}
