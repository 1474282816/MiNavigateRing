#!/usr/bin/env python3
"""Verify and record the signed-package identity for MiNavigateRing.

Run from the repo root: ``python scripts/verify-package-identity.py`` really
builds (Gradle unit test + assembleDebug, explicitly excluding
generateDebugSigning, then the existing RPK build script), verifies the signing
identity across certificate.pem, debug.jks, the fresh APK, the installed
baseline APK and the RPK, and overwrites ``docs/package-identity.md`` with the
values it just computed.

``--check`` is read-only: no Gradle, no RPK build, no report write. It recomputes
the identity from disk and requires the recorded report to agree.

CLI only: importing this module starts no process and writes no file.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Constants fixed by the work order.
BASELINE = "dc70a4280ba9df65d56a08c0890227447b491c51"
JKS_PATH = REPO_ROOT / "app" / "signing" / "debug.jks"
JKS_SHA256 = "1ff650dc756372107cd51bb73a683fbe53a36fd4c3308986de1ade2aaf2746a1"
CERT_PATH = REPO_ROOT / "app" / "signing" / "certificate.pem"
CERT_DER_SHA256 = "b5e1f4b324081de1c9e6abc5662685f5dc00f19bd646b1eaa49ce7987ae5eb1e"
PRIVATE_PEM_PATH = REPO_ROOT / "app" / "signing" / "private.pem"
BASELINE_APK_PATH = Path(
    "C:/program1/MiNavigateRing/deliverables/MiNavigateRing-Probe-0.1.0.apk"
)
BASELINE_APK_SHA256 = (
    "f516de2e28a14c2338a376f2ae97f0ea37f9e4a7bca7bd68c1d1e297b4b4bc31"
)
ICONS_DIR = REPO_ROOT / "band-app" / "src" / "common" / "images"
ICON_NAMES = [
    "arrive.png", "keep-left.png", "keep-right.png", "left.png", "right.png",
    "roundabout-enter.png", "roundabout-exit.png", "sharp-left.png",
    "sharp-right.png", "slight-left.png", "slight-right.png", "straight.png",
    "uturn-left.png", "uturn-right.png",
]
APK_PATH = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
MANIFEST_PATH = REPO_ROOT / "band-app" / "src" / "manifest.json"
DIST_DIR = REPO_ROOT / "band-app" / "dist"
RPK_RECORD_PATH = DIST_DIR / "rpk-build-record.json"
REPORT_PATH = REPO_ROOT / "docs" / "package-identity.md"
EXPECTED_APPLICATION_ID = "com.minavigatering.probe"
EXPECTED_VERSION_CODE = "1"
EXPECTED_VERSION_NAME = "0.1.0"

GRADLE_ARGV = [
    "java", "-classpath", "gradle/wrapper/gradle-wrapper.jar",
    "org.gradle.wrapper.GradleWrapperMain", ":app:testDebugUnitTest",
    "assembleDebug", "-x", "generateDebugSigning", "--no-daemon",
]
GRADLE_TIMEOUT_MS = 600000
RPK_ARGV = ["node", "scripts/build-rpk.js"]
RPK_TIMEOUT_MS = 480000

BADGING_RE = re.compile(
    r"name='([^']*)'\s+versionCode='([^']*)'\s+versionName='([^']*)'"
)
CERT_DIGEST_RE = re.compile(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)")

REPORT_KEYS = [
    "baseline", "head", "build_started_unix", "icon_count", "jks_sha256",
    "cert_der_sha256", "gradle_argv", "gradle_exit", "apk_path", "apk_bytes",
    "apk_sha256", "application_id", "apk_version_code", "apk_version_name",
    "apk_cert_sha256", "baseline_apk_path", "baseline_apk_sha256",
    "baseline_apk_cert_sha256", "rpk_argv", "rpk_exit", "rpk_path", "rpk_bytes",
    "rpk_sha256", "rpk_package", "rpk_version_name", "rpk_version_code",
    "rpk_contains_project_cert",
]
STATUS_LINES = [
    "signature_build=verified",
    "node_rpk_simulation=not_run",
    "device_verification=unverified",
    "qemu_firmware=unverified",
    "amap_live=unverified",
    "watchface_background=unverified",
    "physical_icons=unverified",
    "install_performed=no",
]
FORBIDDEN_STDERR_SUBSTRINGS = ["storePassword", "keyPassword", "PRIVATE KEY"]


class Failure(Exception):
    """A verification step failed; the message explains why."""


def sha256_of_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cert_der_sha256():
    """SHA-256 of certificate.pem's DER body (PEM header/footer stripped)."""
    text = CERT_PATH.read_text(encoding="ascii")
    body = [line for line in text.splitlines() if not line.startswith("-----")]
    der = base64.b64decode("".join(body))
    return hashlib.sha256(der).hexdigest(), der


