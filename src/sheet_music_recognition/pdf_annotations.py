# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Optional native PDF tablature text; geometry and symbol semantics remain in Java."""
import ctypes
import re
from functools import lru_cache

_TOKEN = re.compile(r"-(?:[12]|(?i:full))|(?i:full|whammy|w/bar|bar|upstroke|downstroke|strum up|strum down)|-?(?:1 1/2|1/4|1/2|3/4|1\u00bd|[\u00bc\u00bd\u00be])|[\uE846\uE847]|<[0-9]{1,2}>|\([0-9]{1,2}\)|(?<![0-9])[0-9]{1,2}[bB][0-9]{1,2}(?:[rR][0-9]{1,2})?~*|(?<![0-9])(?:[0-9]{1,2}|[xX])(?:[/\\hHpP][0-9]{1,2})+~*|(?<![0-9])[0-9]{1,3}(?![0-9])~*|[xXHPT=]|[\uE1E7\uE241\uE243\uE245\uE4E3-\uE4E8\uE4A2\uEAB2\uE080-\uE089\uECA5\uECA6\uECB7]")

_TUNING_HEADER = re.compile(r"^\s*tuning\s*[:=]?[^\r\n]+", re.IGNORECASE | re.MULTILINE)
_FONT_SUBSET_PREFIX = re.compile(rb"^[A-Z]{6}\+")


def tuning_header(page):
    textpage = page.get_textpage()
    try:
        match = _TUNING_HEADER.search(textpage.get_text_range())
        return match.group().strip() if match else ""
    finally:
        textpage.close()

def tuning_words(header):
    """Document tuning is metadata, independent of transformed page coordinates."""
    return [dict(text=header, left=.01, top=.001, right=.9, bottom=.009)] if header else []


def _navigation_words(textpage, text, width, height):
    # MuseJazz's legacy code points are unrelated to the same slots in SMuFL.
    # An old MuseScore export can carry them in a substituted Segoe UI Symbol font.
    markers = {"\ue177": "𝄋", "\ue178": "𝄌"}
    if not any(char in text for char in markers):
        return []
    from pypdfium2 import raw

    def font_name(index):
        buffer = ctypes.create_string_buffer(128)
        raw.FPDFText_GetFontInfo(textpage.raw, index, buffer, len(buffer), None)
        return _FONT_SUBSET_PREFIX.sub(b"", buffer.value).replace(b" ", b"")

    def positions(char):
        search = textpage.search(char)
        try:
            while (match := search.get_next()) is not None:
                yield match[0]
        finally:
            search.close()

    # Require the legacy engraving font before trusting a substituted text font.
    legacy = None
    words = []
    for char, symbol in markers.items():
        if char not in text:
            continue
        for index in positions(char):
            font = font_name(index)
            if font == b"SegoeUISymbol" and legacy is None:
                legacy = any(font_name(i) == b"MScore" for i in positions("\ue050"))
            if font != b"MuseJazz" and not (font == b"SegoeUISymbol" and legacy):
                continue
            left, bottom, right, top = textpage.get_charbox(index)
            if 0 <= left < right <= width and 0 <= bottom < top <= height:
                words.append(dict(text=symbol, left=left / width, top=1 - top / height,
                                  right=right / width, bottom=1 - bottom / height))
    return words


def tab_words(page, header=""):
    textpage = page.get_textpage()
    words = []
    try:
        width, height = page.get_width(), page.get_height()
        text = textpage.get_text_range()
        words.extend(_navigation_words(textpage, text, width, height))
        tokens = set(_TOKEN.findall(text)) | {c for c in text if c in "0123456789"}
        if not tokens:
            return words + tuning_words(header)
        # Compound tokens and individual digits can refer to the same PDF character.
        charbox = lru_cache(maxsize=4096)(textpage.get_charbox)
        for token in sorted(tokens, key=len, reverse=True):
            compound_number = len(token) > 1 and token.isdigit()
            search = textpage.search(token)
            try:
                while (match := search.get_next()) is not None:
                    index, count = match
                    if count == 1:
                        left, bottom, right, top = charbox(index)
                    else:
                        boxes = [charbox(i) for i in range(index, index + count)]
                        if compound_number:
                            # Keep separate fret digits when PDF search joins different strings.
                            visible = [b for b in boxes if b[2] > b[0] and b[3] > b[1]]
                            centers = [(b[1] + b[3]) / 2 for b in visible]
                            if centers and max(centers) - min(centers) > max(b[3] - b[1] for b in visible):
                                continue
                        left = min(b[0] for b in boxes)
                        bottom = min(b[1] for b in boxes)
                        right = max(b[2] for b in boxes)
                        top = max(b[3] for b in boxes)
                    left, right = left / width, right / width
                    top, bottom = 1 - top / height, 1 - bottom / height
                    if 0 <= left < right <= 1 and 0 <= top < bottom <= 1:
                        words.append(dict(text=token, left=left, top=top, right=right, bottom=bottom))
            finally:
                search.close()
    finally:
        textpage.close()
    words.extend(tuning_words(header))
    return words
