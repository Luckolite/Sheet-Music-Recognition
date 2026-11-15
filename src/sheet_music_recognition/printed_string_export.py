# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Export source-owned, attack-local Roman string labels."""
from collections import defaultdict
import xml.etree.ElementTree as ET

from .typed_events import event_kind


ROMAN_NUMBERS = {'I': 1, 'II': 2, 'III': 3, 'IV': 4}


def retained(page):
    rows = page.get('printedStringMarks', [])
    notes = page.get('score', {}).get('notes', [])
    if not isinstance(rows, list) or len(rows) > len(notes):
        raise ValueError('Invalid printed string mark list')
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
        if (index in result or event_kind(note) != 'PITCHED' or event_kind(event) != 'PITCHED'
                or note.get('visualSize', 'FULL') != 'FULL'
                or any(key not in event or key not in note or event[key] != note[key]
                       for key in ('measureIndex', 'staffIndex', 'staffCount'))):
            raise ValueError('Invalid printed string mark note identity')
        result[index] = literal, number, placement.lower()
    return result


def write(note, mark):
    literal, number, placement = mark
    notation = ET.SubElement(note, 'notations')
    technical = ET.SubElement(notation, 'technical')
    ET.SubElement(technical, 'string', placement=placement).text = str(number)
    ET.SubElement(technical, 'other-technical', placement=placement).text = literal
