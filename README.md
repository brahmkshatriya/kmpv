# kmpv

`kmpv` is a Kotlin Multiplatform wrapper around libmpv. Linux, Windows x64, and macOS x64 core playback have been runtime-tested, Android is build/JNI-tested, and the macOS x64, macOS arm64, and iOS arm64 core/render/Compose artifacts are compile- and publication-tested on a macOS host. The common consumer API remains independent of platform FFI types.

The API deliberately has two layers:

- `Mpv`: a typed, Kotlin-shaped libmpv client with raw escape hatches.
- `MpvPlayer`: a convenience playback/state layer implemented in `commonMain`.

The Linux implementation follows the proven interop pattern in `../compose-native/demo`: a small C ABI shim owns the single `mpv_wait_event()` loop on a native thread, then copies events into Kotlin. Unlike the demo, play/seek/volume/state behavior lives in common Kotlin instead of the C shim.

## Current status

- Native core targets declared for Linux x64/arm64, Windows x64, macOS x64/arm64, and iOS arm64.
- System libmpv linking (`-lmpv`).
- Android KMP backend with a separate JNI carrier AAR for arm64-v8a, armeabi-v7a, x86, and x86_64.
- Android JNI dynamically loads `libmpv.so`; the core/JNI artifacts do not bundle a particular mpv runtime.
- Typed string/flag/int64/double properties.
- Kotlin-owned `MpvNode` values for arrays, maps, byte arrays, node-valued properties, and structured command results.
- Typed common properties and options.
- Typed command builders plus raw commands.
- Synchronous and true libmpv request/reply async commands and properties.
- Property observation as `Flow<T?>`.
- Native mpv events as `SharedFlow<MpvEvent>`.
- Public event delivery is isolated and bounded so a slow public event collector cannot stall async operations/player state or grow memory without bound; `MpvPlayerState` remains the authoritative lossless state surface.
- Higher-level `MpvPlayerState` as `StateFlow`, including tracks, selected tracks, volume/mute, video size/FPS/bitrate, and active hardware decoder.
- Typed video/audio/subtitle track selection.
- Separate media status, play intent, buffering, and seeking dimensions.
- Idempotent close and cancellation of async mpv commands.
- Optional OpenGL/OpenGL ES rendering for Linux/Windows/macOS/iOS through the separate `kmpv-render` artifact.
- Thin `kmpv-compose` video surfaces for desktop Compose Native, Android Compose, and iOS Compose/UIKit.
- A Compose Native Material OSC-inspired HLS demo.

The native renderer is exposed as `MpvOpenGlRenderer` from the separate `kmpv-render` module. Linux, Windows, macOS, and iOS share the same libmpv OpenGL render bridge; Linux can additionally pass Wayland/X11 display handles for hardware-decoder interop, while iOS supplies an EAGL/OpenGL ES context. The core `kmpv` artifact contains no SDL/OpenGL renderer code. The common `Mpv` and `MpvPlayer` APIs remain free of Compose types.

Windows x64 is cross-built from Linux and has also been final-linked and runtime-smoke-tested against an external `libmpv-2.dll`. The headless smoke test opened the Mux HLS master and reached `MediaStatus.Ready`. The Windows Compose Native Material demo links, but GUI execution under the local Proton environment currently aborts inside Skia Unicode initialization before the player surface stays up; that failure occurs after `libmpv-2.dll` is loaded and is not treated as a kmpv backend failure.

macOS x64 has also been final-linked and runtime-smoke-tested against a checksum-verified external `libmpv.dylib` bundle. The headless smoke opened the same Mux HLS master, enumerated its five advertised video renditions, reached `MediaStatus.Ready`, and shut down cleanly. This verifies the macOS x64 core/libmpv boundary; the OpenGL renderer and Compose surface still need runtime coverage on macOS, and macOS arm64 still needs a real-runtime test on Apple Silicon.

iOS arm64 has core, render, and Compose source implementations. The Compose surface uses `UIKitView` + `GLKView` + an EAGL OpenGL ES 2 context. Core, render, and Compose compilation plus Maven-local target publication have been verified on macOS for `iosArm64`. A final iOS application still needs to supply and link an iOS libmpv build and must be validated on a physical device before the target is called production-ready.

The Android control/event backend and `Surface` output adapter are implemented and their AAR/JNI artifacts compile for the four standard Android ABIs. Android playback has not yet been runtime-tested on a device in this repository because `libmpv.so` itself is intentionally not bundled here yet.

