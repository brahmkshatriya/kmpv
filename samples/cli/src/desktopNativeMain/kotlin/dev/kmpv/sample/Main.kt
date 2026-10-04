package dev.kmpv.sample

import dev.kmpv.MediaStatus
import dev.kmpv.MpvOptions
import dev.kmpv.MpvPlayer
import dev.kmpv.MpvVideoOutput
import dev.kmpv.getOrThrow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val uri = args.firstOrNull()
    if (uri == null) {
        println("Usage: kmpv-cli <media-file-or-url>")
        return@runBlocking
    }

    val player = MpvPlayer.create {
        option(MpvOptions.VideoOutput, MpvVideoOutput.Null)
        option(MpvOptions.Ao, "null")
    }.getOrThrow()

    try {
        player.load(uri, playWhenReady = true).getOrThrow()

        player.state
            .onEach { state ->
                println(
                    "${state.mediaStatus} " +
                        "${state.position.inWholeMilliseconds}ms / " +
                        "${state.duration?.inWholeMilliseconds ?: -1}ms " +
                        "playing=${state.isPlaying}",
                )
            }
            .map { it.mediaStatus }
            .first { it == MediaStatus.Ended || it == MediaStatus.Error || it == MediaStatus.Released }
    } finally {
        player.close()
    }
}
