#!/usr/bin/env python3
"""Honor (HONOR AAP-AN00) *phone-side* channel capture for the MiNavigateRing V0 gate.

W-626 sibling of ``scripts/honor-phone-probe.py``: installs and starts the probe APK,
records the phone-side channel diagnostics, then tries the two synthetic "left turn"
fixtures (300 m then 200 m). No adb wrapper is copied here on purpose (a second copy
would drift): every ``adb`` call goes through ``probe.run_adb`` (explicit ``argv``,
``shell=False``, no ``cmd /c``, no pipes, always a hard ``timeout``). Only one
*explicitly identified* transport is touched (serial + brand + model all match).
Diagnostics and the two send/receipt lines come from a ``uiautomator`` XML dump; an
extractable line is copied verbatim, an unresolvable one is written ``unverified`` —
nothing is invented and the whole window dump never reaches the document. A watch
receipt exists only when the line literally contains ``手环确认 refId=probe-1`` or
``...probe-2``; a queued task, a successful SDK ``Task`` and "尚未收到回执" are not
receipts. This script never requests device-manager permission, accepts a dialog, runs
``pm grant``/``input text``, locks the screen, uninstalls, unbinds or disconnects
Bluetooth, nor disconnects ``tech.pingx.watchface`` for the user.

Import-safe: importing calls no adb, writes no document and never calls
``print-apk-identity.main``; every side effect lives inside :func:`main`.

Exit codes: ``0`` only when a matched device really had ``install`` and ``am start``
exit 0 *and* both send attempts (including a missing button) were written to the
document — a missing receipt is still ``0``. No match, or a ``getprop`` timeout, is ``2``.
"""

from __future__ import annotations

import sys

# Set before the helper modules load so importlib leaves no untracked
# scripts/__pycache__/*.pyc behind.
sys.dont_write_bytecode = True

import importlib.util
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

# --- Load the two helper scripts by path (no copied adb wrapper) ------------

SCRIPTS_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPTS_DIR.parent


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {name} from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


probe = _load_module("honor_phone_probe", SCRIPTS_DIR / "honor-phone-probe.py")
apk_identity = _load_module("print_apk_identity", SCRIPTS_DIR / "print-apk-identity.py")

# --- Reused constants (single source of truth) ------------------------------

TARGET_SERIAL = probe.TARGET_SERIAL
MISSING_TARGET_SERIAL_EXIT = 2
TARGET_BRAND = probe.TARGET_BRAND
TARGET_MODEL = probe.TARGET_MODEL

ADB = probe.ADB
run_adb = probe.run_adb
parse_devices = probe.parse_devices
order_transports = probe.order_transports
parse_props = probe.parse_props
identity_matches = probe.identity_matches
identity_gate = probe.identity_gate
apk_sha256 = probe.apk_sha256

APK_PATH = probe.APK_PATH
IDENTITY_PATH = probe.IDENTITY_PATH
ANDROID_PACKAGE = probe.ANDROID_PACKAGE
MAIN_COMPONENT = probe.MAIN_COMPONENT
COMPANION_PACKAGE = probe.COMPANION_PACKAGE

# --- Paths ------------------------------------------------------------------

DOC_PATH = REPO_ROOT / "docs" / "honor-channel-capture.md"

SOURCE_PATHS = (
    REPO_ROOT / "app/src/main/java/com/minavigatering/probe/MainActivity.kt",
    REPO_ROOT / "app/src/main/java/com/minavigatering/probe/transport/XiaomiWearableTransport.kt",
)

REBUILD_COMMAND = (
    "java -classpath gradle/wrapper/gradle-wrapper.jar "
    "org.gradle.wrapper.GradleWrapperMain :app:assembleDebug --no-daemon"
)

# --- Timeouts (ms): every adb call gets one ---------------------------------

TIMEOUT_DEVICES_MS = 12000
TIMEOUT_GETPROP_MS = 12000
TIMEOUT_INSTALL_MS = 90000
TIMEOUT_AM_START_MS = 20000
TIMEOUT_DUMP_MS = 12000
TIMEOUT_TAP_MS = 8000
TIMEOUT_DUMPSYS_MS = 12000

DIAGNOSTIC_WAIT_S = 8
TAP_SETTLE_S = 3

UNVERIFIED = "unverified"

# --- Diagnostic prefixes and send fixtures ----------------------------------