def candidate_sdk_roots():
    """SDK root candidates: ANDROID_HOME, ANDROID_SDK_ROOT, local.properties
    sdk.dir (if declared)."""
    roots = []
    for env_name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(env_name)
        if value:
            roots.append(Path(value).resolve())
    local_properties = REPO_ROOT / "local.properties"
    if local_properties.is_file():
        text = local_properties.read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            line = line.strip()
            if line.startswith("sdk.dir"):
                value = line.partition("=")[2].strip()
                if value:
                    roots.append(Path(value).resolve())
    return roots


def find_under_build_tools(relative_glob):
    """Return the lexicographically-last match across all SDK build-tools."""
    found = []
    for root in candidate_sdk_roots():
        build_tools = root / "build-tools"
        if not build_tools.is_dir():
            continue
        for candidate in build_tools.glob(relative_glob):
            if candidate.is_file():
                found.append(str(candidate))
    return sorted(found)[-1] if found else None


def git(*args):
    return subprocess.run(
        ["git", *args], cwd=str(REPO_ROOT), shell=False,
        capture_output=True, text=True,
    )


def run_argv(argv, timeout_ms):
    return subprocess.run(
        argv, cwd=str(REPO_ROOT), shell=False, capture_output=True,
        text=True, timeout=timeout_ms / 1000.0,
    )


def verify_baseline_untouched():
    proc = git("diff", "--exit-code", BASELINE, "--", "app", "band-app/src")
    if proc.returncode != 0:
        raise Failure("git diff --exit-code {} -- app band-app/src returned {}\n{}".format(
            BASELINE, proc.returncode, proc.stdout))
    tree = git("ls-tree", "-r", "--name-only", BASELINE, "--", "band-app/src/common/images")
    if tree.returncode != 0:
        raise Failure("git ls-tree failed: {}".format(tree.stderr))
    tree_paths = [line.strip() for line in tree.stdout.splitlines() if line.strip()]
    expected = ["band-app/src/common/images/" + name for name in sorted(ICON_NAMES)]
    if sorted(tree_paths) != expected:
        raise Failure("baseline icon set mismatch: {} != {}".format(sorted(tree_paths), expected))
    for name in ICON_NAMES:
        icon = ICONS_DIR / name
        if not icon.is_file() or icon.stat().st_size <= 0:
            raise Failure("icon missing or empty in worktree: {}".format(icon))


def check_signing_inputs():
    if not JKS_PATH.is_file():
        raise Failure("missing keystore: {}".format(JKS_PATH))
    jks_digest = sha256_of_file(JKS_PATH)
    if jks_digest != JKS_SHA256:
        raise Failure("JKS sha256 mismatch: {} != {}".format(jks_digest, JKS_SHA256))
    if not CERT_PATH.is_file():
        raise Failure("missing certificate: {}".format(CERT_PATH))
    der_digest, der = cert_der_sha256()
    if der_digest != CERT_DER_SHA256:
        raise Failure("certificate DER sha256 mismatch: {} != {}".format(der_digest, CERT_DER_SHA256))
    if not PRIVATE_PEM_PATH.is_file():
        raise Failure("private.pem is missing: {}".format(PRIVATE_PEM_PATH))
    return jks_digest, der_digest, der


def apksigner_verify(apksigner_jar, apk_path):
    proc = run_argv(
        ["java", "-jar", apksigner_jar, "verify", "--print-certs", str(apk_path)], 120000)
    if proc.returncode != 0:
        raise Failure("apksigner verify failed for {} exit {}\n{}\n{}".format(
            apk_path, proc.returncode, proc.stdout, proc.stderr))
    match = CERT_DIGEST_RE.search(proc.stdout)
    if not match:
        raise Failure("cannot parse Signer #1 certificate SHA-256 digest")
    return match.group(1).lower()


