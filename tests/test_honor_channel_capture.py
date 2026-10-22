#!/usr/bin/env python3
"""Unit tests for ``scripts/honor-channel-capture.py`` (work order W-626).

Only pure functions and import side-effect-freedom are covered. These tests must
never start ``adb``, Gradle or QEMU, and must never append to
``docs/honor-channel-capture.md``.

The module is loaded by file path via ``importlib`` so a hyphenated filename works.
"""

from __future__ import annotations

import atexit
import importlib.util
import os
import sys
import unittest
from pathlib import Path
from unittest import mock

# Loading the hyphenated script by path must not leave a bytecode cache behind
# (that would be an untracked, out-of-scope changed path).
sys.dont_write_bytecode = True


def _drop_own_bytecode_cache() -> None:
    """Delete this test file's ``.pyc`` cache as soon as the body runs.

    The loader writes the cache *before* the module body executes, so the
    ``sys.dont_write_bytecode`` line above cannot prevent this single file.
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
SCRIPT_PATH = REPO_ROOT / "scripts" / "honor-channel-capture.py"
DOC_PATH = REPO_ROOT / "docs" / "honor-channel-capture.md"

# A tiny fixture: only the attributes this work order reads, so the tests never
# depend on a real (and much larger) window dump.
UI_FIXTURE = (
    "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
    '<hierarchy rotation="0">'
    '<node index="0" text="通道诊断（手机侧，非手环回执）" resource-id="" '
    'class="android.view.View" package="com.minavigatering.probe" '
    'clickable="false" bounds="[0,100][1272,200]" />'
    '<node index="1" text="宿主服务：宿主服务 API level=3" resource-id="" '
    'class="android.view.View" package="com.minavigatering.probe" '
    'clickable="false" bounds="[0,200][1272,300]" />'
    '<node index="2" text="节点：nodeId=abc" resource-id="" '
    'class="android.view.View" package="com.minavigatering.probe" '
    'clickable="false" bounds="[0,300][1272,400]" />'
    '<node index="3" text="最近一次发送状态：" resource-id="" '
    'class="android.view.View" package="com.minavigatering.probe" '
    'clickable="false" bounds="[0,400][1272,450]" />'
    '<node index="4" text="（尚未发送）" resource-id="" '
    'class="android.view.View" package="com.minavigatering.probe" '
    'clickable="false" bounds="[0,450][1272,500]" />'
    '<node index="5" text="左转 300 米（合成夹具）" resource-id="" '
    'class="android.widget.Button" package="com.minavigatering.probe" '
    'clickable="true" bounds="[100,600][300,700]" />'
    '<node index="6" text="左转 200 米（同一 actionId，合成夹具）" resource-id="" '
    'class="android.widget.Button" package="com.minavigatering.probe" '
    'clickable="true" bounds="[100,800][300,900]" />'
    "</hierarchy>"
)


def _load_module():
    spec = importlib.util.spec_from_file_location("honor_channel_capture", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


os.environ["MIRING_HONOR_SERIAL"] = "TESTSERIAL0001"

capture = _load_module()


class ImportSafetyTest(unittest.TestCase):
    def test_module_loads_from_path(self):
        self.assertTrue(SCRIPT_PATH.is_file())
        for name in ("main", "classify_receipt", "extract_diagnostics",
                     "find_button_center", "parse_ui_nodes", "redact"):
            self.assertTrue(hasattr(capture, name), name)

    def test_import_does_not_execute_main_or_spawn_adb(self):
        with mock.patch("subprocess.run") as run_mock, \
                mock.patch.object(capture, "append_doc") as doc_mock:
            _load_module()
        self.assertEqual(run_mock.call_count, 0, "import must not spawn adb")
        self.assertEqual(doc_mock.call_count, 0, "import must not write the document")

    def test_import_has_no_doc_side_effect(self):
        before = DOC_PATH.read_bytes() if DOC_PATH.exists() else None
        _load_module()
        after = DOC_PATH.read_bytes() if DOC_PATH.exists() else None
        self.assertEqual(before, after)

    def test_identity_main_is_not_called_on_import(self):
        with mock.patch.object(capture.apk_identity, "main") as identity_main:
            _load_module()
        self.assertEqual(identity_main.call_count, 0)


class ClassifyReceiptTest(unittest.TestCase):
    def test_probe_ids_are_receipts(self):
        self.assertEqual(capture.classify_receipt("手环确认 refId=probe-1"), "receipt")
        self.assertEqual(capture.classify_receipt("手环确认 refId=probe-2"), "receipt")

    def test_channel_and_pending_texts_are_not_receipts(self):
        for text in (
            "通道任务成功：messageId=probe-1（不是手环回执）",
            "已排队：messageId=probe-1 nodeId=abc（等待通道任务结果）",
            "尚未收到回执",
            "（尚未收到回执）",
        ):
            with self.subTest(text=text):
                self.assertEqual(capture.classify_receipt(text), "not-receipt")

    def test_other_ref_ids_are_not_receipts(self):
        for text in ("手环确认 refId=probe-3", "手环确认 refId=other"):
            with self.subTest(text=text):
                self.assertEqual(capture.classify_receipt(text), "not-receipt")

    def test_empty_is_not_a_receipt(self):
        self.assertEqual(capture.classify_receipt(""), "not-receipt")
        self.assertEqual(capture.classify_receipt(None), "not-receipt")


class UiExtractionTest(unittest.TestCase):
    def test_extracts_host_service_line(self):
        diagnostics = capture.extract_diagnostics(UI_FIXTURE)
        self.assertEqual(diagnostics["宿主服务："], "宿主服务 API level=3")

    def test_label_only_line_falls_back_to_next_text(self):
        diagnostics = capture.extract_diagnostics(UI_FIXTURE)
        self.assertEqual(diagnostics["最近一次发送状态："], "（尚未发送）")

    def test_missing_prefix_stays_none(self):
        diagnostics = capture.extract_diagnostics(UI_FIXTURE)
        self.assertIsNone(diagnostics["最近 SDK 错误："])
        self.assertIsNone(diagnostics["最近一次回执："])

    def test_finds_button_center(self):
        self.assertEqual(capture.find_button_center(UI_FIXTURE, "左转 300 米"), (200, 650))
        self.assertEqual(capture.find_button_center(UI_FIXTURE, "左转 200 米"), (200, 850))

    def test_missing_button_returns_none(self):
        self.assertIsNone(capture.find_button_center(UI_FIXTURE, "左转 999 米"))


class RedactionTest(unittest.TestCase):
    def test_credential_words_are_redacted_case_insensitively(self):
        redacted = capture.redact("AuthKey=abc account=x token=y SECRET=z")
        # Same policy as ProbeSession.SENSITIVE: the keyword is replaced, so the
        # credential-shaped words themselves never reach the document.
        self.assertNotIn("AuthKey", redacted)
        self.assertNotIn("account", redacted)
        self.assertNotIn("token", redacted)
        self.assertNotIn("SECRET", redacted)
        self.assertEqual(redacted.count("[redacted]"), 4)


    def test_missing_target_serial_returns_2_without_adb(self):
        with mock.patch.object(capture, "TARGET_SERIAL", ""), \
                mock.patch.object(capture, "run_adb") as adb_mock:
            code = capture.main()
        self.assertEqual(code, 2)
        self.assertEqual(adb_mock.call_count, 0, "no adb call without a serial")


if __name__ == "__main__":
    unittest.main()
