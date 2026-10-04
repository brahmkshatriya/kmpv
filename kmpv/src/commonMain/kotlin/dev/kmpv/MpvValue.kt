package dev.kmpv

public sealed interface MpvValue {
    public data class StringValue(val value: String) : MpvValue
    public data class FlagValue(val value: Boolean) : MpvValue
    public data class Int64Value(val value: Long) : MpvValue
    public data class DoubleValue(val value: Double) : MpvValue
    public data class NodeValue(val value: MpvNode) : MpvValue
}

internal enum class MpvFormat(val bridgeValue: Int) {
    String(1),
    Flag(3),
    Int64(4),
    Double(5),
    Node(6),
}