DIAGNOSTIC_PREFIXES = (
    "宿主服务：",
    "节点：",
    "权限：",
    "最近 SDK 错误：",
    "链路：",
    "最近一次发送状态：",
    "最近一次回执：",
)

SEND_SPECS = (
    ("左转 300 米", "probe-1"),
    ("左转 200 米", "probe-2"),
)

# --- Pure helpers (unit tested) ---------------------------------------------

_SENSITIVE = re.compile(r"(?i)authkey|account|token|secret")
_NODE_RE = re.compile(r"<node\b[^>]*>")
_TEXT_ATTR_RE = re.compile(r'\btext="([^"]*)"')
_BOUNDS_ATTR_RE = re.compile(r'\bbounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"')
_CLICKABLE_ATTR = 'clickable="true"'
_XML_ENTITIES = (
    ("&lt;", "<"), ("&gt;", ">"), ("&quot;", '"'), ("&apos;", "'"), ("&amp;", "&"),
)


def redact(text) -> str:
    """Replace credential-looking words (case insensitive) with ``[redacted]``.

    Same policy as ``ProbeSession.SENSITIVE``: ``authkey``/``account``/``token``/
    ``secret`` never reach the evidence document.
    """
    if text is None:
        return ""
    return _SENSITIVE.sub("[redacted]", str(text))


def _unescape(text: str) -> str:
    for entity, char in _XML_ENTITIES:
        text = text.replace(entity, char)
    return text


def parse_ui_nodes(xml) -> "list[dict]":
    """Parse a ``uiautomator`` dump into ``{text, bounds, clickable}`` nodes.

    Pure: no file or process access. Only the attributes this work order needs
    are read, so the whole window dump never reaches the document.
    """
    nodes: "list[dict]" = []
    for match in _NODE_RE.finditer(xml or ""):
        tag = match.group(0)
        text_match = _TEXT_ATTR_RE.search(tag)
        bounds_match = _BOUNDS_ATTR_RE.search(tag)
        nodes.append({
            "text": _unescape(text_match.group(1)) if text_match else "",
            "bounds": (
                tuple(int(bounds_match.group(i)) for i in range(1, 5))
                if bounds_match else None
            ),
            "clickable": _CLICKABLE_ATTR in tag,
        })
    return nodes


def extract_diagnostics(xml) -> "dict[str, str | None]":
    """Extract the seven phone-side diagnostic lines from a UI dump.

    A node whose text *starts with* a prefix and carries a remainder yields that
    remainder (``链路：连接中`` -> ``连接中``). A label-only node falls back to the
    next non-empty text node (the page renders ``最近一次发送状态：`` and its value
    as two ``Text`` composables). An unresolved prefix stays ``None`` and is later
    rendered as ``unverified``; nothing is fabricated.
    """
    nodes = parse_ui_nodes(xml)
    result: "dict[str, str | None]" = {prefix: None for prefix in DIAGNOSTIC_PREFIXES}
    for index, node in enumerate(nodes):
        text = node["text"]
        for prefix in DIAGNOSTIC_PREFIXES:
            if result[prefix] is not None or not text.startswith(prefix):
                continue
            remainder = text[len(prefix):]
            if remainder.strip():
                result[prefix] = remainder.strip()
                continue
            for follower in nodes[index + 1:]:
                if follower["text"].strip():
                    result[prefix] = follower["text"].strip()
                    break
    return result


