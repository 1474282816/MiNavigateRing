#!/usr/bin/env python3
"""Read-only gap probe for the bes-qemu Mi Band 9 emulator (work order W-629).

This script answers one question with evidence: can the existing bes-qemu
emulator be counted as *running* the MiNavigateRing navigation RPK? The answer
recorded here is **no**, and the script only ever produces read-only evidence.

What it does (all inside :func:`main`, i.e. only under ``__main__``):

1. Loads the existing ``rpk_tool.py`` by absolute path and calls **only**
   ``inspect_rpk`` on the authoritative diagnostic RPK (ZIP + manifest check).
2. Sends one read-only ``GET /status`` and one read-only ``qom-get`` for the
   ``install-catalog`` property over the emulator HTTP bridge.
3. Launches ``verify_yaffs_overlay.exe`` directly (argv, ``shell=False``, hard
   timeout) to record its real exit code.
4. Writes the result to ``band-app/dist/bes-qemu-readonly.json`` (gitignored).

Hard rules baked in by the work order:

* Importing this module must not touch the network and must not spawn a process;
  everything happens under ``__main__``.
* No install / uninstall / mutation command is ever sent. The only QMP command
  is a read-only ``qom-get``.
* ``jsRuntimeExecuted`` is hard-coded ``False`` and ``overallNavigationPass`` is
  hard-coded ``False``: a ZIP/manifest check, an HTTP 200, or a demo package name
  are never counted as the navigation RPK running.
* The script never writes ``docs/`` or any tracked file.

Exit codes: ``0`` means the *report was written* (QMP/HTTP/NAND may still have
failed — that is the point of the report). ``1`` means the authoritative
diagnostic RPK was missing or its SHA256 did not match, so no trustworthy report
could be produced.
"""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

# --- Fixed facts (verified against the real artefacts, never rewritten) -----

REPO_ROOT = Path(__file__).resolve().parent.parent

#: Authoritative diagnostic package from VR-347. Its SHA256 is pinned so a
#: missing or substituted package cannot be reported as checked.
RPK_PATH = REPO_ROOT / "band-app" / "dist" / "com.minavigatering.probe.debug.0.1.2-diag.rpk"
RPK_SHA256 = "739151eabd2a145baf199799a70ab2d66ad5dd27b0745b74684229c5c27937ce"
RPK_VERSION_CODE = 3

#: Existing bes-qemu tooling, used read-only.
RPK_TOOL_PATH = Path("C:/program1/bes-qemu/miband9/rpk_tool.py")
VERIFY_EXE = Path("C:/program1/bes-qemu/miband9/verify_yaffs_overlay.exe")

#: Emulator HTTP bridge and the read-only queries sent to it.
QMP_ENDPOINT = "http://127.0.0.1:8090"
STATUS_URL = QMP_ENDPOINT + "/status"
QMP_URL = QMP_ENDPOINT + "/qmp"
HTTP_TIMEOUT = 8  # seconds
NAND_TIMEOUT = 10  # seconds

#: The real, observed failure of ``verify_yaffs_overlay.exe`` on this machine:
#: 0xC0000135 == STATUS_DLL_NOT_FOUND (a required DLL is missing).
NAND_DLL_NOT_FOUND_RC = 3221225781

#: Dynamic output only. ``band-app/dist/`` is gitignored.
OUT_PATH = REPO_ROOT / "band-app" / "dist" / "bes-qemu-readonly.json"


