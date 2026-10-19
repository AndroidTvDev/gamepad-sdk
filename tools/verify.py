#!/usr/bin/env python3
"""Compile real Kotlin sources and run JVM tests without Gradle/Android SDK.

Requires Python 3.9+, a Java 17 runtime, and Maven Central access on first use.
Android source compilation uses Robolectric's API 35 android-all as a classpath.
This checks Kotlin compilation; it does not replace Android lint, AAR packaging,
or device testing. Artifacts are version pinned and SHA-256 checked before use.

    python3 tools/verify.py
    python3 tools/verify.py --module sdk-core --skip-sample
    python3 tools/verify.py --cache ../tooling/gamepad-verify
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

ARTIFACTS = {
    "org/jetbrains/kotlin/kotlin-daemon-embeddable/2.1.21/kotlin-daemon-embeddable-2.1.21.jar": "9401effd82de8606df3289308a275cc1d3573768665ca5edd09dc319c71715f4",
    "org/jetbrains/kotlin/kotlin-script-runtime/2.1.21/kotlin-script-runtime-2.1.21.jar": "d8221f445854f30ac92c248d543609e0ecb5d85bb5ba34c043684b013cb5b897",
    "org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar": "c5fd725bffab51846bf3c77db1383c60aaaebfe1b7fe2f00d23fe1b7df0a439d",
    "org/jetbrains/kotlin/kotlin-stdlib/2.1.21/kotlin-stdlib-2.1.21.jar": "263bdc679e1f62012db7b091796279b6d71cf36f4797a98ff1ace05835f201c8",
    "org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar": "3277ac102ae17aad10a55abec75ff5696c8d109790396434b496e75087854203",
    "org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.21/kotlin-compiler-embeddable-2.1.21.jar": "67a2e3673765f097725608cc7c843b0d8fdd94bd9af41327211361614aa57d49",
    "org/jetbrains/kotlinx/kotlinx-coroutines-android/1.10.2/kotlinx-coroutines-android-1.10.2.jar": "e713f1f874244115a07571065cffa0f24f5e78300e9720fea16de3af1d75fd41",
    "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar": "9860906a1937490bf5f3b06d2f0e10ef451e65b95b269f22daf68a3d1f5065c5",
    "org/jetbrains/kotlin/kotlin-test/2.1.21/kotlin-test-2.1.21.jar": "0177d6d1ffdb2fa0d76c26c2228c991de537dbd89f1766624b43ab09d3da7643",
    "org/jetbrains/kotlinx/kotlinx-coroutines-test-jvm/1.10.2/kotlinx-coroutines-test-jvm-1.10.2.jar": "590a549f8c1db590c9d98a8a20424a1f581a34162a369e6a6bd884ce7d36d3d7",
    "org/jetbrains/annotations/13.0/annotations-13.0.jar": "ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478",
    "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar": "5ca175b38df331fd64155b35cd8cae1251fa9ee369709b36d42e0a288ccce3fd",
    "org/jetbrains/kotlin/kotlin-test-junit5/2.1.21/kotlin-test-junit5-2.1.21.jar": "3c8099d9850475c08eb761fd6a0b704eb3d908ee546de997858c59af26f88208",
    "org/junit/platform/junit-platform-console-standalone/1.12.2/junit-platform-console-standalone-1.12.2.jar": "329bd10288875a74d04c9ca6b7c9889c265ddf87b21a9ea42a7f7392f391472a",
    "org/robolectric/android-all/15-robolectric-12650502/android-all-15-robolectric-12650502.jar": "6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b"
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def obtain(cache: Path, coordinate: str, expected: str) -> Path:
    target = cache / coordinate.rsplit("/", 1)[1]
    if target.exists() and sha256(target) == expected:
        return target
    temporary = target.with_suffix(".part")
    url = "https://repo.maven.apache.org/maven2/" + coordinate
    print("Downloading " + target.name, flush=True)
    try:
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as stream:
            shutil.copyfileobj(response, stream)
        if sha256(temporary) != expected:
            raise RuntimeError("SHA-256 mismatch: " + coordinate)
        temporary.replace(target)
        return target
    finally:
        temporary.unlink(missing_ok=True)


def run(command: list[str], cwd: Path) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=Path.home() / ".cache" / "gamepad-sdk-verify")
    parser.add_argument("--module", choices=["all", "sdk-core"], default="all")
    parser.add_argument("--skip-tests", action="store_true")
    parser.add_argument("--skip-sample", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    if shutil.which("java") is None:
        parser.error("Java 17 or later must be installed and available on PATH")
    required = dict(ARTIFACTS)
    if args.module == "sdk-core":
        required = {name: digest for name, digest in required.items()
                    if "android-all" not in name and "kotlinx-coroutines-android" not in name}
    if args.skip_tests:
        required = {name: digest for name, digest in required.items()
                    if "kotlin-test" not in name and "coroutines-test" not in name
                    and "junit-platform-console" not in name}
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        paths = list(pool.map(lambda entry: obtain(cache, *entry), required.items()))
    jars = {path.name: path for path in paths}

    def jar(name: str) -> str:
        return str(jars[name])

    def classpath(entries: list[str | Path]) -> str:
        return os.pathsep.join(map(str, entries))

    compiler_cp = classpath([
        jar("kotlin-compiler-embeddable-2.1.21.jar"),
        jar("kotlin-stdlib-2.1.21.jar"), jar("kotlin-script-runtime-2.1.21.jar"),
        jar("kotlin-reflect-1.6.10.jar"), jar("kotlin-daemon-embeddable-2.1.21.jar"),
        jar("trove4j-1.0.20200330.jar"), jar("annotations-13.0.jar"),
        jar("kotlinx-coroutines-core-jvm-1.8.0.jar"),
    ])
    common_cp = [jar("kotlin-stdlib-2.1.21.jar"),
                 jar("annotations-13.0.jar"),
                 jar("kotlinx-coroutines-core-jvm-1.10.2.jar")]
    test_cp = common_cp + ([] if args.skip_tests else [
        jar("kotlin-test-2.1.21.jar"), jar("kotlin-test-junit5-2.1.21.jar"),
        jar("kotlinx-coroutines-test-jvm-1.10.2.jar"),
        jar("junit-platform-console-standalone-1.12.2.jar")])
    out = root / "build" / "standalone"
    out.mkdir(parents=True, exist_ok=True)

    def compile_module(module: str, dependencies: list[str | Path], tests: bool = False) -> Path | None:
        kind = "test" if tests else "main"
        sources = sorted((root / module / "src" / kind / "kotlin").rglob("*.kt"))
        if not sources:
            print(f"SKIP {module}/{kind}: no source files", flush=True)
            return None
        target = out / (module + ("-tests" if tests else "") + ".jar")
        target.unlink(missing_ok=True)
        print(f"Compiling {module}/{kind}: {len(sources)} Kotlin files", flush=True)
        command = ["java", "-Xmx2g", "-cp", compiler_cp,
                   "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                   "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                   "-classpath", classpath(dependencies),
                   "-module-name", module.replace("-", "_") + ("_tests" if tests else "")]
        if tests:
            command += ["-Xfriend-paths=" + str(out / (module + ".jar"))]
        command += ["-d", str(target), *map(str, sources)]
        run(command, root)
        return target

    core = compile_module("sdk-core", common_cp)
    if core is None:
        raise RuntimeError("sdk-core has no sources")
    modules = [core]
    demo = None
    sample = None
    if args.module == "all":
        demo = compile_module("protocol-demo", common_cp + [core])
        if demo:
            modules.append(demo)
        compile_module("sdk-android", common_cp + [core,
            jar("kotlinx-coroutines-android-1.10.2.jar"),
            jar("android-all-15-robolectric-12650502.jar")])
        sample = compile_module("sample-console", common_cp + modules)

    if not args.skip_tests:
        test_jars = []
        for module in (["sdk-core", "protocol-demo"] if args.module == "all" else ["sdk-core"]):
            result = compile_module(module, test_cp + modules, tests=True)
            if result:
                test_jars.append(result)
        if test_jars:
            print("Running JVM tests", flush=True)
            run(["java", "-jar", jar("junit-platform-console-standalone-1.12.2.jar"),
                 "execute", "--disable-banner", "--details=tree",
                 "--class-path", classpath(test_cp + modules + test_jars),
                 "--scan-class-path", "--reports-dir", str(out / "test-results"),
                 "--fail-if-no-tests"], root)
        else:
            raise RuntimeError("No JVM tests found; use --skip-tests for a compilation-only check")

    if not args.skip_sample and args.module == "all":
        if sample is None:
            raise RuntimeError("sample-console has no source files")
        print("Running simulated device demo", flush=True)
        run(["java", "-cp", classpath(common_cp + modules + [sample]),
             "dev.gamepad.sdk.sample.MainKt"], root)
    print("Verification completed. Outputs: " + str(out), flush=True)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        print("Verification failed: " + str(error), file=sys.stderr)
        sys.exit(1)
