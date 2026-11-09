# Sheet-Music-Recognition

Convert sheet-music images and PDFs to MusicXML, MIDI, MP3, or JSON. Recognition runs locally.

[![Build and test](https://github.com/Luckolite/Sheet-Music-Recognition/actions/workflows/ci.yml/badge.svg)](https://github.com/Luckolite/Sheet-Music-Recognition/actions/workflows/ci.yml)
[Developer docs](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/README.md) · [Downloads](https://github.com/Luckolite/Sheet-Music-Recognition/releases)

## Install and use

Install from source with Python 3.10 or 3.11 and JDK 17 or newer. Set `JAVA_HOME` or put the JDK tools on your PATH.

```sh
git clone https://github.com/Luckolite/Sheet-Music-Recognition.git
cd Sheet-Music-Recognition
python -m venv .venv
```

Activate the environment: `.\.venv\Scripts\Activate.ps1` in PowerShell or `source .venv/bin/activate` on macOS/Linux. Then build, install, and try the included score:

```sh
python scripts/build_java.py
python -m pip install ".[pdf,audio]"
sheet-music-recognition examples/scale.png -o score.json --musicxml score.musicxml
```

Use your own image or PDF in place of `examples/scale.png`. JSON output (`-o`) is required. Add `--midi preview.mid` or `--mp3 preview.mp3` for playback, or run `sheet-music-recognition --help` for all options.

## What it supports

- Reads printed staff notation and 6- or 7-string guitar tab.
- Exports concert-pitch MusicXML for editing, MIDI and MP3 for playback, and JSON for note and page data.

Recognition is experimental. Check exports against the original score.

## Develop

See the [Python and Java examples](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/integration.md) and the [contribution guide](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/CONTRIBUTING.md). The code and bundled weights are [Apache-2.0 licensed](https://github.com/Luckolite/Sheet-Music-Recognition/blob/main/docs/licensing.md), including for commercial and closed-source use.
