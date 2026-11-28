# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Bind the written upper neighbor and alternate on the final playback clock."""
import math

from .articulation_export import source_flags
from .ornament_export import TRILL, TYPE_MASK
from .typed_events import event_kind


NATURAL = (0, 2, 4, 5, 7, 9, 11)
SHARPS = (3, 0, 4, 1, 5, 2, 6)
FLATS = (6, 2, 5, 1, 4, 0, 3)


def _alter(letter, fifths):
    order = SHARPS if fifths > 0 else FLATS
    return (1 if fifths > 0 else -1) if letter in order[:abs(fifths)] else 0


def _upper_midi(event, source, flags, fifths, accidental):
    bottom = source.get('clefBottomDiatonic', -1)
    step = source.get('staffStep')
    if bottom == -1 and event.get('clefInferred') is True:
        bottom = 18 if source['staffCount'] > 1 and source['staffIndex'] > 0 else 30
    if (type(bottom) is not int or bottom < 0 or type(step) is not int
            or type(fifths) is not int or not -7 <= fifths <= 7):
        return None
    letter = (bottom + step) % 7
    upper = (letter + 1) % 7
    principal = source.get('writtenAccidental', 2)
    if principal not in (-2, -1, 0, 1, 2, 3):
        return None
    principal = _alter(letter, fifths) if principal == 2 else 2 if principal == 3 else principal
    upper_flag = (flags >> 9) & 3
    if upper_flag:
        accidental = upper_flag - 2
    elif accidental is None:
        accidental = _alter(upper, fifths)
    pitch = event['midi'] + (NATURAL[upper] - NATURAL[letter]) % 12 + accidental - principal
    return pitch if 0 <= pitch <= 127 and pitch != event['midi'] else None


def bind(document):
    """Bind source-owned trills before score projection discards raw note identities."""
    key = document.get('initialKeyFifths', 0)
    pages = []
    for page in document['pages']:
        score = page.get('score', {})
        notes = score.get('notes', ())
        changes = sorted((row['measureIndex'], row['fifths']) for row in score.get('keyChanges', ()))
        initial_key = page.get('initialKeyFifths', key)
        events = list(page['events'])
        changed = False
        for index, event in enumerate(events):
            if '_trillUpperMidi' in event:
                events[index] = dict(event)
                events[index].pop('_trillUpperMidi')
                changed = True
        owners = [index for index, note in enumerate(notes)
                  if type(note.get('articulations')) is int and note['articulations'] & TYPE_MASK == TRILL]
        if owners and len(notes) == len(events):
            for index in owners:
                event, note = events[index], notes[index]
                flags = source_flags(page, index)
                if (not flags or event_kind(event) != 'PITCHED' or event.get('guitarEffect')
                        or event.get('tremoloBeats') or event.get('glissando') or event.get('grace')):
                    continue
                fifths = initial_key
                for bar, value in changes:
                    if bar > note['measureIndex']:
                        break
                    fifths = value
                neighbor_accidental = None
                if not (flags >> 9) & 3 and type(note.get('staffStep')) is int:
                    prior = [(other['positionInMeasure'], other['writtenAccidental'])
                             for other in notes
                             if event_kind(other) == 'PITCHED'
                             and other.get('measureIndex') == note['measureIndex']
                             and other.get('staffIndex') == note['staffIndex']
                             and other.get('staffCount') == note['staffCount']
                             and other.get('clefBottomDiatonic') == note.get('clefBottomDiatonic')
                             and other.get('staffStep') == note['staffStep'] + 1
                             and other.get('positionInMeasure', math.inf) <= note.get('positionInMeasure', -1)
                             and other.get('writtenAccidental') in (-2, -1, 0, 1, 3)]
                    if prior:
                        neighbor_accidental = max(prior, key=lambda row: row[0])[1]
                        if neighbor_accidental == 3:
                            neighbor_accidental = 2
                upper = _upper_midi(event, note, flags, fifths, neighbor_accidental)
                if upper is not None:
                    events[index] = dict(event, _trillUpperMidi=upper)
                    changed = True
        key = changes[-1][1] if changes else initial_key
        pages.append(dict(page, events=events) if changed else page)
    return dict(document, pages=pages)


def windows(start, end, pitch, upper, timing):
    """Use the desktop pulse rate and keep the source attack and release exact."""
    if end - start < 3:
        yield start, end, pitch
        return
    seconds_at, tick_at, _ = timing
    seconds = seconds_at(start)
    duration = seconds_at(end) - seconds
    count = min(4095, max(3, math.floor(duration / .075 + .5)), end - start)
    if count % 2 == 0:
        count -= 1
    left = start
    for index in range(count):
        right = end if index + 1 == count else min(
            end - (count - index - 1), max(left + 1, round(tick_at(seconds + duration * (index + 1) / count))))
        yield left, right, upper if index % 2 else pitch
        left = right
