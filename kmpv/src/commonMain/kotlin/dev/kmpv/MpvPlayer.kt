package dev.kmpv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public enum class MediaStatus {
    Idle,
    Opening,
    Ready,
    Ended,
    Error,
    Released,
}

public data class MpvPlayerState(
    val mediaStatus: MediaStatus = MediaStatus.Idle,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val isSeeking: Boolean = false,
    val position: Duration = Duration.ZERO,
    val duration: Duration? = null,
    val volume: Double = 100.0,
    val muted: Boolean = false,
    val speed: Double = 1.0,
    val seekable: Boolean = false,
    val path: String? = null,
    val title: String? = null,
    val tracks: List<MpvTrack> = emptyList(),
    val videoWidth: Long? = null,
    val videoHeight: Long? = null,
    val videoFps: Double? = null,
    val videoBitrate: Double? = null,
    val hardwareDecoder: String? = null,
    val error: MpvError? = null,
) {
    public val isPlaying: Boolean
        get() = mediaStatus == MediaStatus.Ready && playWhenReady && !isBuffering && !isSeeking

    public val videoTracks: List<MpvTrack> get() = tracks.filter { it.type == MpvTrackType.Video }
    public val audioTracks: List<MpvTrack> get() = tracks.filter { it.type == MpvTrackType.Audio }
    public val subtitleTracks: List<MpvTrack> get() = tracks.filter { it.type == MpvTrackType.Subtitle }

    public fun selectedTrack(type: MpvTrackType): MpvTrack? =
        tracks.firstOrNull { it.type == type && it.selected }
}

public sealed interface MpvPlayerEvent {
    public data object MediaLoaded : MpvPlayerEvent
    public data class MediaEnded(val reason: MpvEndFileReason) : MpvPlayerEvent
    public data class PlaybackError(val error: MpvError) : MpvPlayerEvent
}

public data class MpvPlayerConfig(
    val options: Map<String, String> = emptyMap(),
    val configFile: String? = null,
)

/** Type-safe builder for [MpvPlayerConfig]. */
@MpvConfigDsl
public class MpvPlayerConfigBuilder internal constructor() {
    private val options: LinkedHashMap<String, String> = linkedMapOf()

    /** Optional mpv config file loaded before initialization. */
    public var configFile: String? = null

    public fun <T> option(option: MpvOption<T>, value: T) {
        options[option.name] = option.encode(value)
    }

    /** Raw escape hatch for options not yet represented by [MpvOption]. */
    public fun option(name: String, value: String) {
        require(name.isNotBlank()) { "An mpv option name must not be blank" }
        options[name] = value
    }

    public fun configFile(path: String) {
        require(path.isNotBlank()) { "An mpv config file path must not be blank" }
        configFile = path
    }

    internal fun build(): MpvPlayerConfig = MpvPlayerConfig(
        options = options.toMap(),
        configFile = configFile,
    )
}

public fun mpvPlayerConfig(block: MpvPlayerConfigBuilder.() -> Unit): MpvPlayerConfig =
    MpvPlayerConfigBuilder().apply(block).build()

public class MpvPlayer private constructor(public val raw: Mpv) : AutoCloseable {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState: MutableStateFlow<MpvPlayerState> = MutableStateFlow(MpvPlayerState())
    private val mutableEvents: MutableSharedFlow<MpvPlayerEvent> = MutableSharedFlow(extraBufferCapacity = 16)

    public val state: StateFlow<MpvPlayerState> = mutableState.asStateFlow()
    /** Best-effort one-shot notifications; authoritative playback state is [state]. */
    public val events: SharedFlow<MpvPlayerEvent> = mutableEvents.asSharedFlow()

    init {
        observeState()
        observeEvents()
    }

    public suspend fun load(
        uri: String,
        playWhenReady: Boolean = false,
        options: Map<String, String> = emptyMap(),
    ): MpvResult<Unit> {
        when (val pauseResult = raw.setProperty(MpvProperties.Pause, !playWhenReady)) {
            is MpvResult.Failure -> return pauseResult
            is MpvResult.Success -> Unit
        }
        mutableState.update {
            it.copy(mediaStatus = MediaStatus.Opening, playWhenReady = playWhenReady, error = null)
        }
        return raw.commandAsync(MpvCommands.loadFile(uri, options = options))
    }

