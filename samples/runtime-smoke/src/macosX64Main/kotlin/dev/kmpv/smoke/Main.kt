package dev.kmpv.smoke

import dev.kmpv.MediaStatus
import dev.kmpv.MpvOptions
import dev.kmpv.MpvPlayer
import dev.kmpv.MpvVideoOutput
import dev.kmpv.getOrThrow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val SmokeUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

fun main() = runBlocking {
    val player = MpvPlayer.create {
        option(MpvOptions.VideoOutput, MpvVideoOutput.Null)
        option(MpvOptions.Ao, "null")
        option(MpvOptions.Terminal, true)
    }.getOrThrow()

    try {
        player.load(SmokeUrl, playWhenReady = true).getOrThrow()
        withTimeout(20_000) {
            player.state.first { state ->
                state.mediaStatus == MediaStatus.Ready ||
                    state.mediaStatus == MediaStatus.Error ||
                    state.mediaStatus == MediaStatus.Ended
            }.also { state ->
                check(state.mediaStatus == MediaStatus.Ready) {
                    "macOS x64 runtime smoke failed: ${state.mediaStatus} ${state.error}"
                }
            }
        }
        println("KMPV_MACOS_X64_RUNTIME_READY")
    } finally {
        player.close()
    }
}
