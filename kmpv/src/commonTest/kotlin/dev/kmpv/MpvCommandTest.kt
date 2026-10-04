package dev.kmpv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MpvCommandTest {
    @Test
    fun loadFileIsTypedButStillMpvShaped() {
        assertEquals(
            listOf("loadfile", "movie.mkv", "replace"),
            MpvCommands.loadFile("movie.mkv").args,
        )
    }

    @Test
    fun seekUsesSecondsAndExplicitMode() {
        assertEquals(
            listOf("seek", "12.5", "absolute", "exact"),
            MpvCommands.seek(12.5.seconds).args,
        )
    }

    @Test
    fun customPropertiesRemainPossible() {
        assertEquals("my-script/property", MpvProperty.string("my-script/property").name)
    }

    @Test
    fun typedOptionDslKeepsRawEscapeHatch() {
        val config = mpvPlayerConfig {
            option(MpvOptions.VideoOutput, MpvVideoOutput.LibMpv)
            option(MpvOptions.HardwareDecoding, MpvHardwareDecoding.VaapiCopy)
            option(MpvOptions.KeepOpen, true)
            option("demuxer-max-bytes", "64MiB")
        }

        assertEquals("libmpv", config.options["vo"])
        assertEquals("vaapi-copy", config.options["hwdec"])
        assertEquals("yes", config.options["keep-open"])
        assertEquals("64MiB", config.options["demuxer-max-bytes"])
    }

    @Test
    fun resultHelpersMatchKotlinExpectations() {
        val success: MpvResult<Int> = MpvResult.Success(3)
        val failure: MpvResult<Int> = MpvResult.Failure(MpvError(-1, "failed"))

        assertTrue(success.isSuccess)
        assertFalse(success.isFailure)
        assertEquals(6, success.map { it * 2 }.getOrThrow())
        assertEquals(7, failure.recover { 7 }.getOrThrow())
        assertTrue(failure.toKotlinResult().isFailure)
    }
}