def aapt_badging(aapt, apk_path):
    proc = run_argv([aapt, "dump", "badging", str(apk_path)], 60000)
    if proc.returncode != 0:
        raise Failure("aapt dump badging failed for {} exit {}\n{}".format(
            apk_path, proc.returncode, proc.stderr))
    lines = proc.stdout.splitlines()
    first_line = lines[0] if lines else ""
    match = BADGING_RE.search(first_line)
    if not match:
        raise Failure("cannot parse badging first line: {!r}".format(first_line))
    return match.group(1), match.group(2), match.group(3)


def run_gradle_build():
    try:
        return run_argv(GRADLE_ARGV, GRADLE_TIMEOUT_MS).returncode
    except subprocess.TimeoutExpired:
        raise Failure("Gradle build timed out after {} ms".format(GRADLE_TIMEOUT_MS))


def run_rpk_build():
    try:
        return run_argv(RPK_ARGV, RPK_TIMEOUT_MS).returncode
    except subprocess.TimeoutExpired:
        raise Failure("RPK build timed out after {} ms".format(RPK_TIMEOUT_MS))


def read_manifest():
    data = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    version_name, version_code, package = data["versionName"], data["versionCode"], data["package"]
    if not isinstance(version_name, str) or not version_name:
        raise Failure("manifest versionName is not a non-empty string")
    if not isinstance(version_code, int) or version_code <= 0:
        raise Failure("manifest versionCode is not a positive integer")
    return package, version_name, version_code


def collect_rpk_identity(der):
    """Read the RPK record + file and verify identity against the DER."""
    package, version_name, version_code = read_manifest()
    if not RPK_RECORD_PATH.is_file():
        raise Failure("missing RPK build record: {}".format(RPK_RECORD_PATH))
    record = json.loads(RPK_RECORD_PATH.read_text(encoding="utf-8"))
    if record.get("result") != "success":
        raise Failure("RPK record result is {!r}".format(record.get("result")))
    if record.get("aiotExitCode") != 0:
        raise Failure("RPK record aiotExitCode is {!r}".format(record.get("aiotExitCode")))
    if record.get("versionName") != version_name or record.get("versionCode") != version_code:
        raise Failure("RPK record version {}/{} != manifest {}/{}".format(
            record.get("versionName"), record.get("versionCode"), version_name, version_code))
    if record.get("usedProjectCert") is not True:
        raise Failure("RPK record usedProjectCert is not true")
    expected_name = "{}.debug.{}.rpk".format(package, version_name)
    rpk_records = record.get("rpkPath")
    if not rpk_records:
        raise Failure("RPK record rpkPath is missing")
    rpk_path = REPO_ROOT / rpk_records
    if rpk_path.name != expected_name:
        raise Failure("RPK filename {} is not {}".format(rpk_path.name, expected_name))
    matches = [p for p in DIST_DIR.glob("*.rpk") if p.name == expected_name]
    if len(matches) != 1:
        raise Failure("expected exactly one {} in dist, found {}".format(expected_name, len(matches)))
    rpk_bytes = rpk_path.read_bytes()
    rpk_digest = hashlib.sha256(rpk_bytes).hexdigest()
    if record.get("bytes") != len(rpk_bytes):
        raise Failure("RPK record bytes {} != file {}".format(record.get("bytes"), len(rpk_bytes)))
    if record.get("sha256") != rpk_digest:
        raise Failure("RPK record sha256 {} != file {}".format(record.get("sha256"), rpk_digest))
    diagnostic = record.get("diagnosticRpkPath")
    if diagnostic:
        diag_path = REPO_ROOT / diagnostic
        if not diag_path.is_file():
            raise Failure("diagnosticRpkPath not on disk: {}".format(diag_path))
        if sha256_of_file(diag_path) != rpk_digest:
            raise Failure("diagnostic RPK sha256 differs from main RPK")
    if der not in rpk_bytes:
        raise Failure("RPK bytes do not contain the project certificate DER")
    return {
        "rpk_path": str(Path("band-app") / "dist" / expected_name),
        "rpk_bytes": len(rpk_bytes),
        "rpk_sha256": rpk_digest,
        "rpk_package": package,
        "rpk_version_name": version_name,
        "rpk_version_code": version_code,
    }


