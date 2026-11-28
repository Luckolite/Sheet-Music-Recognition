# LilyPond runtime wheels

The `score-reader-lilypond` wheels carry the unmodified upstream LilyPond 2.26.0
runtime archives, including their component notices. The Python unpacker in this
directory is Apache-2.0. LilyPond is GPL-3.0-or-later and the other bundled
components retain the terms in the upstream archive's `licenses` directory.

Build a wheel using the official archive for each target. The builder checks
the archive's SHA-256 before including it.

```sh
python -m pip install wheel
python scripts/build_lilypond_wheel.py win_amd64 lilypond-2.26.0-mingw-x86_64.zip --out wheelhouse
```

Other targets are `manylinux_2_28_x86_64`, `macosx_10_15_x86_64`, and
`macosx_11_0_arm64`. See the [upstream download page](https://lilypond.org/download.html)
for the matching files. Test installation and engraving on each target before
publication. When distributing the runtime wheels, provide the corresponding
source for the exact upstream binaries and their covered components under the
applicable license terms. The [upstream source page](https://lilypond.org/source.html)
and [2.26.0 release](https://gitlab.com/lilypond/lilypond/-/releases/v2.26.0)
identify the build and are starting points for source collection.