For Compose consumers, `MpvVideoSurface` exposes an `onReady` callback. With outputs such as `vo=libmpv`, start loading media from that callback (or from state set by it), not immediately after creating the player: libmpv requires its render context to exist before it initializes video output. Android reports readiness after its `Surface` is attached; desktop/iOS report readiness after the libmpv render context has been created on the platform graphics context.

## Requirements on Arch Linux

The first backend links against the system libmpv. On the development machine used for this initial implementation, `pkg-config --modversion mpv` reports `2.5.0`.

If the headers or shared library are missing:

```sh
yay -S mpv
```

You also need a JDK for Gradle and a normal C toolchain (`cc` and `ar`).

Linux does **not** bundle libmpv. The published KLIB records `-lmpv`, while the final application supplies the native library search directory. For Arch this is normally:

```sh
export LINUX_NATIVE_LIBRARY_DIRS=/usr/lib
```

The build falls back to `/usr/lib` when that variable is not set. Other distributions can point it at their `pkg-config --variable=libdir mpv` result. Echo already uses the same `LINUX_NATIVE_LIBRARY_DIRS` convention in its desktop application build.

## Native target toolchains

Native runtimes are supplied by the application/build environment rather than bundled into `kmpv`. Each target accepts the same environment contract, where `<TARGET>` is one of `LINUX_X64`, `LINUX_ARM64`, `MINGW_X64`, `MACOS_X64`, `MACOS_ARM64`, or `IOS_ARM64`:

```text
KMPV_<TARGET>_CC            C compiler command
KMPV_<TARGET>_CFLAGS        extra C compiler flags, such as include paths
KMPV_<TARGET>_AR            static archiver command
KMPV_<TARGET>_CINTEROP_OPTS extra Kotlin/Native cinterop compiler options
KMPV_<TARGET>_LIBRARY_DIRS  native library search directories
KMPV_<TARGET>_LINKER_OPTS   extra final-link options/frameworks/libraries
```

`KMPV_<TARGET>_LIBRARY_DIRS` uses the host OS path separator (`:` on Unix/macOS and `;` on Windows), so normal Windows drive-letter paths are valid. Linux continues to support `LINUX_NATIVE_LIBRARY_DIRS` as the system-libmpv fallback.

Linux native hosts default to `cc`/`ar`. On Linux x64, the build can also reuse Kotlin/Native's downloaded toolchains to cross-build Linux arm64 and MinGW x64. macOS builds default to `xcrun clang`/`xcrun ar` with the target architecture pinned explicitly. Toolchains can always be overridden through `KMPV_<TARGET>_CC` and `KMPV_<TARGET>_AR`. `KMPV_<TARGET>_CINTEROP_OPTS` is normally unnecessary with a standard Xcode installation; it exists for nonstandard SDK layouts where Kotlin/Native cinterop also needs explicit options such as `-isysroot`.

iOS normally consumes a static libmpv build, so `KMPV_IOS_ARM64_LINKER_OPTS` is the place to provide any FFmpeg libraries and Apple frameworks required by that particular libmpv build. kmpv does not encode one private libmpv build's dependency graph into the public artifact.

## Android runtime model

The Android target uses the same common `Mpv`/`MpvPlayer` API, backed by a small `kmpv-android-jni` AAR. That JNI library resolves libmpv symbols at runtime with `dlopen("libmpv.so")`/`dlsym()` instead of linking a specific mpv build into kmpv.

As a result, an Android application currently needs to provide an ABI-compatible `libmpv.so` in its packaged native libraries. A later optional runtime artifact can provide prebuilt Android mpv binaries without coupling the core API to them. If no Android libmpv runtime is present, creating `Mpv` fails with an explicit runtime-availability message rather than silently falling back to another player.

The published Android `kmpv` artifact carries `kmpv-android-jni` as a transitive runtime dependency. Applications should depend on `kmpv`, not on the JNI carrier directly.

Android video output lives in `kmpv-render`. The default GPU mode configures `vo=gpu`, `gpu-context=android`, OpenGL ES, and `mediacodec-copy`; direct `mediacodec_embed` output is also available. Surface attachment follows mpv's Android `wid` contract and keeps a JNI global reference alive until detach.

