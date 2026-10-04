#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ElementTree
import zipfile
from pathlib import Path

GROUP = "dev.kmpv"
ROOTS = ("kmpv", "kmpv-render", "kmpv-compose")
TARGETS = (
    "android",
    "linuxx64",
    "linuxarm64",
    "mingwx64",
    "macosx64",
    "macosarm64",
    "iosarm64",
)
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def fail(message: str) -> None:
    raise SystemExit(message)


def coordinate_dir(repository: Path, artifact: str, version: str) -> Path:
    return repository / "dev" / "kmpv" / artifact / version


def xml_text(root: ElementTree.Element, path: str) -> str:
    node = root.find(path, POM_NS)
    return node.text.strip() if node is not None and node.text else ""


def validate_pom(path: Path, artifact: str, version: str, central_ready: bool) -> ElementTree.Element:
    try:
        root = ElementTree.parse(path).getroot()
    except ElementTree.ParseError as error:
        fail(f"Invalid POM {path}: {error}")
    if (
        xml_text(root, "m:groupId") != GROUP
        or xml_text(root, "m:artifactId") != artifact
        or xml_text(root, "m:version") != version
    ):
        fail(f"Wrong POM coordinates in {path}")
    if central_ready:
        required = (
            "m:name",
            "m:description",
            "m:url",
            "m:licenses/m:license/m:name",
            "m:licenses/m:license/m:url",
            "m:developers/m:developer/m:name",
            "m:scm/m:url",
            "m:scm/m:connection",
        )
        missing = [query for query in required if not xml_text(root, query)]
        if missing:
            fail(f"{path} is missing Maven Central metadata: {missing}")
    if "SNAPSHOT" in path.read_text(encoding="utf-8").upper():
        fail(f"SNAPSHOT reference leaked into {path}")
    return root


def validate_module(path: Path, version: str) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        fail(f"Invalid Gradle module metadata {path}: {error}")
    text = path.read_text(encoding="utf-8")
    if "SNAPSHOT" in text.upper():
        fail(f"SNAPSHOT reference leaked into {path}")
    component = data.get("component", {})
    if component.get("version") != version:
        fail(f"Wrong module metadata version in {path}: {component.get('version')}")
    variants = data.get("variants")
    if not isinstance(variants, list) or not variants:
        fail(f"No variants in {path}")
    return data


def available_at_modules(data: dict) -> set[tuple[str, str, str]]:
    result: set[tuple[str, str, str]] = set()
    for variant in data.get("variants", []):
        if not isinstance(variant, dict):
            continue
        available = variant.get("available-at")
        if isinstance(available, dict):
            result.add(
                (
                    str(available.get("group", "")),
                    str(available.get("module", "")),
                    str(available.get("version", "")),
                )
            )
    return result


def dependencies(root: ElementTree.Element) -> set[tuple[str, str]]:
    result: set[tuple[str, str]] = set()
    for dependency in root.findall("m:dependencies/m:dependency", POM_NS):
        group = xml_text(dependency, "m:groupId")
        artifact = xml_text(dependency, "m:artifactId")
        if group and artifact:
            result.add((group, artifact))
    return result


