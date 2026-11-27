# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Exact source-column ownership for optically proved instrument/Play cue captions."""
import math
import struct
SOURCE = 'printed-cue-instruction'
INSTRUMENTS = {'guitar', 'piano', 'violin', 'viola', 'cello', 'flute', 'oboe', 'clarinet',
               'bassoon', 'trumpet', 'trombone', 'horn', 'bass', 'harp', 'organ', 'saxophone'}

def owns(mark):
    evidence = mark.get('evidence', [])
    if (mark.get('kind') != 'UNRESOLVED_DIRECTION' or mark.get('qualifierText', '')
            or len(evidence) != 1 or not (mark.get('targetEventId') or '').startswith('expression-column:')):
        return False
    row = evidence[0]
    text = row.get('printedText', '')
    token = text.strip().lower().rstrip('.:') if type(text) is str else ''
    return (row.get('sourceId') == SOURCE and token in INSTRUMENTS | {'play'}
            and all(row.get(k) == mark.get(k) for k in ('staffIndex', 'staffCount')))

def members(mark, page):
    if not owns(mark):
        return []
    try:
        m, staff, count, bits = map(int, mark['targetEventId'][len('expression-column:'):].split(':'))
        pos = struct.unpack('>f', struct.pack('>I', bits & 0xffffffff))[0]
    except (ValueError, struct.error):
        return []
    if (m < 0 or not 0 <= staff < count <= 8 or not math.isfinite(pos) or not 0 <= pos <= 1
            or staff != mark.get('staffIndex') or count != mark.get('staffCount')
            or any(not -2147483648 <= v <= 2147483647 for v in (m, staff, count, bits))):
        return []
    notes = page.get('score', {}).get('notes', [])
    events = page.get('events', [])
    if len(notes) != len(events):
        return []
    result = []
    for i, note in enumerate(notes):
        p = note.get('positionInMeasure')
        if (type(p) not in (int, float) or not math.isfinite(p) or not 0 <= p <= 1
                or note.get('measureIndex') != m or note.get('staffIndex') != staff):
            continue
        if struct.unpack('>f', struct.pack('>f', p))[0] != pos:
            continue
        event = events[i]
        if (note.get('staffCount') != count or note.get('kind', 'PITCHED') != 'PITCHED'
                or note.get('articulations', 0) & 32768
                or type(event.get('sourceNoteIndex', i)) is not int
                or event.get('sourceNoteIndex', i) != i
                or any(type(note.get(k)) is not int or event.get(k) != note[k]
                       for k in ('measureIndex', 'staffIndex', 'staffCount'))):
            return []
        result.append(i)
    return result


OPENING_RANGE = 'printed-opening-cue:v1'


def playback_document(document):
    pages = []
    changed = False
    for page in document['pages']:
        score = page.get('score', {})
        notes = score.get('notes', [])
        events = page.get('events', [])
        muted = set()
        for mark in score.get('expressiveEvents', []):
            muted.update(_opening_members(mark, notes, len(page['measureBeats'])))
        if muted:
            tagged = [dict(event, _printedCueSilent=True)
                      if type(event.get('sourceNoteIndex', i)) is int
                      and event.get('sourceNoteIndex', i) in muted
                      and all(event.get(k) == notes[event.get('sourceNoteIndex', i)][k]
                              for k in ('measureIndex', 'staffIndex', 'staffCount'))
                      else event for i, event in enumerate(events)]
            page = dict(page, events=tagged)
            changed = True
        pages.append(page)
    return dict(document, pages=pages) if changed else document


def _opening_members(mark, notes, bars):
    evidence = mark.get('evidence', [])
    if (mark.get('kind') != 'UNRESOLVED_DIRECTION'
            or not mark.get('eventId', '').startswith(OPENING_RANGE + ':')
            or len(evidence) != 2
            or any(e.get('sourceId') != OPENING_RANGE or any(
                e.get(k) != mark.get(k) for k in ('staffIndex', 'staffCount')) for e in evidence)):
        return ()
    names = [e.get('printedText', '').strip().lower().rstrip('.:') for e in evidence]
    if names[0] not in INSTRUMENTS or names[1] != 'play' or not (
            0 <= evidence[0].get('visualX', -1) < evidence[1].get('visualX', -1) <= 1):
        return ()
    start, end = mark.get('start'), mark.get('end')
    if not isinstance(start, dict) or not isinstance(end, dict):
        return ()
    first, stop = start.get('measureIndex'), end.get('measureIndex')
    if (type(first) is not int or type(stop) is not int or not 0 <= first <= stop - 2 or stop >= bars
            or start.get('quarterBeatOffset') != 0 or end.get('quarterBeatOffset') != 0):
        return ()
    owned = []
    entrance = False
    for i, note in enumerate(notes):
        if any(note.get(k) != mark.get(k) for k in ('staffIndex', 'staffCount')):
            continue
        m = note.get('measureIndex', -1)
        if not first <= m <= stop:
            continue
        ordinary = note.get('kind', 'PITCHED') == 'PITCHED' and not note.get('articulations', 0) & 32768
        if m == stop:
            entrance |= (ordinary and note.get('visualSize', 'FULL') == 'FULL'
                         and not note.get('tiedFromPrevious', False))
        elif ordinary and note.get('visualSize') == 'CUE':
            owned.append(i)
        else:
            return ()
    return owned if entrance else ()
