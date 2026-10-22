#!/usr/bin/env python3
"""Unit tests for ``scripts/bes-qemu-readonly-probe.py`` (work order W-629).

These tests are read-only. They exercise the probe's pure classification helpers
and run the *real* ``inspect_rpk`` from the existing bes-qemu toolchain against
the authoritative diagnostic RPK and a synthetic minimal ZIP. They must never
send a QMP command, never spawn the NAND verifier, and never touch the network.

The module is loaded by file path via ``importlib`` (the filename is hyphenated),
and the first checks assert that importing it neither runs ``main`` nor spawns a
process.
"""

from __future__ import annotations

import atexit
import importlib.util
import json
import sys
import unittest
import zipfile
from pathlib import Path
from unittest import mock

# Loading the hyphenated script by file path must not leave a bytecode cache in
# scripts/__pycache__ (that would be an untracked, out-of-scope changed path).
sys.dont_write_bytecode = True


def _drop_own_bytecode_cache() -> None:
    """Delete the ``.pyc`` CPython caches for THIS module while loading it.

    ``python -m unittest`` imports this file and the loader writes its bytecode
    cache *before* the module body runs, so the ``sys.dont_write_bytecode`` line
    above cannot prevent this single file. The leftover ``tests/__pycache__``
    binary path is outside the work order's scope, so drop it as soon as the
    body executes (and again at interpreter shutdown).
    """
    try:
        module_path = Path(__file__).resolve()
        cache_dir = module_path.parent / "__pycache__"
        if not cache_dir.is_dir():
            return
        for stale in cache_dir.glob(module_path.stem + ".*.pyc"):
            try:
                stale.unlink()
            except OSError:
                pass
        try:
            cache_dir.rmdir()
        except OSError:
            pass
    except OSError:
        pass


_drop_own_bytecode_cache()
atexit.register(_drop_own_bytecode_cache)

REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPT_PATH = REPO_ROOT / "scripts" / "bes-qemu-readonly-probe.py"
RPK_PATH = REPO_ROOT / "band-app" / "dist" / "com.minavigatering.probe.debug.0.1.2-diag.rpk"

RPK_SHA256 = "739151eabd2a145baf199799a70ab2d66ad5dd27b0745b74684229c5c27937ce"
RPK_VERSION_CODE = 3
NAND_DLL_NOT_FOUND_RC = 3221225781

FORBIDDEN_SOURCE_TOKENS = (
    "qom-set",
    "install-file",
    "remove-file",
    "verify_miband9",
    "run_full_rpk_test",
)


def _load_module():
    spec = importlib.util.spec_from_file_location("bes_qemu_readonly_probe", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


probe = _load_module()


class ImportSafetyTest(unittest.TestCase):
    def test_module_loads_from_path(self):
        self.assertTrue(SCRIPT_PATH.is_file())
        for name in ("main", "build_report", "classify_inspect", "interpret_qmp", "interpret_nand"):
            self.assertTrue(hasattr(probe, name), name)

    def test_import_does_not_execute_main_or_spawn_process(self):
        """Importing must not run main() and must not spawn a subprocess."""
        with mock.patch("subprocess.run") as run_mock:
            _load_module()
        self.assertEqual(run_mock.call_count, 0, "import must not spawn a process")

    def test_import_does_not_touch_network(self):
        with mock.patch("urllib.request.urlopen") as url_mock:
            _load_module()
        self.assertEqual(url_mock.call_count, 0, "import must not open a socket")


class MissingAppJsTest(unittest.TestCase):
    def test_zip_without_app_js_fails_spec(self):
        """A ZIP with only manifest.json (no app.js) must not spec-pass."""
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            rpk = Path(tmp) / "no-app-js.rpk"
            with zipfile.ZipFile(rpk, "w") as zf:
                zf.writestr("manifest.json", json.dumps({"package": "com.example.tiny"}))
            info = probe.load_inspect_rpk()(rpk)
            cls = probe.classify_inspect(info)
        self.assertFalse(cls["has_app_js"])
        self.assertFalse(cls["specPass"], "missing app.js must never spec-pass")
        self.assertFalse(cls["jsRuntimeExecuted"])


class DiagnosticRpkInspectTest(unittest.TestCase):
    def test_authoritative_rpk_identity(self):
        if not RPK_PATH.is_file():
            self.skipTest(f"diagnostic RPK not present: {RPK_PATH}")
        info = probe.load_inspect_rpk()(RPK_PATH)
        self.assertEqual(info["package"], "com.minavigatering.probe")
        self.assertEqual(info["versionCode"], RPK_VERSION_CODE)
        self.assertEqual(info["sha256"], RPK_SHA256)
        cls = probe.classify_inspect(info)
        self.assertFalse(cls["jsRuntimeExecuted"], "inspection never executes JS")


class InterpretQmpTest(unittest.TestCase):
    def test_http_200_with_error_is_not_ok(self):
        body = json.dumps({"error": "[WinError 10054] remote host closed connection"})
        result = probe.interpret_qmp(200, body)
        self.assertFalse(result["ok"], "HTTP 200 with an error body is not a success")
        self.assertEqual(result["httpStatus"], 200)
        self.assertLessEqual(len(result["bodyHead"]), 300)

    def test_non_200_is_not_ok(self):
        self.assertFalse(probe.interpret_qmp(500, "{}")["ok"])

    def test_valid_catalog_is_ok(self):
        body = json.dumps({"return": "64:15572:" + RPK_SHA256})
        result = probe.interpret_qmp(200, body)
        self.assertTrue(result["ok"])
        self.assertEqual(result["entries"][0]["type"], 64)
        self.assertEqual(result["entries"][0]["sha256"], RPK_SHA256)

    def test_malformed_catalog_is_not_ok(self):
        self.assertFalse(probe.interpret_qmp(200, json.dumps({"return": "64:abc"}))["ok"])


class InterpretNandTest(unittest.TestCase):
    def test_dll_not_found_layout_is_not_verified(self):
        result = probe.interpret_nand(NAND_DLL_NOT_FOUND_RC)
        self.assertFalse(result["layoutVerified"])
        self.assertEqual(result["reason"], "dll_not_found")

    def test_clean_exit_still_not_verified(self):
        result = probe.interpret_nand(0)
        self.assertFalse(result["layoutVerified"], "mock names are not a content hash")
        self.assertEqual(result["reason"], "mock_name_only_not_content_hash")


class SourceSafetyTest(unittest.TestCase):
    def test_source_has_no_mutation_or_full_test_tokens(self):
        source = SCRIPT_PATH.read_text(encoding="utf-8")
        for token in FORBIDDEN_SOURCE_TOKENS:
            self.assertNotIn(token, source, f"source must not reference {token!r}")


if __name__ == "__main__":
    unittest.main()
