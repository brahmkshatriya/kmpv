package dev.kmpv.internal

import dev.kmpv.MpvEvent
import dev.kmpv.MpvNode
import dev.kmpv.MpvValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidBinaryTest {
    @Test
    fun decodesRecursiveNodeResult() {
        val payload = BinaryWriter().apply {
            int(0)
            byte(8) // map
            int(3)

            string("title")
            byte(1)
            string("demo")

            string("tracks")
            byte(7) // array
            int(2)
            byte(3)
            byte(1)
            byte(4)
            long(42)

            string("blob")
            byte(9)
            int(3)
            raw(byteArrayOf(1, 2, 3))
        }.toByteArray()

        val result = decodeNodeResult(payload)
        assertEquals(0, result.code)
        val map = assertIs<MpvNode.MapValue>(result.value)
        assertEquals(MpvNode.StringValue("demo"), map.values["title"])

        val tracks = assertIs<MpvNode.ArrayValue>(map.values["tracks"])
        assertEquals(MpvNode.FlagValue(true), tracks.values[0])
        assertEquals(MpvNode.Int64Value(42), tracks.values[1])

        val blob = assertIs<MpvNode.BytesValue>(map.values["blob"])
        assertContentEquals(byteArrayOf(1, 2, 3), blob.value)
    }

    @Test
    fun decodesObservedDoubleProperty() {
        val payload = BinaryWriter().apply {
            int(22)
            long(99)
            int(0)
            string("time-pos")
            int(5)
            byte(1)
            byte(5)
            double(12.5)
        }.toByteArray()

        val event = assertIs<MpvEvent.PropertyChange>(
            decodeEvent(payload) { code, context -> error("unexpected error $code at $context") },
        )
        assertEquals(99, event.observerId)
        assertEquals("time-pos", event.name)
        assertEquals(MpvValue.DoubleValue(12.5), event.value)
    }

    @Test
    fun decodesCommandReplyNode() {
        val payload = BinaryWriter().apply {
            int(5)
            long(7)
            int(0)
            byte(1)
            string("ok")
        }.toByteArray()

        val event = assertIs<MpvEvent.CommandReply>(
            decodeEvent(payload) { code, context -> error("unexpected error $code at $context") },
        )
        assertEquals(7, event.requestId)
        assertEquals(null, event.error)
        assertEquals(MpvNode.StringValue("ok"), event.result)
    }

    @Test
    fun encodesAndDecodesEveryNodeShape() {
        val expected = MpvNode.MapValue(
            linkedMapOf(
                "none" to MpvNode.None,
                "text" to MpvNode.StringValue("hello"),
                "flag" to MpvNode.FlagValue(true),
                "int" to MpvNode.Int64Value(42),
                "double" to MpvNode.DoubleValue(1.25),
                "array" to MpvNode.ArrayValue(
                    listOf(MpvNode.StringValue("a"), MpvNode.Int64Value(2)),
                ),
                "bytes" to MpvNode.BytesValue(byteArrayOf(1, 2, 3, 0xff.toByte())),
            ),
        )
        val encoded = encodeNode(expected)
        val payload = ByteArray(4 + encoded.size)
        encoded.copyInto(payload, destinationOffset = 4)

        assertEquals(expected, decodeNodeResult(payload).value)
    }

    @Test
    fun preservesNodeWrapperForScalarObservedNode() {
        val payload = BinaryWriter().apply {
            int(22)
            long(11)
            int(0)
            string("user-data/test")
            int(6)
            byte(1)
            byte(1)
            string("value")
        }.toByteArray()

        val event = assertIs<MpvEvent.PropertyChange>(
            decodeEvent(payload) { code, context -> error("unexpected error $code at $context") },
        )
        assertEquals(MpvValue.NodeValue(MpvNode.StringValue("value")), event.value)
    }

    @Test
    fun decodesAsyncPropertyReplies() {
        val getPayload = BinaryWriter().apply {
            int(3)
            long(41)
            int(0)
            string("pause")
            int(3)
            byte(1)
            byte(3)
            byte(1)
        }.toByteArray()
        val setPayload = BinaryWriter().apply {
            int(4)
            long(42)
            int(0)
        }.toByteArray()

        val get = assertIs<MpvEvent.GetPropertyReply>(
            decodeEvent(getPayload) { code, context -> error("unexpected error $code at $context") },
        )
        assertEquals(41, get.requestId)
        assertEquals("pause", get.name)
        assertEquals(MpvValue.FlagValue(true), get.value)

        val set = assertIs<MpvEvent.SetPropertyReply>(
            decodeEvent(setPayload) { code, context -> error("unexpected error $code at $context") },
        )
        assertEquals(42, set.requestId)
        assertEquals(null, set.error)
    }

    private class BinaryWriter {
        private val bytes = ArrayList<Byte>()

        fun byte(value: Int) {
            bytes += value.toByte()
        }

        fun int(value: Int) {
            repeat(4) { shift -> byte(value ushr (shift * 8)) }
        }

        fun long(value: Long) {
            repeat(8) { shift -> byte((value ushr (shift * 8)).toInt()) }
        }

        fun double(value: Double) = long(value.toBits())

        fun string(value: String) {
            val encoded = value.encodeToByteArray()
            int(encoded.size)
            raw(encoded)
        }

        fun raw(value: ByteArray) {
            value.forEach { bytes += it }
        }

        fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it] }
    }
}
