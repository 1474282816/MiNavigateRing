#!/usr/bin/env python3
"""Verify the vendored xms-wearable AAR matches the pinned SHA256.

The AAR is extracted from the official Xiaomi interconnect demo ZIP; see
``docs/sdk-provenance.md``. Exits 1 on mismatch so it can gate a build.
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

EXPECTED_SHA256 = "9c40fd1c5409bb948523d503af71e2978ae522c35636afe7d64f474c0f6bc195"
EXPECTED_SIZE = 102453
AAR = Path(__file__).resolve().parent.parent / "app" / "libs" / "xms-wearable-lib_1.4_release.aar"


def main() -> int:
    if not AAR.exists():
        print(f"FAIL missing AAR: {AAR}")
        return 1

    data = AAR.read_bytes()
    actual_size = len(data)
    actual = hashlib.sha256(data).hexdigest()

    print(f"path      : {AAR}")
    print(f"size      : {actual_size} (expected {EXPECTED_SIZE})")
    print(f"sha256    : {actual}")
    print(f"expected  : {EXPECTED_SHA256}")

    if actual != EXPECTED_SHA256:
        print("FAIL sha256 mismatch")
        return 1
    if actual_size != EXPECTED_SIZE:
        print("FAIL size mismatch")
        return 1

    print("OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
