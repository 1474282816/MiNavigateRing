#!/usr/bin/env python3
"""Honor (HONOR AAP-AN00) helper-phone probe for the MiNavigateRing V0 gate.

Installs the existing debug APK on one *explicitly identified* phone, records the
real exit codes, and appends the result to ``docs/honor-win-probe.md`` as a
matrix keeping three columns apart: Honor phone / K90 phone / band communication.

Safety rules (work order W-615): every ``adb`` call goes through
:func:`run_adb` (explicit ``argv``, ``shell=False``, no ``cmd /c``, no pipes,
always a hard ``timeout``); targets are chosen by *device attributes* only, and
all three of ro.serialno/brand/model must match at once; only the single matched
transport may receive install/am/pm/dumpsys; no ``pm grant``, no ``input``, no
screen lock, no licence dialogs, no uninstall / unbind / factory reset.

Import-safe: importing never touches ``adb`` and never runs :func:`main`.

Exit codes: ``0`` only when a matched device was really installed and started via
this run's ADB calls. No matching device, or a ``getprop`` query timeout, ends
with exit code ``2`` and does **not** mean the install succeeded.
"""

from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, NamedTuple, Sequence

# --- Target identity -------------------------------------------------------

TARGET_SERIAL = os.environ.get("MIRING_HONOR_SERIAL", "").strip()
MISSING_TARGET_SERIAL_EXIT = 2
TARGET_BRAND = "HONOR"
TARGET_MODEL = "AAP-AN00"

MDNS_SERIAL_PREFIX = "adb-" + TARGET_SERIAL
MDNS_SERVICE_SUFFIX = "_adb-tls-connect._tcp"

ANDROID_PACKAGE = "com.minavigatering.probe"
MAIN_COMPONENT = ANDROID_PACKAGE + "/.MainActivity"
COMPANION_PACKAGE = "com.mi.health"
WEARABLE_PACKAGE = "com.xiaomi.wearable"

ADB = "adb"

# --- Paths -----------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parent.parent
APK_PATH = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
DOC_PATH = REPO_ROOT / "docs" / "honor-win-probe.md"

# --- APK identity gate -----------------------------------------------------
# The expected hash is never hard-coded here: it is read from the identity file
# written when the APK was metered (W-619) and then compared with the real bytes.
IDENTITY_PATH = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "apk-identity.txt"

# The previously delivered (stale) package: never install this one again.
OLD_APK_SHA256 = "ae1d1cd04aa9c9dadc824db7dcd644a05d47cd8e66be76a6e89e325bef1380a6"

# --- Timeouts (ms): every adb call gets one; a timeout counts as failure -----

TIMEOUT_DEVICES_MS = 12000
TIMEOUT_GETPROP_MS = 12000
TIMEOUT_INSTALL_MS = 90000
TIMEOUT_AM_START_MS = 20000
TIMEOUT_DUMPSYS_MS = 12000

UNVERIFIED = "unverified"


# --- Low level adb wrapper -------------------------------------------------


class AdbCall(NamedTuple):
    """Result of one ``adb`` invocation."""

    argv: tuple
    returncode: "int | None"
    stdout: str
    stderr: str
    timed_out: bool

    @property
    def ok(self) -> bool:
        return (not self.timed_out) and self.returncode == 0

    @property
    def exit_code_text(self) -> str:
        if self.timed_out:
            return "timeout"
        if self.returncode is None:
            return "not-executed"
        return str(self.returncode)

    def describe(self, limit: int = 40) -> str:
        """Short rendering used in the evidence document."""
        bits = ["TIMEOUT"] if self.timed_out else []
        bits += [f"{name}={_collapse(text, limit)}" for name, text in
                 (("stderr", self.stderr), ("stdout", self.stdout)) if text.strip()]
        return " | ".join(bits) if bits else "(no output)"


def _collapse(text: str, limit: int) -> str:
    lines = [line for line in text.splitlines() if line.strip()]
    joined = " / ".join(lines[:limit])
    if len(lines) > limit:
        joined += f" / ...(+{len(lines) - limit} more lines)"
    return joined


