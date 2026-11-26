# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Realize printed eighth-note feel on source audio intervals before navigation."""
import itertools
import math
import re
from collections import defaultdict

from .navigation import arrangement_ranges
from .tie_owners import releasing_links, sound_lanes
from .typed_events import event_kind

_SOURCE = 'printed-eighth-feel'
_SWING = re.compile(
    r'(?:swing\s+(?:8ths?|eighths?)(?:\s+[a-z♩♪♫.]?\s*=\s*\d{2,3})?'
    r'|rock\s+swing|(?:back|return)\s+to\s+swing)[.!]?')
_STRAIGHT = re.compile(r'(?:play\s+straight|straight\s+(?:8ths?|eighths?))[.!]?')


def _feel(event):
    start = event.get('start') or {}
    if (event.get('kind') != 'UNRESOLVED_DIRECTION' or event.get('scope') != 'SCORE'
            or start.get('quarterBeatOffset') != 0):
        return None
    qualifier = event.get('qualifierText')
    for evidence in event.get('evidence', ()):
        if evidence.get('sourceId') != _SOURCE:
            continue
        caption = evidence.get('printedText', '').strip().lower()
        if qualifier == 'eighth-feel-v1:swing' and _SWING.fullmatch(caption):
            return True
        if qualifier == 'eighth-feel-v1:straight' and _STRAIGHT.fullmatch(caption):
            return False
    return None


def _tie_owners(events):
    pitches = [(i, n) for i, n in enumerate(events) if event_kind(n) == 'PITCHED']
    if not any(n.get('tiedFromPrevious') for _, n in pitches):
        return set()
    rows = [dict(start=n['startBeat'], end=n['startBeat'] + n['durationBeats'],
                 lane=(n['staffCount'], n['staffIndex'], n['midi']),
                 tied=n.get('tiedFromPrevious', False), eligible=not n.get('grace', False))
            for _, n in pitches]
    links = releasing_links(rows, lambda row: sound_lanes(row, .125, .04))
    return {pitches[i][0] for i in set(links) | set(links.values())}


def _adjust_page(page, feel, changes):
    events = page['events']
    beats = page['measureBeats']
    starts = list(itertools.accumulate(beats, initial=0.))
    active = []
    for bar in range(len(beats)):
        feel = changes.get(bar, feel)
        active.append(feel)
    if not any(active):
        return page, feel

    source_notes = page.get('score', {}).get('notes', ())
    groups = defaultdict(lambda: ([], [], set()))
    tied = _tie_owners(events)

    def slot(start, bar):
        if not math.isfinite(start):
            return None
        half = round(start * 2)
        if half >= 0 and abs(start - half / 2) <= 1e-6 and half // 2 + 1 <= beats[bar] + 1e-6:
            return half
        return None

    for i, note in enumerate(events):
        bar = note['measureIndex']
        owner = note.get('sourceNoteIndex', -1)
        if (not 0 <= bar < len(beats) or not active[bar] or not 0 <= owner < len(source_notes)
                or i in tied or note.get('tiedFromPrevious') or note.get('boundaryTies')
                or note.get('grace') or note.get('_continuousSlide') or note.get('tremoloBeats')):
            continue
        written = source_notes[owner]
        if written.get('tupletDivisor', 1) != 1:
            continue
        start = note['startBeat'] - starts[bar]
        duration = note['durationBeats']
        staff = note['staffIndex']
        for kind, position in (('leadingRestBeats', start - .5),
                               ('followingRestBeats', start + duration)):
            if written.get(kind) == .5 and (half := slot(position, bar)) is not None:
                groups[bar, staff, half // 2][2].add(half % 2)
        if (not written.get('augmentationDots') and abs(duration - .5) <= 1e-6
                and (half := slot(start, bar)) is not None):
            groups[bar, staff, half // 2][half % 2].append(i)

    updated = {}
    for (bar, _, quarter), (downbeat, offbeat, rests) in groups.items():
        if not (downbeat or 0 in rests) or not (offbeat or 1 in rests):
            continue
        middle = starts[bar] + quarter + 2 / 3
        for i in downbeat:
            note = events[i]
            updated[i] = dict(note, durationBeats=middle - note['startBeat'])
        for i in offbeat:
            note = events[i]
            end = note['startBeat'] + note['durationBeats']
            updated[i] = dict(note, startBeat=middle, durationBeats=end - middle)
    if not updated:
        return page, feel
    return dict(page, events=[updated.get(i, note) for i, note in enumerate(events)]), feel


def realize(document):
    pages = document['pages']
    commands = []
    for page in pages:
        changes = {}
        for event in page.get('score', {}).get('expressiveEvents', ()):
            feel = _feel(event)
            if feel is not None:
                bar = event['start'].get('measureIndex')
                if type(bar) is int and 0 <= bar < len(page['measureBeats']):
                    changes[bar] = changes.get(bar, feel) and feel
        commands.append(changes)
    if not any(True in changes.values() for changes in commands):
        return document
    resets = ({part['firstPage'] for part in arrangement_ranges(pages)[1:]}
              if any(page.get('score', {}).get('firstMeasureNumber', 0) > 0 for page in pages[1:])
              else set())
    feel = False
    adjusted = []
    for i, (page, changes) in enumerate(zip(pages, commands)):
        page, feel = _adjust_page(page, feel and i not in resets, changes)
        adjusted.append(page)
    return dict(document, pages=adjusted)
