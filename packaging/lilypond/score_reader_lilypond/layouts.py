# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Prepare temporary MusicXML for the requested engraving layout."""
from collections import defaultdict
from xml.etree import ElementTree as ET

LAYOUTS = ('original', 'guitar', 'single-staff')


def prepare(source, destination, layout):
    if layout == 'original':
        return source
    if layout not in LAYOUTS:
        raise ValueError(f'Unknown sheet layout: {layout}')
    try:
        tree = ET.parse(source)
    except ET.ParseError as error:
        raise ValueError('Layout changes require uncompressed score-partwise MusicXML') from error
    root = tree.getroot()
    if root.tag != 'score-partwise':
        raise ValueError('Layout changes require uncompressed score-partwise MusicXML')
    if layout == 'guitar':
        _guitar(root)
    else:
        _single_staff(root)
    tree.write(destination, encoding='utf-8', xml_declaration=True)
    return destination


def _guitar(root):
    for part in root.findall('part'):
        if part.find('.//unpitched') is not None:
            raise ValueError('Guitar layout requires pitched music')
        first = part.find('measure')
        if first is None:
            continue
        attributes = first.find('attributes')
        if attributes is None:
            attributes = ET.Element('attributes')
            first.insert(0, attributes)
        count = int(attributes.findtext('staves', '1'))
        for group in part.findall('measure/attributes'):
            for clef in group.findall('clef'):
                group.remove(clef)
        for staff in range(1, count + 1):
            number = {'number': str(staff)} if count > 1 else {}
            clef = ET.Element('clef', number)
            ET.SubElement(clef, 'sign').text = 'TAB'
            ET.SubElement(clef, 'line').text = '5'
            boundary = next((i for i, node in enumerate(attributes)
                             if node.tag in ('staff-details', 'transpose', 'measure-style')),
                            len(attributes))
            attributes.insert(boundary, clef)
            for details in attributes.findall('staff-details'):
                if details.get('number', '1') == str(staff):
                    attributes.remove(details)
            details = ET.Element('staff-details', number)
            ET.SubElement(details, 'staff-lines').text = '6'
            for line, (step, octave) in enumerate(zip('EADGBE', (2, 2, 3, 3, 3, 4)), 1):
                tuning = ET.SubElement(details, 'staff-tuning', line=str(line))
                ET.SubElement(tuning, 'tuning-step').text = step
                ET.SubElement(tuning, 'tuning-octave').text = str(octave)
            boundary = next((i for i, node in enumerate(attributes)
                             if node.tag in ('transpose', 'measure-style')), len(attributes))
            attributes.insert(boundary, details)


def _single_staff(root):
    parts = root.findall('part')
    if not parts:
        raise ValueError('MusicXML contains no score parts')
    measures = [part.findall('measure') for part in parts]
    numbering = [m.get('number') for m in measures[0]]
    if any([m.get('number') for m in sequence] != numbering for sequence in measures[1:]):
        raise ValueError('Single-staff layout requires matching measure numbers across parts')
    voices = {}
    state = defaultdict(dict)
    for group in zip(*measures):
        primary = group[0]
        closing = [node for node in primary.findall('barline') if node.get('location', 'right') == 'right']
        for node in closing:
            primary.remove(node)
        cursor = 0
        for part_index, measure in enumerate(group):
            if cursor:
                backup = ET.SubElement(primary, 'backup')
                ET.SubElement(backup, 'duration').text = str(cursor)
            cursor = 0
            for node in list(measure):
                if node.tag == 'attributes':
                    for child in list(node):
                        if child.tag in ('divisions', 'key', 'time', 'transpose'):
                            state[part_index][child.tag] = ET.canonicalize(ET.tostring(child), strip_text=True)
                        if child.tag in ('clef', 'staves', 'staff-details', 'part-symbol'):
                            node.remove(child)
                    continue
                if node.tag in ('backup', 'forward') or (
                        node.tag == 'note' and node.find('chord') is None):
                    cursor += int(node.findtext('duration', '0')) * (-1 if node.tag == 'backup' else 1)
                voice = node.find('voice')
                if voice is None and node.tag == 'note':
                    voice = ET.Element('voice')
                    voice.text = '1'
                    after = {'chord', 'grace', 'cue', 'pitch', 'unpitched', 'rest', 'duration',
                             'tie', 'instrument', 'footnote', 'level'}
                    index = next((i for i, child in enumerate(node) if child.tag not in after), len(node))
                    node.insert(index, voice)
                if voice is not None:
                    key = part_index, voice.text
                    voice.text = voices.setdefault(key, str(len(voices) + 1))
                staff = node.find('staff')
                if staff is not None:
                    node.remove(staff)
                if part_index and node.tag not in ('print', 'barline'):
                    primary.append(node)
        if any(state[i] != state[0] for i in range(1, len(parts))):
            raise ValueError('Single-staff layout requires matching divisions, key, meter and transposition')
        primary.extend(closing)
    for part in parts[1:]:
        root.remove(part)
    part_list = root.find('part-list')
    if part_list is not None:
        for item in list(part_list):
            if item.get('id') != parts[0].get('id'):
                part_list.remove(item)
