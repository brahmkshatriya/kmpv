package dev.kmpv

public class MpvProperty<T> private constructor(
    public val name: String,
    internal val format: MpvFormat,
    internal val decode: (MpvValue) -> T?,
    internal val encode: (T) -> MpvValue,
) {
    public companion object {
        private fun requireName(name: String) {
            require(name.isNotBlank()) { "An mpv property name must not be blank" }
        }

        public fun string(name: String): MpvProperty<String> = mappedString(name, { it }, { it })

        public fun <T> mappedString(
            name: String,
            decode: (String) -> T?,
            encode: (T) -> String,
        ): MpvProperty<T> = MpvProperty(
            name = name,
            format = MpvFormat.String,
            decode = { value -> (value as? MpvValue.StringValue)?.value?.let(decode) },
            encode = { value -> MpvValue.StringValue(encode(value)) },
        ).also { requireName(name) }

        public fun flag(name: String): MpvProperty<Boolean> = MpvProperty(
            name = name,
            format = MpvFormat.Flag,
            decode = { (it as? MpvValue.FlagValue)?.value },
            encode = MpvValue::FlagValue,
        ).also { requireName(name) }

        public fun int64(name: String): MpvProperty<Long> = MpvProperty(
            name = name,
            format = MpvFormat.Int64,
            decode = { (it as? MpvValue.Int64Value)?.value },
            encode = MpvValue::Int64Value,
        ).also { requireName(name) }

        public fun double(name: String): MpvProperty<Double> = MpvProperty(
            name = name,
            format = MpvFormat.Double,
            decode = { (it as? MpvValue.DoubleValue)?.value },
            encode = MpvValue::DoubleValue,
        ).also { requireName(name) }

        public fun node(name: String): MpvProperty<MpvNode> = MpvProperty(
            name = name,
            format = MpvFormat.Node,
            decode = { (it as? MpvValue.NodeValue)?.value },
            encode = MpvValue::NodeValue,
        ).also { requireName(name) }
    }
}

public object MpvProperties {
    public val Pause: MpvProperty<Boolean> = MpvProperty.flag("pause")
    public val TimePos: MpvProperty<Double> = MpvProperty.double("time-pos")
    public val Duration: MpvProperty<Double> = MpvProperty.double("duration")
    public val Volume: MpvProperty<Double> = MpvProperty.double("volume")
    public val Mute: MpvProperty<Boolean> = MpvProperty.flag("mute")
    public val Speed: MpvProperty<Double> = MpvProperty.double("speed")
    public val Path: MpvProperty<String> = MpvProperty.string("path")
    public val MediaTitle: MpvProperty<String> = MpvProperty.string("media-title")
    public val Seekable: MpvProperty<Boolean> = MpvProperty.flag("seekable")
    public val Seeking: MpvProperty<Boolean> = MpvProperty.flag("seeking")
    public val PausedForCache: MpvProperty<Boolean> = MpvProperty.flag("paused-for-cache")
    public val EofReached: MpvProperty<Boolean> = MpvProperty.flag("eof-reached")
    public val IdleActive: MpvProperty<Boolean> = MpvProperty.flag("idle-active")
    public val CoreIdle: MpvProperty<Boolean> = MpvProperty.flag("core-idle")
    public val CacheBufferingState: MpvProperty<Long> = MpvProperty.int64("cache-buffering-state")
    public val MpvVersion: MpvProperty<String> = MpvProperty.string("mpv-version")
    public val VideoTrack: MpvProperty<String> = MpvProperty.string("vid")
    public val AudioTrack: MpvProperty<String> = MpvProperty.string("aid")
    public val SubtitleTrack: MpvProperty<String> = MpvProperty.string("sid")
    public val Width: MpvProperty<Long> = MpvProperty.int64("width")
    public val Height: MpvProperty<Long> = MpvProperty.int64("height")
    public val EstimatedVideoFps: MpvProperty<Double> = MpvProperty.double("estimated-vf-fps")
    public val VideoBitrate: MpvProperty<Double> = MpvProperty.double("video-bitrate")
    public val HwdecCurrent: MpvProperty<String> = MpvProperty.string("hwdec-current")
    public val TrackList: MpvProperty<MpvNode> = MpvProperty.node("track-list")
}
