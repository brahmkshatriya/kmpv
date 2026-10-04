package dev.kmpv

import kotlin.time.Duration

public class MpvCommand internal constructor(public val args: List<String>) {
    init {
        require(args.isNotEmpty()) { "An mpv command must have at least one argument" }
    }

    override fun toString(): String = args.joinToString(" ")
}

public enum class LoadMode(public val mpvValue: String) {
    Replace("replace"),
    Append("append"),
    AppendPlay("append-play"),
}

public enum class SeekMode(public val mpvValue: String) {
    Relative("relative"),
    Absolute("absolute"),
    AbsolutePercent("absolute-percent"),
    RelativePercent("relative-percent"),
}

public object MpvCommands {
    public fun raw(vararg args: String): MpvCommand = MpvCommand(args.toList())

    public fun loadFile(
        uri: String,
        mode: LoadMode = LoadMode.Replace,
        options: Map<String, String> = emptyMap(),
    ): MpvCommand = MpvCommand(
        buildList {
            add("loadfile")
            add(uri)
            add(mode.mpvValue)
            if (options.isNotEmpty()) {
                require(options.keys.none { ',' in it || '=' in it }) {
                    "mpv per-file option names must not contain ',' or '='"
                }
                require(options.values.none { ',' in it }) {
                    "mpv per-file option values containing ',' are not supported by this helper"
                }
                add("-1")
                add(options.entries.joinToString(",") { (name, value) -> "$name=$value" })
            }
        },
    )

    public fun seek(
        position: Duration,
        mode: SeekMode = SeekMode.Absolute,
        exact: Boolean = true,
    ): MpvCommand = MpvCommand(
        buildList {
            add("seek")
            add((position.inWholeMilliseconds / 1000.0).toString())
            add(mode.mpvValue)
            if (exact) add("exact")
        },
    )

    public fun stop(): MpvCommand = MpvCommand(listOf("stop"))
    public fun playlistNext(): MpvCommand = MpvCommand(listOf("playlist-next", "force"))
    public fun playlistPrevious(): MpvCommand = MpvCommand(listOf("playlist-prev", "force"))
}