def read_head():
    proc = git("rev-parse", "HEAD")
    if proc.returncode != 0:
        raise Failure("git rev-parse HEAD failed: {}".format(proc.stderr))
    return proc.stdout.strip()


def build_report(values):
    lines = [
        "# 签名打包身份核验报告",
        "",
        "本报告由 `scripts/verify-package-identity.py` 默认模式覆盖生成，"
        "机器记录段只用本轮计算值，不含手抄旧文档哈希。",
        "",
        "## 机器记录",
        "",
        "```",
    ]
    lines += ["{}={}".format(key, values[key]) for key in REPORT_KEYS]
    lines += ["```", "", "## 固定状态", "", "```"]
    lines += STATUS_LINES
    lines += ["```", "", "## 说明", ""]
    lines += [
        "本票只验证签名构建身份：`signature_build=verified` 表示 certificate.pem 的 "
        "DER SHA-256、debug.jks 的 SHA-256、新构建 APK 的签名证书以及已安装基线 APK "
        "的签名证书四者一致，且 APK 通过 `apksigner verify --print-certs`（退出码 0）。",
        "",
        "未运行 `band-app/test/check-built-rpk.js` 或任何宿主/QEMU 模拟，"
        "`node_rpk_simulation=not_run`；Node 侧 RPK 构建只证明 RPK 字节包含同一项目证书 "
        "DER，其模拟结果不能当作固件或真机通过。",
        "",
        "未连接设备，未执行 `adb install`、`adb shell` 或 BLE，`install_performed=no`、"
        "`device_verification=unverified`。",
        "",
        "原生 QEMU/固件兼容（`qemu_firmware=unverified`）、真实高德 "
        "（`amap_live=unverified`）、回表盘/长期后台（`watchface_background=unverified`）、"
        "物理图标可见性（`physical_icons=unverified`）均为 unverified。",
        "",
        "安装前的新包模拟验证门槛由 L3 后续独立检查，打包完成不豁免此门槛。",
        "",
        "`baseline_apk_sha256` 是定位已安装基线的定位值，不是本票 APK 或 RPK 的哈希；"
        "本票产物身份以上表中的 `apk_sha256` 与 `rpk_sha256` 为准。",
        "",
    ]
    return "\n".join(lines)


