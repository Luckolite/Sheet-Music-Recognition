# Contributing

When [reporting a bug](https://github.com/Luckolite/Sheet-Music-Recognition/issues), include a small reproducer, what you expected, and what happened. For recognition bugs, name the page and measure and give the expected notes or rhythms. Use a synthetic, public-domain, or licensed example and include its source and license. Keep private scores, libraries, logs, and credentials out of public reports.

For a fix, add a regression when practical. Use rules that work across scores and keep formatting changes separate. Describe what you tested on generated examples and on real scores.

Contributions are under Apache-2.0. By submitting material, you confirm you have the rights to contribute it under that license.

## Build from source

Clone the repository and activate a Python 3.10 or 3.11 virtual environment.
With JDK 17+ installed, run:

```sh
python scripts/build_java.py
python -m pip install ".[pdf,audio]"
```

## Run the checks

While iterating, select exact Java test class names with repeatable `--test`
arguments. For example, run `python scripts/test_java.py --test ShortFinalStaffTest`.
Build the Java sources again after changing production Java code. Run the full
checks below before publishing a change.

```sh
python scripts/test_java.py --jobs 4
python -m unittest discover -s tests
python scripts/smoke_test.py
python scripts/verify_package.py
```

`--jobs 4` runs the Java tests across four isolated JVMs with 512 MiB heaps
by default. Logs and results are saved under `build/test-parallel`. Omit
`--jobs` to run the suite serially.

For changes to the pickup-measure caller, also run this after building the JAR. Use a fresh output directory each time.

```sh
python scripts/check_pickup_ingress.py --public . --classpath src/sheet_music_recognition/interpreter.jar --out build/pickup-ingress
```

## Code style

Use Google Java Format 1.24.0 in AOSP mode. To preserve existing imports and literals:

```sh
java -jar google-java-format-1.24.0-all-deps.jar --aosp --skip-sorting-imports --skip-removing-unused-imports --skip-reflowing-long-strings --skip-javadoc-formatting --replace FILE.java
```

## Maintainers

When porting changes from the app, run `python scripts/check_app_drift.py --app <active-app-worktree>` and review helpers, adapters, and assets outside the mapped files. See [source provenance](docs/source-provenance.md). Keep weight hashes, lineage, and evaluation in sync; publish new weights with a new release. To check the Android and native pickup callers, add `--app <app-worktree>` to the pickup check and use the matching core classpath. This checks source calls, without running Android or the packaged worker.
