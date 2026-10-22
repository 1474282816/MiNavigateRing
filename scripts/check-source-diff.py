#!/usr/bin/env python3
"""Read-only checkpoint guard: exact allowed file set + text-line budget.

Run from the repository root: ``python scripts/check-source-diff.py``.
Prints ``text_lines=<n>``, one ``binary ...`` record per authorised binary and
``OK`` on success (exit 0); prints the reason to stderr and exits 1 on failure.
"""

from __future__ import annotations

import hashlib
import subprocess
import sys
from pathlib import Path

BASELINE = "f47f15a0edc907dc8271c077d094a83a2f9befea"
MAX_TEXT_LINES = 2500
AUTHORIZED = {
    "app/libs/xms-wearable-lib_1.4_release.aar": (
        102453,
        "9c40fd1c5409bb948523d503af71e2978ae522c35636afe7d64f474c0f6bc195",
        "https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip",
    ),
    "gradle/wrapper/gradle-wrapper.jar": (
        47623,
        "238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5",
        "https://github.com/gradle/gradle/raw/v9.8.1/gradle/wrapper/gradle-wrapper.jar",
    ),
}
ALLOWED = {
    ".gitignore", "README.md", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties", "app/build.gradle.kts",
    "app/libs/xms-wearable-lib_1.4_release.aar", "app/src/main/AndroidManifest.xml",
    "app/src/main/res/values/themes.xml",
    "app/src/main/java/com/minavigatering/probe/MainActivity.kt",
    "app/src/main/java/com/minavigatering/probe/protocol/Json.kt",
    "app/src/main/java/com/minavigatering/probe/protocol/NavProtocol.kt",
    "app/src/main/java/com/minavigatering/probe/engine/NavigationEngine.kt",
    "app/src/main/java/com/minavigatering/probe/transport/XiaomiWearableTransport.kt",
    "app/src/main/java/com/minavigatering/probe/transport/ProbeForegroundService.kt",
    "app/src/test/java/com/minavigatering/probe/protocol/NavCodecTest.kt",
    "app/src/test/java/com/minavigatering/probe/engine/NavigationEngineTest.kt",
    "scripts/check-aar-hash.py", "scripts/gen-debug-signing.py", "scripts/check-source-diff.py",
    "docs/sdk-provenance.md",
}
FORBIDDEN_SUFFIXES = (".jks", ".pem", ".p12", "local.properties")


def git(*args: str) -> str:
    done = subprocess.run(["git", *args], check=True, capture_output=True, text=True)
    return done.stdout


def fail(msg: str) -> None:
    print(msg, file=sys.stderr)
    raise SystemExit(1)


def normalize(path: str) -> str:
    return path.replace("\\", "/").strip()


def count_lines(blob: bytes) -> int:
    if not blob:
        return 0
    return blob.count(b"\n") + (0 if blob.endswith(b"\n") else 1)


def main() -> None:
    rows: dict[str, tuple[str, str]] = {}
    for line in git("diff", "--numstat", BASELINE).splitlines():
        if not line.strip():
            continue
        added, deleted, path = line.split("\t", 2)
        rows[normalize(path)] = (added, deleted)

    text_lines = 0
    seen_binary: set[str] = set()

    for path, (added, deleted) in rows.items():
        if path not in ALLOWED or path.endswith(FORBIDDEN_SUFFIXES):
            fail("unknown path: " + path)
        if added == "-" and deleted == "-":
            if path not in AUTHORIZED:
                fail("unauthorized binary: " + path)
            seen_binary.add(path)
            continue
        if not added.isdigit() or not deleted.isdigit():
            fail("unparseable numstat: " + path)
        text_lines += int(added) + int(deleted)

    for raw in git("ls-files", "--others", "--exclude-standard").splitlines():
        path = normalize(raw)
        if not path:
            continue
        if path not in ALLOWED:
            fail("unknown path: " + path)
        if path in rows or path in AUTHORIZED:
            continue
        blob = Path(path).read_bytes()
        if b"\0" in blob:
            fail("unauthorized binary: " + path)
        text_lines += count_lines(blob)

    if text_lines > MAX_TEXT_LINES:
        fail("text_lines=%s exceeds %s" % (text_lines, MAX_TEXT_LINES))

    for path, (size, sha, source) in AUTHORIZED.items():
        if path not in seen_binary:
            fail("unauthorized binary or missing binary numstat: " + path)
        blob = Path(path).read_bytes()
        digest = hashlib.sha256(blob).hexdigest()
        if len(blob) != size or digest != sha:
            fail("binary mismatch: " + path)
        print("binary %s size=%s sha256=%s source=%s" % (path, len(blob), digest, source))

    print("text_lines=%s" % text_lines)
    print("OK")


if __name__ == "__main__":
    main()