```kotlin
val mpv = Mpv.create()
mpv.configureAndroidVideoOutput().getOrThrow()
mpv.initialize().getOrThrow()

val output = mpv.createAndroidSurfaceOutput()
output.attach(surface).getOrThrow()
output.resize(width, height).getOrThrow()

// Before the Android Surface is destroyed:
output.detach().getOrThrow()
```

`samples/android` is a plain `Activity` + `SurfaceView` consumer of `kmpv-render`. It intentionally has no Compose or AndroidX dependency, so it exercises the public Android library boundary directly. Build it with:

```sh
./gradlew :samples:android:assembleDebug
```

The APK receives `libkmpv_jni.so` transitively for arm64-v8a, armeabi-v7a, x86, and x86_64, but intentionally contains no `libmpv.so`. Add an ABI-compatible Android libmpv runtime to the app's native libraries before using it for playback on a device or emulator. Without one, the sample displays the explicit runtime-availability error.

## Raw typed API

```kotlin
val mpv = Mpv.create()

mpv.setOption(MpvOptions.HardwareDecoding, MpvHardwareDecoding.AutoSafe).getOrThrow()
mpv.initialize().getOrThrow()

mpv.command(MpvCommands.loadFile("movie.mkv")).getOrThrow()
mpv[MpvProperties.Pause] = false

val duration = mpv[MpvProperties.Duration].getOrNull()
val version = mpv.getAsync(MpvProperties.MpvVersion).getOrThrow()
mpv.setAsync(MpvProperties.Pause, true).getOrThrow()

mpv.observe(MpvProperties.TimePos).collect { seconds ->
    println(seconds)
}

mpv.close()
```

The catalog is optional. Application-specific or future mpv surface area can be used without waiting for a kmpv release:

```kotlin
val custom = MpvProperty.string("my-script/custom-property")
val value = mpv[custom]

mpv.command("script-message", "my-message", "arg")
val futureCommand = MpvCommands.raw("some-future-mpv-command", "arg")

val commandList = mpv.getNodeProperty("command-list").getOrThrow()
val userData = MpvNode.MapValue(mapOf("mode" to MpvNode.StringValue("demo")))
mpv.setNodePropertyAsync("user-data/kmpv", userData).getOrThrow()
val roundTrip = mpv.getNodePropertyAsync("user-data/kmpv").getOrThrow()
val expanded = mpv.commandResult("expand-text", "${'$'}{mpv-version}").getOrThrow()
```

## Convenience player

```kotlin
val player = MpvPlayer.create {
    option(MpvOptions.VideoOutput, MpvVideoOutput.LibMpv)
    option(MpvOptions.HardwareDecoding, MpvHardwareDecoding.AutoCopySafe)
}.getOrThrow()

player.load(
    "https://example.com/video.mkv",
    playWhenReady = true,
).getOrThrow()

player.state.collect { state ->
    println(state.mediaStatus)
    println(state.playWhenReady)
    println(state.isBuffering)
    println(state.position)
    println("${state.videoWidth}x${state.videoHeight} ${state.videoFps} fps")
    println(state.videoTracks)
    println(state.selectedTrack(MpvTrackType.Audio))
}

player.selectTrack(MpvTrackType.Subtitle, MpvTrackSelection.Disabled)
```

## Compose video surface

For `vo=libmpv` desktop/iOS playback, wait until the surface is ready before loading the file:

```kotlin
var surfaceReady by remember { mutableStateOf(false) }

MpvVideoSurface(
    player = player,
    modifier = Modifier.fillMaxSize(),
    onError = { error -> println(error.message) },
    onReady = { surfaceReady = true },
)

LaunchedEffect(surfaceReady) {
    if (surfaceReady) {
        player.load(url, playWhenReady = true).getOrThrow()
    }
}
```

The low-level `kmpv-render` API intentionally requires an explicit OpenGL/OpenGL ES function resolver:

```kotlin
val renderer = mpv.createOpenGlRenderer(
    getProcAddress = ::resolveOpenGlFunction,
).getOrThrow()

renderer.render(
    target = MpvOpenGlRenderTarget(
        framebuffer = fbo,
        width = width,
        height = height,
        internalFormat = internalFormat,
    ),
    options = MpvOpenGlRenderOptions(flipY = true),
).getOrThrow()
```