def load_inspect_rpk():
    """Load ``rpk_tool.py`` by absolute path and return its ``inspect_rpk``.

    Import-safe: this touches neither the network nor a subprocess. It only
    imports the module and hands back the one read-only function we are allowed
    to call.
    """
    spec = importlib.util.spec_from_file_location("bes_qemu_rpk_tool", RPK_TOOL_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load rpk tool from {RPK_TOOL_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.inspect_rpk


def classify_inspect(info: dict) -> dict:
    """Turn a raw ``inspect_rpk`` result into the read-only classification.

    ``specPass`` is only true when both the app entry script and the routed
    entry code are present. ``jsRuntimeExecuted`` is always ``False``: inspecting
    a ZIP proves the archive is well-formed, never that any JavaScript ran.
    """
    has_app_js = bool(info.get("has_app_js"))
    has_entry_code = bool(info.get("has_entry_code"))
    return {
        "has_app_js": has_app_js,
        "has_entry_code": has_entry_code,
        "specPass": has_app_js and has_entry_code,
        "jsRuntimeExecuted": False,
    }


def interpret_qmp(http_status: int, body_text: str) -> dict:
    """Interpret a QMP ``install-catalog`` response.

    ``ok`` is true only when the HTTP status is 200, the JSON body carries no
    ``error`` key, and the ``return`` value parses as comma-separated
    ``type:size:sha256`` triples. An HTTP 200 whose body carries an ``error`` is
    explicitly **not** a success. The first 300 characters of the body are kept
    for the record.
    """
    result = {
        "ok": False,
        "httpStatus": http_status,
        "bodyHead": (body_text or "")[:300],
        "entries": [],
        "reason": None,
    }
    if http_status != 200:
        result["reason"] = "http_status_not_200"
        return result
    try:
        payload = json.loads(body_text)
    except (TypeError, ValueError):
        result["reason"] = "body_not_json"
        return result
    if not isinstance(payload, dict):
        result["reason"] = "body_not_object"
        return result
    if "error" in payload:
        result["reason"] = "body_has_error"
        return result
    raw = payload.get("return")
    if not isinstance(raw, str) or raw.strip() == "":
        result["reason"] = "empty_catalog"
        return result

    entries = []
    for chunk in raw.split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        parts = chunk.split(":")
        if len(parts) != 3:
            result["reason"] = "catalog_not_type_size_sha256"
            return result
        type_str, size_str, sha = parts
        try:
            type_val = int(type_str)
            size_val = int(size_str)
        except ValueError:
            result["reason"] = "catalog_not_type_size_sha256"
            return result
        if not sha:
            result["reason"] = "catalog_not_type_size_sha256"
            return result
        entries.append({"type": type_val, "size": size_val, "sha256": sha})

    if not entries:
        result["reason"] = "empty_catalog"
        return result
    result["ok"] = True
    result["entries"] = entries
    return result


def interpret_nand(returncode) -> dict:
    """Interpret the NAND overlay verifier exit code.

    ``layoutVerified`` is always ``False``. Even a clean exit of the standalone
    verifier only compares *mock* node names in memory; it never hashes this
    project's RPK content. ``3221225781`` (0xC0000135) means the verifier did not
    even start because a DLL was missing.
    """
    if returncode == NAND_DLL_NOT_FOUND_RC:
        reason = "dll_not_found"
    else:
        reason = "mock_name_only_not_content_hash"
    return {"layoutVerified": False, "reason": reason}


def _http_get_status() -> dict:
    """Read-only ``GET /status``; records the code and a body head."""
    try:
        with urllib.request.urlopen(STATUS_URL, timeout=HTTP_TIMEOUT) as resp:
            body = resp.read().decode("utf-8", "replace")
            return {"code": resp.status, "bodyHead": body[:300]}
    except urllib.error.HTTPError as exc:
        body = ""
        try:
            body = exc.read().decode("utf-8", "replace")
        except Exception:
            body = ""
        return {"code": exc.code, "bodyHead": body[:300]}
    except Exception as exc:  # noqa: BLE001 - record any transport failure verbatim
        return {"code": None, "bodyHead": f"{type(exc).__name__}: {exc}"[:300]}


def _http_post_qmp_catalog() -> tuple[int | None, str]:
    """Read-only ``qom-get`` for ``install-catalog``; returns (code, body)."""
    payload = json.dumps(
        {"execute": "qom-get", "arguments": {"path": "/machine", "property": "install-catalog"}}
    ).encode("utf-8")
    req = urllib.request.Request(
        QMP_URL, data=payload, headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        body = ""
        try:
            body = exc.read().decode("utf-8", "replace")
        except Exception:
            body = ""
        return exc.code, body
    except Exception as exc:  # noqa: BLE001 - record any transport failure verbatim
        return None, f"{type(exc).__name__}: {exc}"


def _run_nand_verifier() -> int | None:
    """Launch the NAND verifier by direct argv (no shell), hard timeout."""
    try:
        proc = subprocess.run(
            [str(VERIFY_EXE)],
            capture_output=True,
            timeout=NAND_TIMEOUT,
            shell=False,
        )
        return proc.returncode
    except subprocess.TimeoutExpired:
        return None
    except Exception:  # noqa: BLE001 - a launch failure is still a real failure
        return None


def build_report() -> tuple[dict, bool]:
    """Assemble the full read-only report. Returns ``(record, rpk_ok)``.

    ``rpk_ok`` is false only when the authoritative diagnostic RPK is missing or
    has the wrong SHA256; in that case no trustworthy report can be produced.
    """
    record = {
        "probe": "bes-qemu-readonly-probe",
        "rpk": {
            "path": str(RPK_PATH),
            "expectedSha256": RPK_SHA256,
            "sha256": None,
            "bytes": None,
            "versionCode": None,
            "versionName": None,
            "package": None,
        },
        "inspect": {
            "has_app_js": False,
            "has_entry_code": False,
            "specPass": False,
            "jsRuntimeExecuted": False,
        },
        "httpStatus": {"code": None, "bodyHead": ""},
        "qmp": {"ok": False, "httpStatus": None, "bodyHead": "", "entries": [], "reason": None},
        "nand": {"returncode": None, "layoutVerified": False, "reason": None},
        "overallNavigationPass": False,
        "notes": [
            "ZIP/manifest inspection only; no JavaScript was executed.",
            "A read-only QMP catalog query is not proof that the RPK ran.",
            "The NAND overlay verifier compares mock node names, not this RPK's content hash.",
            "No install, uninstall, or mutation command was sent.",
        ],
    }

    rpk_ok = False
    if not RPK_PATH.is_file():
        record["rpk"]["error"] = "diagnostic RPK missing"
        return record, False

    info = load_inspect_rpk()(RPK_PATH)
    record["rpk"]["sha256"] = info.get("sha256")
    record["rpk"]["bytes"] = info.get("size")
    record["rpk"]["versionCode"] = info.get("versionCode")
    record["rpk"]["versionName"] = info.get("versionName")
    record["rpk"]["package"] = info.get("package")
    record["inspect"] = classify_inspect(info)

    rpk_ok = info.get("sha256") == RPK_SHA256 and info.get("versionCode") == RPK_VERSION_CODE

    record["httpStatus"] = _http_get_status()

    qmp_code, qmp_body = _http_post_qmp_catalog()
    record["qmp"] = interpret_qmp(qmp_code if qmp_code is not None else 0, qmp_body)

    nand_rc = _run_nand_verifier()
    nand = interpret_nand(nand_rc)
    nand["returncode"] = nand_rc
    record["nand"] = nand

    return record, rpk_ok


def main() -> int:
    record, rpk_ok = build_report()

    # The report must never claim JavaScript ran; guard against regressions.
    if record["inspect"]["jsRuntimeExecuted"] is not False:
        print("FAIL: jsRuntimeExecuted must always be false", file=sys.stderr)
        return 1
    record["overallNavigationPass"] = False

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {OUT_PATH}")

    if not rpk_ok:
        print(
            "FAIL: diagnostic RPK missing or SHA256/versionCode mismatch; "
            "no trustworthy report.",
            file=sys.stderr,
        )
        return 1

    print(
        "report complete: overallNavigationPass=False, "
        f"nand.layoutVerified=False, qmp.ok={record['qmp']['ok']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