def run_adb(argv: Sequence[str], timeout_ms: int) -> AdbCall:
    """Run ``adb`` from an argv list, with ``shell=False`` and a timeout.

    This is the *only* place in the module that spawns a process.
    """
    argv = tuple(str(part) for part in argv)
    try:
        proc = subprocess.run(
            list(argv), shell=False, capture_output=True, text=True,
            timeout=timeout_ms / 1000.0,
        )
    except subprocess.TimeoutExpired as exc:
        to_text = lambda raw: raw.decode("utf-8", "replace") if isinstance(raw, bytes) else (raw or "")
        return AdbCall(argv, None, to_text(exc.stdout), to_text(exc.stderr), True)
    except OSError as exc:  # adb missing, permission denied, ...
        return AdbCall(argv, None, "", f"OSError: {exc}", False)
    return AdbCall(argv, proc.returncode, proc.stdout or "", proc.stderr or "", False)


# --- Pure helpers (imported by tests) --------------------------------------


def parse_devices(devices_output: str) -> "list[tuple[str, str]]":
    """Parse ``adb devices -l`` output into ``(serial, state)`` pairs.

    Keeps every listed transport (any state) in the order adb reported it.
    Daemon chatter lines (``adb server ...``, ``* daemon started``) are dropped.
    """
    pairs: "list[tuple[str, str]]" = []
    for raw_line in devices_output.splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("List of devices attached", "*")):
            continue
        if line.startswith(("adb server", "adb daemon")) or "daemon started" in line:
            continue
        parts = line.split()
        if len(parts) >= 2:
            pairs.append((parts[0], parts[1]))
    return pairs


def is_target_mdns_serial(serial: str) -> bool:
    """True for the mDNS transport of the target phone."""
    if not TARGET_SERIAL:
        return False
    return serial.startswith(MDNS_SERIAL_PREFIX) and MDNS_SERVICE_SUFFIX in serial


def order_transports(devices: "Iterable[Sequence[str]] | str") -> "list[tuple[str, str]]":
    """Order device-state transports: target mDNS first, everything else after.

    Non-``device`` states (``offline``, ``unauthorized``, ...) are dropped —
    they cannot answer a ``getprop`` query.
    """
    if isinstance(devices, str):
        pairs = parse_devices(devices)
    else:
        pairs = [(str(pair[0]), str(pair[1])) for pair in devices]

    usable = [(serial, state) for serial, state in pairs if state == "device"]
    mdns_first = [item for item in usable if is_target_mdns_serial(item[0])]
    others = [item for item in usable if not is_target_mdns_serial(item[0])]
    return mdns_first + others


_PROP_LINE = re.compile(r"^\[(?P<key>[^\]]+)\]:\s*\[(?P<value>.*)\]\s*$")


def parse_props(raw: str) -> "dict[str, str]":
    """Parse ``getprop`` output (``[key]: [value]``) or ``key=value`` text."""
    props: "dict[str, str]" = {}
    for raw_line in raw.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        match = _PROP_LINE.match(line)
        if match:
            props[match.group("key")] = match.group("value")
            continue
        if "=" in line and not line.startswith("["):
            key, _, value = line.partition("=")
            props[key.strip()] = value.strip()
    return props


def identity_matches(props: "dict[str, str]") -> bool:
    """All three identity attributes must match at the same time."""
    if not TARGET_SERIAL:
        return False
    return (
        props.get("ro.serialno", "").strip() == TARGET_SERIAL
        and props.get("ro.product.brand", "").strip() == TARGET_BRAND
        and props.get("ro.product.model", "").strip() == TARGET_MODEL
    )


def apk_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


IDENTITY_FIELDS = ("package", "versionCode", "versionName", "sha256")
IDENTITY_OK_LINE = "OK"
_SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")

def identity_gate(record_text: str, apk_sha256: str) -> "tuple[bool, str]":
    """Decide whether an APK really is the metered new package.

    Pure: reads no file, spawns no process, writes nothing. ``apk_sha256`` is
    the hash of the actual APK bytes as computed by the caller; the *expected*
    value comes from the record under test, never from a constant here.

    Returns ``(True, "ok")`` only when every field matches; otherwise a short
    ``(False, reason)`` pair.
    """
    fields: "dict[str, str]" = {}
    ok_line = False
    for raw_line in record_text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line == IDENTITY_OK_LINE:
            ok_line = True
            continue
        for key in IDENTITY_FIELDS:
            prefix = key + "="
            if line.startswith(prefix) and key not in fields:
                value = line[len(prefix):].strip()
                if value:
                    fields[key] = value
                break

    if not ok_line:
        return False, "missing OK line"
    for key in IDENTITY_FIELDS:
        if key not in fields:
            return False, f"missing {key}"
    if fields["package"] != ANDROID_PACKAGE:
        return False, "unexpected package"
    if fields["versionCode"] != "1":
        return False, "unexpected versionCode"
    if fields["versionName"] != "0.1.0":
        return False, "unexpected versionName"
    record_sha = fields["sha256"]
    if not _SHA256_HEX.match(record_sha):
        return False, "malformed sha256"
    if record_sha == OLD_APK_SHA256:
        return False, "stale apk"
    if record_sha != apk_sha256:
        return False, "sha256 mismatch"
    return True, "ok"