    /** Loads media with type-safe per-file options and a raw option escape hatch. */
    public suspend fun load(
        uri: String,
        playWhenReady: Boolean = false,
        options: MpvOptionsBuilder.() -> Unit,
    ): MpvResult<Unit> = load(uri, playWhenReady, mpvOptions(options))

    public fun play(): MpvResult<Unit> {
        val result = raw.setProperty(MpvProperties.Pause, false)
        if (result is MpvResult.Success) mutableState.update { it.copy(playWhenReady = true) }
        return result
    }

    public fun pause(): MpvResult<Unit> {
        val result = raw.setProperty(MpvProperties.Pause, true)
        if (result is MpvResult.Success) mutableState.update { it.copy(playWhenReady = false) }
        return result
    }

    public fun togglePlayPause(): MpvResult<Unit> =
        if (state.value.playWhenReady) pause() else play()

    public fun stopPlayback(): MpvResult<Unit> = raw.command(MpvCommands.stop())
    public fun stop(): MpvResult<Unit> = stopPlayback()
    public fun seekTo(position: Duration): MpvResult<Unit> = raw.command(MpvCommands.seek(position))
    public fun skip(delta: Duration): MpvResult<Unit> = seekTo((state.value.position + delta).coerceAtLeast(Duration.ZERO))

    public fun setVolume(volume: Double): MpvResult<Unit> {
        require(volume.isFinite() && volume >= 0.0) { "volume must be a finite non-negative value" }
        return raw.setProperty(MpvProperties.Volume, volume)
    }

    public fun setMuted(muted: Boolean): MpvResult<Unit> = raw.setProperty(MpvProperties.Mute, muted)

    public fun toggleMute(): MpvResult<Unit> = setMuted(!state.value.muted)

    public fun setSpeed(speed: Double): MpvResult<Unit> {
        require(speed.isFinite() && speed > 0.0) { "speed must be a finite positive value" }
        return raw.setProperty(MpvProperties.Speed, speed)
    }

    public fun tracks(): MpvResult<List<MpvTrack>> = raw.trackList()

    public fun selectTrack(
        type: MpvTrackType,
        selection: MpvTrackSelection,
    ): MpvResult<Unit> {
        val property = when (type) {
            MpvTrackType.Video -> MpvProperties.VideoTrack
            MpvTrackType.Audio -> MpvProperties.AudioTrack
            MpvTrackType.Subtitle -> MpvProperties.SubtitleTrack
            MpvTrackType.Unknown -> {
                return MpvResult.Failure(MpvError(-1, "cannot select an unknown track type", "track-selection"))
            }
        }
        return raw.setProperty(property, selection.mpvValue())
    }

    public fun selectTrack(track: MpvTrack): MpvResult<Unit> =
        selectTrack(track.type, MpvTrackSelection.Id(track.id))

    override fun close() {
        if (mutableState.value.mediaStatus == MediaStatus.Released) return
        raw.close()
        scope.cancel()
        mutableState.update { it.copy(mediaStatus = MediaStatus.Released) }
    }

