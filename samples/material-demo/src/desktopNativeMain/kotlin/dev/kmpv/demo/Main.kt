package dev.kmpv.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.kmpv.MediaStatus
import dev.kmpv.MpvOptions
import dev.kmpv.MpvPlayer
import dev.kmpv.MpvResult
import dev.kmpv.MpvTrack
import dev.kmpv.MpvTrackSelection
import dev.kmpv.MpvTrackType
import dev.kmpv.MpvVideoOutput
import dev.kmpv.getOrThrow
import dev.kmpv.compose.MpvVideoSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val DemoUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

private val Accent = Color(0xFF00BBFF)
private val PillBackground = Color(0xFF050708).copy(alpha = 0.66f)
private val TrackBackground = Color(0xFF282828)
private val SecondaryText = Color(0xFFB1B1B1)

private data class VideoInfo(
    val width: Long? = null,
    val height: Long? = null,
    val fps: Double? = null,
    val bitrate: Double? = null,
    val hwdec: String? = null,
)

private class DemoController : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var closed = false

    val player = MpvPlayer.create {
        option(MpvOptions.VideoOutput, MpvVideoOutput.LibMpv)
        option(MpvOptions.HardwareDecoding, platformHwdecOption())
        option(MpvOptions.KeepOpen, true)
        option(MpvOptions.AudioClientName, "kmpv Material demo")
        option(MpvOptions.Terminal, false)
    }.getOrThrow()

    private val mutableRequestedBitrate = MutableStateFlow<Long?>(null)
    val requestedBitrate: StateFlow<Long?> = mutableRequestedBitrate.asStateFlow()

    init {
        scope.launch {
            var previousSummary: String? = null
            player.state.collect { state ->
                val variants = state.videoTracks.joinToString { track ->
                    val size = if (track.width != null && track.height != null) {
                        "${track.width}x${track.height}"
                    } else {
                        "unknown"
                    }
                    "id=${track.id}:$size${if (track.selected) "*" else ""}"
                }
                if (variants.isNotEmpty() && variants != previousSummary) {
                    previousSummary = variants
                    println("kmpv demo HLS variants: $variants")
                }
            }
        }
    }

    suspend fun start(): MpvResult<Unit> = player.load(DemoUrl, playWhenReady = true)

    fun selectQuality(videoTrack: MpvTrack?) {
        if (videoTrack == null) {
            player.selectTrack(MpvTrackType.Video, MpvTrackSelection.Auto)
            player.selectTrack(MpvTrackType.Audio, MpvTrackSelection.Auto)
            mutableRequestedBitrate.value = null
        } else {
            // Keep this as an in-place mpv track switch. Platform defaults use
            // copy-back hardware decoding so resolution changes do not reuse
            // fragile zero-copy decoder surfaces.
            player.selectTrack(videoTrack)
            if (player.state.value.audioTracks.any { it.id == videoTrack.id }) {
                player.selectTrack(MpvTrackType.Audio, MpvTrackSelection.Id(videoTrack.id))
            }
            mutableRequestedBitrate.value = videoTrack.hlsBitrate
        }
        scope.launch {
            delay(350)
            val state = player.state.value
            println(
                "kmpv demo quality switch: requested=${videoTrack?.height?.let { "${it}p" } ?: "auto"} " +
                    "active=${state.videoWidth}x${state.videoHeight} " +
                    "hwdec=${state.hardwareDecoder ?: "reinitializing"}",
            )
        }
    }

    fun seekToFraction(fraction: Float) {
        val duration = player.state.value.duration ?: return
        player.seekTo(duration * fraction.coerceIn(0f, 1f).toDouble())
    }

    fun skip(seconds: Int) {
        player.skip(seconds.seconds)
    }

    fun setVolume(value: Double) {
        player.setVolume(value.coerceIn(0.0, 150.0))
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        player.close()
    }
}

