# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Runtime lookup for score inference and Java decoding."""
from contextlib import contextmanager
from hashlib import sha256
import os
import platform
import shutil
import stat
import subprocess
import sys
from pathlib import Path


_SHORT_DECODER_MAX_PIXELS = 1_200_000
_ARCHIVE_TRAIN_AND_GEOMETRY_MAX_PIXELS = 500_000

_SHORT_DECODER_BUILDS = (
    ('IMPLEMENTOR="JetBrains s.r.o."', 'JAVA_RUNTIME_VERSION="21.0.10+-14961533-b1163.108"'),
    ('IMPLEMENTOR="Eclipse Adoptium"', 'JAVA_RUNTIME_VERSION="17.0.20.1+1"'),
    ('IMPLEMENTOR="Oracle Corporation"', 'JAVA_VERSION="19.0.1"', 'SOURCE=".:git:aa986c51a096"'),
)


def java_executable():
    if os.environ.get('JAVA_HOME'):
        path = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        if path.is_file():
            return str(path)
    path = shutil.which('java')
    if not path:
        raise RuntimeError('Install Java 17 or newer and put java on PATH or set JAVA_HOME')
    return path


def small_page_jvm_args(java, pixels):
    """Avoid higher-tier compilation for short runs on qualified HotSpot builds."""
    if sys.platform != "win32" or not 0 < pixels <= _SHORT_DECODER_MAX_PIXELS:
        return ()
    if any(os.environ.get(name) for name in ("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")):
        return ()
    try:
        release = set((Path(java).parent.parent / "release").read_text(encoding="utf-8").splitlines())
    except (OSError, UnicodeError):
        return ()
    if any(release.issuperset(build) for build in _SHORT_DECODER_BUILDS):
        return ("-XX:TieredStopAtLevel=1",)
    return ()


def run_page_decoder(java, jar, pixels, arguments):
    """Run a single page, reusing local JVM class data on qualified short reads."""
    short = small_page_jvm_args(java, pixels)
    command = [java, "-Xmx1g", *short, "-jar", str(jar), *arguments]
    appdata = Path(os.environ.get("LOCALAPPDATA", ""))
    if short and appdata.is_absolute():
        cache = appdata / "ScoreReader" / "cds-v1"
        if pixels <= _ARCHIVE_TRAIN_AND_GEOMETRY_MAX_PIXELS:
            return _run_with_class_archive(command, java, jar, cache)
        if _archive_marked_ready(cache):
            with _ready_class_archive(java, jar, cache) as options:
                if options is not None:
                    result = _run_java(command, options)
                    if result.returncode == 0:
                        return result
    return _run_java(command)


def _run_java(command, options=()):
    return subprocess.run([command[0], *options, *command[1:]], capture_output=True, text=True)


def _run_with_class_archive(command, java, jar, cache):
    with _class_archive_slot(java, jar, cache) as slot:
        if slot is not None:
            resolved, state = slot
            try:
                return _use_or_train_archive(command, resolved, cache, state)
            except OSError:
                pass
    return _run_java(command)


@contextmanager
def _class_archive_slot(java, jar, cache):
    import msvcrt

    try:
        java = Path(java).resolve(strict=True)
        jar = Path(jar).resolve(strict=True)
        modules = java.parent.parent / "lib" / "modules"
        runtime = modules.stat()
        jar_time = jar.stat().st_mtime_ns
        key = sha256()
        with jar.open("rb") as source:
            for chunk in iter(lambda: source.read(128 * 1024), b""):
                key.update(chunk)
        key.update(
            repr((str(java), runtime.st_size, runtime.st_mtime_ns, str(jar), jar_time)).encode()
        )
        state = key.hexdigest()
        cache.mkdir(parents=True, exist_ok=True)
        lock = (cache / "lock").open("a+b")
    except OSError:
        yield None
        return

    # Closing the handle releases the lock, including when a launch raises.
    with lock:
        try:
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            yield None
        else:
            yield java, state


def _archive_marked_ready(cache):
    try:
        return (cache / "state").read_text(encoding="ascii", errors="replace").endswith(":ready")
    except OSError:
        return False


@contextmanager
def _ready_class_archive(java, jar, cache):
    with _class_archive_slot(java, jar, cache) as slot:
        arguments = None
        if slot is not None:
            resolved, state = slot
            try:
                if (cache / "state").read_text(encoding="ascii", errors="replace") == state + ":ready":
                    arguments = _archive_arguments(resolved, cache)
            except OSError:
                pass
        yield arguments