    private fun observeState() {
        observe(MpvProperties.Pause) { value ->
            value?.let { paused -> mutableState.update { it.copy(playWhenReady = !paused) } }
        }
        observe(MpvProperties.PausedForCache) { value ->
            mutableState.update { it.copy(isBuffering = value == true) }
        }
        observe(MpvProperties.Seeking) { value ->
            mutableState.update { it.copy(isSeeking = value == true) }
        }
        observe(MpvProperties.TimePos) { value ->
            mutableState.update { it.copy(position = (value ?: 0.0).seconds) }
        }
        observe(MpvProperties.Duration) { value ->
            mutableState.update { it.copy(duration = value?.seconds) }
        }
        observe(MpvProperties.Volume) { value ->
            value?.let { volume -> mutableState.update { it.copy(volume = volume) } }
        }
        observe(MpvProperties.Mute) { value ->
            value?.let { muted -> mutableState.update { it.copy(muted = muted) } }
        }
        observe(MpvProperties.Speed) { value ->
            mutableState.update { it.copy(speed = value ?: 1.0) }
        }
        observe(MpvProperties.Seekable) { value ->
            mutableState.update { it.copy(seekable = value == true) }
        }
        observe(MpvProperties.Path) { value ->
            mutableState.update { it.copy(path = value) }
        }
        observe(MpvProperties.MediaTitle) { value ->
            mutableState.update { it.copy(title = value) }
        }
        observe(MpvProperties.TrackList) { value ->
            mutableState.update { it.copy(tracks = value?.toTrackList().orEmpty()) }
        }
        observe(MpvProperties.Width) { value ->
            mutableState.update { it.copy(videoWidth = value) }
        }
        observe(MpvProperties.Height) { value ->
            mutableState.update { it.copy(videoHeight = value) }
        }
        observe(MpvProperties.EstimatedVideoFps) { value ->
            mutableState.update { it.copy(videoFps = value) }
        }
        observe(MpvProperties.VideoBitrate) { value ->
            mutableState.update { it.copy(videoBitrate = value) }
        }
        observe(MpvProperties.HwdecCurrent) { value ->
            mutableState.update { it.copy(hardwareDecoder = value) }
        }
    }

    private fun observeEvents() {
        scope.launch {
            raw.internalEvents.collect { event ->
                when (event) {
                    MpvEvent.StartFile -> mutableState.update {
                        it.copy(mediaStatus = MediaStatus.Opening, error = null)
                    }
                    MpvEvent.FileLoaded -> {
                        mutableState.update { it.copy(mediaStatus = MediaStatus.Ready, error = null) }
                        mutableEvents.tryEmit(MpvPlayerEvent.MediaLoaded)
                    }
                    is MpvEvent.EndFile -> {
                        if (event.error != null) {
                            mutableState.update { it.copy(mediaStatus = MediaStatus.Error, error = event.error) }
                            mutableEvents.tryEmit(MpvPlayerEvent.PlaybackError(event.error))
                        } else {
                            val status = if (event.reason == MpvEndFileReason.Eof) MediaStatus.Ended else MediaStatus.Idle
                            mutableState.update { it.copy(mediaStatus = status) }
                            mutableEvents.tryEmit(MpvPlayerEvent.MediaEnded(event.reason))
                        }
                    }
                    MpvEvent.Shutdown -> mutableState.update { it.copy(mediaStatus = MediaStatus.Released) }
                    else -> Unit
                }
            }
        }
    }

    private fun <T> observe(property: MpvProperty<T>, update: (T?) -> Unit) {
        scope.launch {
            raw.observe(property).collect(update)
        }
    }

    public companion object {
        /** Creates and initializes a player from a type-safe configuration block. */
        public fun create(block: MpvPlayerConfigBuilder.() -> Unit): MpvResult<MpvPlayer> =
            create(mpvPlayerConfig(block))

        public fun create(config: MpvPlayerConfig = MpvPlayerConfig()): MpvResult<MpvPlayer> {
            val mpv = Mpv.create()
            config.options.forEach { (name, value) ->
                when (val result = mpv.setOption(name, value)) {
                    is MpvResult.Success -> Unit
                    is MpvResult.Failure -> {
                        mpv.close()
                        return result
                    }
                }
            }
            config.configFile?.let { path ->
                when (val result = mpv.loadConfigFile(path)) {
                    is MpvResult.Success -> Unit
                    is MpvResult.Failure -> {
                        mpv.close()
                        return result
                    }
                }
            }
            return when (val result = mpv.initialize()) {
                is MpvResult.Success -> MpvResult.Success(MpvPlayer(mpv))
                is MpvResult.Failure -> {
                    mpv.close()
                    result
                }
            }
        }
    }
}
