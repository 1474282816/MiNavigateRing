#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Direct-run gate for the vc7 RPK host verification negative case.

Runs from the repository root (or anywhere) with:

    python tests/test_product_runtime_gate.py

It is a plain script, not a pytest module. It invokes the Node host with an
explicit absolute ``--rpk`` plus ``--negative missing-png`` and proves that:

  * the Node process exits non-zero,
  * its stdout never contains PRODUCT_UI_PASS,
  * its combined output mentions ``Missing PNG asset``,
  * the fixed vc7 RPK is byte-for-byte unchanged (size 52581, SHA256
    9654691d80a30a6c0bf1cfe1c992cf15c7b3d0296d1da09f1032a2db2931f3ea) across
    the run.

This is a bounded Node host check, not the official QEMU firmware, a real
device or a real AMap pass.
"""

import hashlib
import os
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RPK_PATH = "C:/program1/MiNavigateRing/deliverables/MiNavigateRing-Navigation-vc7-9654691d.rpk"
EXPECTED_SIZE = 52581
EXPECTED_SHA256 = "9654691d80a30a6c0bf1cfe1c992cf15c7b3d0296d1da09f1032a2db2931f3ea"
NODE_SCRIPT = os.path.join("scripts", "test-product-runtime.js")


def identity(path):
    with open(path, "rb") as handle:
        data = handle.read()
    return len(data), hashlib.sha256(data).hexdigest()


def tail(text, limit=2000):
    text = text or ""
    return text[-limit:]


def main():
    failures = []

    if not os.path.isfile(RPK_PATH):
        print("FAILED: fixed RPK is missing: " + RPK_PATH)
        return 1

    size_before, sha_before = identity(RPK_PATH)
    if size_before != EXPECTED_SIZE or sha_before != EXPECTED_SHA256:
        print("FAILED: fixed RPK identity before run is wrong: size=%d sha256=%s" % (size_before, sha_before))
        return 1

    argv = ["node", NODE_SCRIPT, "--rpk", RPK_PATH, "--negative", "missing-png"]
    proc = subprocess.run(
        argv,
        cwd=REPO_ROOT,
        shell=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    stdout = proc.stdout.decode("utf-8", "replace")
    stderr = proc.stderr.decode("utf-8", "replace")
    combined = stdout + "\n" + stderr

    if proc.returncode == 0:
        failures.append("node exit code was 0, expected non-zero")
    if "PRODUCT_UI_PASS" in stdout:
        failures.append("stdout contained PRODUCT_UI_PASS")
    if "Missing PNG asset" not in combined:
        failures.append("output did not contain 'Missing PNG asset'")

    size_after, sha_after = identity(RPK_PATH)
    if size_after != EXPECTED_SIZE or sha_after != EXPECTED_SHA256:
        failures.append(
            "fixed RPK identity changed: size=%d sha256=%s" % (size_after, sha_after)
        )

    if failures:
        print("FAILED: " + "; ".join(failures))
        print("observed node exit code: %s" % proc.returncode)
        print("--- stdout tail ---")
        print(tail(stdout))
        print("--- stderr tail ---")
        print(tail(stderr))
        return 1

    print("gate ok: node exit=%d, no PRODUCT_UI_PASS, 'Missing PNG asset' observed" % proc.returncode)
    print("fixed RPK unchanged: size=%d sha256=%s" % (size_after, sha_after))
    return 0


if __name__ == "__main__":
    sys.exit(main())
