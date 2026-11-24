# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Export source-owned, attack-local Roman string labels."""
from collections import defaultdict
import math
import xml.etree.ElementTree as ET

from .typed_events import event_kind


ROMAN_NUMBERS = {'I': 1, 'II': 2, 'III': 3, 'IV': 4}


def retained(page):
    rows = page.get('printedStringMarks', [])
    notes = page.get('score', {}).get('notes', [])
    if not isinstance(rows, list) or len(rows) > 2 * len(notes):
        raise ValueError('Invalid printed string mark list')
    if not rows:
        return {}
    owners = defaultdict(list)
    for index, event in enumerate(page['events']):
        owner = event.get('sourceNoteIndex')
        if type(owner) is int:
            owners[owner].append(index)
    result = defaultdict(dict)
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('Invalid printed string mark row')
        owner, literal, number, placement = (
            row.get(key) for key in ('noteIndex', 'literal', 'number', 'placement')
        )
        if (type(owner) is not int or not 0 <= owner < len(notes)
                or not isinstance(literal, str) or type(number) is not int
                or ROMAN_NUMBERS.get(literal) != number
                or placement not in ('ABOVE', 'BELOW') or len(owners[owner]) != 1):
            raise ValueError('Invalid printed string mark owner, numeral or placement')
        note, (index,) = notes[owner], owners[owner]
        event = page['events'][index]
        if (placement in result[index] or event_kind(note) != 'PITCHED' or event_kind(event) != 'PITCHED'
                or note.get('visualSize', 'FULL') != 'FULL'
                or any(key not in event or key not in note or event[key] != note[key]
                       for key in ('measureIndex', 'staffIndex', 'staffCount'))):
            raise ValueError('Invalid printed string mark note identity')
        result[index][placement] = literal, number, placement.lower()
    return {index: tuple(marks[k] for k in ('ABOVE', 'BELOW') if k in marks)
            for index, marks in result.items()}


def write(note, marks):
    notation = ET.SubElement(note, 'notations')
    technical = ET.SubElement(notation, 'technical')
    for literal, number, placement in marks:
        ET.SubElement(technical, 'string', placement=placement).text = str(number)
        ET.SubElement(technical, 'other-technical', placement=placement).text = literal


def continuations(page, rendered, bars):
    rows = page.get('printedStringContinuations', [])
    if not rows:
        return []
    notes = page.get('score', {}).get('notes', [])
    events = page.get('events', [])
    if (not isinstance(rows, list) or len(rows) > len(notes) // 2
            or len(rendered) != len(events) or len(notes) != len(events)):
        raise ValueError('Invalid printed string continuation list')
    strings = retained(page)
    owners = {event['sourceNoteIndex']: index for index, event in enumerate(events)
              if type(event.get('sourceNoteIndex')) is int}
    owner_counts = defaultdict(int)
    for event in events:
        if type(event.get('sourceNoteIndex')) is int:
            owner_counts[event['sourceNoteIndex']] += 1

    def attack(owner):
        if type(owner) is not int or not 0 <= owner < len(notes) or owner_counts[owner] != 1:
            raise ValueError('Invalid printed string continuation owner')
        index = owners[owner]
        event, source, out = events[index], notes[owner], rendered[index]
        measure = source.get('measureIndex')
        if (type(measure) is not int or not 0 <= measure < len(bars)
                or any(event.get(k) != source.get(k) for k in ('measureIndex','staffIndex','staffCount'))
                or out.get('sourceNoteIndex') != owner or event_kind(event) != 'PITCHED'
                or out.get('grace', False) or event.get('durationFallback', False)):
            raise ValueError('Invalid printed string continuation attack')
        bar = bars[measure]
        tick = out['start']
        if not bar['start'] <= tick < bar['start'] + bar['length']:
            raise ValueError('Invalid printed string continuation clock')
        return dict(index=index, start=tick, barStart=bar['start'],
                    staffIndex=source['staffIndex'], staffCount=source['staffCount'])

    spans = []
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('Invalid printed string continuation row')
        text, number, side, style = (row.get(k) for k in ('text','number','placement','style'))
        relative_x = row.get('endRelativeX')
        if (not isinstance(text, str) or type(number) is not int or ROMAN_NUMBERS.get(text) != number
                or side not in ('ABOVE','BELOW') or style != 'DASHED'
                or relative_x is not None and (type(relative_x) not in (int,float)
                    or not math.isfinite(relative_x) or not -20 <= relative_x <= 0)):
            raise ValueError('Invalid printed string continuation notation')
        first, stop = attack(row.get('startNoteIndex')), attack(row.get('stopBeforeNoteIndex'))
        placement = side.lower()
        if ((text, number, placement) not in strings.get(first['index'], ())
                or stop['start'] <= first['start']
                or any(stop[k] != first[k] for k in ('barStart','staffIndex','staffCount'))):
            raise ValueError('Conflicting printed string continuation endpoints')
        first.update(action='start', side=placement)
        stop.update(action='stop', side=placement, relativeX=relative_x)
        spans.append((first,stop))
    return spans


def write_continuation(measure, mark, offset):
    direction = ET.SubElement(measure, 'direction', placement=mark['side'])
    line = ET.SubElement(direction, 'direction-type')
    dash = ET.SubElement(line, 'bracket', type=mark['action'], number=mark['lineNumber'],
                        **{'line-end': 'none', 'line-type': 'dashed'})
    if mark.get('relativeX') is not None:
        dash.set('relative-x', str(mark['relativeX']))
    ET.SubElement(direction, 'offset').text = str(offset)
