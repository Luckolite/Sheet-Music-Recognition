# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Source spelling for concert-pitch XML, only while its sounding identity agrees."""
from collections import defaultdict
import xml.etree.ElementTree as ET

from .clef_export import LAYOUT
from .typed_events import event_kind

PRINTED_ACCIDENTALS = {
    -2: (-2, 'flat-flat'), -1: (-1, 'flat'), 0: (0, 'natural'),
    1: (1, 'sharp'), 3: (2, 'double-sharp'),
}
ACCIDENTAL_NAMES = dict(PRINTED_ACCIDENTALS.values())


def printed_accidentals(page):
    """Validate explicit glyph records. Legacy pitch spellings do not identify glyphs."""
    rows = page.get('printedAccidentals', [])
    notes = page.get('score', {}).get('notes', [])
    if not isinstance(rows, list) or len(rows) > len(notes):
        raise ValueError('Invalid printed accidental list')
    result = {}
    for row in rows:
        index = row.get('noteIndex') if isinstance(row, dict) else None
        value = row.get('accidental') if isinstance(row, dict) else None
        if (type(index) is not int or not 0 <= index < len(notes) or index in result
                or event_kind(notes[index]) != 'PITCHED'
                or type(value) is not int or value not in PRINTED_ACCIDENTALS):
            raise ValueError('Invalid printed accidental owner or sign')
        if 'naturalPrefix' in row and (row['naturalPrefix'] is not True or value not in (-1, 1)):
            raise ValueError('Natural prefix requires a printed flat or sharp')
        result[index] = value
    return result


def cancellation_prefixes(page):
    """The printed cancellation and following sign share one pitch owner."""
    return {mark['noteIndex'] for mark in page.get('printedAccidentals', [])
            if mark.get('naturalPrefix') is True}


def printed_mark(rows, index, spelling, prefixed_accidentals=()):
    if spelling is None or index not in rows:
        return None
    alter, mark = PRINTED_ACCIDENTALS[rows[index]]
    # A boundary tie, edit or retune can replace the original spelling. Keep only matching ink.
    if spelling[1] != alter:
        return None
    return 'natural-' + mark if mark in ('flat', 'sharp') and index in prefixed_accidentals else mark


def fill_required_accidentals(root, source_marks, octave_shifts):
    """Supply visible signs for reconstructed attacks in written time order."""
    for measure in root.findall('part/measure'):
        fifths = int(measure.findtext('attributes/key/fifths', '0'))
        order = 'FCGDAEB' if fifths >= 0 else 'BEADGCF'
        key = dict.fromkeys(order[:abs(fifths)], 1 if fifths >= 0 else -1)
        groups = defaultdict(lambda: defaultdict(list))
        grace_counts = defaultdict(int)
        cursor = onset = 0
        for note in measure:
            if note.tag in ('backup', 'forward'):
                cursor += int(note.findtext('duration')) * (-1 if note.tag == 'backup' else 1)
            if note.tag != 'note':
                continue
            grace = note.find('grace')
            if note.find('chord') is None:
                onset = cursor
                cursor += int(note.findtext('duration', '0'))
            pitch = note.find('pitch')
            if pitch is None or note.get('print-object') == 'no':
                continue
            mark = note.find('accidental')
            if mark is None and (note in source_marks or note.find('cue') is not None
                                 or note.find('tie[@type="stop"]') is not None):
                continue
            position = onset, 2, 0
            if grace is not None:
                phase = 0 if 'steal-time-previous' in grace.attrib else 1
                grace_key = onset, phase, note.findtext('voice')
                position = onset, phase, grace_counts[grace_key]
                grace_counts[grace_key] += 1
            letter = pitch.findtext('step')
            written_octave = int(pitch.findtext('octave')) - octave_shifts.get(note, 0)
            alteration = int(pitch.findtext('alter', '0'))
            groups[position][letter, written_octave].append((note, alteration, mark is not None))
        carried = {}
        for position in sorted(groups):
            for slot, heads in groups[position].items():
                previous = carried.get(slot, key.get(slot[0], 0))
                alterations = {alter for _, alter, _ in heads}
                printed = {alter for _, alter, marked in heads if marked}
                for note, alter, _ in heads:
                    if (alter in ACCIDENTAL_NAMES and alter not in printed
                            and (alter != previous or len(alterations) > 1)):
                        # MusicXML orders the accidental after type/dots and before time modification.
                        index = max(i for i, child in enumerate(note) if child.tag in ('voice', 'type', 'dot'))
                        mark = ET.Element('accidental')
                        mark.text = ACCIDENTAL_NAMES[alter]
                        note.insert(index + 1, mark)
                        printed.add(alter)
                # When simultaneous voices disagree, the next attack must restate its sign.
                carried[slot] = next(iter(alterations)) if len(alterations) == 1 else None


def retained(page, index, key, printed=None):
    event = page['events'][index]
    if event_kind(event) != 'PITCHED':
        return None
    notes = page.get('score', {}).get('notes', [])
    if len(notes) != len(page['events']):
        return None
    source = notes[index]
    if event_kind(source) != 'PITCHED':
        return None
    fields = ('measureIndex', 'staffIndex', 'staffCount')
    if (type(event.get('sourceNoteIndex', index)) is not int
            or event.get('sourceNoteIndex', index) != index
            or any(k not in event or k not in source or event[k] != source[k] for k in fields)):
        return None
    bottom = source.get('clefBottomDiatonic', -1)
    inferred = event.get('clefInferred', False)
    if inferred and (bottom != -1 or type(printed) is not int
                     or printed not in PRINTED_ACCIDENTALS
                     or event.get('sourceNoteIndex') != index):
        return None
    if bottom == -1 and not inferred:
        return None
    step, accidental = source.get('staffStep'), source.get('writtenAccidental')
    shift = source.get('octaveShift', 0)
    if step is None or accidental is None or inferred and printed != accidental:
        return None
    if (type(bottom) is not int or bottom not in (*LAYOUT, -1)
            or any(type(v) is not int for v in (step, accidental, shift))
            or accidental not in (-2, -1, 0, 1, 2, 3)):
        raise ValueError('Invalid retained pitch spelling')
    if event.get('clefBottomDiatonic', bottom) != bottom:
        return None
    if bottom == -1:
        # The owned glyph fixes the alteration. Check that its resulting spelling
        # reproduces the decoder's pitch with the same fallback staff geometry.
        bottom = 18 if source['staffCount'] > 1 and source['staffIndex'] > 0 else 30
    diatonic = bottom + step + 7 * shift
    octave, letter = divmod(diatonic, 7)
    if accidental == 2:  # FROM_KEY is distinct from the double-sharp wire value 3.
        if type(key) is not int or not -7 <= key <= 7:
            raise ValueError('Invalid retained pitch key')
        order = (3, 0, 4, 1, 5, 2, 6) if key >= 0 else (6, 2, 5, 1, 4, 0, 3)
        alter = (1 if key > 0 else -1) if letter in order[:abs(key)] else 0
    else:
        alter = 2 if accidental == 3 else accidental
    midi = (octave + 1) * 12 + (0, 2, 4, 5, 7, 9, 11)[letter] + alter
    if midi != event['midi']:
        return None  # Edited or retuned heads must not borrow stale source spelling.
    return 'CDEFGAB'[letter], alter, octave
