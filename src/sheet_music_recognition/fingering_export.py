# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Attack-local printed fingering whose source owner was verified by the decoder."""
from collections import defaultdict
import xml.etree.ElementTree as ET

from .typed_events import event_kind


def retained(page):
    rows = page.get('printedFingerings', [])
    notes = page.get('score', {}).get('notes', [])
    if not isinstance(rows, list) or len(rows) > len(notes):
        raise ValueError('Invalid printed fingering list')
    if not rows:
        return {}
    owners = defaultdict(list)
    for index, event in enumerate(page['events']):
        owner = event.get('sourceNoteIndex')
        if type(owner) is int:
            owners[owner].append(index)
    result = {}
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
        if (index in result or event_kind(note) != 'PITCHED' or event_kind(event) != 'PITCHED'
                or note.get('visualSize', 'FULL') != 'FULL'
                or any(k not in event or k not in note or event[k] != note[k]
                       for k in ('measureIndex', 'staffIndex', 'staffCount'))):
            raise ValueError('Invalid printed fingering note identity')
        result[index] = digit, placement.lower()
    return result


def write(notation, mark):
    technical = notation.find('technical')
    if technical is None:
        technical = ET.SubElement(notation, 'technical')
    digit, placement = mark
    ET.SubElement(technical, 'fingering', placement=placement).text = str(digit)
