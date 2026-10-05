# Releasing kmpv

The release pipeline uses isolated Maven repository shards. Each compatible CI host publishes only the variants it can build, GitHub Actions merges those repositories while rejecting conflicting files, and Maven Central credentials are exposed only to the final upload job.

## Before the first public release

1. Verify the `dev.brahmkshatriya.kmpv` namespace in Maven Central.
2. Configure the GitHub Actions secrets below.

The public Maven identity is checked into `gradle/kmpv-release.properties`: `https://github.com/brahmkshatriya/kmpv`, Apache-2.0, and developer `brahmkshatriya` / Shivam. `KMPV_POM_*` environment variables remain available as optional overrides for local or forked publication builds.

## GitHub Actions secrets

`GRADLE_PROPERTIES_CONTENT` contains the Maven Central token and signing metadata used only by the final publish job:

```properties
mavenCentralUsername=...
mavenCentralPassword=...
signing.keyId=...
signing.password=...
```

`GPG_SECRET_KEY_RING_BASE64` is the base64-encoded secret GPG keyring matching `signing.keyId`.

No signing or Maven Central secret is provided to the Linux, Android, Apple, test, or merge jobs.

## CI/release topology

`.github/workflows/publish.yml` creates eight isolated Maven repository shards. Every platform target is built in its own CI job:

- `common` — root Kotlin Multiplatform metadata for `kmpv`, `kmpv-render`, and `kmpv-compose`.
- `linux_x64` — Linux x64 variants and Linux x64 tests.
- `linux_arm64` — Linux arm64 variants.
- `mingw_x64` — MinGW x64 variants.
- `android` — `kmpv-android-jni` plus Android core/render/Compose AAR publications. The JNI carrier is built with NDK r29 and does **not** bundle `libmpv.so`.
- `macos_x64` — macOS x64 variants on an Intel macOS runner.
- `macos_arm64` — macOS arm64 variants on an Apple Silicon runner.
- `ios_arm64` — iOS arm64 variants on an Apple Silicon runner.

The merge job rejects non-identical duplicate files and verifies:

- the exact coordinate set;
- KMP root `available-at` target links;
- sources and javadoc artifacts;
- Android JNI ABI payloads;
- Android transitive dependency wiring;
- absence of a bundled Android `libmpv.so`;
- absence of `SNAPSHOT` references;
- Maven Central POM metadata on release runs;
- resolution from a clean external Gradle consumer project.

The merged Maven repository is uploaded as a workflow artifact on every CI run. A release does not rebuild anything after this verification step.

## Publishing

Maven Central publication is triggered only by a pushed version tag, such as `v0.1.0-alpha01`. Branch pushes, pull requests, and manual workflow runs build and verify artifacts but never publish them.

For a tag release, CI signs every file in the already-verified merged repository, generates Maven checksums, creates one Maven Central Portal bundle, uploads it with `publishingType=AUTOMATIC`, and waits for the Central deployment to reach `PUBLISHED`.

## Local checks

Normal development validation:

```bash
./scripts/check.sh
./scripts/check-publication.sh
```

Apple host validation:

```bash
./scripts/check-apple.sh
```

Local release readiness requires a non-SNAPSHOT version and then runs the full build/publication gates:

```bash
KMPV_VERSION=0.1.0-alpha01 ./scripts/check-release-readiness.sh
```

Linux remains a system-libmpv consumer. Android's published JNI carrier dynamically loads an app-supplied `libmpv.so`; the CI test application may inject a runtime for device testing, but release artifacts do not bundle one.