def extract_version_name(dumpsys_output: str) -> str:
    match = re.search(r"^\s*versionName=(\S+)\s*$", dumpsys_output, re.MULTILINE)
    return match.group(1) if match else "(not parsed)"


# --- Document rendering ----------------------------------------------------

DOC_HEADER = """# 荣耀辅助机（HONOR AAP-AN00）手机端探针证据

本文件由 `scripts/honor-phone-probe.py` 追加写入：每次运行新增一节，**不覆盖旧证据**。

三列证据互相独立，不得互相替代：

| 列 | 含义 |
|---|---|
| 荣耀手机端 | 工单选中的 HONOR 辅助机（ro.serialno 为 `<HONOR_SERIAL>`）上的手机端事实 |
| K90 手机端 | 目标机 K90；本单未在其上执行任何命令 |
| 手环通信 | 手环 9 的绑定、消息通道与后台能力；本单不执行、不推断 |

`unverified` 一律表示**本次运行没有取得对应证据**，不表示通过，也不表示失败。
"""

MATRIX_ITEMS = (
    "安装（`adb install -r`）",
    "手机锁屏",
    "手环熄屏",
    "回表盘后继续更新",
    "震动",
    "5 秒心跳",
    "15 秒超时",
    "通信恢复后最新状态",
)

def _marketname_note(run: "dict") -> str:
    """Render the marketing-name note from observed properties only.

    An empty value keeps the existing "marketing name unproven" wording; a
    non-empty value is quoted verbatim and still never counts as band binding.
    """
    marketname = ""
    for probe_entry in run.get("probes") or []:
        if probe_entry.get("serial") == run.get("selected_serial"):
            marketname = ((probe_entry.get("props") or {}).get("ro.product.marketname") or "").strip()
            break
    if not marketname:
        return (
            "本次运行未取得荣耀设备上 `ro.product.marketname` 的非空取值，"
            "因此与荣耀WIN的营销名对应仍未由属性证实。"
        )
    return (
        f"荣耀设备本次返回 `ro.product.marketname={marketname}`（照录属性值）。"
        "该属性只是手机端型号事实，**不表示手环已绑定**，也不构成手环通信证据。"
    )


