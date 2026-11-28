# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Build an optional renderer wheel from an unmodified upstream archive."""
import argparse
import hashlib
from pathlib import Path

from wheel.wheelfile import WheelFile

VERSION = '2.26.0'
ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'score_reader_lilypond'
UPSTREAM = f'https://gitlab.com/lilypond/lilypond/-/releases/v{VERSION}'
PLATFORMS = {
    'win_amd64': ('mingw-x86_64.zip', '14ddddc233b469ef60d4ffc1c7f35520f15ac6b58e38cab458c60bdcc3af650c'),
    'manylinux_2_28_x86_64': ('linux-x86_64.tar.gz', 'cd8a097a9f52cb2b9f4e7914774786f203f4fc61fcd299afcbb63c23fa5c6b24'),
    'macosx_10_15_x86_64': ('darwin-x86_64.tar.gz', '6dcbca34b13ad6d4ba3606a0b48edd02688284fcd52e9c00141242df1996a148'),
    'macosx_11_0_arm64': ('darwin-arm64.tar.gz', '18ffc454fef3753c26a015d95a3c232f89b22f052b897c046e0198740a1221be'),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('platform', choices=PLATFORMS)
    parser.add_argument('archive', type=Path, help='Official LilyPond binary archive')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    suffix, expected = PLATFORMS[args.platform]
    with args.archive.open('rb') as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        parser.error(f'SHA-256 mismatch for lilypond-{VERSION}-{suffix}')
    args.out.mkdir(parents=True, exist_ok=True)
    tag = f'py3-none-{args.platform}'
    dist_info = f'{PACKAGE}-{VERSION}.dist-info'
    path = args.out / f'{PACKAGE}-{VERSION}-{tag}.whl'
    source = ROOT / 'packaging' / 'lilypond'
    with WheelFile(path, 'w') as wheel:
        wheel.write(source / PACKAGE / '__init__.py', f'{PACKAGE}/__init__.py')
        payload = 'runtime.zip' if suffix.endswith('.zip') else 'runtime.tar.gz'
        wheel.write(args.archive, f'{PACKAGE}/{payload}')
        wheel.write(source / 'README.md', f'{PACKAGE}/README.md')
        wheel.write(ROOT / 'LICENSE', f'{dist_info}/licenses/Apache-2.0.txt')
        wheel.writestr(f'{dist_info}/METADATA', (
            'Metadata-Version: 2.1\nName: score-reader-lilypond\n'
            f'Version: {VERSION}\nSummary: Bundled LilyPond runtime for Score Reader\n'
            'Requires-Python: >=3.10\n'
            f'Project-URL: Upstream release, {UPSTREAM}\n\n'
            'The runtime archive includes upstream licenses and notices.\n'))
        wheel.writestr(f'{dist_info}/WHEEL',
                       f'Wheel-Version: 1.0\nRoot-Is-Purelib: false\nTag: {tag}\n')
    print(path)


if __name__ == '__main__':
    main()
