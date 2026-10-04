package dev.kmpv.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kmpv.Mpv
import dev.kmpv.MpvError
import dev.kmpv.MpvPlayer

/**
 * Displays video from an already initialized [Mpv] instance.
 *
 * The caller owns [mpv] and must configure platform video output before initialization.
 * This composable owns only the platform rendering/surface attachment resource.
 * [onReady] is invoked once that resource is attached and it is safe to start
 * loading video for outputs such as `vo=libmpv` that require a render context
 * before video initialization.
 */
@Composable
public fun MpvVideoSurface(
    mpv: Mpv,
    modifier: Modifier = Modifier,
    onError: (MpvError) -> Unit = {},
) {
    MpvVideoSurface(
        mpv = mpv,
        modifier = modifier,
        onReady = {},
        onError = onError,
    )
}

/**
 * Displays video and invokes [onReady] once the platform output resource is
 * attached and media loading can safely begin.
 */
@Composable
public fun MpvVideoSurface(
    mpv: Mpv,
    modifier: Modifier = Modifier,
    onReady: () -> Unit,
    onError: (MpvError) -> Unit = {},
) {
    check(mpv.isInitialized) { "initialize mpv before composing MpvVideoSurface" }
    PlatformMpvVideoSurface(mpv, modifier, onError, onReady)
}

/** Displays video for the low-level client owned by [player]. */
@Composable
public fun MpvVideoSurface(
    player: MpvPlayer,
    modifier: Modifier = Modifier,
    onError: (MpvError) -> Unit = {},
) {
    MpvVideoSurface(player.raw, modifier, onError)
}

/** Player overload with an explicit render/surface readiness callback. */
@Composable
public fun MpvVideoSurface(
    player: MpvPlayer,
    modifier: Modifier = Modifier,
    onReady: () -> Unit,
    onError: (MpvError) -> Unit = {},
) {
    MpvVideoSurface(
        mpv = player.raw,
        modifier = modifier,
        onReady = onReady,
        onError = onError,
    )
}

@Composable
internal expect fun PlatformMpvVideoSurface(
    mpv: Mpv,
    modifier: Modifier,
    onError: (MpvError) -> Unit,
    onReady: () -> Unit,
)