def do_default():
    build_started_unix = time.time()
    verify_baseline_untouched()
    jks_digest, der_digest, der = check_signing_inputs()

    # Remove a possibly up-to-date APK so the file recorded is truly produced now
    # and the --check freshness gate (mtime >= build_started_unix - 5s) is real.
    if APK_PATH.is_file():
        APK_PATH.unlink()

    gradle_exit = run_gradle_build()
    if gradle_exit != 0:
        raise Failure("Gradle build failed with exit code {}".format(gradle_exit))
    if not APK_PATH.is_file():
        raise Failure("APK not found after build: {}".format(APK_PATH))

    apksigner_jar = find_under_build_tools("*/lib/apksigner.jar")
    if apksigner_jar is None:
        raise Failure("apksigner.jar not found under any SDK root")
    aapt = find_under_build_tools("*/aapt.exe")
    if aapt is None:
        raise Failure("aapt.exe not found under any SDK root")

    apk_cert = apksigner_verify(apksigner_jar, APK_PATH)
    if apk_cert != der_digest:
        raise Failure("APK certificate {} != certificate.pem DER {}".format(apk_cert, der_digest))
    application_id, version_code, version_name = aapt_badging(aapt, APK_PATH)
    if application_id != EXPECTED_APPLICATION_ID:
        raise Failure("APK applicationId {!r} != {!r}".format(application_id, EXPECTED_APPLICATION_ID))
    if version_code != EXPECTED_VERSION_CODE:
        raise Failure("APK versionCode {!r} != {!r}".format(version_code, EXPECTED_VERSION_CODE))
    if version_name != EXPECTED_VERSION_NAME:
        raise Failure("APK versionName {!r} != {!r}".format(version_name, EXPECTED_VERSION_NAME))

    apk_bytes = APK_PATH.read_bytes()
    apk_digest = hashlib.sha256(apk_bytes).hexdigest()
    if der not in apk_bytes:
        raise Failure("APK bytes do not contain the project certificate DER")
    if apk_digest == BASELINE_APK_SHA256:
        raise Failure("new APK sha256 equals the installed baseline APK sha256")

    if not BASELINE_APK_PATH.is_file():
        raise Failure("baseline APK missing: {}".format(BASELINE_APK_PATH))
    baseline_digest = sha256_of_file(BASELINE_APK_PATH)
    if baseline_digest != BASELINE_APK_SHA256:
        raise Failure("baseline APK sha256 {} != {}".format(baseline_digest, BASELINE_APK_SHA256))
    baseline_cert = apksigner_verify(apksigner_jar, BASELINE_APK_PATH)
    if baseline_cert != der_digest:
        raise Failure("baseline APK certificate {} != DER {}".format(baseline_cert, der_digest))

    # Remove a possibly stale RPK for the same freshness reason as the APK.
    _, manifest_version_name, _ = read_manifest()
    stale_rpk = DIST_DIR / "{}.debug.{}.rpk".format(EXPECTED_APPLICATION_ID, manifest_version_name)
    if stale_rpk.is_file():
        stale_rpk.unlink()

    rpk_exit = run_rpk_build()
    if rpk_exit != 0:
        raise Failure("RPK build failed with exit code {}".format(rpk_exit))
    rpk = collect_rpk_identity(der)

    values = {
        "baseline": BASELINE,
        "head": read_head(),
        "build_started_unix": repr(build_started_unix),
        "icon_count": len(ICON_NAMES),
        "jks_sha256": jks_digest,
        "cert_der_sha256": der_digest,
        "gradle_argv": " ".join(GRADLE_ARGV),
        "gradle_exit": gradle_exit,
        "apk_path": str(Path("app") / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"),
        "apk_bytes": len(apk_bytes),
        "apk_sha256": apk_digest,
        "application_id": application_id,
        "apk_version_code": version_code,
        "apk_version_name": version_name,
        "apk_cert_sha256": apk_cert,
        "baseline_apk_path": str(BASELINE_APK_PATH),
        "baseline_apk_sha256": baseline_digest,
        "baseline_apk_cert_sha256": baseline_cert,
        "rpk_argv": " ".join(RPK_ARGV),
        "rpk_exit": rpk_exit,
        "rpk_path": rpk["rpk_path"],
        "rpk_bytes": rpk["rpk_bytes"],
        "rpk_sha256": rpk["rpk_sha256"],
        "rpk_package": rpk["rpk_package"],
        "rpk_version_name": rpk["rpk_version_name"],
        "rpk_version_code": rpk["rpk_version_code"],
        "rpk_contains_project_cert": "true",
    }
    report = build_report(values)
    for needle in FORBIDDEN_STDERR_SUBSTRINGS:
        if needle in report:
            raise Failure("report would contain forbidden substring {!r}".format(needle))
    REPORT_PATH.write_text(report, encoding="utf-8")
    for key in REPORT_KEYS:
        print("{}={}".format(key, values[key]))
    print("OK")
    return 0


def parse_report(path):
    text = path.read_text(encoding="utf-8")
    values = {}
    for line in text.splitlines():
        line = line.strip()
        if "=" in line and not line.startswith("#"):
            key, _, value = line.partition("=")
            key = key.strip()
            if key in REPORT_KEYS and key not in values:
                values[key] = value.strip()
    return text, values


def do_check():
    if not REPORT_PATH.is_file():
        raise Failure("report missing: {}".format(REPORT_PATH))
    report_text, values = parse_report(REPORT_PATH)
    for key in REPORT_KEYS:
        if key not in values:
            raise Failure("report missing key {}".format(key))
    for line in STATUS_LINES:
        if line not in report_text:
            raise Failure("report missing status line {}".format(line))
    for marker in ("install_performed=no", "打包完成不豁免"):
        if marker not in report_text:
            raise Failure("report missing required text: {}".format(marker))
    if values["gradle_exit"] != "0":
        raise Failure("report gradle_exit is {}".format(values["gradle_exit"]))
    if values["rpk_exit"] != "0":
        raise Failure("report rpk_exit is {}".format(values["rpk_exit"]))

    verify_baseline_untouched()
    jks_digest, der_digest, der = check_signing_inputs()
    if values["jks_sha256"] != jks_digest:
        raise Failure("report jks_sha256 != on-disk")
    if values["cert_der_sha256"] != der_digest:
        raise Failure("report cert_der_sha256 != on-disk")

    apksigner_jar = find_under_build_tools("*/lib/apksigner.jar")
    if apksigner_jar is None:
        raise Failure("apksigner.jar not found under any SDK root")
    aapt = find_under_build_tools("*/aapt.exe")
    if aapt is None:
        raise Failure("aapt.exe not found under any SDK root")
    if not APK_PATH.is_file():
        raise Failure("APK missing: {}".format(APK_PATH))

    apk_cert = apksigner_verify(apksigner_jar, APK_PATH)
    apk_digest = sha256_of_file(APK_PATH)
    apk_size = APK_PATH.stat().st_size
    if values["apk_sha256"] != apk_digest:
        raise Failure("report apk_sha256 != on-disk")
    if values["apk_bytes"] != str(apk_size):
        raise Failure("report apk_bytes != on-disk")
    if values["apk_cert_sha256"] != apk_cert:
        raise Failure("report apk_cert_sha256 != on-disk")
    application_id, version_code, version_name = aapt_badging(aapt, APK_PATH)
    if values["application_id"] != application_id:
        raise Failure("report application_id != on-disk")
    if values["apk_version_code"] != version_code:
        raise Failure("report apk_version_code != on-disk")
    if values["apk_version_name"] != version_name:
        raise Failure("report apk_version_name != on-disk")

    if not BASELINE_APK_PATH.is_file():
        raise Failure("baseline APK missing: {}".format(BASELINE_APK_PATH))
    baseline_digest = sha256_of_file(BASELINE_APK_PATH)
    if baseline_digest != BASELINE_APK_SHA256:
        raise Failure("baseline APK sha256 != expected")
    baseline_cert = apksigner_verify(apksigner_jar, BASELINE_APK_PATH)
    if values["baseline_apk_sha256"] != baseline_digest:
        raise Failure("report baseline_apk_sha256 != on-disk")
    if values["baseline_apk_cert_sha256"] != baseline_cert:
        raise Failure("report baseline_apk_cert_sha256 != on-disk")

    rpk = collect_rpk_identity(der)
    if values["rpk_path"] != rpk["rpk_path"]:
        raise Failure("report rpk_path != on-disk")
    if values["rpk_sha256"] != rpk["rpk_sha256"]:
        raise Failure("report rpk_sha256 != on-disk")
    if values["rpk_bytes"] != str(rpk["rpk_bytes"]):
        raise Failure("report rpk_bytes != on-disk")
    if values["rpk_package"] != rpk["rpk_package"]:
        raise Failure("report rpk_package != on-disk")
    if values["rpk_version_name"] != rpk["rpk_version_name"]:
        raise Failure("report rpk_version_name != on-disk")
    if values["rpk_version_code"] != str(rpk["rpk_version_code"]):
        raise Failure("report rpk_version_code != on-disk")

    floor = float(values["build_started_unix"]) - 5.0
    if APK_PATH.stat().st_mtime < floor:
        raise Failure("APK mtime is older than build_started_unix - 5s")
    rpk_abs = REPO_ROOT / rpk["rpk_path"]
    if rpk_abs.stat().st_mtime < floor:
        raise Failure("RPK mtime is older than build_started_unix - 5s")

    print("check-ok")
    return 0


def main(argv):
    args = argv[1:]
    message = None
    try:
        if args == ["--check"]:
            code = do_check()
        elif args == []:
            code = do_default()
        else:
            print("usage: python scripts/verify-package-identity.py [--check]")
            return 2
    except Failure as exc:
        message = str(exc)
        code = 1
    if message and not any(n in message for n in FORBIDDEN_STDERR_SUBSTRINGS):
        sys.stderr.write(message + "\n")
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