def find_button_center(xml, text_prefix: str) -> "tuple[int, int] | None":
    """Return the tap centre of the first node starting with ``text_prefix``.

    A clickable node is preferred over a plain label. ``None`` means the button is
    not in the dump, in which case no coordinate is guessed.
    """
    candidates = [
        node for node in parse_ui_nodes(xml)
        if node["text"].startswith(text_prefix) and node["bounds"]
    ]
    if not candidates:
        return None
    clickable = [node for node in candidates if node["clickable"]]
    left, top, right, bottom = (clickable or candidates)[0]["bounds"]
    return ((left + right) // 2, (top + bottom) // 2)


def classify_receipt(text) -> str:
    """Classify a receipt line: ``receipt`` only for the two probe refIds.

    "通道任务成功", "已排队", "尚未收到回执" and any other refId are ``not-receipt``.
    """
    if text and ("手环确认 refId=probe-1" in text or "手环确认 refId=probe-2" in text):
        return "receipt"
    return "not-receipt"


# --- Document rendering -----------------------------------------------------

DOC_HEADER = """# 荣耀辅助机（HONOR AAP-AN00）手机侧通道采集证据

本文件由 `scripts/honor-channel-capture.py` 追加写入：每次运行新增一节，**不覆盖旧证据**。

三列证据互相独立，不得互相替代：

| 列 | 含义 |
|---|---|
| 荣耀手机端 | 工单选中的 HONOR 辅助机（`ro.serialno` = `<HONOR_SERIAL>`）上的手机侧事实 |
| K90手机端 | 目标机 K90；本单未在其上执行任何命令 |
| 手环通信 | 手环 9 的绑定、消息通道与后台能力；本单不执行、不推断 |

`unverified` 一律表示**本次运行没有取得对应证据**，不表示通过，也不表示失败。
只有回执行真的出现「手环确认 refId=probe-1」或「手环确认 refId=probe-2」时，才可写
「取得手环确认」；其余任何文字都写「未取得回执」，不得写 pass。
"""

MATRIX_ITEMS = (
    "四行通道诊断可见", "左转 300 米发送", "左转 200 米发送", "手环确认 refId 回执",
    "锁屏", "熄屏", "回表盘", "5 秒心跳", "15 秒超时",
)


def _cell(value: str) -> str:
    text = redact(value).replace("|", "\\|").replace("\n", " ").strip()
    return text or UNVERIFIED


def _device_table(devices) -> str:
    if not devices:
        return "本次运行没有解析到任何 ADB 传输。\n"
    rows = "\n".join(f"| {i} | `{_cell(serial)}` | {_cell(state)} |"
                     for i, (serial, state) in enumerate(devices, start=1))
    return "| 顺序 | ADB 序列 | state |\n|---|---|---|\n" + rows + "\n"


def _probe_table(probes) -> str:
    if not probes:
        return ""
    out = ["### 身份查询结果", "",
           "| ADB 序列 | 判定 | ro.serialno | ro.product.brand | ro.product.model |",
           "|---|---|---|---|---|"]
    for entry in probes:
        props = entry.get("props") or {}
        out.append("| `{serial}` | {verdict} | {s} | {b} | {m} |".format(
            serial=_cell(entry.get("serial", "")), verdict=_cell(entry.get("verdict", "")),
            s=_cell(props.get("ro.serialno", "(未返回)")),
            b=_cell(props.get("ro.product.brand", "(未返回)")),
            m=_cell(props.get("ro.product.model", "(未返回)"))))
    return "\n".join(out) + "\n\n"


def _diagnostics_table(diagnostics) -> str:
    rows = "\n".join(
        f"| {prefix} | {_cell(diagnostics.get(prefix) or UNVERIFIED)} |"
        for prefix in DIAGNOSTIC_PREFIXES
    )
    return "| 行 | 值 |\n|---|---|\n" + rows + "\n"


def _sends_table(sends) -> str:
    if not sends:
        return "本次运行没有执行发送尝试。\n"
    out = ["| 顺序 | 按钮 | 点击 | 发送状态 | 回执 | 回执判定 |",
           "|---|---|---|---|---|---|"]
    for index, send in enumerate(sends, start=1):
        verdict = ("receipt（取得手环确认）" if send.get("receipt_class") == "receipt"
                   else "not-receipt（未取得回执）")
        out.append("| {i} | {label} | {tap} | {status} | {receipt} | {verdict} |".format(
            i=index, label=_cell(send.get("label", "")), tap=_cell(send.get("tap", UNVERIFIED)),
            status=_cell(send.get("send_status") or UNVERIFIED),
            receipt=_cell(send.get("receipt") or UNVERIFIED), verdict=verdict))
    return "\n".join(out) + "\n"


def _honor_rows(run) -> str:
    selected = run.get("selected_serial")
    if not selected:
        return (
            f"| 安装（`adb install -r`） | 本次运行**没有**匹配到目标设备，**未执行安装**；"
            f"退出码 {UNVERIFIED} |\n"
            f"| 启动（`adb shell am start -n {MAIN_COMPONENT}`） | {UNVERIFIED}（未调用） |"
        )
    return "\n".join([
        f"| 选中的 ADB 序列 | `{_cell(selected)}` |",
        f"| 身份属性 | `ro.serialno={TARGET_SERIAL}`、`ro.product.brand={TARGET_BRAND}`、"
        f"`ro.product.model={TARGET_MODEL}` |",
        f"| `adb -s {selected} install -r <APK>` 退出码 | **{run.get('install_exit', 'not-executed')}** |",
        f"| install 输出 | {_cell(run.get('install_detail', ''))} |",
        f"| `adb -s {selected} shell am start -n {MAIN_COMPONENT}` 退出码 | "
        f"**{run.get('am_start_exit', 'not-executed')}** |",
        f"| am start 输出 | {_cell(run.get('am_start_detail', ''))} |"])


def _receipt_verdict(run) -> str:
    if any(send.get("receipt_class") == "receipt" for send in run.get("sends") or []):
        refs = [s.get("refId", "") for s in run["sends"] if s.get("receipt_class") == "receipt"]
        return "取得手环确认（refId=" + "、".join(r for r in refs if r) + "）"
    return f"未取得回执（{UNVERIFIED}）"


def _matrix(run) -> str:
    selected = run.get("selected_serial")
    diag = run.get("diagnostics") or {}
    diag_seen = any(diag.get(prefix) for prefix in DIAGNOSTIC_PREFIXES)

    def honor_col(item: str) -> str:
        if item == "四行通道诊断可见":
            return "已照录（见上表）" if diag_seen else UNVERIFIED
        if item == "左转 300 米发送":
            send = (run.get("sends") or [None])[0]
        elif item == "左转 200 米发送":
            sends = run.get("sends") or []
            send = sends[1] if len(sends) > 1 else None
        elif item == "手环确认 refId 回执":
            return _receipt_verdict(run)
        else:
            # 锁屏/熄屏/回表盘/5 秒心跳/15 秒超时: this order never performs them.
            return UNVERIFIED
        return (send or {}).get("tap", UNVERIFIED) if send else UNVERIFIED

    rows = []
    for item in MATRIX_ITEMS:
        honor = honor_col(item)
        if not selected and item in ("四行通道诊断可见", "左转 300 米发送", "左转 200 米发送"):
            honor = UNVERIFIED
        rows.append(f"| {item} | {_cell(honor)} | {UNVERIFIED} | {UNVERIFIED} |")
    return "\n".join(rows)


def _companion_line(run) -> str:
    present = run.get("companion_present")
    exit_text = run.get("companion_exit", "not-executed")
    if present is True:
        fact = f"`{COMPANION_PACKAGE}` 在本机已安装"
    elif present is False:
        fact = f"`{COMPANION_PACKAGE}` 未在本机解析到"
    else:
        fact = f"`{COMPANION_PACKAGE}` 是否安装：{UNVERIFIED}（未取得证据）"
    return (f"- 只读 `dumpsys package {COMPANION_PACKAGE}`（退出码 {exit_text}）：{fact}。"
            "**这只是一条手机端包事实，不是手环绑定，也不构成手环通信证据。**")


SECTION_TEMPLATE = """---
## 运行记录 {timestamp}（荣耀辅助机手机侧通道采集）

- 脚本：`scripts/honor-channel-capture.py`；APK：`app/build/outputs/apk/debug/app-debug.apk`
- APK SHA256（本次实测）：{sha}
- APK 身份闸门（在**任何 adb 调用之前**执行）：{identity_verdict}
- Android 版本约束：`versionCode=1`、`versionName=0.1.0`（由 `identity_gate` 强制，见 `scripts/print-apk-identity.py`）
- 目标身份：`ro.serialno`=`{serial}` 且 `ro.product.brand`=`{brand}` 且 `ro.product.model`=`{model}`（三项同时成立）

### 传输探测

{device_table}
{probe_table}### 荣耀手机端执行结果

| 项 | 值 |
|---|---|
{honor_rows}

### 四行通道诊断（手机侧，非手环回执）

{diagnostics_table}

### 两次发送尝试（合成夹具）

{sends_table}

### 手机包事实（不构成手环绑定证据）

{companion_line}

### 三列证据矩阵

| 验证项 | 荣耀手机端 | K90手机端 | 手环通信 |
|---|---|---|---|
{matrix_rows}

- 荣耀列的四行诊断与两次发送取自 `uiautomator` XML：能抽到的行照录，抽不到的行写 {unverified}。
- K90手机端各列均记 {unverified}：本单未在 K90 上执行任何 adb 命令。
- 手环通信各列均记 {unverified}：本单不执行、不推断手环侧能力。
- 锁屏、熄屏、回表盘、5 秒心跳、15 秒超时均记 {unverified}：本单不执行这些动作。
- 合成夹具声明：「左转 300 米（合成夹具）」「左转 200 米（同一 actionId，合成夹具）」只用于协议/状态验证，**不代表已验收的高德真实解析**。
- 前提 `tech.pingx.watchface` 蓝牙是否已断开：{unverified}（脚本不能代用户断开）。
- 边界声明：以上只覆盖荣耀辅助机的手机侧采集，不能代表 K90 手机端或手环通信的最终验收。
- 本次运行进程退出码：**{exit_code}**
"""


def build_doc_section(run: "dict") -> str:
    """Render one appended evidence section from a run record (pure)."""
    sha = (f"`{run.get('apk_sha256', '(unknown)')}`"
           if run.get("apk_sha256") else "（APK 缺失，未计算）")
    return "\n" + SECTION_TEMPLATE.format(
        timestamp=run.get("timestamp", ""), sha=sha,
        identity_verdict=_cell(run.get("identity_verdict", UNVERIFIED)),
        serial=TARGET_SERIAL, brand=TARGET_BRAND, model=TARGET_MODEL,
        device_table=_device_table(run.get("devices") or []),
        probe_table=_probe_table(run.get("probes") or []), honor_rows=_honor_rows(run),
        diagnostics_table=_diagnostics_table(run.get("diagnostics") or {}),
        sends_table=_sends_table(run.get("sends") or []), companion_line=_companion_line(run),
        matrix_rows=_matrix(run), unverified=UNVERIFIED,
        exit_code=run.get("exit_code", "unknown"))


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


# --- Main flow --------------------------------------------------------------


def _dump_ui(serial: str) -> "tuple[str, str]":
    """Run the documented dump + cat pair and return ``(xml, note)``.

    The ``note`` records the two real exit codes; the XML is empty when the read
    failed, so the caller writes ``unverified`` instead of inventing a line."""
    dump = run_adb([ADB, "-s", serial, "shell", "uiautomator", "dump",
                    "/sdcard/miniring-ui.xml"], TIMEOUT_DUMP_MS)
    cat = run_adb([ADB, "-s", serial, "exec-out", "cat", "/sdcard/miniring-ui.xml"],
                  TIMEOUT_DUMP_MS)
    note = f"dump={dump.exit_code_text} cat={cat.exit_code_text}"
    return (cat.stdout if cat.ok else ""), note


def _record_send(run, label: str, ref_id: str, tap: str, xml: str) -> None:
    diagnostics = extract_diagnostics(xml)
    receipt = diagnostics.get("最近一次回执：")
    run["sends"].append({
        "label": label,
        "refId": ref_id,
        "tap": tap,
        "send_status": diagnostics.get("最近一次发送状态："),
        "receipt": receipt,
        "receipt_class": classify_receipt(receipt),
    })


def main(argv: Sequence[str] = ()) -> int:
    """Capture the Honor phone-side channel state. Returns a process exit code."""
    run: "dict" = {
        "timestamp": datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S %z"),
        "devices": [], "probes": [], "selected_serial": None, "sends": [],
        "diagnostics": {}, "companion_present": None,
    }
    # 0. Without a target serial nothing below can run: stop before any adb call.
    if not TARGET_SERIAL:
        print("ERROR: MIRING_HONOR_SERIAL is unset or empty; "
              "set it to the helper phone ro.serialno.", file=sys.stderr)
        return MISSING_TARGET_SERIAL_EXIT

    # 1. APK freshness: missing or older than the UI sources stops before any adb.
    if not APK_PATH.exists():
        print(f"ERROR: debug APK not found at: {APK_PATH}", file=sys.stderr)
        print(f"       rebuild it with:\n       {REBUILD_COMMAND}", file=sys.stderr)
        print("       No adb call was made.", file=sys.stderr)
        return _finish(run, 1)

    apk_mtime = APK_PATH.stat().st_mtime
    for source in SOURCE_PATHS:
        if source.exists() and apk_mtime < source.stat().st_mtime:
            print(
                f"ERROR: debug APK is older than {source.name}; rebuild it with:\n"
                f"       {REBUILD_COMMAND}", file=sys.stderr)
            print("       No adb call was made.", file=sys.stderr)
            return _finish(run, 1)

    run["apk_present"] = True

    # 2. Refresh the identity record, then gate on it — before any adb call.
    refresh_code = apk_identity.main()
    if refresh_code != 0:
        print(f"ERROR: `print-apk-identity.py` returned {refresh_code}; "
              "no adb call was made.", file=sys.stderr)
        run["identity_verdict"] = f"identity refresh failed ({refresh_code})"
        return _finish(run, 1)

    try:
        identity_text = IDENTITY_PATH.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"ERROR: cannot read APK identity record: {exc}", file=sys.stderr)
        run["identity_verdict"] = f"identity file unreadable: {exc}"
        return _finish(run, 1)

    run["apk_sha256"] = apk_sha256(APK_PATH)
    gate_ok, gate_reason = identity_gate(identity_text, run["apk_sha256"])
    run["identity_verdict"] = gate_reason
    if not gate_ok:
        print(f"ERROR: APK identity gate failed ({gate_reason}); "
              "no adb call was made.", file=sys.stderr)
        return _finish(run, 1)

    # 3. List transports.
    devices_call = run_adb([ADB, "devices", "-l"], TIMEOUT_DEVICES_MS)
    if not devices_call.ok:
        print(f"ERROR: `adb devices -l` failed ({devices_call.exit_code_text}); "
              "no install was attempted.", file=sys.stderr)
        return _finish(run, 1)

    ordered = order_transports(parse_devices(devices_call.stdout))
    run["devices"] = ordered

    # 4. Pick the single transport whose three identity attributes all match.
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

    # 5. Install on the single matched transport only.
    install_call = run_adb([ADB, "-s", serial, "install", "-r", str(APK_PATH)], TIMEOUT_INSTALL_MS)
    run["install_exit"] = install_call.exit_code_text
    run["install_detail"] = install_call.describe()
    if not install_call.ok:
        print(f"ERROR: install failed on {serial} ({install_call.exit_code_text}); "
              "am start was not attempted.", file=sys.stderr)
        run["am_start_exit"] = UNVERIFIED + "（安装失败，未尝试）"
        return _finish(run, 1)

    # 6. Start the probe page.
    am_call = run_adb([ADB, "-s", serial, "shell", "am", "start", "-n", MAIN_COMPONENT],
                      TIMEOUT_AM_START_MS)
    run["am_start_exit"] = am_call.exit_code_text
    run["am_start_detail"] = am_call.describe()
    if not am_call.ok:
        print(f"ERROR: am start failed on {serial} ({am_call.exit_code_text}).", file=sys.stderr)
        return _finish(run, 1)

    # 7. Wait locally (never `shell sleep`), then read the four diagnostic lines.
    time.sleep(DIAGNOSTIC_WAIT_S)
    xml, dump_note = _dump_ui(serial)
    run["diagnostics"] = extract_diagnostics(xml)
    run["dump_note"] = dump_note

    # 8. Tap the two synthetic fixtures, dumping after each to read status/receipt.
    for label, ref_id in SEND_SPECS:
        center = find_button_center(xml, label)
        if center is None:
            _record_send(run, label, ref_id, UNVERIFIED, xml)
            print(f"WARN: button `{label}` not found in the UI dump; no tap made.", file=sys.stderr)
            continue
        x, y = center
        tap_call = run_adb([ADB, "-s", serial, "shell", "input", "tap", str(x), str(y)],
                           TIMEOUT_TAP_MS)
        if not tap_call.ok:
            _record_send(run, label, ref_id, f"tap failed ({tap_call.exit_code_text})", xml)
            continue
        time.sleep(TAP_SETTLE_S)
        xml, _note = _dump_ui(serial)
        _record_send(run, label, ref_id, f"已点击 ({x},{y})", xml)

    # 11. Read-only companion package fact; never a band-binding claim.
    companion_call = run_adb([ADB, "-s", serial, "shell", "dumpsys", "package",
                              COMPANION_PACKAGE], TIMEOUT_DUMPSYS_MS)
    run["companion_exit"] = companion_call.exit_code_text
    run["companion_present"] = COMPANION_PACKAGE in (companion_call.stdout + companion_call.stderr)

    # 13. Exit 0 means install + am start really exited 0 and both attempts are recorded.
    print(f"Captured on {serial}; install exit {run['install_exit']}, "
          f"am start exit {run['am_start_exit']}, sends recorded {len(run['sends'])}.")
    return _finish(run, 0)


if __name__ == "__main__":
    raise SystemExit(main())
