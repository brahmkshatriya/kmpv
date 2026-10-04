package dev.kmpv

/** A Kotlin-owned copy of libmpv's `mpv_node` value tree. */
public sealed interface MpvNode {
    public data object None : MpvNode
    public data class StringValue(val value: String) : MpvNode
    public data class FlagValue(val value: Boolean) : MpvNode
    public data class Int64Value(val value: Long) : MpvNode
    public data class DoubleValue(val value: Double) : MpvNode
    public data class ArrayValue(val values: List<MpvNode>) : MpvNode
    public data class MapValue(val values: Map<String, MpvNode>) : MpvNode

    public class BytesValue(public val value: ByteArray) : MpvNode {
        override fun equals(other: Any?): Boolean =
            other is BytesValue && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "BytesValue(${value.size} bytes)"
    }
}
