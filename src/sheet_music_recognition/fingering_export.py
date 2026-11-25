# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Attack-local printed fingering whose source owner was verified by the decoder."""
from collections import defaultdict
import xml.etree.ElementTree as ET

from .typed_events import event_kind


def retained(page):
    rows = page.get('printedFingerings', [])
    notes = page.get('score', {}).get('notes', [])
    groups = page.get('printedFingeringGroups', [])
    if not isinstance(rows, list) or len(rows) > 4 * len(notes):
        raise ValueError('Invalid printed fingering list')
    if not isinstance(groups, list) or len(groups) > len(rows) // 2:
        raise ValueError('Invalid printed fingering group list')
    if not rows:
        return {}
    owners = defaultdict(list)
    for index, event in enumerate(page['events']):
        owner = event.get('sourceNoteIndex')
        if type(owner) is int:
            owners[owner].append(index)
    result = defaultdict(dict)
    source_sites = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('Invalid printed fingering row')
        owner, digit, placement = (row.get(k) for k in ('noteIndex', 'digit', 'placement'))
        if (type(owner) is not int or not 0 <= owner < len(notes)
                or type(digit) is not int or not 0 <= digit <= 4
                or placement not in ('ABOVE', 'BELOW')
                or len(owners[owner]) != 1):
            raise ValueError('Invalid printed fingering owner, digit or placement')
        note, index = notes[owner], owners[owner][0]
        event = page['events'][index]
        site = result[index].setdefault(placement, [])
        if (len(site) >= 2 or str(digit) in site
                or event_kind(note) != 'PITCHED' or event_kind(event) != 'PITCHED'
                or note.get('visualSize', 'FULL') != 'FULL'
                or any(k not in event or k not in note or event[k] != note[k]
                       for k in ('measureIndex', 'staffIndex', 'staffCount'))):
            raise ValueError('Invalid printed fingering note identity')
        site.append(str(digit))
        source_sites[owner, placement] = index

    boundaries = set()
    spans = defaultdict(list)
    for group in groups:
        if not isinstance(group, dict):
            raise ValueError('Invalid printed fingering group')
        first, last, placement = (group.get(key) for key in ('startNoteIndex', 'endNoteIndex', 'placement'))
        if (type(first) is not int or type(last) is not int or not 0 <= first < last < len(notes)
                or placement not in ('ABOVE', 'BELOW') or (first, placement) not in source_sites
                or (last, placement) not in source_sites):
            raise ValueError('Invalid printed fingering group endpoints')
        # The attack-local source order is validated against the written note clock.
        start, stop = [page['events'][source_sites[owner, placement]] for owner in (first, last)]
        keys = ('measureIndex', 'staffIndex', 'staffCount')
        if (any(start[k] != stop[k] for k in keys)
                or type(start.get('startBeat')) not in (int, float)
                or type(stop.get('startBeat')) not in (int, float)
                or not start['startBeat'] < stop['startBeat']):
            raise ValueError('Invalid printed fingering group clock')
        lane = (*(start[k] for k in keys), placement)
        low, high = start['startBeat'], stop['startBeat']
        if ((first, placement) in boundaries or (last, placement) in boundaries
                or any(low <= earlier_high and earlier_low <= high for earlier_low, earlier_high in spans[lane])):
            raise ValueError('Overlapping printed fingering groups')
        spans[lane].append((low, high))
        boundaries.update(((first, placement), (last, placement)))
        a, b = (source_sites[owner, placement] for owner in (first, last))
        for index, field, opening in ((a, 'startDigit', True), (b, 'endDigit', False)):
            digits = result[index][placement]
            if field not in group and len(digits) == 1:
                endpoint = digits[0]
            else:
                value = group.get(field)
                if type(value) is not int or str(value) not in digits:
                    raise ValueError('Missing or invalid printed fingering group endpoint digit')
                endpoint = str(value)
            target = digits.index(endpoint)
            digits[target] = '(' + endpoint if opening else endpoint + ')'

    for (owner, placement), index in source_sites.items():
        if len(result[index][placement]) > 1 and (owner, placement) not in boundaries:
            raise ValueError('Multiple printed fingerings require an explicit group endpoint')
    return {index: tuple((digit, side.lower()) for side in ('ABOVE', 'BELOW') for digit in marks.get(side, ()))
            for index, marks in result.items()}


def write(notation, marks):
    technical = notation.find('technical')
    if technical is None:
        technical = ET.SubElement(notation, 'technical')
    for text, placement in marks:
        ET.SubElement(technical, 'fingering', placement=placement).text = text
