# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Join two proved page-edge shoulders without guessing ties from equal pitches."""

from collections import Counter

from .navigation import arrangement_ranges, source_pages_adjacent
from .typed_events import event_kind, validate_unpitched


def _valid_boundary_ties(value):
    if type(value) is not int or value < 0 or value & ~((1 << 22) - 1):
        return False
    if not value & 16:
        return value <= 15
    string = value >> 5 & 7
    count = 6 + (value >> 8 & 1)
    fret = value >> 9 & 63
    effective_open = value >> 15 & 127
    return bool(value & 15) and string < count and fret <= 36 and effective_open + fret <= 127


def _compatible_boundary_ties(before, after):
    if not _valid_boundary_ties(before) or not _valid_boundary_ties(after):
        return False
    return ((before & ~15) == (after & ~15) if before & 16 and after & 16
            else not (before | after) & 16)


def resolve_boundary_ties(document):
    pages = list(document["pages"])
    for page in pages:
        for event in page['events']:
            validate_unpitched(event)
            if not _valid_boundary_ties(event.get('boundaryTies', 0)):
                raise ValueError('Invalid boundary tie identity')

    numbered = any(page.get('score', {}).get('firstMeasureNumber', 0) > 0 for page in pages[1:])
    restarts = None
    for index in range(1, len(pages)):
        before, after = pages[index - 1], pages[index]
        if not source_pages_adjacent(before, after):
            continue
        events = list(after["events"])
        candidates = {}
        claims = Counter()
        outgoing = None
        for number, current in enumerate(events):
            if event_kind(current) == 'UNPITCHED':
                continue
            incoming = current.get("boundaryTies", 0) & 3
            if not incoming or abs(current["startBeat"]) > .04:
                continue
            if outgoing is None:
                outgoing = [(i, event) for i, event in enumerate(before["events"])
                            if event.get("boundaryTies", 0) & 12 and event_kind(event) != 'UNPITCHED']
            current_ties = current["boundaryTies"]
            tab_typed = bool(current_ties & 16)
            for previous_index, previous in outgoing:
                previous_ties = previous["boundaryTies"]
                if not ((previous_ties >> 2) & incoming):
                    continue
                if not _compatible_boundary_ties(previous_ties, current_ties):
                    continue
                if (not tab_typed and (previous.get("boundaryPitch") is None
                        or previous["boundaryPitch"] != current.get("boundaryPitch"))
                        or previous["staffIndex"] != current["staffIndex"]
                        or previous["staffCount"] != current["staffCount"]
                        or abs(previous["startBeat"] + previous["durationBeats"] - before["totalBeats"]) > .04):
                    continue
                # An unmarked continuation keeps the tied pitch across a bar/key change.
                if (tab_typed or current.get("boundaryAccidental") != 2) and previous["midi"] != current["midi"]:
                    continue
                candidates.setdefault(number, []).append(previous_index)
                claims[previous_index] += 1
        matches = [(number, owners[0]) for number, owners in candidates.items()
                   if len(owners) == 1 and claims[owners[0]] == 1]
        if not matches:
            continue
        if numbered:
            if restarts is None:
                restarts = {part['firstPage'] for part in arrangement_ranges(pages)}
            if index in restarts:
                continue

        score = dict(after.get("score", {}))
        score_notes = list(score.get("notes", []))
        for number, previous_index in matches:
            current = events[number]
            previous = before["events"][previous_index]
            events[number] = dict(current, tiedFromPrevious=True, midi=previous["midi"])
            note_index = current.get("sourceNoteIndex", -1)
            if 0 <= note_index < len(score_notes):
                score_notes[note_index] = dict(score_notes[note_index], tiedFromPrevious=True)
                if not current.get("boundaryTies", 0) & 16:
                    pitch = previous["boundaryPitch"]
                    natural = (pitch // 7 + 1) * 12 + (0, 2, 4, 5, 7, 9, 11)[pitch % 7]
                    accidental = previous["midi"] - natural
                    score_notes[note_index]["writtenAccidental"] = 3 if accidental == 2 else accidental
        if "notes" in score:
            score["notes"] = score_notes
        pages[index] = dict(after, events=events, score=score)
    return dict(document, pages=pages)