def require_zip_entries(path: Path, entries: set[str]) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
    except zipfile.BadZipFile as error:
        fail(f"Invalid ZIP/JAR/AAR {path}: {error}")
    missing = entries - names
    if missing:
        fail(f"{path} is missing embedded payloads: {sorted(missing)}")
    bundled_mpv = [name for name in names if name.endswith("/libmpv.so")]
    if bundled_mpv:
        fail(f"{path} unexpectedly bundles libmpv: {bundled_mpv}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify the merged kmpv Maven repository.")
    parser.add_argument("repository", type=Path)
    parser.add_argument("version")
    parser.add_argument("--allow-missing-apple", action="store_true")
    parser.add_argument("--central-ready", action="store_true")
    args = parser.parse_args()

    repository = args.repository.expanduser().resolve()
    version = args.version
    if not repository.is_dir():
        fail(f"Repository does not exist: {repository}")
    if "SNAPSHOT" in version.upper():
        fail(f"Merged release repository version must not be a SNAPSHOT: {version}")

    expected = {"kmpv-android-jni"}
    for root_artifact in ROOTS:
        expected.add(root_artifact)
        expected.update(f"{root_artifact}-{target}" for target in TARGETS)
    if args.allow_missing_apple:
        for root_artifact in ROOTS:
            for target in ("macosx64", "macosarm64", "iosarm64"):
                expected.discard(f"{root_artifact}-{target}")

    base = repository / "dev" / "kmpv"
    if not base.is_dir():
        fail(f"Missing dev.kmpv repository root: {base}")

    actual: set[str] = set()
    all_poms = sorted(base.rglob("*.pom"))
    stale_poms = [pom for pom in all_poms if pom.parent.name != version]
    if stale_poms:
        fail(
            "Repository contains POMs outside the requested version "
            f"{version}: {[str(path.relative_to(repository)) for path in stale_poms[:20]]}"
        )
    for pom in base.rglob(f"*/{version}/*.pom"):
        actual.add(pom.parent.parent.name)
    if actual != expected:
        fail(
            "Published coordinate set mismatch. "
            f"Missing={sorted(expected - actual)}, extra={sorted(actual - expected)}"
        )

    modules: dict[str, dict] = {}
    poms: dict[str, ElementTree.Element] = {}
    checked_files = 0
    for artifact in sorted(expected):
        version_dir = coordinate_dir(repository, artifact, version)
        stem = f"{artifact}-{version}"
        pom = version_dir / f"{stem}.pom"
        module = version_dir / f"{stem}.module"
        sources = version_dir / f"{stem}-sources.jar"
        javadoc = version_dir / f"{stem}-javadoc.jar"
        for required in (pom, module, sources, javadoc):
            if not required.is_file():
                fail(f"Missing required artifact: {required}")
        poms[artifact] = validate_pom(pom, artifact, version, args.central_ready)
        modules[artifact] = validate_module(module, version)

        if artifact in ROOTS:
            payload = version_dir / f"{stem}.jar"
        elif artifact.endswith("-android") or artifact == "kmpv-android-jni":
            payload = version_dir / f"{stem}.aar"
        else:
            payload = version_dir / f"{stem}.klib"
        if not payload.is_file():
            fail(f"Missing primary artifact: {payload}")

        for path in version_dir.iterdir():
            if not path.is_file():
                continue
            checked_files += 1
            if path.stat().st_size <= 0:
                fail(f"Empty artifact: {path}")
            if "SNAPSHOT" in path.name.upper():
                fail(f"SNAPSHOT artifact leaked into repository: {path}")

    for root_artifact in ROOTS:
        if root_artifact not in modules:
            continue
        expected_targets = {
            (GROUP, f"{root_artifact}-{target}", version) for target in TARGETS
        }
        missing = expected_targets - available_at_modules(modules[root_artifact])
        if missing:
            fail(f"{root_artifact} root metadata is missing targets: {sorted(missing)}")

    required_dependencies = {
        "kmpv-android": {(GROUP, "kmpv-android-jni")},
        "kmpv-render-android": {
            (GROUP, "kmpv-android"),
            (GROUP, "kmpv-android-jni"),
        },
        "kmpv-compose-android": {(GROUP, "kmpv-render-android")},
    }
    for artifact, required in required_dependencies.items():
        missing = required - dependencies(poms[artifact])
        if missing:
            fail(f"{artifact} POM is missing dependencies: {sorted(missing)}")

    jni_aar = coordinate_dir(repository, "kmpv-android-jni", version) / f"kmpv-android-jni-{version}.aar"
    require_zip_entries(
        jni_aar,
        {
            "jni/arm64-v8a/libkmpv_jni.so",
            "jni/armeabi-v7a/libkmpv_jni.so",
            "jni/x86/libkmpv_jni.so",
            "jni/x86_64/libkmpv_jni.so",
        },
    )

    print(
        f"Verified {len(expected)} kmpv publications ({checked_files} files) "
        f"at version {version}"
    )


if __name__ == "__main__":
    main()
