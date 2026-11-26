# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Build the optional Android ARM64 OCR custom operators."""

import argparse
from hashlib import sha256
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zig", type=Path)
    parser.add_argument(
        "headers", type=Path, help="Extracted ONNX Runtime Android 1.25.1 headers"
    )
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    zig = args.zig.resolve(strict=True)
    headers = args.headers.resolve(strict=True)
    if subprocess.check_output([zig, "version"], text=True).strip() != "0.15.2":
        parser.error("Expected Zig 0.15.2")
    expected_headers = {
        "onnxruntime_c_api.h": "4763b298a1ab8b5df88c3949b560c1e00a3605e61995d2d3243fd8007679d585",
        "onnxruntime_ep_c_api.h": "cf9d5508894b09cb0b0f84405de873a0ba78a35c01ffc28b14096c65fe7abbff",
    }
    for name, expected in expected_headers.items():
        if sha256((headers / name).read_bytes()).hexdigest() != expected:
            parser.error(f"Unexpected header checksum for {name}")
    includes = zig.parent / "lib/libc/include"
    source = Path(__file__).with_name("packed_recognizer.c").resolve()
    with TemporaryDirectory(dir=args.output.resolve().parent) as temp:
        binary = Path(temp) / "libscore_reader_packed_ocr.so"
        subprocess.run(
            [
                zig,
                "cc",
                "-target",
                "aarch64-linux-musl",
                "-shared",
                "-fPIC",
                "-nostdlib",
                "-O3",
                "-fno-stack-protector",
                "-fno-builtin",
                "-ffp-contract=off",
                "-mno-outline-atomics",
                "-Wl,--no-undefined,-z,max-page-size=16384,-z,common-page-size=16384,-s",
                "-isystem",
                includes / "aarch64-linux-musl",
                "-isystem",
                includes / "generic-musl",
                "-isystem",
                includes / "any-linux-any",
                "-I",
                headers,
                source,
                "-o",
                binary,
            ],
            check=True,
        )
        digest = sha256(binary.read_bytes()).hexdigest()
        if digest != "33e277d9de0a25cbe2568f220d0765dd53772c40efa8100ff900acd73be5d054":
            parser.error(f"Unexpected custom library checksum {digest}")
        binary.replace(args.output.resolve())
    print(digest)


if __name__ == "__main__":
    main()