fun main() = application {
    val windowState = rememberWindowState(width = 1120.dp, height = 700.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "kmpv · Mux HLS",
        state = windowState,
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Accent,
                secondary = Accent,
                background = Color.Black,
                surface = Color(0xFF050708),
                onBackground = Color.White,
                onSurface = Color.White,
            ),
        ) {
            MaterialOscDemo()
        }
    }
}

@Composable
private fun MaterialOscDemo() {
    val controller = remember { DemoController() }
    val state by controller.player.state.collectAsState()
    val requestedBitrate by controller.requestedBitrate.collectAsState()
    val videoInfo = VideoInfo(
        width = state.videoWidth,
        height = state.videoHeight,
        fps = state.videoFps,
        bitrate = state.videoBitrate,
        hwdec = state.hardwareDecoder,
    )

    var loadError by remember { mutableStateOf<String?>(null) }
    var surfaceReady by remember { mutableStateOf(false) }
    var draggingSeek by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(controller, surfaceReady) {
        if (!surfaceReady) return@LaunchedEffect
        when (val result = controller.start()) {
            is MpvResult.Success -> Unit
            is MpvResult.Failure -> loadError = result.error.message
        }
    }

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    val durationMillis = state.duration?.inWholeMilliseconds ?: 0L
    val positionFraction = if (durationMillis > 0) {
        (state.position.inWholeMilliseconds.toDouble() / durationMillis).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val displayedSeek = if (draggingSeek) seekFraction else positionFraction
    val seekSliderState = rememberSliderState(value = displayedSeek)
    seekSliderState.value = displayedSeek

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        MpvVideoSurface(
            player = controller.player,
            modifier = Modifier.fillMaxSize(),
            onReady = { surfaceReady = true },
            onError = { loadError = it.message },
        )

        StreamBadge(
            info = videoInfo,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )

        if (state.isBuffering || state.mediaStatus == MediaStatus.Opening) {
            CircularProgressIndicator(
                color = Accent,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(42.dp),
            )
        }

        loadError?.let { error ->
            OscPill(
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(error, color = Color(0xFFFFB4AB), modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Slider(
                state = seekSliderState,
                onValueChange = {
                    draggingSeek = true
                    seekFraction = it
                },
                onValueChangeFinished = {
                    controller.seekToFraction(seekFraction)
                    draggingSeek = false
                },
                enabled = durationMillis > 0,
                modifier = Modifier.fillMaxWidth().height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = TrackBackground,
                    disabledThumbColor = SecondaryText,
                    disabledActiveTrackColor = TrackBackground,
                    disabledInactiveTrackColor = TrackBackground,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TransportPill(
                    playing = state.playWhenReady,
                    onBack = { controller.skip(-5) },
                    onPlayPause = { controller.player.togglePlayPause() },
                    onForward = { controller.skip(5) },
                )

                TimePill(
                    position = if (draggingSeek && state.duration != null) state.duration!! * seekFraction.toDouble() else state.position,
                    duration = state.duration,
                )

                Spacer(Modifier.weight(1f))

                VolumePill(
                    volume = state.volume,
                    onVolume = controller::setVolume,
                )

                QualityPill(
                    tracks = state.tracks,
                    requestedBitrate = requestedBitrate,
                    videoInfo = videoInfo,
                    onSelect = controller::selectQuality,
                )
            }
        }
    }
}

@Composable
private fun StreamBadge(info: VideoInfo, modifier: Modifier = Modifier) {
    val resolution = if (info.width != null && info.height != null) "${info.width}×${info.height}" else "HLS"
    val fps = info.fps?.takeIf { it > 0 }?.let { " · ${it.roundToInt()} fps" }.orEmpty()
    val bitrate = info.bitrate?.takeIf { it > 0 }?.let { " · ${(it / 1000.0).roundToInt()} kbps" }.orEmpty()
    val hwdec = info.hwdec?.takeIf { it.isNotBlank() && it != "no" }?.let { " · $it" }.orEmpty()
    OscPill(modifier) {
        Text(
            "Mux HLS · $resolution$fps$bitrate$hwdec",
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun TransportPill(
    playing: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
) {
    OscPill {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OscTextButton("−5", onBack)
            OscTextButton(if (playing) "Ⅱ" else "▶", onPlayPause, strong = true)
            OscTextButton("+5", onForward)
        }
    }
}

@Composable
private fun TimePill(position: Duration, duration: Duration?) {
    OscPill {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatTime(position), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(" / ", color = SecondaryText, fontSize = 13.sp)
            Text(formatTime(duration), color = SecondaryText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun VolumePill(volume: Double, onVolume: (Double) -> Unit) {
    val sliderValue = volume.toFloat().coerceIn(0f, 150f)
    val sliderState = rememberSliderState(value = sliderValue, trackRange = 0f..150f)
    sliderState.value = sliderValue

    OscPill {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (volume <= 0.5) "Mute" else "Vol", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Slider(
                state = sliderState,
                onValueChange = { onVolume(it.toDouble()) },
                modifier = Modifier.width(130.dp).height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = if (volume > 100.0) Color(0xFFFF9800) else Color.White,
                    activeTrackColor = if (volume > 100.0) Color(0xFFFF9800) else Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                ),
            )
        }
    }
}

@Composable
private fun QualityPill(
    tracks: List<MpvTrack>,
    requestedBitrate: Long?,
    videoInfo: VideoInfo,
    onSelect: (MpvTrack?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val videoTracks = tracks
        .filter { it.type == MpvTrackType.Video }
        .sortedWith(compareByDescending<MpvTrack> { it.height ?: -1 }.thenByDescending { it.width ?: -1 })
    val activeTrack = videoTracks.firstOrNull { it.selected }
    val currentHeight = videoInfo.height ?: activeTrack?.height
    val label = if (requestedBitrate == null) {
        if (currentHeight != null) "Best · ${currentHeight}p" else "Best"
    } else {
        activeTrack?.height?.let { "${it}p" } ?: "Quality"
    }

    Box {
        OscPill(
            modifier = Modifier.clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Text("⌄", color = SecondaryText, fontSize = 13.sp)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF111315), RoundedCornerShape(18.dp)),
        ) {
            DropdownMenuItem(
                text = {
                    QualityMenuText(
                        title = "Best",
                        detail = "reload using mpv hls-bitrate=max",
                        selected = requestedBitrate == null,
                    )
                },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            videoTracks.forEach { track ->
                val resolution = when {
                    track.width != null && track.height != null -> "${track.width}×${track.height}"
                    track.height != null -> "${track.height}p"
                    else -> "Track ${track.id}"
                }
                val fps = track.fps?.let { " · ${it.roundToInt()} fps" }.orEmpty()
                DropdownMenuItem(
                    text = {
                        QualityMenuText(
                            title = track.height?.let { "${it}p" } ?: "Video ${track.id}",
                            detail = buildString {
                                append(resolution)
                                append(fps)
                                track.hlsBitrate?.let { append(" · ${it / 1000} kbps") }
                            },
                            selected = requestedBitrate != null && requestedBitrate == track.hlsBitrate,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(track)
                    },
                )
            }
        }
    }
}

@Composable
private fun QualityMenuText(title: String, detail: String, selected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (selected) "●" else "○",
            color = if (selected) Accent else SecondaryText,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(9.dp))
        Column {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                detail,
                color = SecondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OscPill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = PillBackground,
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
private fun OscTextButton(
    text: String,
    onClick: () -> Unit,
    strong: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = if (strong) 18.sp else 13.sp,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

private fun formatTime(duration: Duration?): String {
    if (duration == null || duration.isInfinite()) return "--:--"
    val totalSeconds = duration.inWholeSeconds.coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes}:${seconds.toString().padStart(2, '0')}"
    }
}
