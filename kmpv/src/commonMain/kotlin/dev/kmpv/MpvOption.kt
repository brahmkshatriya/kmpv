package dev.kmpv

public class MpvOption<T> private constructor(
    public val name: String,
    internal val encode: (T) -> String,
) {
    public companion object {
        /** Creates a custom typed mpv option without giving up compile-time value typing. */
        public fun <T> of(name: String, encode: (T) -> String): MpvOption<T> {
            require(name.isNotBlank()) { "An mpv option name must not be blank" }
            return MpvOption(name, encode)
        }

        public fun string(name: String): MpvOption<String> = of(name) { it }
        public fun flag(name: String): MpvOption<Boolean> = of(name) { if (it) "yes" else "no" }
        public fun int(name: String): MpvOption<Int> = of(name, Int::toString)
        public fun double(name: String): MpvOption<Double> = of(name, Double::toString)
    }
}

/** Common libmpv video outputs. [MpvOptions.Vo] remains available for arbitrary values. */
public enum class MpvVideoOutput(public val mpvValue: String) {
    LibMpv("libmpv"),
    Gpu("gpu"),
    MediaCodecEmbed("mediacodec_embed"),
    Null("null"),
}

/** Common hardware-decoding policies. [MpvOptions.Hwdec] remains the raw string escape hatch. */
public enum class MpvHardwareDecoding(public val mpvValue: String) {
    No("no"),
    Auto("auto"),
    AutoSafe("auto-safe"),
    AutoCopySafe("auto-copy-safe"),
    VaapiCopy("vaapi-copy"),
    D3d11vaCopy("d3d11va-copy"),
    VideoToolbox("videotoolbox"),
    VideoToolboxCopy("videotoolbox-copy"),
    MediaCodec("mediacodec"),
    MediaCodecCopy("mediacodec-copy"),
}

public object MpvOptions {
    public val Vo: MpvOption<String> = MpvOption.string("vo")
    public val Ao: MpvOption<String> = MpvOption.string("ao")
    public val Hwdec: MpvOption<String> = MpvOption.string("hwdec")
    public val VideoOutput: MpvOption<MpvVideoOutput> = MpvOption.of("vo", MpvVideoOutput::mpvValue)
    public val HardwareDecoding: MpvOption<MpvHardwareDecoding> =
        MpvOption.of("hwdec", MpvHardwareDecoding::mpvValue)
    public val KeepOpen: MpvOption<Boolean> = MpvOption.flag("keep-open")
    public val Terminal: MpvOption<Boolean> = MpvOption.flag("terminal")
    public val AudioClientName: MpvOption<String> = MpvOption.string("audio-client-name")
}

@DslMarker
public annotation class MpvConfigDsl

/** Typed option collector used by player setup and per-file loading. */
@MpvConfigDsl
public class MpvOptionsBuilder internal constructor() {
    private val mutableOptions: LinkedHashMap<String, String> = linkedMapOf()

    public fun <T> option(option: MpvOption<T>, value: T) {
        mutableOptions[option.name] = option.encode(value)
    }

    /** Raw escape hatch for options not yet present in the typed catalog. */
    public fun option(name: String, value: String) {
        require(name.isNotBlank()) { "An mpv option name must not be blank" }
        mutableOptions[name] = value
    }

    internal fun build(): Map<String, String> = mutableOptions.toMap()
}

/** Builds an encoded option map while keeping known option values type-safe at the call site. */
public fun mpvOptions(block: MpvOptionsBuilder.() -> Unit): Map<String, String> =
    MpvOptionsBuilder().apply(block).build()
