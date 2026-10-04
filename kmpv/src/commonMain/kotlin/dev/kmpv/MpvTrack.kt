package dev.kmpv

public enum class MpvTrackType {
    Video,
    Audio,
    Subtitle,
    Unknown,
}

public data class MpvTrack(
    val index: Int,
    val id: Long,
    val type: MpvTrackType,
    val selected: Boolean,
    val title: String? = null,
    val language: String? = null,
    val codec: String? = null,
    val width: Long? = null,
    val height: Long? = null,
    val fps: Double? = null,
    val hlsBitrate: Long? = null,
)

/** Type-safe representation of mpv's `vid` / `aid` / `sid` selection values. */
public sealed interface MpvTrackSelection {
    public data object Auto : MpvTrackSelection
    public data object Disabled : MpvTrackSelection
    public data class Id(val id: Long) : MpvTrackSelection {
        init {
            require(id > 0) { "mpv track ids must be positive" }
        }
    }
}

internal fun MpvTrackSelection.mpvValue(): String = when (this) {
    MpvTrackSelection.Auto -> "auto"
    MpvTrackSelection.Disabled -> "no"
    is MpvTrackSelection.Id -> id.toString()
}

public fun Mpv.trackList(): MpvResult<List<MpvTrack>> {
    return this[MpvProperties.TrackList].map(MpvNode::toTrackList)
}

internal fun MpvNode.toTrackList(): List<MpvTrack> {
    val list = this as? MpvNode.ArrayValue ?: return emptyList()
    return buildList {
        list.values.forEachIndexed { index, node ->
            val values = (node as? MpvNode.MapValue)?.values ?: return@forEachIndexed
            val id = (values["id"] as? MpvNode.Int64Value)?.value ?: return@forEachIndexed
            val type = when ((values["type"] as? MpvNode.StringValue)?.value) {
                "video" -> MpvTrackType.Video
                "audio" -> MpvTrackType.Audio
                "sub" -> MpvTrackType.Subtitle
                else -> MpvTrackType.Unknown
            }
            add(
                MpvTrack(
                    index = index,
                    id = id,
                    type = type,
                    selected = (values["selected"] as? MpvNode.FlagValue)?.value == true,
                    title = (values["title"] as? MpvNode.StringValue)?.value,
                    language = (values["lang"] as? MpvNode.StringValue)?.value,
                    codec = (values["codec"] as? MpvNode.StringValue)?.value,
                    width = (values["demux-w"] as? MpvNode.Int64Value)?.value,
                    height = (values["demux-h"] as? MpvNode.Int64Value)?.value,
                    fps = when (val value = values["demux-fps"]) {
                        is MpvNode.DoubleValue -> value.value
                        is MpvNode.Int64Value -> value.value.toDouble()
                        else -> null
                    },
                    hlsBitrate = (values["hls-bitrate"] as? MpvNode.Int64Value)?.value,
                ),
            )
        }
    }
}