`render()` and `close()` must run on the graphics thread with the same graphics context current. The libmpv update callback may arrive from another thread and should only schedule a graphics/UI-thread render. Hosts that own the actual buffer-swap/present operation can call `renderer.reportSwap()` immediately after that swap. The Compose adapters do not call it from their draw callbacks because Compose/GLKView perform presentation after those callbacks return. On Linux, the Compose adapter defaults to no X11/Wayland native-display handle and is intended for copy-back hardware decoding such as `vaapi-copy`; applications deliberately using direct zero-copy interop can use the low-level renderer API and supply `MpvOpenGlNativeDisplay` themselves.

`isPlaying` is derived. Buffering and seeking are not collapsed into a mutually-exclusive `Playing/Paused/Buffering` enum.

The typed low-level client remains available as `player.raw` for advanced behavior.

## Material OSC demo

The graphical demo plays the Mux multi-variant HLS master playlist:

```text
https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
```

Run it on Linux x64 with:

```sh
./scripts/run-material-demo.sh
```

The controls intentionally follow the local Material OSC visual language used while developing this project: a `#00bbff` accent, near-black translucent rounded pills, a thin dark seek track, 12dp controller insets, and orange boosted-volume feedback above 100%.

The quality menu is populated from mpv's `track-list` at runtime rather than from hard-coded Mux values. For this test stream it currently discovers 1080p, 720p, 480p, 288p, and 184p video variants. Selecting a quality switches the matching `vid` and `aid` in-place, so quality changes do not reload the URL or lose playback position.

The Linux demo currently uses `hwdec=vaapi-copy`. Direct VAAPI zero-copy (`auto-safe` selecting `vaapi`) proved unreliable when HLS track switches changed resolution inside the Compose Native/SDL OpenGL render path: initial frames could stay black and upward resolution switches could produce corrupted reference surfaces. `vaapi-copy` keeps VAAPI hardware decoding but copies decoded frames out of driver-owned surfaces before libmpv renders them, which avoids that zero-copy surface reconfiguration failure while preserving fast in-place track switching.

## Project shape

```text
kmpv/
  src/commonMain/
    Mpv.kt                 typed libmpv API
    MpvPlayer.kt           common convenience/state layer
    MpvProperty.kt         typed property descriptors/catalog
    MpvCommand.kt          typed commands + raw escape hatch
    MpvEvent.kt            stable Kotlin event model
    internal/MpvBackend.kt platform boundary

  src/nativeMain/
    internal/NativeMpvBackend.kt
    MpvNativeAccess.kt

  src/androidMain/
    internal/AndroidMpvBackend.kt
    internal/AndroidBinary.kt

  src/nativeInterop/cinterop/
    kmpv_bridge.c          owns the libmpv event pump
    include/kmpv_bridge.h  stable C ABI for Kotlin/Native

kmpv-android-jni/
  src/main/java/
    AndroidMpvNative.java  stable JNI declarations
  src/main/cpp/
    mpv_dynamic.c          runtime dlopen/dlsym loader
    binary.c               copies nodes/events into JVM-owned payloads
    jni_*.c                client/property/command/event JNI bridge

kmpv-render/
  src/openGlNativeMain/
    MpvOpenGlRenderer.kt   Linux/Windows/macOS/iOS OpenGL renderer
  src/androidMain/
    MpvAndroidSurfaceOutput.kt Android Surface / wid adapter
  src/nativeInterop/
    kmpv_render_gl.c       portable libmpv OpenGL render bridge

kmpv-compose/
  src/desktopNativeMain/   Compose Native OpenGL surface
  src/androidMain/         Android Compose SurfaceView adapter
  src/iosArm64Main/        UIKit/GLKView OpenGL ES surface

samples/cli/
samples/material-demo/       Compose Native HLS/OpenGL demo
samples/android/             Android SurfaceView consumer sample
samples/runtime-smoke/       headless Windows/macOS x64 runtime/HLS smoke samples
```

This keeps cinterop/JNI types out of the public API. Commands, properties, observation, coroutine behavior, node values, and `MpvPlayer` remain common across the Native and Android backends.

## Validate

`scripts/check.sh` locates a JDK 21 from `JAVA_HOME` or `~/.jdks` and runs the Linux native tests plus sample link steps. If an Android SDK is available through `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `$HOME/Android/Sdk`, it also builds the Android JNI AAR and KMP Android AAR:

```sh
./scripts/check.sh
```

With `JAVA_HOME` already configured, the equivalent x64 command is:

```sh
./gradlew \
  :kmpv:linuxX64Test \
  :kmpv:testAndroidHostTest \
  :samples:cli:linkDebugExecutableLinuxX64 \
  :samples:material-demo:linkDebugExecutableLinuxX64 \
  :kmpv-android-jni:assembleDebug \
  :kmpv:assembleAndroidMain \
  :kmpv-render:assembleAndroidMain \
  :samples:android:assembleDebug
