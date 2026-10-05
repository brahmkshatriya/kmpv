# kmpv

[![Maven Central](https://img.shields.io/maven-central/v/dev.brahmkshatriya.kmpv/kmpv.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.brahmkshatriya.kmpv/kmpv)

`kmpv` is a Kotlin Multiplatform wrapper for [libmpv](https://mpv.io/). It provides a typed playback API, observable player state, optional video rendering helpers, and Compose video surfaces.

The library is split into three modules:

- `kmpv` — playback, commands, properties, tracks, events, and player state.
- `kmpv-render` — video output helpers for desktop, iOS, and Android.
- `kmpv-compose` — Compose video surfaces built on top of `kmpv-render`.

## Supported platforms

| Platform | Core player | Rendering | Compose |
| --- | --- | --- | --- |
| Linux x64 | Yes | Yes | Yes |
| Linux arm64 | Yes | Yes | Yes |
| Windows x64 | Yes | Yes | Yes |
| macOS x64 | Yes | Yes | Yes |
| macOS arm64 | Yes | Yes | Yes |
| iOS arm64 | Yes | Yes | Yes |
| Android | Yes | Yes | Yes |

`kmpv` does not bundle libmpv. Your application must provide a compatible libmpv build for the platforms it ships on.

## Add the dependency

Releases are published to Maven Central from version tags.

```kotlin
val kmpvVersion = "<version>"

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brahmkshatriya.kmpv:kmpv:$kmpvVersion")
        }
    }
}
```

For video rendering, add:

```kotlin
implementation("dev.brahmkshatriya.kmpv:kmpv-render:$kmpvVersion")
```

For Compose video surfaces, add:

```kotlin
implementation("dev.brahmkshatriya.kmpv:kmpv-compose:$kmpvVersion")
```

If your Compose setup uses development Compose artifacts, keep the Compose development repository available:

```kotlin
repositories {
    mavenCentral()
    google()
    maven("https://redirector.kotlinlang.org/maven/compose-dev")
}
```

## Basic playback

`MpvPlayer` is the easiest API for normal playback.

```kotlin
import dev.kmpv.MpvHardwareDecoding
import dev.kmpv.MpvOptions
import dev.kmpv.MpvPlayer
import dev.kmpv.getOrThrow

val player = MpvPlayer.create {
    option(MpvOptions.HardwareDecoding, MpvHardwareDecoding.AutoSafe)
}.getOrThrow()

player.load(
    uri = "https://example.com/video.mp4",
    playWhenReady = true,
).getOrThrow()
```

Player state is exposed as a `StateFlow`:

```kotlin
player.state.collect { state ->
    println("position = ${state.position}")
    println("duration = ${state.duration}")
    println("playing = ${state.isPlaying}")
    println("buffering = ${state.isBuffering}")
}
```

Playback controls are straightforward:

```kotlin
player.pause()
player.play()
player.setVolume(80.0)
player.setMuted(true)
player.seekTo(30.seconds)
```

Call `player.close()` when the player is no longer needed.

## Compose video

Add `kmpv-compose`, create a player, and place `MpvVideoSurface` in your UI.

```kotlin
var surfaceReady by remember { mutableStateOf(false) }

MpvVideoSurface(
    player = player,
    modifier = Modifier.fillMaxSize(),
    onReady = { surfaceReady = true },
    onError = { error -> println(error.message) },
)

LaunchedEffect(surfaceReady) {
    if (surfaceReady) {
        player.load(url, playWhenReady = true).getOrThrow()
    }
}
```

For desktop and iOS rendering, waiting for `onReady` before loading media is recommended because the video surface must exist before libmpv starts video output.

## Low-level API

Use `Mpv` directly when you need full control over libmpv commands and properties.

```kotlin
val mpv = Mpv.create()

mpv.setOption(MpvOptions.HardwareDecoding, MpvHardwareDecoding.AutoSafe).getOrThrow()
mpv.initialize().getOrThrow()

mpv.command(MpvCommands.loadFile("movie.mkv")).getOrThrow()

val duration = mpv[MpvProperties.Duration].getOrNull()
mpv[MpvProperties.Pause] = false

mpv.close()
```

Custom or newer mpv properties and commands can also be used without waiting for a new `kmpv` release:

```kotlin
val property = MpvProperty.string("my-script/custom-property")
val value = mpv[property]

mpv.command("script-message", "my-message", "arg")
```

## Tracks

`MpvPlayer` exposes the current track list and supports typed video, audio, and subtitle selection.

```kotlin
val tracks = player.tracks().getOrThrow()

val audio = tracks.first { it.type == MpvTrackType.Audio }
player.selectTrack(audio)

player.selectTrack(
    MpvTrackType.Subtitle,
    MpvTrackSelection.Disabled,
)
```

## Platform setup

### Linux

Install libmpv through your distribution package manager. For example:

```sh
# Arch Linux
sudo pacman -S mpv

# Debian / Ubuntu
sudo apt install libmpv-dev
```

If libmpv is installed in a non-standard directory, point the build to it:

```sh
export LINUX_NATIVE_LIBRARY_DIRS="$(pkg-config --variable=libdir mpv)"
```

### Windows and macOS

Provide a compatible libmpv build when linking and packaging your application. `kmpv` does not choose or bundle a particular mpv distribution.

### iOS

Provide an iOS-compatible libmpv build and its required libraries/frameworks in the final application. The `kmpv` iOS artifact contains the Kotlin API and video integration, not an mpv binary.

### Android

The Android library includes the `kmpv` native helper automatically, but it intentionally does not include `libmpv.so`.

Your app must package a compatible `libmpv.so` for every Android ABI you support, for example:

```text
src/main/jniLibs/arm64-v8a/libmpv.so
src/main/jniLibs/armeabi-v7a/libmpv.so
src/main/jniLibs/x86/libmpv.so
src/main/jniLibs/x86_64/libmpv.so
```

For a non-Compose Android `Surface`, configure video output before initialization and attach the surface afterward:

```kotlin
val mpv = Mpv.create()
mpv.configureAndroidVideoOutput().getOrThrow()
mpv.initialize().getOrThrow()

val output = mpv.createAndroidSurfaceOutput()
output.attach(surface).getOrThrow()
output.resize(width, height).getOrThrow()

// Before the Surface is destroyed:
output.detach().getOrThrow()
```

Compose applications can use `MpvVideoSurface` or `MpvAndroidVideoSurface` instead.

## Samples

The repository includes a few small examples:

- `samples/android` — Android `SurfaceView` playback.
- `samples/cli` — headless playback and integration testing.
- `samples/material-demo` — Compose desktop video player demo.
- `samples/runtime-smoke` — simple desktop runtime checks.

## Building the project

Run the main checks with:

```sh
./scripts/check.sh
```

The project uses JDK 21. Android builds also require an Android SDK.

## Releasing

Normal branch pushes and manual workflow runs only build and verify the library. Maven Central publication happens only when a version tag is pushed, for example:

```sh
git tag v0.1.0
git push origin v0.1.0
```

Release-maintainer details are documented in [RELEASING.md](RELEASING.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).
