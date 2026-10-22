#!/usr/bin/env python3
"""Unit tests for ``scripts/honor-phone-probe.py`` (work order W-615).

Pure functions only — these tests must never invoke ``adb``. The module is
loaded by file path via ``importlib`` so a hyphenated filename works, and the
first assertion checks that importing it does not run ``main``.
"""

from __future__ import annotations

import atexit
import importlib.util
import os
import sys
import unittest
from pathlib import Path
from unittest import mock

# Loading the hyphenated script by file path must not leave a bytecode cache in
# scripts/__pycache__ (that would be an untracked, out-of-scope changed path).
sys.dont_write_bytecode = True

def _drop_own_bytecode_cache() -> None:
    """Delete the ``.pyc`` CPython caches for THIS module while loading it.

    ``python -m unittest tests/test_honor_phone_probe.py`` imports this file and
    the loader writes its bytecode cache *before* the module body runs, so the
    ``sys.dont_write_bytecode`` line above cannot prevent this single file. The
    leftover ``tests/__pycache__/*.pyc`` is an untracked binary path outside the
    work order's scope, so drop it as soon as the body executes (again at
    interpreter shutdown, in case a loader caches it after the body).
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
SCRIPT_PATH = REPO_ROOT / "scripts" / "honor-phone-probe.py"
DOC_PATH = REPO_ROOT / "docs" / "honor-win-probe.md"

FAKE_SERIAL = "TESTSERIAL0001"

# The probe script reads the helper-phone serial from the environment, so the
# value must be set before the module is loaded (below) and is never a real one.
os.environ["MIRING_HONOR_SERIAL"] = FAKE_SERIAL

TARGET_MDNS = f"adb-{FAKE_SERIAL}-Hvz49o._adb-tls-connect._tcp"
TARGET_IP = "192.0.2.10:5555"

NEW_APK_SHA256 = "f516de2e28a14c2338a376f2ae97f0ea37f9e4a7bca7bd68c1d1e297b4b4bc31"
OLD_APK_SHA256 = "ae1d1cd04aa9c9dadc824db7dcd644a05d47cd8e66be76a6e89e325bef1380a6"

IDENTITY_RECORD = (
    "path=C:\\program1\\MiNavigateRing-integration\\.coagent-worktrees\\"
    "MiRing-V0-probe-20261012\\app\\build\\outputs\\apk\\debug\\app-debug.apk\n"
    "package=com.minavigatering.probe\n"
    "versionCode=1\n"
    "versionName=0.1.0\n"
    "size=11485258\n"
    f"sha256={NEW_APK_SHA256}\n"
    "OK\n"
)


def _load_module():
    spec = importlib.util.spec_from_file_location("honor_phone_probe", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


probe = _load_module()


class ImportSafetyTest(unittest.TestCase):
    def test_module_loads_from_path(self):
        self.assertTrue(SCRIPT_PATH.is_file())
        for name in ("main", "parse_devices", "order_transports", "identity_matches"):
            self.assertTrue(hasattr(probe, name), name)

    def test_import_does_not_execute_main(self):
        """Importing the script must not run main() or touch adb."""
        with mock.patch("subprocess.run") as run_mock:
            _load_module()
        self.assertEqual(run_mock.call_count, 0, "import must not spawn adb")

    def test_import_has_no_doc_side_effect(self):
        """Importing must not append an evidence section to the document."""
        before = DOC_PATH.read_bytes() if DOC_PATH.exists() else None
        _load_module()
        after = DOC_PATH.read_bytes() if DOC_PATH.exists() else None
        self.assertEqual(before, after)


class ParseDevicesTest(unittest.TestCase):
    def test_parses_serial_and_state(self):
        output = (
            "List of devices attached\n"
            f"{TARGET_MDNS}\tdevice\n"
            f"{TARGET_IP}\tdevice\n"
            "SOMEOFFLINEDEV\tunauthorized\n"
            "\n"
            "* daemon started successfully\n"
        )
        self.assertEqual(
            probe.parse_devices(output),
            [(TARGET_MDNS, "device"), (TARGET_IP, "device"), ("SOMEOFFLINEDEV", "unauthorized")],
        )

    def test_ignores_daemon_chatter(self):
        output = (
            "adb server version (41) doesn't match this client (39); killing...\n"
            "List of devices attached\n"
        )
        self.assertEqual(probe.parse_devices(output), [])


class OrderTransportsTest(unittest.TestCase):
    def test_mdns_transport_goes_first(self):
        ordered = probe.order_transports([(TARGET_IP, "device"), (TARGET_MDNS, "device")])
        self.assertEqual(ordered[0][0], TARGET_MDNS)
        self.assertEqual(ordered[1][0], TARGET_IP)

    def test_mdns_first_when_already_first(self):
        ordered = probe.order_transports([(TARGET_MDNS, "device"), (TARGET_IP, "device")])
        self.assertEqual([serial for serial, _ in ordered], [TARGET_MDNS, TARGET_IP])

    def test_non_device_states_dropped(self):
        ordered = probe.order_transports([(TARGET_MDNS, "offline"), (TARGET_IP, "device")])
        self.assertEqual(ordered, [(TARGET_IP, "device")])

    def test_accepts_raw_adb_output(self):
        output = (
            "List of devices attached\n"
            f"{TARGET_IP}\tdevice\n"
            f"{TARGET_MDNS}\tdevice\n"
        )
        self.assertEqual(probe.order_transports(output)[0][0], TARGET_MDNS)

    def test_unrelated_mdns_is_not_prioritised(self):
        other = "adb-SOMEOTHERSERIAL-abc._adb-tls-connect._tcp"
        self.assertFalse(probe.is_target_mdns_serial(other))
        self.assertEqual(probe.order_transports([(other, "device")]), [(other, "device")])


class IdentityMatchesTest(unittest.TestCase):
    def _props(self, **overrides):
        base = {
            "ro.serialno": FAKE_SERIAL,
            "ro.product.brand": "HONOR",
            "ro.product.model": "AAP-AN00",
        }
        base.update(overrides)
        return base

    def test_full_identity_matches(self):
        self.assertTrue(probe.identity_matches(self._props()))

    def test_empty_marketname_does_not_affect_match(self):
        self.assertTrue(probe.identity_matches(self._props(**{"ro.product.marketname": ""})))

    def test_any_single_mismatch_is_false(self):
        cases = {
            "brand not HONOR": {"ro.product.brand": "Xiaomi"},
            "wrong serial": {"ro.serialno": "OTHER123"},
            "wrong model": {"ro.product.model": "K90"},
            "missing brand": {"ro.product.brand": None},
        }
        for label, override in cases.items():
            with self.subTest(label):
                props = self._props()
                if override.get("ro.product.brand", "") is None:
                    del props["ro.product.brand"]
                else:
                    props.update(override)
                self.assertFalse(probe.identity_matches(props))

    def test_parse_props_reads_getprop_format(self):
        raw = (
            f"[ro.serialno]: [{FAKE_SERIAL}]\n"
            "[ro.product.brand]: [HONOR]\n"
            "[ro.product.model]: [AAP-AN00]\n"
            "[ro.product.marketname]: []\n"
            "[ro.build.version.release]: [16]\n"
        )
        props = probe.parse_props(raw)
        self.assertTrue(probe.identity_matches(props))
        self.assertEqual(props["ro.build.version.release"], "16")
        self.assertEqual(props["ro.product.marketname"], "")


class MainExitCodeTest(unittest.TestCase):
    """main() must not look like a success when nothing was really installed.

    ``append_doc`` is always mocked so no test ever appends to
    docs/honor-win-probe.md.
    """

    def _run_main(self, adb_side_effect):
        appended = []
        identity = mock.MagicMock()
        identity.exists.return_value = True
        # The gate must pass here so these cases can reach the adb phase; the
        # record is matched to the hash the APK mock reports.
        identity.read_text.return_value = IDENTITY_RECORD.replace(NEW_APK_SHA256, "0" * 64)
        with mock.patch.object(probe, "APK_PATH") as apk, \
                mock.patch.object(probe, "IDENTITY_PATH", identity), \
                mock.patch.object(probe, "apk_sha256", return_value="0" * 64), \
                mock.patch.object(probe, "append_doc", side_effect=appended.append), \
                mock.patch.object(probe, "run_adb", side_effect=adb_side_effect) as run_mock:
            apk.exists.return_value = True
            code = probe.main()
        return code, appended, run_mock

    @staticmethod
    def _argvs(run_mock):
        return [list(call.args[0]) for call in run_mock.call_args_list if call.args]

    def test_no_matching_device_is_not_success_and_does_not_install(self):
        """adb lists no transport at all: exit is non-zero, install is never run."""
        def side_effect(argv, timeout_ms):
            return probe.AdbCall(tuple(argv), 0, "List of devices attached\n", "", False)

        code, appended, run_mock = self._run_main(side_effect)
        self.assertEqual(code, 2, "no matching device must exit 2, never 0")
        self.assertNotEqual(code, 0, "no matching device must not report success")
        self.assertEqual(len(appended), 1, "an evidence section is still rendered")
        self.assertNotIn("已执行", appended[0], "no unmatched run may look executed")
        for argv in self._argvs(run_mock):
            self.assertNotIn("install", argv, argv)
        self.assertEqual(run_mock.call_count, len(self._argvs(run_mock)))

    def test_getprop_timeout_is_not_success_and_does_not_install(self):
        """A listed device that times out on getprop matches nothing."""
        def side_effect(argv, timeout_ms):
            if list(argv[:2]) == [probe.ADB, "devices"]:
                return probe.AdbCall(
                    tuple(argv), 0,
                    "List of devices attached\n" + TARGET_MDNS + "\tdevice\n", "", False,
                )
            return probe.AdbCall(tuple(argv), None, "", "", True)

        code, appended, run_mock = self._run_main(side_effect)
        self.assertEqual(code, 2, "a getprop timeout must exit 2, never 0")
        self.assertNotEqual(code, 0, "a getprop timeout must not report success")
        self.assertNotIn("真实退出码 0", appended[0], "no fabricated success code")
        self.assertIn(probe.UNVERIFIED, appended[0])
        queried = self._argvs(run_mock)
        self.assertTrue(any("getprop" in argv for argv in queried), queried)
        for argv in queried:
            self.assertNotIn("install", argv, argv)


class IdentityGateTest(unittest.TestCase):
    """The gate must reject an old package and any record/APK hash mismatch."""

    def test_matching_record_passes(self):
        self.assertEqual(probe.identity_gate(IDENTITY_RECORD, NEW_APK_SHA256), (True, "ok"))

    def test_old_apk_hash_is_rejected(self):
        self.assertEqual(probe.OLD_APK_SHA256, OLD_APK_SHA256)
        stale_record = IDENTITY_RECORD.replace(NEW_APK_SHA256, OLD_APK_SHA256)
        passed, reason = probe.identity_gate(stale_record, OLD_APK_SHA256)
        self.assertFalse(passed)
        self.assertEqual(reason, "stale apk")

    def test_record_hash_not_matching_apk_bytes_is_rejected(self):
        passed, reason = probe.identity_gate(IDENTITY_RECORD, OLD_APK_SHA256)
        self.assertFalse(passed)
        self.assertNotEqual(reason, "ok")

    def test_missing_ok_line_is_rejected(self):
        without_ok = "\n".join(
            line for line in IDENTITY_RECORD.splitlines() if line.strip() != "OK"
        )
        self.assertFalse(probe.identity_gate(without_ok, NEW_APK_SHA256)[0])

    def test_blank_required_field_is_rejected(self):
        blanked = IDENTITY_RECORD.replace("versionCode=1\n", "versionCode=\n")
        self.assertFalse(probe.identity_gate(blanked, NEW_APK_SHA256)[0])

    def test_wrong_package_or_version_is_rejected(self):
        for broken in (
            IDENTITY_RECORD.replace("package=com.minavigatering.probe", "package=com.other.probe"),
            IDENTITY_RECORD.replace("versionCode=1", "versionCode=2"),
            IDENTITY_RECORD.replace("versionName=0.1.0", "versionName=0.2.0"),
            IDENTITY_RECORD.replace(NEW_APK_SHA256, NEW_APK_SHA256.upper()),
        ):
            with self.subTest(broken=broken[:40]):
                self.assertFalse(probe.identity_gate(broken, NEW_APK_SHA256)[0])

class IdentityGateMainFlowTest(unittest.TestCase):
    """main() must gate on the record before it ever calls adb."""

    def _run_main(self, adb_side_effect, identity_text=None, identity_exists=True):
        appended = []
        identity = mock.MagicMock()
        identity.exists.return_value = identity_exists
        if identity_text is None:
            # Default: a record that matches the hash the APK mock reports, so a
            # test only reaches the adb phase when it intends to.
            identity_text = IDENTITY_RECORD
        identity.read_text.return_value = identity_text
        with mock.patch.object(probe, "APK_PATH") as apk, \
                mock.patch.object(probe, "IDENTITY_PATH", identity), \
                mock.patch.object(probe, "apk_sha256", return_value=NEW_APK_SHA256), \
                mock.patch.object(probe, "append_doc", side_effect=appended.append), \
                mock.patch.object(probe, "run_adb", side_effect=adb_side_effect) as run_mock:
            apk.exists.return_value = True
            code = probe.main()
        return code, appended, run_mock

    @staticmethod
    def _no_adb(argv, timeout_ms):
        raise AssertionError(f"adb must not be called by the gate: {list(argv)}")

    def test_stale_apk_exits_1_without_any_adb_call(self):
        stale = IDENTITY_RECORD.replace(NEW_APK_SHA256, OLD_APK_SHA256)
        code, appended, run_mock = self._run_main(self._no_adb, identity_text=stale)
        self.assertEqual(code, 1)
        self.assertEqual(run_mock.call_count, 0, "no adb call before the gate passes")
        self.assertEqual(len(appended), 1)

    def test_missing_identity_file_exits_1_without_any_adb_call(self):
        code, appended, run_mock = self._run_main(
            self._no_adb, identity_text="", identity_exists=False,
        )
        self.assertEqual(code, 1)
        self.assertEqual(run_mock.call_count, 0)
        self.assertEqual(len(appended), 1)

    def test_gate_failure_never_reports_install(self):
        stale = IDENTITY_RECORD.replace(NEW_APK_SHA256, OLD_APK_SHA256)
        _code, appended, _run_mock = self._run_main(self._no_adb, identity_text=stale)
        self.assertNotIn("已执行", appended[0])

    def test_matched_device_installs_and_starts_only_that_transport(self):
        """A matched device gets install/am; exit 0 needs both calls at 0."""
        seen = []

        def side_effect(argv, timeout_ms):
            argv = list(argv)
            seen.append(argv)
            if argv[:2] == [probe.ADB, "devices"]:
                return probe.AdbCall(tuple(argv), 0,
                                     "List of devices attached\n" + TARGET_MDNS + "\tdevice\n", "", False)
            if "getprop" in argv:
                return probe.AdbCall(
                    tuple(argv), 0,
                    f"[ro.serialno]: [{FAKE_SERIAL}]\n"
                    "[ro.product.brand]: [HONOR]\n"
                    "[ro.product.model]: [AAP-AN00]\n",
                    "", False,
                )
            if "install" in argv:
                return probe.AdbCall(tuple(argv), 0, "Success\n", "", False)
            return probe.AdbCall(tuple(argv), 0,
                                 "versionName=0.1.0\n" if "dumpsys" in argv else "", "", False)

        code, appended, _run_mock = self._run_main(side_effect)
        self.assertEqual(code, 0)
        device_actions = [argv for argv in seen if any(
            word in argv for word in ("install", "am", "dumpsys", "pm"))]
        self.assertTrue(device_actions)
        for argv in device_actions:
            self.assertIn(TARGET_MDNS, argv, argv)
            self.assertNotIn(TARGET_IP, argv, argv)
        self.assertIn("真实退出码 0", appended[0])

    def test_am_start_failure_is_not_success(self):
        def side_effect(argv, timeout_ms):
            argv = list(argv)
            if argv[:2] == [probe.ADB, "devices"]:
                return probe.AdbCall(tuple(argv), 0,
                                     "List of devices attached\n" + TARGET_MDNS + "\tdevice\n", "", False)
            if "getprop" in argv:
                return probe.AdbCall(
                    tuple(argv), 0,
                    f"[ro.serialno]: [{FAKE_SERIAL}]\n"
                    "[ro.product.brand]: [HONOR]\n"
                    "[ro.product.model]: [AAP-AN00]\n",
                    "", False,
                )
            if "install" in argv:
                return probe.AdbCall(tuple(argv), 0, "Success\n", "", False)
            if "am" in argv:
                return probe.AdbCall(tuple(argv), 1, "", "Error: Activity not started", False)
            return probe.AdbCall(tuple(argv), 0, "", "", False)

        code, appended, _run_mock = self._run_main(side_effect)
        self.assertEqual(code, 1, "a failed am start must not exit 0")
        self.assertIn("**1**", appended[0], "the real am start exit code is recorded")

class MissingTargetSerialFailClosedTest(unittest.TestCase):
    """An empty helper-phone serial must fail closed before any adb call."""

    def test_main_exits_2_without_calling_adb(self):
        with mock.patch.object(probe, "TARGET_SERIAL", ""), \
                mock.patch.object(probe, "run_adb") as run_mock, \
                mock.patch.object(probe, "append_doc") as append_mock, \
                mock.patch.object(probe.sys, "stderr") as stderr_mock:
            code = probe.main()
        self.assertEqual(code, 2, "a missing serial must exit 2, never 0")
        self.assertEqual(probe.MISSING_TARGET_SERIAL_EXIT, 2)
        self.assertEqual(run_mock.call_count, 0, "no adb call without a target serial")
        self.assertEqual(append_mock.call_count, 0, "no evidence section without a serial")
        note = "".join(str(call.args[0]) for call in stderr_mock.write.call_args_list)
        self.assertIn("MIRING_HONOR_SERIAL", note)
        self.assertIn("ro.serialno", note)

    def test_identity_functions_reject_everything_without_a_serial(self):
        with mock.patch.object(probe, "TARGET_SERIAL", ""):
            self.assertFalse(
                probe.identity_matches(
                    {
                        "ro.serialno": "",
                        "ro.product.brand": "HONOR",
                        "ro.product.model": "AAP-AN00",
                    }
                )
            )
            self.assertFalse(probe.is_target_mdns_serial(TARGET_MDNS))
            self.assertFalse(
                probe.is_target_mdns_serial("adb-_adb-tls-connect._tcp")
            )


if __name__ == "__main__":
    unittest.main()