```

The library modules can also be checked as real Maven publications without a remote repository:

```sh
./scripts/check-publication.sh
```

This verifies publications that the current host can actually build. On Linux x64 that currently includes Linux x64, cross-built Linux arm64 and MinGW x64 variants for `kmpv`, `kmpv-render`, and `kmpv-compose`; when an Android SDK is available it also publishes/verifies the Android artifacts and JNI runtime dependency. Linux publications remain free of Android/JNI payloads and do not bundle libmpv.

`check-publication.sh` resolves the artifact version from `KMPV_VERSION` first and otherwise from `kmpv.version`, so the same verifier works for snapshot and release-version publication checks.

The custom native bridge archives are declared as inputs to their cinterop tasks. This matters for release correctness: changing `kmpv_bridge.c` or `kmpv_render_gl.c` invalidates the generated KLIB and downstream executables/publications instead of leaving an older embedded static archive in place.

Release versioning and remote repository configuration are environment-driven so a release does not require editing Gradle files:

```sh
export KMPV_VERSION=0.1.0
export KMPV_MAVEN_REPOSITORY_URL=https://your.maven.repository/releases
export KMPV_MAVEN_USERNAME=...   # when the repository uses basic credentials
export KMPV_MAVEN_PASSWORD=...

# Optional in-memory PGP signing for repositories that require signatures.
export KMPV_SIGNING_KEY=...
export KMPV_SIGNING_PASSWORD=...
```

`kmpv.version` in `gradle.properties` remains the local-development default. `KMPV_VERSION` overrides it for a release. Project URL, SCM coordinates, Apache-2.0 license metadata, and developer identity are checked into the build and can be overridden with `KMPV_POM_*` environment variables when needed. Run `./scripts/check-release-readiness.sh` before remote publication.

Compose Native `1.13.0-alpha10` is used for the Native Compose integration. Its root KMP metadata routing has been verified from a clean build, so `kmpv-compose` participates in the normal root publication shard alongside `kmpv` and `kmpv-render`.

For compatible-host CI, use the target-specific validator:

```sh
./scripts/check-native-target.sh linuxX64
./scripts/check-native-target.sh linuxArm64
./scripts/check-native-target.sh mingwX64
./scripts/check-native-target.sh macosX64
./scripts/check-native-target.sh macosArm64
./scripts/check-native-target.sh iosArm64
```

Desktop target validation runs the core tests, links the headless CLI executable against the supplied libmpv runtime, builds `kmpv-render` and `kmpv-compose`, and publishes all three target variants to Maven Local. The iOS target validator builds and publishes core, OpenGL ES render, and Compose/UIKit artifacts on macOS; final application linkage against an iOS libmpv runtime is a separate integration step.

For a single macOS-host Apple compile/publication matrix check, run:

```sh
./scripts/check-apple.sh
```

`check-apple.sh` verifies the `kmpv`, `kmpv-render`, and `kmpv-compose` target artifacts for macOS x64, macOS arm64, and iOS arm64 and publishes those variants to Maven Local. It intentionally does not link a final executable against libmpv, so it does not require `KMPV_*_LIBRARY_DIRS`. Runtime validation remains a separate integration step. macOS x64 core playback has been final-linked and smoke-tested against a real external libmpv dylib bundle; macOS arm64 still needs equivalent real-runtime coverage, and iOS still requires a real iOS libmpv build exercised on device.

The CLI sample is intentionally headless (`vo=null`, `ao=null`) so it is useful as an integration check. Pass it a local media file or URL.

## Next architectural steps

1. Validate the iOS GLKView surface with a real iOS libmpv build on a physical device, runtime-test macOS OpenGL/Compose rendering, and run the core runtime smoke on Apple Silicon. macOS x64 core playback and the Apple compile/publication matrix are already verified.
2. Run the Windows Compose surface on an actual Windows host; the core/libmpv runtime path is already smoke-tested independently of Compose.
3. Generate a larger property/option/command catalog while preserving raw access.
4. Keep Linux system-libmpv-first. Android/Apple/Windows runtime binaries should remain optional packaging concerns rather than becoming part of the common API.

## License

kmpv is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
