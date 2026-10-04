package dev.kmpv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MpvLinuxIntegrationTest {
    @Test
    fun initializesAgainstSystemLibMpv() {
        val mpv = Mpv.create()
        try {
            mpv.setOption(MpvOptions.Vo, "null").getOrThrow()
            mpv.setOption(MpvOptions.Ao, "null").getOrThrow()
            mpv.initialize().getOrThrow()

            val version = mpv[MpvProperties.MpvVersion].getOrThrow()
            assertTrue(version.contains("mpv", ignoreCase = true), version)
            assertTrue(mpv.clientId > 0)
            assertTrue(mpv.clientName.isNotBlank())
        } finally {
            mpv.close()
        }
    }

    @Test
    fun typedObservationReceivesCurrentValue() {
        runBlocking {
            val mpv = Mpv.create()
            try {
                mpv.setOption(MpvOptions.Vo, "null").getOrThrow()
                mpv.setOption(MpvOptions.Ao, "null").getOrThrow()
                mpv.initialize().getOrThrow()

                val paused = withTimeout(5.seconds) {
                    mpv.observe(MpvProperties.Pause).first()
                }
                assertNotNull(paused)
            } finally {
                mpv.close()
            }
        }
    }

    @Test
    fun nodePropertyCopiesNestedArraysAndMaps() {
        val mpv = Mpv.create()
        try {
            mpv.setOption(MpvOptions.Vo, "null").getOrThrow()
            mpv.setOption(MpvOptions.Ao, "null").getOrThrow()
            mpv.initialize().getOrThrow()

            val commands = mpv.getNodeProperty("command-list").getOrThrow()
            assertTrue(commands is MpvNode.ArrayValue)
            assertTrue(commands.values.isNotEmpty())

            val first = commands.values.first()
            assertTrue(first is MpvNode.MapValue)
            assertTrue(first.values["name"] is MpvNode.StringValue)
            assertTrue(first.values["args"] is MpvNode.ArrayValue)
            assertTrue(first.values["vararg"] is MpvNode.FlagValue)
        } finally {
            mpv.close()
        }
    }

    @Test
    fun commandResultsWorkSynchronouslyAndAsynchronously() = runBlocking {
        val mpv = Mpv.create()
        try {
            mpv.setOption(MpvOptions.Vo, "null").getOrThrow()
            mpv.setOption(MpvOptions.Ao, "null").getOrThrow()
            mpv.initialize().getOrThrow()

            val expectedVersion = mpv[MpvProperties.MpvVersion].getOrThrow()

            assertEquals(
                MpvNode.StringValue(expectedVersion),
                mpv.commandResult("expand-text", "${'$'}{mpv-version}").getOrThrow(),
            )
            assertEquals(
                MpvNode.StringValue(expectedVersion),
                withTimeout(5.seconds) {
                    mpv.commandResultAsync("expand-text", "${'$'}{mpv-version}").getOrThrow()
                },
            )
        } finally {
            mpv.close()
        }
    }

    @Test
    fun slowPublicEventConsumerDoesNotDelayAsyncReplies() = runBlocking {
        val mpv = Mpv.create()
        try {
            mpv.setOption(MpvOptions.Vo, "null").getOrThrow()
            mpv.setOption(MpvOptions.Ao, "null").getOrThrow()
            mpv.initialize().getOrThrow()

            val subscribed = CompletableDeferred<Unit>()
            val slowCollector = launch {
                mpv.events
                    .onStart { subscribed.complete(Unit) }
                    .collect { delay(2.seconds) }
            }
            subscribed.await()

            mpv.commandResultAsync("expand-text", "first").getOrThrow()
            assertEquals(
                MpvNode.StringValue("second"),
                withTimeout(750.milliseconds) {
                    mpv.commandResultAsync("expand-text", "second").getOrThrow()
                },
            )
            slowCollector.cancel()
        } finally {
            mpv.close()
        }
    }

    @Test
    fun asyncPropertiesUseLibMpvRequestRepliesForScalarsAndNodes() = runBlocking {
        val mpv = Mpv.create()
        try {
            mpv.setOption(MpvOptions.Vo, "null").getOrThrow()
            mpv.setOption(MpvOptions.Ao, "null").getOrThrow()
            mpv.initialize().getOrThrow()

            assertTrue(mpv.getAsync(MpvProperties.MpvVersion).getOrThrow().isNotBlank())

            mpv.setAsync(MpvProperties.Pause, true).getOrThrow()
            assertTrue(mpv.getAsync(MpvProperties.Pause).getOrThrow())

            val node = MpvNode.MapValue(
                mapOf(
                    "name" to MpvNode.StringValue("async"),
                    "values" to MpvNode.ArrayValue(
                        listOf(MpvNode.Int64Value(1), MpvNode.FlagValue(true)),
                    ),
                ),
            )
            mpv.setNodePropertyAsync("user-data/kmpv-async", node).getOrThrow()
            assertEquals(node, mpv.getNodePropertyAsync("user-data/kmpv-async").getOrThrow())
        } finally {
            mpv.close()
        }
    }

    @Test
    fun nodePropertiesRoundTripAndObserveNestedValues() = runBlocking {
        val mpv = Mpv.create()
        try {
            mpv.setOption(MpvOptions.Vo, "null").getOrThrow()
            mpv.setOption(MpvOptions.Ao, "null").getOrThrow()
            mpv.initialize().getOrThrow()

            val property = MpvProperty.node("user-data/kmpv-test")
            val expected = MpvNode.MapValue(
                linkedMapOf(
                    "title" to MpvNode.StringValue("demo"),
                    "enabled" to MpvNode.FlagValue(true),
                    "values" to MpvNode.ArrayValue(
                        listOf(MpvNode.Int64Value(1), MpvNode.DoubleValue(2.5)),
                    ),
                    "bytes" to MpvNode.BytesValue(byteArrayOf(0, 1, 2, 0xff.toByte())),
                ),
            )

            mpv.setProperty(property, expected).getOrThrow()
            assertEquals(expected, mpv[property].getOrThrow())
            assertEquals(expected, mpv.getNodeProperty(property.name).getOrThrow())

            val observed = withTimeout(5.seconds) { mpv.observe(property).first() }
            assertEquals(expected, observed)
        } finally {
            mpv.close()
        }
    }

    @Test
    @OptIn(KmpvNativeApi::class)
    fun nativeResourceLeasePreventsPrematureClose() {
        val mpv = Mpv.create()
        var lease: MpvNativeHandleLease? = null
        try {
            mpv.setOption(MpvOptions.Vo, "null").getOrThrow()
            mpv.setOption(MpvOptions.Ao, "null").getOrThrow()
            mpv.initialize().getOrThrow()

            lease = mpv.acquireNativeHandleLease()
            assertFailsWith<IllegalStateException> { mpv.close() }
            assertFalse(mpv.isClosed)
            assertTrue(mpv[MpvProperties.MpvVersion].getOrThrow().isNotBlank())

            lease.close()
            lease = null
            mpv.close()
            assertTrue(mpv.isClosed)
        } finally {
            lease?.close()
            if (!mpv.isClosed) mpv.close()
        }
    }
}
