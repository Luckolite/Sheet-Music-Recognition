# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""MusicXML transport for explicitly printed bow-position instructions."""
import xml.etree.ElementTree as ET


def plan(page, rendered, bars):
    directions = page.get('printedBowPositionDirections', [])
    connectors = page.get('printedBowPositionConnectors', [])
    if not (directions or connectors):
        return []
    notes = page.get('score', {}).get('notes', [])
    events = page.get('events', [])
    if not (len(notes) == len(events) == len(rendered)):
        raise ValueError('Invalid printed bow position source alignment')
    if (not isinstance(directions, list) or len(directions) > len(notes)
            or not isinstance(connectors, list) or len(connectors) > len(directions) // 2):
        raise ValueError('Invalid printed bow position rows')

    marks = {}
    keys = ('measureIndex', 'staffIndex', 'staffCount')
    for data in directions:
        if not isinstance(data, dict):
            raise ValueError('Invalid printed bow position mark')
        owner, label, side = (data.get(k) for k in ('noteIndex', 'text', 'placement'))
        if (type(owner) is not int or not 0 <= owner < len(notes) or owner in marks
                or label not in ('Fr.', 'M') or side not in ('ABOVE', 'BELOW')):
            raise ValueError('Invalid printed bow position mark')
        note, source, exported = notes[owner], events[owner], rendered[owner]
        bar = note.get('measureIndex')
        if (type(bar) is not int or not 0 <= bar < len(bars) or type(source.get('sourceNoteIndex')) is not int
                or source['sourceNoteIndex'] != owner or exported.get('sourceNoteIndex') != owner
                or any(source.get(key) != note.get(key) for key in keys)
                or source.get('kind', 'PITCHED') != 'PITCHED'
                or source.get('durationFallback', False) or exported.get('grace', False)):
            raise ValueError('Invalid printed bow position attack owner')
        start = bars[bar]['start']
        tick = exported['start']
        if not start <= tick < start + bars[bar]['length']:
            raise ValueError('Printed bow position lies outside its source measure')
        marks[owner] = dict(side=side.lower(), text=label, start=tick, barStart=start,
                            staffIndex=note['staffIndex'], staffCount=note['staffCount'])

    links = []
    for data in connectors:
        if not isinstance(data, dict):
            raise ValueError('Invalid printed bow position connector')
        a, b, side, style = (data.get(k) for k in ('startNoteIndex', 'endNoteIndex', 'placement', 'style'))
        if (type(a) is not int or type(b) is not int or a not in marks or b not in marks
                or side not in ('ABOVE', 'BELOW') or style != 'SOLID'):
            raise ValueError('Invalid printed bow position connector')
        left, right = marks[a], marks[b]
        if (left['text'] != 'Fr.' or right['text'] != 'M' or not left['start'] < right['start']
                or any(left[k] != right[k] for k in ('side', 'staffIndex', 'staffCount'))
                or left['side'] != side.lower() or left['barStart'] != right['barStart']):
            raise ValueError('Invalid printed bow position connector endpoints')
        if 'connector' in left or 'connector' in right:
            raise ValueError('Duplicate printed bow position connector')
        left['connector'] = 'start'
        right['connector'] = 'stop'
        links.append((left, right))

    # Bracket numbers need only distinguish overlapping spans on the same part.
    active = {}
    for left, right in sorted(links, key=lambda pair: pair[0]['start']):
        lane = left['staffIndex'], left['staffCount']
        ends = active.setdefault(lane, {})
        number = next((k for k in range(1, 17) if ends.get(k, -1) < left['start']), None)
        if number is None:
            raise ValueError('Too many overlapping printed bow position connectors')
        ends[number] = right['start']
        left['lineNumber'] = right['lineNumber'] = str(number)
    return list(marks.values())


def write(measure, mark, offset):
    direction = ET.SubElement(measure, 'direction', placement=mark['side'])
    words = ET.SubElement(direction, 'direction-type')
    ET.SubElement(words, 'words').text = mark['text']
    if connector := mark.get('connector'):
        line = ET.SubElement(direction, 'direction-type')
        ET.SubElement(line, 'bracket', type=connector, number=mark['lineNumber'],
                      **{'line-end': 'none', 'line-type': 'solid'})
    ET.SubElement(direction, 'offset').text = str(offset)
