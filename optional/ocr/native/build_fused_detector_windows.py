# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Build the optional Windows x64 exact-affine detector operations."""

import argparse
from hashlib import sha256
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

from build_packed_recognizer import pinned_toolchain


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zig", type=Path)
    parser.add_argument("headers", type=Path, help="Extracted ONNX Runtime 1.25.1 AAR headers")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        zig, headers = pinned_toolchain(args.zig, args.headers)
    except ValueError as error:
        parser.error(str(error))
    source = Path(__file__).with_name("detector_affine.c").resolve()
    with TemporaryDirectory(dir=args.output.resolve().parent) as temp:
        binary = Path(temp) / "score_reader_fused_detector.dll"
        subprocess.run([
            zig, "cc", "-target", "x86_64-windows-gnu", "-shared", "-O3",
            "-ffp-contract=off", "-s", "-DRegisterDetectorAffineOps=RegisterCustomOps",
            "-I", headers, source, "-o", binary,
        ], check=True)
        digest = sha256(binary.read_bytes()).hexdigest()
        if digest != "8c1ee63c6529d4f7eced11719fa23a0badd63915924e1282f68b54d6deab9d10":
            parser.error(f"Unexpected custom library checksum {digest}")
        binary.replace(args.output.resolve())
    print(digest)


if __name__ == "__main__":
    main()
