# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Written ornament glyphs from proved source heads, never performed subdivisions."""
import xml.etree.ElementTree as ET
from .articulation_export import source_flags
from .typed_events import event_kind

TYPES = {1: 'trill-mark', 2: 'turn', 3: 'inverted-turn',
         4: 'inverted-mordent', 5: 'mordent'}
MASK = (7 << 5) | (1 << 8) | (3 << 9) | (3 << 11) | (1 << 13) | (1 << 14) | (7 << 16)
TYPE_MASK = 7 << 5
TRILL = 1 << 5
TRILL_WAVE = 1 << 8


def bind_trill_waves(notes, tie_links):
    """Attach wave endpoints to the existing written notes and proved ties."""
    successors = {earlier: current for current, earlier in tie_links.items()}
    occupied = {}
    trills = []
    chain = set()
    for index, note in enumerate(notes):
        note.pop('trillWaveStart', None)
        note.pop('trillWaveStop', None)
        if note['retainedOrnamentFlags'] & TYPE_MASK == TRILL:
            trills.append(index)
    for index in sorted(trills, key=lambda index: notes[index]['start']):
        note = notes[index]
        flags = note['retainedOrnamentFlags']
        if index in chain:
            note['retainedOrnamentFlags'] &= 7 << 16  # Retain a genuinely independent tremolo mark.
            continue
        if not flags & TRILL_WAVE:
            continue
        last = index
        while last in successors:
            following = successors[last]
            next_flags = notes[following]['retainedOrnamentFlags']
            if next_flags & (TYPE_MASK | TRILL_WAVE) != TRILL:
                break
            last = following
            chain.add(last)
        number = next((i for i in range(1, 17) if occupied.get(i, -1) <= note['start']), None)
        if number is None:
            raise ValueError('Too many overlapping MusicXML trill waves')
        occupied[number] = notes[last]['end']
        note['trillWaveStart'] = number
        notes[last]['trillWaveStop'] = number


def retained(page, index):
    flags = source_flags(page, index) & MASK
    kind, beams = (flags >> 5) & 7, (flags >> 16) & 7
    isolated = kind == 6 and not flags & (1 << 14)
    if kind not in TYPES and not isolated and not 1 <= beams <= 4:
        return 0
    if event_kind(page['events'][index]) != 'PITCHED':
        if isolated and not 1 <= beams <= 4:
            return 0
        raise ValueError('Unpitched source ornaments need explicit instrument semantics')
    beam_depth = page['score']['notes'][index].get('beamCount', 0)
    if type(beam_depth) is int and 0 < beam_depth < beams <= 4:
        # Source flags encode the performed subdivision, including rhythmic beams.
        flags -= beam_depth << 16
    return flags


def write(notation, flags, wave_start=None, wave_stop=None):
    kind = (flags >> 5) & 7
    glyph = TYPES.get(kind)
    beams = (flags >> 16) & 7
    beams = beams if 1 <= beams <= 4 else 0
    if kind == 6 and not flags & (1 << 14):
        articulations = notation.find('articulations')
        if articulations is None:
            articulations = ET.SubElement(notation, 'articulations')
        approach = ET.SubElement(articulations, 'plop' if flags & (1 << 13) else 'scoop')
        # Bit8 is shape only for an isolated SLIDE; TURN keeps its delayed meaning.
        if flags & (1 << 8):
            approach.set('line-shape', 'straight')
    if glyph is None and not (beams or wave_start or wave_stop):
        return
    ornaments = ET.SubElement(notation, 'ornaments')
    if glyph is not None:
        if flags & (1 << 8) and kind in (2, 3):
            glyph = 'delayed-turn' if kind == 2 else 'delayed-inverted-turn'
        attributes = {'start-note': 'main'} if kind == 1 else {}
        ET.SubElement(ornaments, glyph, attributes)
    if glyph is not None:
        for shift, side in ((9, 'above'), (11, 'below')):
            accidental = (flags >> shift) & 3
            if accidental:
                ET.SubElement(ornaments, 'accidental-mark', placement=side).text = (
                    'flat', 'natural', 'sharp')[accidental-1]
    if wave_start:
        ET.SubElement(ornaments, 'wavy-line', type='start', number=str(wave_start), **{'start-note': 'main'})
    if wave_stop:
        # MusicXML represents a single-note wave with a rightward stop on the same note.
        attributes = {'relative-x': '24'} if wave_start == wave_stop else {}
        ET.SubElement(ornaments, 'wavy-line', type='stop', number=str(wave_stop), **attributes)
    if beams:
        ET.SubElement(ornaments, 'tremolo', type='single').text = str(beams)
