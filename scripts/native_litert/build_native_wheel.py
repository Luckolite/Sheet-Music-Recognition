# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Build the optional Windows x64 score-reader wheel."""
import argparse
from tempfile import TemporaryDirectory
from pathlib import Path
import platform
import shutil
import subprocess
import sys

SOURCE = Path(__file__).resolve().parent


def run(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True)


def compile_kernel(output):
    if shutil.which("cl.exe") is None or shutil.which("link.exe") is None:
        raise RuntimeError("MSVC x64 build tools are required")
    cl = ("cl.exe", "/nologo", "/c", "/O2", "/MT", "/std:c++17")
    run(*cl, "/arch:AVX2", "/fp:strict", "/EHsc", str(SOURCE / "sparse_conv.cc"), cwd=output)
    run(*cl, str(SOURCE / "cpu_support.cc"), cwd=output)
    run("link.exe", "/nologo", "/DLL", "/OUT:_score_sparse_avx2.dll",
        "sparse_conv.obj", "cpu_support.obj", cwd=output)
    return output / "_score_sparse_avx2.dll"


def build(base, out, scratch):
    if sys.platform != "win32" or platform.machine().upper() != "AMD64":
        raise RuntimeError("The native wheel targets Windows x64")
    if not base.name.startswith("score_reader-") or not base.name.endswith("-py3-none-any.whl"):
        raise ValueError("Pass the base score_reader universal wheel")
    out.mkdir(exist_ok=True, parents=True)
    scratch.mkdir(exist_ok=True, parents=True)
    with TemporaryDirectory(dir=scratch) as folder:
        work = Path(folder)
        unpacked = work / "unpacked"
        run(sys.executable, "-m", "wheel", "unpack", str(base), "-d", str(unpacked))
        stage, = unpacked.iterdir()
        package = stage / "sheet_music_recognition"
        wheel_info, = stage.glob("*.dist-info/WHEEL")
        metadata = wheel_info.read_text(encoding="utf-8")
        universal, windows = "Tag: py3-none-any", "Tag: py3-none-win_amd64"
        if metadata.count(universal) != 1:
            raise ValueError("Unexpected base wheel tags")
        wheel_info.write_text(metadata.replace(universal, windows, 1), encoding="utf-8")
        if not (package / "_sparse_litert.py").is_file():
            raise ValueError("Build the universal wheel from a source tree with the native runtime")
        shutil.copy2(compile_kernel(work), package)
        run(sys.executable, "-B", str(SOURCE / "generate_native_model.py"), str(stage))
        run(sys.executable, "-m", "wheel", "pack", str(stage), "-d", str(out))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_wheel", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--scratch", required=True, type=Path)
    options = parser.parse_args()
    build(options.base_wheel.resolve(), options.out.resolve(), options.scratch.resolve())