def _cell(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ").strip() or UNVERIFIED


def _device_table(devices) -> str:
    if not devices:
        return "本次运行没有解析到任何 ADB 传输。\n"
    rows = "\n".join(
        f"| {i} | `{_cell(s)}` | {_cell(st)} |"
        for i, (s, st) in enumerate(devices, start=1)
    )
    return "| 顺序 | ADB 序列 | state |\n|---|---|---|\n" + rows + "\n"


def _probe_table(probes) -> str:
    if not probes:
        return ""
    out = [
        "### 身份查询结果",
        "",
        "| ADB 序列 | 判定 | ro.serialno | ro.product.brand | ro.product.model "
        "| ro.product.marketname | ro.build.version.release | ro.build.display.id |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for probe in probes:
        props = probe.get("props") or {}
        cells = [
            _cell(probe.get("serial", "")),
            _cell(probe.get("verdict", "")),
            _cell(props.get("ro.serialno", "(未返回)")),
            _cell(props.get("ro.product.brand", "(未返回)")),
            _cell(props.get("ro.product.model", "(未返回)")),
            _cell(props.get("ro.product.marketname", "") or "(空)"),
            _cell(props.get("ro.build.version.release", "(未返回)")),
            _cell(props.get("ro.build.display.id", "(未返回)")),
        ]
        out.append("| `" + cells[0] + "` | " + " | ".join(cells[1:]) + " |")
    return "\n".join(out) + "\n\n"


SECTION_TEMPLATE = """---
## 运行记录 {timestamp}（荣耀辅助机探针）

- 脚本：`scripts/honor-phone-probe.py`；APK：`app/build/outputs/apk/debug/app-debug.apk`
- APK SHA256（本次实测，非前置条件）：{sha}
- APK 身份记录：`app/build/outputs/apk/debug/apk-identity.txt`；闸门判定：{identity_verdict}
- 身份闸门在**任何 adb 调用之前**执行：记录中的 sha256 必须与本次 APK 字节一致，且不得等于旧包哈希 `{old_sha}`
- 目标选择条件：`ro.serialno`=`{serial}` 且 `ro.product.brand`=`{brand}` 且 `ro.product.model`=`{model}`（三项同时成立；传输名相似不作为依据）
- 传输顺序策略：目标 mDNS 传输（`{mdns_prefix}*{mdns_suffix}`）优先，其余 `device` 传输在后

### 传输探测

{device_table}
{probe_table}### 荣耀手机端执行结果

| 项 | 值 |
|---|---|
{honor_rows}

### 手机包事实（不构成手环绑定证据）

| 命令 | 退出码 |
|---|---|
| `dumpsys package {android_pkg}` | {dumpsys_probe_exit} |
| `dumpsys package {companion_pkg}` | {dumpsys_companion_exit} |
| `pm path {wearable_pkg}` | {pm_path_exit} |

{companion_line}
{wearable_line}

### 三列证据矩阵

| 验证项 | 荣耀手机端 | K90手机端 | 手环通信 |
|---|---|---|---|
{matrix_rows}

- 荣耀列的锁屏、熄屏、回表盘、震动、5 秒心跳、15 秒超时、恢复后最新状态均记 {unverified}：MainActivity 未启动 ProbeForegroundService，且本单不得锁屏或接受授权弹窗。
- K90 手机端各列均记 {unverified}：本单未在 K90 上执行任何 adb 命令。
- 手环通信各列均记 {unverified}：本单不执行、不推断手环侧能力。
- 补充说明：{marketname_note}
- 边界声明：以上荣耀手机端结果只覆盖该辅助机的安装与启动，不能代表 K90 手机端或手环通信的最终验收，也不能作为手环侧能力的证据。
- 本次运行进程退出码：**{exit_code}**
"""

def build_doc_section(run: "dict") -> str:
    """Render one appended evidence section from a run record."""
    selected = run.get("selected_serial")
    install_exit = run.get("install_exit", "not-executed")
    sha = (
        f"`{run.get('apk_sha256', '(unknown)')}`"
        if run.get("apk_present")
        else "（APK 缺失，未计算）"
    )
    if selected:
        honor_rows = [
            f"| 选中的 ADB 序列 | `{selected}` |",
            f"| 身份属性 | `ro.serialno={TARGET_SERIAL}`、`ro.product.brand={TARGET_BRAND}`、"
            f"`ro.product.model={TARGET_MODEL}` |",
            f"| `adb -s {selected} install -r app/build/outputs/apk/debug/app-debug.apk` 退出码 | **{install_exit}** |",
            f"| 安装输出 | {_cell(run.get('install_detail', ''))} |",
            f"| `adb -s {selected} shell am start -n {MAIN_COMPONENT}` 退出码 | "
            f"**{run.get('am_start_exit', 'not-executed')}** |",
            f"| am start 输出 | {_cell(run.get('am_start_detail', ''))} |",
            f"| 本次安装包版本 | {run.get('version_name', '(未解析)')} |",
        ]
    else:
        honor_rows = [
            f"| 安装 | 本次运行**没有**匹配到目标设备，因此**未执行安装**；退出码 {UNVERIFIED} |",
            f"| am start | {UNVERIFIED}（未调用 am start） |",
        ]

    companion = run.get("companion_present")
    if companion is True:
        companion_line = (
            f"- `{COMPANION_PACKAGE}` 在本机已安装。**这只是一条手机端包事实，"
            "不能据此认为当前手环已绑定或可以迁移。**"
        )
    elif companion is False:
        companion_line = f"- `{COMPANION_PACKAGE}` 未在本机解析到。"
    else:
        companion_line = f"- `{COMPANION_PACKAGE}` 是否安装：{UNVERIFIED}（未取得证据）"
    wearable_line = (
        f"- `{WEARABLE_PACKAGE}` 路径事实：{_cell(run['wearable_path'])}"
        if run.get("wearable_path")
        else ""
    )

    if selected:
        honor_col = f"已执行，真实退出码 {install_exit}（序列 `{selected}`）"
    elif run.get("identity_verdict") not in ("ok", None):
        honor_col = f"未执行（身份闸门未通过：{run['identity_verdict']}），{UNVERIFIED}"
    elif "exit_code" in run and run.get("exit_code") == 1:
        honor_col = f"未执行（本次运行中止），{UNVERIFIED}"
    else:
        honor_col = "未执行（无匹配设备），" + UNVERIFIED
    matrix_rows = "\n".join(
        f"| {item} | {_cell(honor_col if i == 0 else UNVERIFIED)} | {UNVERIFIED} | {UNVERIFIED} |"
        for i, item in enumerate(MATRIX_ITEMS)
    )
    return "\n" + SECTION_TEMPLATE.format(
        timestamp=run["timestamp"],
        sha=sha,
        identity_verdict=run.get("identity_verdict", UNVERIFIED),
        old_sha=OLD_APK_SHA256,
        serial=TARGET_SERIAL,
        brand=TARGET_BRAND,
        model=TARGET_MODEL,
        mdns_prefix=MDNS_SERIAL_PREFIX,
        mdns_suffix=MDNS_SERVICE_SUFFIX,
        device_table=_device_table(run.get("devices") or []),
        probe_table=_probe_table(run.get("probes") or []),
        honor_rows="\n".join(honor_rows),
        android_pkg=ANDROID_PACKAGE,
        dumpsys_probe_exit=run.get("dumpsys_probe_exit", "not-executed"),
        companion_pkg=COMPANION_PACKAGE,
        dumpsys_companion_exit=run.get("dumpsys_companion_exit", "not-executed"),
        wearable_pkg=WEARABLE_PACKAGE,
        pm_path_exit=run.get("pm_path_wearable_exit", "not-executed"),
        companion_line=companion_line,
        wearable_line=wearable_line,
        marketname_note=_marketname_note(run),
        matrix_rows=matrix_rows,
        unverified=UNVERIFIED,
        exit_code=run.get("exit_code", "unknown"),
    )


def append_doc(section: str) -> None:
    """Append one section, creating the file with its header on first use."""
    DOC_PATH.parent.mkdir(parents=True, exist_ok=True)
    prefix = "" if DOC_PATH.exists() else DOC_HEADER
    with open(DOC_PATH, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(prefix + section)


def _finish(run: "dict", code: int) -> int:
    """Record the exit code, append the evidence section, return the code."""
    run["exit_code"] = code
    append_doc(build_doc_section(run))
    return code


# --- Main flow -------------------------------------------------------------


def main(argv: Sequence[str] = ()) -> int:
    """Probe one explicitly identified Honor phone. Returns a process exit code."""
    run: "dict" = {
        "timestamp": datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S %z"),
        "devices": [],
        "probes": [],
        "selected_serial": None,
        "companion_present": None,
    }

    # The helper phone serial comes from the environment only: without it no
    # identity can be matched, so stop before any adb call and before any
    # evidence section is appended.
    if not TARGET_SERIAL:
        print(
            "ERROR: MIRING_HONOR_SERIAL is unset or empty; "
            "set it to the helper phone ro.serialno.",
            file=sys.stderr,
        )
        return MISSING_TARGET_SERIAL_EXIT

    # APK precondition: a missing APK exits 1 with a stderr note and no install.
    if not APK_PATH.exists():
        run["apk_present"] = False
        print(f"ERROR: debug APK not found at: {APK_PATH}", file=sys.stderr)
        print("       build it first (e.g. ./gradlew :app:assembleDebug).", file=sys.stderr)
        print("       No install was attempted.", file=sys.stderr)
        return _finish(run, 1)

    run["apk_present"] = True
    run["apk_sha256"] = apk_sha256(APK_PATH)

    # Identity gate: before any adb call, prove that this APK is the metered new
    # package (never the stale one). A missing record or a failed gate stops
    # here with exit code 1 and no adb invocation at all.
    if not IDENTITY_PATH.exists():
        run["identity_verdict"] = "identity file missing"
        print(f"ERROR: APK identity record not found at: {IDENTITY_PATH}", file=sys.stderr)
        print("       No adb call was made and no install was attempted.", file=sys.stderr)
        return _finish(run, 1)
    try:
        identity_text = IDENTITY_PATH.read_text(encoding="utf-8")
    except OSError as exc:
        run["identity_verdict"] = f"identity file unreadable: {exc}"
        print(f"ERROR: cannot read APK identity record: {exc}", file=sys.stderr)
        print("       No adb call was made and no install was attempted.", file=sys.stderr)
        return _finish(run, 1)

    run["identity_sha256"] = None
    for raw_line in identity_text.splitlines():
        if raw_line.strip().startswith("sha256="):
            run["identity_sha256"] = raw_line.strip().split("=", 1)[1].strip()
            break

    gate_ok, gate_reason = identity_gate(identity_text, run["apk_sha256"])
    run["identity_verdict"] = gate_reason
    if not gate_ok:
        print(
            f"ERROR: APK identity gate failed ({gate_reason}); "
            "no adb call was made and no install was attempted.",
            file=sys.stderr,
        )
        return _finish(run, 1)

    devices_call = run_adb([ADB, "devices", "-l"], TIMEOUT_DEVICES_MS)
    if not devices_call.ok:
        print(
            f"ERROR: `adb devices -l` failed ({devices_call.exit_code_text}); "
            "no install was attempted.",
            file=sys.stderr,
        )
        return _finish(run, 1)

    ordered = order_transports(parse_devices(devices_call.stdout))
    run["devices"] = ordered

    # Walk the ordered transports; stop at the first one reporting the target
    # identity. A timeout only skips that transport; mismatch never installs.
    selected_props: "dict[str, str] | None" = None
    for serial, _state in ordered:
        call = run_adb([ADB, "-s", serial, "shell", "getprop"], TIMEOUT_GETPROP_MS)
        if call.timed_out:
            verdict, props = "查询超时，跳过该传输", {}
        elif call.returncode != 0:
            verdict, props = f"getprop 失败（退出码 {call.returncode}），跳过该传输", {}
        else:
            props = parse_props(call.stdout)
            verdict = "目标匹配" if identity_matches(props) else "非目标（属性不匹配），未安装"
        run["probes"].append({"serial": serial, "verdict": verdict, "props": props})
        if verdict == "目标匹配":
            run["selected_serial"] = serial
            selected_props = props
            break

    if selected_props is None:
        print("No device matched the target identity; nothing was installed.")
        return _finish(run, 2)

    serial = run["selected_serial"]

    # Device actions: only the single matched transport, in this order.
    install_call = run_adb([ADB, "-s", serial, "install", "-r", str(APK_PATH)], TIMEOUT_INSTALL_MS)
    run["install_exit"] = install_call.exit_code_text
    run["install_detail"] = install_call.describe()
    if not install_call.ok:
        print(
            f"ERROR: install failed on {serial} ({install_call.exit_code_text}); "
            "am start was not attempted.",
            file=sys.stderr,
        )
        run["am_start_exit"] = UNVERIFIED + "（安装失败，未尝试）"
        return _finish(run, 1)

    am_call = run_adb(
        [ADB, "-s", serial, "shell", "am", "start", "-n", MAIN_COMPONENT],
        TIMEOUT_AM_START_MS,
    )
    run["am_start_exit"] = am_call.exit_code_text
    run["am_start_detail"] = am_call.describe()

    def query(subcommand, package, key):
        call = run_adb([ADB, "-s", serial] + subcommand + [package], TIMEOUT_DUMPSYS_MS)
        run[key] = call.exit_code_text
        return call

    probe_call = query(["shell", "dumpsys", "package"], ANDROID_PACKAGE, "dumpsys_probe_exit")
    run["version_name"] = extract_version_name(probe_call.stdout)
    companion_call = query(["shell", "dumpsys", "package"], COMPANION_PACKAGE, "dumpsys_companion_exit")
    run["companion_present"] = COMPANION_PACKAGE in (companion_call.stdout + companion_call.stderr)
    wearable_call = query(["shell", "pm", "path"], WEARABLE_PACKAGE, "pm_path_wearable_exit")
    run["wearable_path"] = _collapse(wearable_call.stdout, 4)

    if not am_call.ok:
        print(f"ERROR: am start failed on {serial} ({am_call.exit_code_text}).", file=sys.stderr)
        return _finish(run, 1)

    print(
        f"Installed and started on {serial}; install exit {run['install_exit']}, "
        f"am start exit {run['am_start_exit']}."
    )
    return _finish(run, 0)


if __name__ == "__main__":
    raise SystemExit(main())