@contextmanager
def ready_page_decoder(jar, pixels, *, short_tier=True):
    """Hold the current warm archive while a pipe decoder starts."""
    cache = Path(os.environ.get("LOCALAPPDATA", ""))
    max_pixels = _SHORT_DECODER_MAX_PIXELS if short_tier else _ARCHIVE_TRAIN_AND_GEOMETRY_MAX_PIXELS
    if sys.platform != "win32" or not 0 < pixels <= max_pixels or not cache.is_absolute():
        yield None
        return
    cache = cache / "ScoreReader" / "cds-v1"
    try:
        java = java_executable() if _archive_marked_ready(cache) else None
    except (OSError, RuntimeError):
        java = None
    if java is None:
        yield None
        return
    short = small_page_jvm_args(java, pixels)
    if not short:
        yield None
        return
    with _ready_class_archive(java, jar, cache) as options:
        yield [java, "-Xmx1g", *(short if short_tier else ()), *options] if options is not None else None


def _archive_arguments(java, cache):
    app = cache / "decoder.jsa"
    platform_archive = cache / "platform.jsa"
    needs_platform = not (java.parent / "server" / "classes.jsa").is_file()
    if not app.is_file() or (needs_platform and not platform_archive.is_file()):
        return None
    files = os.pathsep.join((str(platform_archive), str(app))) if needs_platform else str(app)
    return ("-Xshare:on", f"-XX:SharedArchiveFile={files}")


def _set_archive_state(cache, state):
    try:
        (cache / "state").write_text(state, encoding="ascii")
    except OSError:
        pass


def _clear_archive(path):
    # HotSpot publishes archives as read-only files on Windows.
    if not path.is_symlink() and path.is_file():
        path.chmod(path.stat().st_mode | stat.S_IWRITE)
    path.unlink(missing_ok=True)


def _use_or_train_archive(command, java, cache, state):
    state_file = cache / "state"
    saved = state_file.read_text(encoding="ascii", errors="replace") if state_file.is_file() else ""
    if saved not in (state, state + ":ready", state + ":disabled"):
        result = _run_java(command)
        if result.returncode == 0:
            _set_archive_state(cache, state)
        return result
    if saved == state + ":disabled":
        return _run_java(command)

    app = cache / "decoder.jsa"
    platform_archive = cache / "platform.jsa"
    needs_platform = not (java.parent / "server" / "classes.jsa").is_file()
    ready = _archive_arguments(java, cache) if saved == state + ":ready" else None
    if ready is not None:
        result = _run_java(command, ready)
        if result.returncode == 0:
            return result
        _set_archive_state(cache, state + ":disabled")
        return _run_java(command)

    platform_options = ()
    if needs_platform:
        # HotSpot records this path in the dynamic archive. Write the base at its
        # final location while other launches fall back to the ordinary JVM.
        classes = cache / "base.classlist"
        classes.write_bytes(b"")
        _clear_archive(platform_archive)
        platform_options = (f"-XX:SharedArchiveFile={platform_archive}",)
        setup = _run_java(
            [java], ("-Xshare:dump", *platform_options, f"-XX:SharedClassListFile={classes}")
        )
        if setup.returncode:
            _set_archive_state(cache, state + ":disabled")
            return _run_java(command)
    pending = cache / "decoder.pending.jsa"
    _clear_archive(pending)
    result = _run_java(command, ("-Xshare:on", *platform_options, f"-XX:ArchiveClassesAtExit={pending}"))
    if result.returncode:
        _set_archive_state(cache, state + ":disabled")
        return _run_java(command)
    try:
        _clear_archive(app)
        pending.replace(app)
        _set_archive_state(cache, state + ":ready")
    except OSError:
        _set_archive_state(cache, state + ":disabled")
    return result


def inference_interpreter(*, bundled):
    """Use the qualified Windows CPU runtime for the bundled model."""
    if bundled and sys.platform == "win32" and platform.machine() == "AMD64":
        from importlib.metadata import version
        try:
            if version("ai-edge-litert") == "2.3.0":
                from ai_edge_litert.interpreter import Interpreter
                if Path(__file__).with_name("_score_sparse_avx2.dll").is_file():
                    try:
                        from ._sparse_litert import factory_for
                        return factory_for(Interpreter)
                    except (AttributeError, ImportError, OSError, RuntimeError, ValueError):
                        pass
                return Interpreter
        except (ImportError, OSError):
            # Source installs and older workers can still use their TensorFlow runtime.
            pass

    try:
        import tensorflow as tf
    except ImportError as error:
        raise RuntimeError("Recognition dependencies are missing. Run: pip install --upgrade score-reader") from error
    return tf.lite.Interpreter
