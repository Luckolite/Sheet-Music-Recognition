# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Sound paired guitar tablature under a proved arrangement-local capo."""
from .navigation import source_pages_adjacent

_HEADER = 'printed-tab-capo:v1'
_ROW = 'printed-tab-capo-row:v1'
_NATURAL = (0, 2, 4, 5, 7, 9, 11)


def _read(event, source):
    if (event.get('kind') != 'UNRESOLVED_DIRECTION' or event.get('scope') != 'UNRESOLVED'
            or not event.get('eventId', '').startswith(source+':')
            or (event.get('staffIndex'), event.get('staffCount')) != (0, 1)):
        return None
    evidence = event.get('evidence', [])
    start, end, payload = (event.get(x) for x in ('start', 'end', 'targetEventId'))
    if (len(evidence) != 1 or evidence[0].get('sourceId') != source
            or start is None or end is None or start.get('quarterBeatOffset') != 0
            or end.get('quarterBeatOffset') != 0 or not isinstance(payload, str)):
        return None
    return evidence[0]['pageIndex'], start['measureIndex'], end['measureIndex'], payload


def _shift(capo, profile):
    counts = {}
    try:
        for item in profile.split(','):
            delta, count = map(int, item.split('='))
            if delta in counts or not -24 <= delta <= 0 or count <= 0:
                return 0
            counts[delta] = count
    except ValueError:
        return 0
    ranked = sorted(((counts.get(-delta, 0), delta) for delta in {0, 12, capo, capo+12}), reverse=True)
    highest, delta = ranked[0]
    return capo-delta if highest >= 3 and highest-ranked[1][0] >= 2 else 0


def _written_midi(note, keys):
    clef = note['clefBottomDiatonic']
    octave, letter = divmod((30 if clef == -1 else clef)+note['staffStep'], 7)
    accidental = note['writtenAccidental']
    if accidental == 2:
        fifths = 0
        for key in keys:
            if key['measureIndex'] <= note['measureIndex']:
                fifths = key['fifths']
        order = (3, 0, 4, 1, 5, 2, 6) if fifths >= 0 else (6, 2, 5, 1, 4, 0, 3)
        accidental = ((1 if fifths > 0 else -1) if letter in order[:abs(fifths)] else 0)
    return ((octave+1+note['octaveShift'])*12+_NATURAL[letter]
            + (2 if accidental == 3 else accidental) + note.get('harmonicSemitones', 0))


def apply_capo(document):
    pages, previous, context, capo, changed = [], None, None, -1, False
    for page in document['pages']:
        score = page.get('score', {})
        marks = score.get('expressiveEvents', [])
        headers = [parsed for event in marks if (parsed := _read(event, _HEADER)) is not None]
        if len(headers) != 1:
            previous, context, capo = page, None, -1
            pages.append(page)
            continue
        physical, start, end, header = headers[0]
        try:
            first, declared = map(int, header.split(':'))
        except ValueError:
            first, declared = -1, -3
        if start != 0 or end != len(page['measureBeats']) or first < 0 or not -2 <= declared <= 12:
            previous, context, capo = page, None, -1
            pages.append(page)
            continue
        if context != (physical-1, first) or not source_pages_adjacent(previous, page):
            capo = -1
        if declared != -2:
            capo = declared
        previous, context = page, (physical, first+end if first else 0)
        if capo <= 0:
            pages.append(page)
            continue
        rows = [parsed for event in marks if (parsed := _read(event, _ROW)) is not None]
        shifts = {}
        for i, (owner, left, right, profile) in enumerate(rows):
            if (owner != physical or not 0 <= left < right <= end or any(
                    j != i and a < right and b > left for j, (_, a, b, _) in enumerate(rows))):
                continue
            shift = _shift(capo, profile)
            if shift:
                shifts.update((bar, shift) for bar in range(left, right))
        events = list(page['events'])
        notes = score.get('notes', [])
        for i, event in enumerate(events):
            owner = event.get('sourceNoteIndex')
            if type(owner) is not int or not 0 <= owner < len(notes):
                continue
            note = notes[owner]
            shift = shifts.get(note['measureIndex'], 0)
            if (not shift or note.get('kind', 'PITCHED') != 'PITCHED'
                    or (note['staffIndex'], note['staffCount']) != (0, 1)):
                continue
            midi = _written_midi(note, score.get('keyChanges', []))+shift
            if 0 <= midi <= 127:
                events[i] = dict(event, midi=midi, _printedCapoSounding=True)
                changed = True
        pages.append(dict(page, events=events))
    return dict(document, pages=pages) if changed else document
