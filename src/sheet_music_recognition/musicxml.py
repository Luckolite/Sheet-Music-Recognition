from . import straight_slide, slide_numbering
# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""MusicXML 4.0 concert-pitch export of the decoded performance (not source engraving)."""
from bisect import bisect_left, bisect_right
from collections import defaultdict
from functools import lru_cache
from itertools import accumulate
from math import gcd
from fractions import Fraction
from decimal import Decimal
import math
import struct
from pathlib import Path
import xml.etree.ElementTree as ET
from .typed_events import event_kind, validate_unpitched, notation_order, display_position
from .rest_kinds import rest_kind, full_measure_span
from . import visual_size, cue_caption, bow_export, clef_export, articulation_export, ornament_export, grace_export, spelling_export, tremolo_export
from . import guitar_export, fingering_export, printed_string_export
from .natural_harmonic_notation import owner_index as natural_harmonic_owner_index
from .fall_off_notation import owner_index as fall_off_owner_index
from . import parenthesized_note_group
from . import beam_export
from .beam_source import checked as checked_beams

DIVISIONS = 10080  # Exact common binary values and triplet/quintuplet/septuplet subdivisions.


def _natural_harmonic_target(note):
    """Mirror ScoreNaturalHarmonicNotation.target using the wire's float32 values."""
    try:
        measure, staff, count = note['measureIndex'], note['staffIndex'], note['staffCount']
        step = note['staffStep']
        position, page_y = float(note['positionInMeasure']), float(note['pageY'])
    except (KeyError, TypeError, ValueError):
        return None
    if (event_kind(note) != 'PITCHED' or type(measure) is not int or measure < 0
            or type(staff) is not int or type(count) is not int or count < 1 or not 0 <= staff < count
            or type(step) is not int or not math.isfinite(position) or not 0 <= position <= 1
            or not math.isfinite(page_y) or not 0 <= page_y <= 1):
        return None
    try:
        pbits = struct.unpack('>I', struct.pack('>f', position))[0]
        ybits = struct.unpack('>I', struct.pack('>f', page_y))[0]
    except (OverflowError, struct.error):
        return None
    return (f'natural-harmonic-note-v1:{measure}:{staff}:{count}:'
            f'{pbits:08x}:{step}:{ybits:08x}')


def element(parent, tag, value=None, **attributes):
    node = ET.SubElement(parent, tag, {key: str(value) for key, value in attributes.items()})
    if value is not None:
        node.text = str(value)
    return node


def ticks(value):
    if not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
        raise ValueError("MusicXML needs finite, nonnegative event times")
    return round(value * DIVISIONS)


def note_type(node, duration, event=None):
    actual = event.get('tupletActualNotes', 1) if event else 1
    normal = event.get('tupletNormalNotes', 1) if event else 1
    if (type(actual) is not int or type(normal) is not int or actual not in (1,2,3,4,5,6,7)
            or not 1 <= normal <= 16 or actual == 1 and normal != 1):
        raise ValueError('Unsupported explicit tuplet ratio')
    if actual != 1:
        for denominator, name in ((1,'whole'), (2,'half'), (4,'quarter'), (8,'eighth'),
                                  (16,'16th'), (32,'32nd'), (64,'64th')):
            base = DIVISIONS * 4 // denominator
            for dots, scale in ((0,1), (1,1.5), (2,1.75)):
                if abs(duration - base*scale*normal/actual) <= 1:
                    element(node, 'type', name)
                    for _ in range(dots):
                        element(node, 'dot')
                    modification = element(node, 'time-modification')
                    element(modification, 'actual-notes', actual)
                    element(modification, 'normal-notes', normal)
                    return
        return  # An explicit ratio must not silently become ordinary or a different tuplet.
    for denominator, name in ((1, 'whole'), (2, 'half'), (4, 'quarter'), (8, 'eighth'),
                              (16, '16th'), (32, '32nd'), (64, '64th')):
        base = DIVISIONS * 4 // denominator
        for dots, scale in ((0, 1), (1, 1.5), (2, 1.75)):
            if abs(duration - base * scale) <= 1:
                element(node, 'type', name)
                for _ in range(dots):
                    element(node, 'dot')
                return
        for actual, normal in ((3, 2), (5, 4), (7, 4)):
            if abs(duration - base * normal / actual) <= 1:
                element(node, 'type', name)
                modification = element(node, 'time-modification')
                element(modification, 'actual-notes', actual)
                element(modification, 'normal-notes', normal)
                return


@lru_cache(maxsize=256)
def exact_written_parts(duration, values):
    """Fewest exact glyphs from a proved duration vocabulary; no clock rounding."""
    if not values or duration <= 0:
        return None
    quantum = 0
    for value in values:
        quantum = gcd(quantum, value)
    if duration % quantum:
        return None
    target = duration // quantum
    units = tuple(value // quantum for value in values)
    unreachable = target + 1
    count, choice = [unreachable] * (target + 1), [0] * (target + 1)
    count[0] = 0
    for amount in range(1, target + 1):
        for unit in units:
            if unit <= amount and count[amount-unit] + 1 < count[amount]:
                count[amount], choice[amount] = count[amount-unit] + 1, unit
    if not choice[target]:
        return None
    parts = []
    while target:
        parts.append(choice[target] * quantum)
        target -= choice[target]
    return tuple(sorted(parts, reverse=True))


def duration_parts(duration, events=()):
    """Split exact composite spans while retaining each head's explicit ratio."""
    heads = tuple(events) or (None,)
    probes = []
    for event in heads:
        probe = ET.Element('note')
        note_type(probe, duration, event)
        probes.append(probe)
    if all(probe.find('type') is not None for probe in probes):
        return (duration,)
    written_values = {Fraction(DIVISIONS * 4, denominator) * Fraction(numerator, 4)
                      for denominator in (1, 2, 4, 8, 16, 32, 64)
                      for numerator in (4, 6, 7)}
    if any(event and event.get('tupletActualNotes', 1) != 1 for event in heads):
        common = None
        for event in heads:
            actual = event.get('tupletActualNotes', 1) if event else 1
            normal = event.get('tupletNormalNotes', 1) if event else 1
            values = {int(value * normal / actual) for value in written_values
                      if (value * normal / actual).denominator == 1}
            common = values if common is None else common & values
        parts = exact_written_parts(duration, tuple(sorted(common, reverse=True)))
        return parts if parts is not None else (duration,)
    # Keep the existing inferred-clock boundary: only ordinary 64th-grid spans split.
    if duration % (DIVISIONS // 16):
        return (duration,)
    values = sorted((int(value) for value in written_values if value.denominator == 1), reverse=True)
    parts, remaining = [], duration
    while remaining:
        value = next(v for v in values if v <= remaining)
        parts.append(value)
        remaining -= value
    return tuple(parts)


def _retain_printed_beam_voices(beams, groups, bar_starts, bar_ends):
    # Greedy assignment may switch voices after an independent sustain releases.
    # A whole printed run may stay in one of its current voices if that voice is free.
    if not beams:
        return
    chords = sorted(groups.items())
    starts = [key[0] for key, _ in chords]
    running_ends = list(accumulate((key[1] for key, _ in chords), max))
    owners = {frozenset(n['sourceIdentity'] for n in chord): i
              for i, (_, chord) in enumerate(chords)}
    for columns, bridges, _ in beams:
        if len(columns) < 2 or not all(mask & 1 for mask in bridges):
            continue
        members = [owners.get(frozenset(column)) for column in columns]
        if any(i is None for i in members) or len(set(members)) != len(columns):
            continue
        run = [chords[i] for i in members]
        start, end = run[0][0][0], run[-1][0][1]
        bar = bisect_right(bar_starts, start) - 1
        voices = list(dict.fromkeys(chord[0]['voice'] for _, chord in run))
        if (len(voices) == 1 or bar < 0 or end > bar_ends[bar]
                or any(left[0][1] != right[0][0] for left, right in zip(run, run[1:]))):
            continue
        states = (n for _, chord in run for n in chord)
        if any(n.get('durationFallback') or n.get('retainedTremolo')
               or n.get('retainedVisualSize') == 'CUE'
               or n.get('tie_start') or n.get('tie_stop') for n in states):
            continue
        for (attack, release, _), chord in run:
            for note in chord:
                probe = ET.Element('note')
                note_type(probe, release - attack, note)
                if probe.findtext('type') not in ('eighth', '16th', '32nd', '64th',
                                                  '128th', '256th', '512th', '1024th'):
                    break
            else:
                continue
            break
        else:
            members = set(members)
            reserved = {chords[i][1][0]['voice']
                        for i in range(bisect_right(running_ends, start), bisect_left(starts, end))
                        if i not in members and chords[i][0][1] > start}
            voice = next((v for v in voices if v not in reserved), None)
            if voice is not None:
                for _, chord in run:
                    for note in chord:
                        note['voice'] = voice


def metric_pulse(value):
    for i, name in enumerate(('whole', 'half', 'quarter', 'eighth', '16th', '32nd')):
        for dots, scale in enumerate((1, 1.5, 1.75)):
            if value == math.ldexp(4, -i)*scale:
                return name, dots
    raise ValueError('Unsupported MusicXML metric pulse')


def tempo_mark(tempo):
    """Printed pulse and rate; sound tempo remains quarter notes per minute."""
    bpm, pulse = tempo['bpm'], tempo.get('beatUnit', 1)
    if (type(bpm) not in (int, float) or not math.isfinite(bpm) or not 15 <= bpm <= 1600
            or type(pulse) not in (int, float) or not math.isfinite(pulse) or not 0 < pulse <= 4):
        raise ValueError('Invalid printed tempo')
    names = ('whole', 'half', 'quarter', 'eighth', '16th', '32nd', '64th',
             '128th', '256th', '512th', '1024th')
    for i, name in enumerate(names):
        for dots, scale in enumerate((1, 1.5, 1.75)):
            unit = math.ldexp(4, -i) * scale
            if abs(unit - pulse) < 1e-9:
                return name, dots, bpm / unit
    # Match ScoreTempoMark: unsupported pulses use an equivalent quarter mark.
    return 'quarter', 0, bpm


def expression_direction(measure, mark, offset):
    kind = mark['kind']
    caption = mark.get('_neutralRestCaption', False)
    below_bow_caption = kind == 'UNRESOLVED_DIRECTION' and any(
        row.get('sourceId') == 'printed-expression-below-bow-caption'
        and row.get('printedText', '').strip().lower() == 'segue'
        and row.get('staffIndex') == mark.get('staffIndex')
        and row.get('staffCount') == mark.get('staffCount')
        for row in mark.get('evidence', []))
    below = caption or below_bow_caption or kind in ('PEDAL_DOWN', 'PEDAL_UP')
    direction = element(measure, 'direction', placement='below' if below else 'above')
    type_node = element(direction, 'direction-type')
    if mark.get('_hairpin'):
        direction.set('placement', 'below')
        element(type_node, 'wedge', type=mark['_hairpin'], number=mark['_hairpinNumber'],
                **mark.get('_hairpinGeometry', {}))
    elif mark.get('_absoluteDynamic'):
        direction.set('placement', 'below')
        caption = mark.get('_suddenDynamic')
        if caption and caption['before']:
            element(type_node, 'words', caption['qualifier']+' ')
            type_node = element(direction, 'direction-type')
        element(element(type_node, 'dynamics'), mark['_absoluteDynamic'])
        if caption and not caption['before']:
            element(element(direction, 'direction-type'), 'words', ' '+caption['qualifier'])
    elif kind in ('PEDAL_DOWN', 'PEDAL_UP'):
        literal = (mark.get('targetEventId') or '').startswith('expression-column:') and any(
            e.get('sourceId') == 'printed-expression-word' for e in mark.get('evidence', []))
        if literal:
            printed = next((e['printedText'] for e in mark.get('evidence', []) if e.get('printedText')), None)
            if not printed:
                measure.remove(direction)
                return
            element(type_node, 'words', printed)
        else:
            element(type_node, 'pedal', type='start' if kind == 'PEDAL_DOWN' else 'stop', line='yes', sign='no')
    elif kind == 'METRIC_MODULATION':
        fields = mark.get('qualifierText', '').split(':')
        if len(fields) != 3 or fields[0] != 'metric-pulse-v1':
            raise ValueError('Metric relationship has no explicit pulse pair')
        metronome = element(type_node, 'metronome')
        for encoded in fields[1:]:
            name, dots = metric_pulse(float(encoded))
            element(metronome, 'beat-unit', name)
            for _ in range(dots):
                element(metronome, 'beat-unit-dot')
    elif kind in ('SFORZANDO', 'SFORZATO', 'SFORZANDO_PIANO'):
        name = {'SFORZANDO': 'sf', 'SFORZATO': 'sfz', 'SFORZANDO_PIANO': 'sfp'}[kind]
        element(element(type_node, 'dynamics'), name)
    else:
        fallback = {'RITARDANDO': 'rit.', 'RALLENTANDO': 'rall.', 'RITENUTO': 'ritenuto',
                    'ACCELERANDO': 'accel.', 'A_TEMPO': 'a tempo', 'TEMPO_PRIMO': 'tempo primo',
                    'SAME_TEMPO': "l'istesso tempo", 'FERMATA': 'fermata', 'BREATH': 'breath', 'CAESURA': 'caesura'}
        printed = next((e['printedText'] for e in mark.get('evidence', []) if e.get('printedText')), fallback.get(kind))
        if printed is None:
            measure.remove(direction)
            return
        element(type_node, 'words', printed)
    element(direction, 'offset', offset)


def _pitch_element(note, spelling):
    step, alter, octave = spelling
    pitch = element(note, 'pitch')
    element(pitch, 'step', step)
    if alter:
        element(pitch, 'alter', alter)
    element(pitch, 'octave', octave)


def _harmonic_role(note, kind, role):
    notation = note.find('notations')
    if notation is None:
        notation = element(note, 'notations')
    technical = notation.find('technical')
    if technical is None:
        technical = element(notation, 'technical')
    harmonic = element(technical, 'harmonic', **{'print-object': 'no'})
    element(harmonic, kind)
    element(harmonic, role)
    return technical


def _written_open_harmonics(page, measure_starts, continuations, bars):
    rows = page.get('printedOpenHarmonicChords', [])
    if not rows:
        return {}
    if not isinstance(rows, list) or len(rows) > 3:
        raise ValueError('Invalid printed open harmonic chords')
    score = page['score']
    regions, raw_notes = score['measures'], score['notes']
    events = page['events']
    width, height = page['width'], page['height']
    result = defaultdict(list)
    owners = set()
    for row in rows:
        index, offset, heads = (row.get(k) for k in ('measureIndex', 'quarterBeatOffset', 'heads'))
        if (type(index) is not int or not 0 <= index < len(bars)
                or index in continuations or index + 1 in continuations
                or type(offset) is not int or offset != len(result[index])
                or not isinstance(heads, list) or len(heads) != 2):
            raise ValueError('Invalid printed open harmonic chord position')
        region = regions[index]
        chord = []
        for head in heads:
            step, midi, x, y = (head.get(k) for k in ('staffStep', 'writtenMidi', 'x', 'y'))
            if (type(step) is not int or not 0 <= step <= 8 or type(midi) is not int
                    or not 0 <= midi <= 127
                    or type(x) not in (float, int) or type(y) not in (float, int)
                    or not region['left'] < x / width < region['right']
                    or not region['top'] < y / height < region['bottom']):
                raise ValueError('Invalid printed open harmonic head')
            octave, letter = divmod(30 + step, 7)
            natural = (octave + 1) * 12 + (0, 2, 4, 5, 7, 9, 11)[letter]
            alter = midi - natural
            key = bars[index]['key']
            key_order = (3, 0, 4, 1, 5, 2, 6) if key >= 0 else (6, 2, 5, 1, 4, 0, 3)
            if alter != ((1 if key > 0 else -1) if letter in key_order[:abs(key)] else 0):
                raise ValueError('Printed open harmonic head has inconsistent written spelling')
            sound, owner = head.get('soundingMidi'), head.get('sourceNoteIndex')
            if owner is not None:
                if (type(owner) is not int or not 0 <= owner < min(len(raw_notes), len(events))
                        or owner in owners or type(sound) is not int or not 0 <= sound <= 127):
                    raise ValueError('Invalid open harmonic sounding owner')
                event, native = events[owner], raw_notes[owner]
                expected = dict(measureIndex=index, staffIndex=0, staffCount=1)
                if (any(event.get(k) != v or native.get(k) != v for k, v in expected.items())
                        or native.get('clefBottomDiatonic') != 30 or native.get('staffStep') != step
                        or event.get('sourceNoteIndex') != owner or event.get('midi') != sound
                        or event.get('writtenMidi') != midi or event.get('harmonicTouchMidi') != midi
                        or (event.get('x'), event.get('y')) != (x, y)
                        or ticks(event['startBeat']) != ticks(measure_starts[index] + offset)
                        or ticks(event['durationBeats']) != DIVISIONS):
                    raise ValueError('Open harmonic sounding owner does not match the written head')
                owners.add(owner)
            elif sound is not None:
                raise ValueError('A neutral written harmonic cannot claim a sounding pitch')
            chord.append((('CDEFGAB'[letter], alter, octave), sound))
        if (heads[0]['x'] != heads[1]['x'] or heads[0]['y'] <= heads[1]['y']
                or heads[0]['staffStep'] >= heads[1]['staffStep']):
            raise ValueError('A printed open harmonic chord requires two ordered heads on one shaft')
        result[index].append(chord)
    for index, chords in result.items():
        if (bars[index]['length'] != len(chords) * DIVISIONS
                or any(row.get('measureIndex') == index for row in score.get('rests', ()))
                or any(event.get('measureIndex') == index and owner not in owners
                       for owner, event in enumerate(events))):
            raise ValueError('Written open harmonic chords must fill one unoccupied bar')
    if len(owners) not in (0, len(rows) * 2):
        raise ValueError('Mixed neutral and sounding harmonic owners')
    return result


def _write_open_harmonics(measure, chords):
    original = measure.findall('note')
    sounding = chords[0][0][1] is not None
    if sounding:
        expected = [sound for chord in chords for _, sound in chord]
        pitches = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}
        actual = [(int(n.findtext('pitch/octave')) + 1) * 12
                  + pitches[n.findtext('pitch/step')] + int(n.findtext('pitch/alter', '0'))
                  for n in original if n.find('pitch') is not None]
        if (len(original) != len(expected) or actual != expected
                or any(n.findtext('duration') != str(DIVISIONS) or n.findtext('voice') != '1'
                       or (n.find('chord') is not None) != bool(i % 2)
                       or n.find('tie') is not None or n.find('grace') is not None
                       for i, n in enumerate(original))):
            raise ValueError('Cannot identify the exact open harmonic playback carriers')
    elif (len(original) != 1 or original[0].find('rest') is None
          or original[0].findtext('duration') != str(DIVISIONS * len(chords))
          or original[0].find('notations') is not None):
        raise ValueError('Cannot replace a nonempty bar with neutral written harmonics')
    if measure.find('backup') is not None or measure.find('forward') is not None:
        raise ValueError('Open harmonic chords require an unambiguous bar clock')
    at = list(measure).index(original[0])
    for node in original:
        measure.remove(node)
    for i, chord in enumerate(chords):
        if sounding:
            for carrier in original[2*i:2*i+2]:
                carrier.set('print-object', 'no')
                _harmonic_role(carrier, 'natural', 'sounding-pitch')
                measure.insert(at, carrier)
                at += 1
        for k, (spelling, _) in enumerate(chord):
            note = ET.Element('note')
            element(note, 'cue')
            if sounding or k:
                element(note, 'chord')
            _pitch_element(note, spelling)
            element(note, 'duration', DIVISIONS)
            element(note, 'voice', 1)
            element(note, 'type', 'quarter', size='full')
            element(note, 'stem', 'up')
            element(note, 'notehead', 'diamond', filled='no')
            technical = _harmonic_role(note, 'natural', 'touching-pitch')
            element(technical, 'fingering', '0', placement='above' if k else 'below')
            measure.insert(at, note)
            at += 1


def _harmonic_spelling(diatonic, midi):
    octave, letter = divmod(diatonic, 7)
    alter = midi - ((octave + 1) * 12 + (0, 2, 4, 5, 7, 9, 11)[letter])
    if not 0 <= octave <= 9 or not -2 <= alter <= 2:
        raise ValueError('Harmonic has invalid written spelling')
    return 'CDEFGAB'[letter], alter, octave


def _closed_artificial_spelling(event, source, third):
    base, touch, sound = (event.get(k) for k in ('writtenMidi', 'harmonicTouchMidi', 'midi'))
    fifth = event.get('artificialHarmonicFifth') is True
    interval, steps, transposition, sounding_steps = (
        (7, 4, 19, 11) if fifth else (4, 2, 28, 16) if third else (5, 3, 24, 14))
    if (any(type(x) is not int or not 0 <= x <= 127 for x in (base, touch, sound))
            or touch - base != interval or sound - base != transposition
            or event_kind(event) != 'PITCHED' or event.get('octaveShift', 0)
            or event.get('clefInferred', False) or source is None
            or event_kind(source) != 'PITCHED' or source.get('harmonicSemitones') != transposition
            or any(event.get(k) != source.get(k) for k in ('measureIndex', 'staffIndex', 'staffCount'))):
        raise ValueError('Artificial harmonic requires one retained stopped head and matching pitches')
    diatonic = source['clefBottomDiatonic'] + source['staffStep']
    return (_harmonic_spelling(diatonic, base), _harmonic_spelling(diatonic + steps, touch),
            _harmonic_spelling(diatonic + sounding_steps, sound))


def _beamed_fourth_triplet(event):
    return (event.get('durationBeats') == 1 / 3
            and event.get('tupletActualNotes') == 3 and event.get('tupletNormalNotes') == 2)


def _dotted_beamed_fourth(event):
    return event.get('durationBeats') == .375 and event.get('tupletActualNotes', 1) == 1


def _third_hook_fourth(event):
    return event.get('durationBeats') == .125 and event.get('tupletActualNotes', 1) == 1


def _beamed_fourth_spelling(event, source, engraved):
    triplet = _beamed_fourth_triplet(event)
    dotted = _dotted_beamed_fourth(event)
    written_clock = (source.get('beamCount') == 1 and source.get('tupletDivisor', 1) == 3
                     and source.get('tupletNormalNotes', 1) == 2
                     and source.get('augmentationDots', 0) == 0) if triplet else (
                         (((dotted or event.get('durationBeats') == .25) and source.get('beamCount') == 2)
                          or (_third_hook_fourth(event) and source.get('beamCount') == 3))
                         and source.get('augmentationDots', 0) == int(dotted)
                         and source.get('tupletDivisor', 1) == 1)
    if (event.get('artificialHarmonicFourth') is not True
            or event.get('artificialHarmonicThird') is True
            or not written_clock
            or event.get('durationFallback') or event.get('tiedFromPrevious')
            or source.get('staffIndex') != 0 or source.get('staffCount') != 1
            or source.get('clefBottomDiatonic') != 30
            or type(source.get('stemDirection')) is not int or source['stemDirection'] not in (-1, 1)
            or source.get('visualSize', 'FULL') != 'FULL'
            or source.get('articulations', 0) not in (0, 4, 8, 12)  # Retain staccato and tenuto on the stopped head.
            or any(source.get(k) for k in ('octaveShift', 'unbeamedDurationBeats',
                                          'leadingRestBeats', 'followingRestBeats', 'crossStaffBeam',
                                          'tiedFromPrevious', 'boundaryTies'))):
        raise ValueError('Beamed artificial fourth requires a complete written sixteenth or eighth triplet')
    if 'printedHarmonicTouchAccidental' not in event:
        return None
    mark = event['printedHarmonicTouchAccidental']
    if type(mark) is not int or mark not in (-1, 0, 1) or mark != engraved[1][1]:
        raise ValueError('Printed touching accidental disagrees with the written fourth')
    return {-1: 'flat', 0: 'natural', 1: 'sharp'}[mark]


def _ordinary_compound_quarter(note, stem=1):
    return (event_kind(note) == 'PITCHED' and note.get('clefBottomDiatonic') == 30
            and note.get('visualSize', 'FULL') == 'FULL' and note.get('beamCount') == 0
            and note.get('unbeamedDurationBeats') == 1 and note.get('augmentationDots', 0) == 0
            and note.get('tupletDivisor', 1) == 1 and note.get('stemDirection') == stem
            and not any(note.get(k) for k in ('octaveShift', 'crossStaffBeam', 'articulations',
                                              'boundaryTies', 'tiedFromPrevious')))


def _upstem_compound_harmonic_bars(page):
    events = page['events']
    flagged = defaultdict(list)
    for index, event in enumerate(events):
        if event.get('compoundUpstemHarmonicBar') is True:
            flagged[event['measureIndex']].append(index)
    accepted = set()
    spans = page['measureBeats']
    starts = list(accumulate(spans, initial=0))
    for bar, owners in flagged.items():
        if (type(bar) is not int or not 0 <= bar < len(spans) or spans[bar] != 2
                or bar in page.get('printedMeasureContinuations', ())
                or bar + 1 in page.get('printedMeasureContinuations', ())
                or len(owners) != 4
                or any(rest['measureIndex'] == bar for rest in page['score'].get('rests', ()))
                or any(e['measureIndex'] == bar and i not in owners for i, e in enumerate(events))):
            raise ValueError('Compound upstem harmonics require a complete two-quarter bar')
        for offset in (0, 1):
            chord = [events[i] for i in owners if events[i]['startBeat'] == starts[bar] + offset]
            natural = [e for e in chord if type(e.get('openStringMidi')) is int]
            artificial = [e for e in chord if _compound_upstem_interval(e, offset)]
            if len(chord) != 2 or len(natural) != 1 or len(artificial) != 1 or natural[0] is artificial[0]:
                raise ValueError('Compound upstem harmonics require two opposite open and stopped pairs')
        accepted.update(owners)
    return accepted


def _compound_upstem_interval(event, offset):
    if event.get('artificialHarmonicThird') is True:
        return False
    fifth = event.get('artificialHarmonicFifth') is True
    fourth = event.get('artificialHarmonicFourth') is True and event.get('compoundHarmonicFourth') is True
    return fifth and not fourth if offset == 0 else fourth and not fifth


def _compound_harmonic_fingering(event, natural, placement):
    item = event.get('compoundHarmonicFingering')
    if not isinstance(item, dict):
        raise ValueError('Compound harmonic is missing its printed fingering')
    base, touch, side = (item.get(k) for k in ('base', 'touch', 'placement'))
    if (side != placement or type(base) is not int or type(touch) is not int
            or (base != 0 if natural else base < 1) or not base < touch <= 4):
        raise ValueError('Compound harmonic has an invalid printed fingering')
    return base, touch, side


def _compound_open_harmonics(page, source_notes, shared_terminal=(), upstem_bars=(), opening_fingers=None):
    """Bind the recovered open-string attack to the printed shared shaft."""
    events = page['events']
    result = {}
    fourth_partners = {}
    fourth_touches = {}
    for i, base in enumerate(events):
        if i in shared_terminal:
            continue
        third = base.get('artificialHarmonicThird') is True
        fourth = base.get('compoundHarmonicFourth') is True
        mixed = base.get('mixedHarmonicQuarter') is True
        fifth = base.get('artificialHarmonicFifth') is True
        if not (third or fourth or fifth):
            continue
        upstem = i in upstem_bars
        if (i >= len(source_notes) or base.get('sourceNoteIndex') != i
                or sum((third, fourth, fifth)) != 1 or fifth and not upstem
                or (base.get('artificialHarmonicFourth') is True) != fourth
                or fifth and mixed):
            raise ValueError('Invalid stopped compound harmonic owner')
        stopped = source_notes[i]
        touch = stopped.get('openStringTouch')
        stem = stopped.get('stemDirection') if fourth or fifth else -1 if mixed else 1
        if stem not in (-1, 1):
            raise ValueError('Compound harmonic has no printed shaft direction')
        matches = [
            (j, event) for j, event in enumerate(events)
            if j != i and type(event.get('openStringMidi')) is int
            and type(event.get('x')) in (int, float) and type(base.get('x')) in (int, float)
            and abs(event['x'] - base['x']) <= page['width'] * .002
            and all(event.get(k) == base.get(k) for k in
                    ('measureIndex', 'staffIndex', 'staffCount', 'startBeat', 'durationBeats'))
        ]
        if (not isinstance(touch, dict) or not _ordinary_compound_quarter(stopped, stem)
                or len(matches) != 1 or base.get('durationBeats') != 1 or base.get('tiedFromPrevious')
                or base.get('durationFallback')):
            raise ValueError('Compound harmonic requires one retained open-string quarter')
        j, event = matches[0]
        if (j >= len(source_notes) or event.get('sourceNoteIndex') != j
                or event.get('tiedFromPrevious') or event.get('durationFallback')
                or event.get('clefInferred') or event_kind(event) != 'PITCHED' or j in result
                or event.get('octaveShift', 0) or event.get('boundaryTies', 0)
                or (base.get('mixedHarmonicQuarter') is True) != (event.get('mixedHarmonicQuarter') is True)):
            raise ValueError('Invalid compound open-string harmonic owner')
        note = source_notes[j]
        steps = note.get('staffStep')
        written, sound, open_string = (event.get(k) for k in ('writtenMidi', 'midi', 'openStringMidi'))
        if (not _ordinary_compound_quarter(note, stem) or type(steps) is not int
                or steps != touch.get('staffStep') or note.get('pageY') != touch.get('pageY')
                or note.get('positionInMeasure') != stopped.get('positionInMeasure')
                or type(event.get('y')) not in (int, float) or type(note.get('pageY')) not in (int, float)
                or not abs(event['y'] - note['pageY'] * page['height']) <= .01
                or note.get('writtenAccidental') != touch.get('writtenAccidental')
                or note.get('clefBottomDiatonic') != stopped.get('clefBottomDiatonic')
                or any(event.get(k) != note.get(k) for k in ('measureIndex', 'staffIndex', 'staffCount'))
                or any(type(n) is not int or not 0 <= n <= 127 for n in (written, sound))
                or written != event.get('harmonicTouchMidi')
                or open_string not in (55, 62, 69, 76)
                or sound - open_string != {4: 28, 5: 24, 7: 19, 9: 28}.get(written - open_string)):
            raise ValueError('Compound open string has invalid printed or sounding pitch')
        diatonic = note['clefBottomDiatonic'] + steps
        sound_step = diatonic + {4: 14, 5: 11, 7: 7, 9: 11}[written - open_string]
        if fourth or fifth:
            separation = steps - stopped['staffStep']
            opening_lower = base.get('openingLowerOpenFourth') is True
            if opening_lower and (stem != 1 or separation != -2 or written - open_string != 4
                    or not _accepted_lower_opening_fourth(page, i, j, shared_terminal)):
                raise ValueError('Opening lower natural harmonic requires two complete upstem quarters')
            lower = (fifth if upstem
                     else (opening_lower or _terminal_lower_open_fourth(page, base, stopped, written, separation, stem)
                           or _closed_lower_open_fourth(page, base, stopped, written, separation, stem)))
            if ((upstem and (stem != 1 or j not in upstem_bars
                            or (lower and (separation != -2 or written >= base['writtenMidi']))
                            or (not lower and separation != 5)))
                    or (not lower and (separation not in (5, 6) or written <= base['harmonicTouchMidi']))
                    or mixed and (stem != -1 or separation != 6)
                    or sound == base['midi']):
                raise ValueError('Stopped harmonic does not share its printed shaft with the open string')
            stem_name = 'up' if stem > 0 else 'down'
            fourth_partners[i] = stem_name
            fourth_touches[j] = stem_name, lower
            if opening_lower and opening_fingers is not None:
                # The source lower touching head has no printed open-string zero.
                opening_fingers[j] = None
                if 'compoundHarmonicFingering' in base:
                    opening_fingers[i] = _compound_harmonic_fingering(base, False, 'above')
        result[j] = _harmonic_spelling(diatonic, written), _harmonic_spelling(sound_step, sound)
    return result, fourth_partners, fourth_touches

def _accepted_lower_opening_fourth(page, stopped, natural, shared_quarters):
    events = page['events']
    onset = events[stopped]['startBeat']
    bar = events[stopped]['measureIndex']
    spans = page['measureBeats']
    regions = page['score'].get('measures', ())
    if (type(bar) is not int or not 0 < bar < len(spans) - 1 or len(regions) != len(spans)
            or spans[bar] != 2 or regions[bar].get('cadenceQuarterBeats', 0)
            or onset != sum(spans[:bar])
            or bar in page.get('printedMeasureContinuations', ())
            or bar + 1 in page.get('printedMeasureContinuations', ())
            or any(rest['measureIndex'] == bar for rest in page['score'].get('rests', ()))):
        return False
    others = [i for i, event in enumerate(events) if event['measureIndex'] == bar and i not in (stopped, natural)]
    return (len(others) == 2 and all(i in shared_quarters and events[i].get('sharedUpstemHarmonicQuarter') is True
            and events[i]['startBeat'] == onset + 1 and events[i]['durationBeats'] == 1 for i in others)
            and sum(events[i].get('artificialHarmonicThird') is True for i in others) == 1
            and sum(events[i].get('artificialHarmonicFourth') is True for i in others) == 1)


def _closed_lower_open_fourth(page, event, stopped, written, separation, stem):
    """The two downstem quarters each have an independently retained lower open touch."""
    bar = stopped.get('measureIndex')
    spans = page['measureBeats']
    regions = page['score'].get('measures', ())
    if (event.get('closedDownstemCompoundBar') is not True or stem != -1 or separation != -2
            or type(event.get('writtenMidi')) is not int or written >= event['writtenMidi']
            or type(bar) is not int or not 0 < bar < len(spans) - 1 or len(regions) != len(spans)
            or spans[bar] != 2 or regions[bar].get('cadenceQuarterBeats', 0)
            or bar in page.get('printedMeasureContinuations', ())
            or bar + 1 in page.get('printedMeasureContinuations', ())
            or any(rest['measureIndex'] == bar for rest in page['score'].get('rests', ()))):
        return False
    attack = sum(spans[:bar])
    owners = [member for member in page['events'] if member.get('measureIndex') == bar]
    bases = [member for member in owners if member.get('closedDownstemCompoundBar') is True]
    opens = [member for member in owners if type(member.get('openStringMidi')) is int]
    if len(owners) == 4 and len(bases) == len(opens) == 1:
        opening = [member for member in owners if member['startBeat'] == attack]
        return (bar == len(spans) - 2 and spans[-1] == 1 and regions[-1].get('cadenceQuarterBeats') == 1
                and bases == [event] and event['startBeat'] == opens[0]['startBeat'] == attack + 1
                and len(opening) == 2 and all(member.get('sharedDownstemHarmonicQuarter') is True
                    and member.get('openStringMidi') is None and member.get('durationBeats') == 1
                    and member.get('staffIndex') == 0 and member.get('staffCount') == 1
                    for member in opening)
                and sum(member.get('artificialHarmonicThird') is True
                        and member.get('artificialHarmonicFourth') is not True for member in opening) == 1
                and sum(member.get('artificialHarmonicFourth') is True
                        and member.get('artificialHarmonicThird') is not True for member in opening) == 1)
    if len(owners) != 4 or len(bases) != 2 or len(opens) != 2:
        return False
    clocks = {attack, attack + 1}
    return (all(member.get('staffIndex') == 0 and member.get('staffCount') == 1
                and member.get('durationBeats') == 1 for member in owners)
            and {member.get('startBeat') for member in bases} == clocks
            and {member.get('startBeat') for member in opens} == clocks
            and all(member.get('compoundHarmonicFourth') is True
                    and member.get('artificialHarmonicFourth') is True
                    and member.get('openStringMidi') is None for member in bases))


def _terminal_lower_open_fourth(page, event, stopped, written, separation, stem):
    """The lower touch of a closed final quarter carries its open fingering below."""
    spans = page['measureBeats']
    regions = page['score'].get('measures', ())
    bar = stopped.get('measureIndex')
    return (stem == -1 and separation == -2 and type(event.get('writtenMidi')) is int
            and written < event['writtenMidi']
            and type(bar) is int and bar >= 1 and bar + 1 == len(spans) == len(regions)
            and spans[bar] == 1 and regions[bar].get('cadenceQuarterBeats') == 1
            and event['startBeat'] == sum(spans[:bar])
            and bar not in page.get('printedMeasureContinuations', ()))


def _terminal_shared_artificial_harmonics(page, source_notes):
    """Two stopped harmonic attacks can share the closed final quarter's upstem."""
    spans = page['measureBeats']
    regions = page['score'].get('measures', ())
    bar = len(spans) - 1
    if (bar < 1 or spans[bar] != 1 or len(regions) != len(spans)
            or regions[bar].get('cadenceQuarterBeats') != 1
            or bar in page.get('printedMeasureContinuations', ())
            or any(rest['measureIndex'] == bar for rest in page['score'].get('rests', ()))):
        return {}
    events = [(i, event) for i, event in enumerate(page['events']) if event.get('measureIndex') == bar]
    if len(events) != 2 or not any(event.get('artificialHarmonicThird') is True for _, event in events):
        return {}
    onset = sum(spans[:bar])
    width, height = page['width'], page['height']
    members = []
    for i, event in events:
        third = event.get('artificialHarmonicThird') is True
        if (event.get('sourceNoteIndex') != i or i >= len(source_notes)
                or (event.get('artificialHarmonicFourth') is True) == third
                or event.get('compoundHarmonicFourth') or event.get('mixedHarmonicQuarter')
                or event.get('openStringMidi') is not None or event.get('staffIndex') != 0
                or event.get('staffCount') != 1 or event.get('startBeat') != onset
                or event.get('durationBeats') != 1 or event.get('durationFallback')
                or event.get('tiedFromPrevious') or event.get('boundaryTies')
                or not all(type(event.get(k)) in (int, float) and math.isfinite(event[k])
                           for k in ('x', 'y'))):
            return {}
        note = source_notes[i]
        if (not _ordinary_compound_quarter(note, 1) or note.get('openStringTouch') is not None
                or type(note.get('staffStep')) is not int
                or type(note.get('pageY')) not in (int, float)
                or not abs(event['y'] - note['pageY'] * height) <= .01):
            return {}
        _closed_artificial_spelling(event, note, third)
        members.append((event, note, third))
    (upper, hi, hi_third), (lower, lo, lo_third) = sorted(members, key=lambda row: row[0]['y'])
    if (hi_third or not lo_third or hi['staffStep'] - lo['staffStep'] != 4
            or hi.get('writtenAccidental', 2) != 2 or lo.get('writtenAccidental') != -1
            or abs(upper['x'] - lower['x']) > width * .00065):
        return {}
    return {i: 'up' for i, _ in events}


def _shared_artificial_fourths(page, source_notes, opposed_offsets=None):
    """The stopped members share a proved written quarter shaft."""
    events = page['events']
    upstems = {i for i, event in enumerate(events) if event.get('sharedUpstemHarmonicQuarter') is True}
    downstems = {i for i, event in enumerate(events) if event.get('sharedDownstemHarmonicQuarter') is True}
    opposed = {i for i, event in enumerate(events) if event.get('oppositeShaftHarmonicQuarter') is True}
    if not opposed <= upstems:
        raise ValueError('Opposite harmonic heads require a proved upstem')
    if upstems & downstems:
        raise ValueError('Conflicting shared harmonic shaft directions')
    if not upstems and not downstems and not any(event.get('artificialHarmonicFourth') is True for event in events):
        return {}, {}
    spans = page['measureBeats']
    starts = list(accumulate(spans, initial=0))
    attacks = defaultdict(list)
    source_bars = page.get('score', {}).get('measures', ())
    terminal_quarter = (len(source_bars) == len(spans) and bool(spans) and spans[-1] == 1
                        and source_bars[-1].get('cadenceQuarterBeats') == 1)
    for i, event in enumerate(events):
        bar, staff, onset = (event.get(k) for k in ('measureIndex', 'staffIndex', 'startBeat'))
        if (type(bar) is int and 0 <= bar < len(spans)
                and (spans[bar] == 2 or (terminal_quarter and bar == len(spans) - 1))
                and type(staff) is int and staff == 0
                and type(onset) in (int, float) and math.isfinite(onset)):
            attacks[bar, onset].append((i, event))
    width, height = page.get('width'), page.get('height')
    if (type(width) not in (int, float) or not math.isfinite(width) or width <= 0
            or type(height) not in (int, float) or not math.isfinite(height) or height <= 0):
        return {}, {}
    stems = {}
    fingers = {}
    head_offsets = {}
    for (bar, onset), owners in attacks.items():
        if (len(owners) != 2 or onset not in (starts[bar], starts[bar] + 1)
                or onset + 1 > starts[bar + 1]
                or bar in page.get('printedMeasureContinuations', ())
                or bar + 1 in page.get('printedMeasureContinuations', ())):
            continue
        members = []
        stem = 1 if all(i in upstems for i, _ in owners) else -1
        owned_downstem = all(i in downstems for i, _ in owners)
        for i, event in owners:
            third = event.get('artificialHarmonicThird') is True
            if (i >= len(source_notes) or event.get('sourceNoteIndex') != i
                    or (event.get('artificialHarmonicFourth') is True) == third
                    or (third and stem == -1 and not owned_downstem) or event.get('compoundHarmonicFourth')
                    or event.get('mixedHarmonicQuarter') or event.get('openStringMidi') is not None
                    or event.get('staffCount') != 1 or event.get('durationBeats') != 1
                    or event.get('durationFallback') or event.get('tiedFromPrevious')
                    or not all(type(event.get(k)) in (int, float) and math.isfinite(event[k])
                               for k in ('x', 'y'))):
                break
            note = source_notes[i]
            if (not _ordinary_compound_quarter(note, stem)
                    or note.get('harmonicSemitones') != (28 if third else 24)
                    or note.get('openStringTouch') is not None
                    or any(event.get(k) != note.get(k) for k in ('measureIndex', 'staffIndex', 'staffCount'))
                    or type(note.get('staffStep')) is not int
                    or type(note.get('pageY')) not in (int, float)
                    or not abs(event['y'] - note['pageY'] * height) <= .01
                    or type(event.get('writtenMidi')) is not int):
                break
            members.append((i, event, note))
        if len(members) != 2:
            continue
        (upper_index, upper, hi), (lower_index, lower, lo) = sorted(members, key=lambda x: x[1]['y'])
        shifted = upper_index in opposed and lower_index in opposed
        if (upper_index in opposed) != (lower_index in opposed):
            continue
        if shifted:
            if (bar >= len(source_bars) or onset != starts[bar] or hi['staffStep'] - lo['staffStep'] != 4
                    or lower.get('artificialHarmonicThird') is True):
                continue
            region = source_bars[bar]
            gap = (region['bottom'] - region['top']) * height / 8.2
            dx = upper['x'] - lower['x']
            if not (10 <= gap <= 25 and 1.06 * gap <= dx <= 1.52 * gap):
                continue
        else:
            tolerance = width * .00065
            if owned_downstem and bar < len(source_bars):
                region = source_bars[bar]
                gap = (region['bottom'] - region['top']) * height / 8.2
                if 10 <= gap <= 25:
                    # A proved shared downstem can retain the upper head's ledger-biased anchor.
                    tolerance = max(tolerance, .28 * gap)
            if abs(upper['x'] - lower['x']) > tolerance:
                continue
        if (hi['staffStep'] - lo['staffStep'] not in (4, 5)
                or upper['writtenMidi'] - lower['writtenMidi'] not in (7, 8, 9)
                or upper.get('artificialHarmonicThird') is True):
            continue
        stems.update((i, 'up' if stem == 1 else 'down') for i, _, _ in members)
        if shifted:
            head_offsets[lower_index] = 0
            head_offsets[upper_index] = round(10 * dx / gap, 5)
        if stem == 1 or owned_downstem:
            for index, event, placement in ((upper_index, upper, 'above'), (lower_index, lower, 'below')):
                if 'compoundHarmonicFingering' in event:
                    fingers[index] = _compound_harmonic_fingering(event, False, placement)
    if any(stems.get(i) != 'up' for i in upstems) or any(stems.get(i) != 'down' for i in downstems):
        raise ValueError('Shared harmonics require both owners of the same complete quarter')
    if opposed != head_offsets.keys():
        raise ValueError('Opposite harmonic heads require both members of the written chord')
    if opposed_offsets is not None:
        opposed_offsets.update(head_offsets)
    return stems, fingers


def _mixed_stopped_pair(page, notes, events, first, second, reversed_order=False):
    if any(i >= len(notes) or events[i].get('sourceNoteIndex') != i
           or any(type(events[i].get(k)) not in (int, float) or not math.isfinite(events[i][k])
                  for k in ('x', 'y')) for i in first + second):
        raise ValueError('Mixed harmonics require complete retained note owners')
    first = sorted(first, key=lambda i: events[i]['y'], reverse=True)
    second = sorted(second, key=lambda i: events[i]['y'], reverse=True)
    lower, upper = (events[i] for i in second)
    if (lower.get('artificialHarmonicThird') is not True
            or upper.get('artificialHarmonicFourth') is not True
            or any(e.get('compoundHarmonicFourth') or e.get('openStringMidi') is not None
                   for e in (lower, upper))
            or abs(lower['x'] - upper['x']) > page['width'] * .00065):
        raise ValueError('Mixed harmonics require one stopped third and one stopped fourth on the same stem')
    lo, hi = (notes[i] for i in second)
    if hi['staffStep'] - lo['staffStep'] != 4:
        raise ValueError('Invalid written interval for the stopped harmonic pair')
    open_lo, open_hi = (notes[i] for i in first)
    lower_matches = (events[first[0]].get('harmonicTouchMidi') == lower.get('harmonicTouchMidi')
                     and open_lo['staffStep'] == lo['staffStep'] + 2)
    repeated_touches = (lower_matches and lo.get('writtenAccidental') == 0
                        and hi.get('writtenAccidental', 2) == 2
                        and events[first[1]].get('harmonicTouchMidi') == upper.get('harmonicTouchMidi')
                        and open_hi['staffStep'] == hi['staffStep'] + 3)
    open_fourth = (lower_matches
                   and all(n.get('writtenAccidental', 2) == 2 for n in (open_lo, open_hi, lo, hi))
                   and open_hi['staffStep'] == hi['staffStep'] + 1
                   and open_hi['staffStep'] - open_lo['staffStep'] == 3
                   and type(lower.get('harmonicTouchMidi')) is int
                   and events[first[1]].get('harmonicTouchMidi') == events[first[0]].get('harmonicTouchMidi') + 5)
    independent_second = (reversed_order and all(n.get('writtenAccidental', 2) == 2
                          for n in (open_lo, open_hi, lo, hi))
                          and events[first[0]]['x'] > max(lower['x'], upper['x']))
    if not (repeated_touches or open_fourth or independent_second):
        raise ValueError('Incompatible open and stopped harmonic pitches')
    for i in second:
        event, note = events[i], notes[i]
        if (not _ordinary_compound_quarter(note, -1) or note.get('openStringTouch') is not None
                or event.get('durationBeats') != 1 or event.get('durationFallback')
                or event.get('tiedFromPrevious') or event.get('boundaryTies')
                or not abs(event['y'] - note['pageY'] * page['height']) <= .01):
            raise ValueError('The stopped harmonic pair requires complete downstem quarters')
        base, touch, _ = _closed_artificial_spelling(event, note, i == second[0])
        if repeated_touches and (base[1] if i == second[0] else touch[1] - 1) != 0:
            raise ValueError('Mixed harmonics disagree with the printed natural and sharp')
    hands = {}
    for indices, natural in ((first, True), (second, False)):
        for i, expected in zip(indices, ('below', 'above'), strict=True):
            item = events[i].get('mixedHarmonicFingering')
            if not isinstance(item, dict):
                raise ValueError('Missing printed harmonic fingering')
            base, touch, side = (item.get(k) for k in ('base', 'touch', 'placement'))
            if (side != expected or type(base) is not int or type(touch) is not int
                    or (base != 0 if natural else base < 1) or not base < touch <= 4):
                raise ValueError('Invalid printed harmonic fingering')
            hands[i] = base, touch, side
    return hands


def _opening_open_harmonics(page, notes, compound, fourth_partners):
    events = page['events']
    groups = defaultdict(list)
    for i, event in enumerate(events):
        if event.get('openingOpenHarmonicPair') is True:
            groups[event['measureIndex']].append(i)
    accepted, fingers, stops = {}, {}, {}
    starts = list(accumulate(page['measureBeats'], initial=0))
    meter_changes = {(row['measureIndex'], row['numerator'], row['denominator'])
                     for row in page['score'].get('meterChanges', ())}
    for bar, owners in groups.items():
        if (type(bar) is not int or not 0 <= bar < len(starts) - 1
                or (bar, 2, 4) not in meter_changes or starts[bar + 1] - starts[bar] != 2
                or bar in page.get('printedMeasureContinuations', ())
                or bar + 1 in page.get('printedMeasureContinuations', ())
                or any(rest['measureIndex'] == bar for rest in page['score'].get('rests', ()))):
            raise ValueError('Opening harmonics require a literal closed 2/4 bar')
        local = [i for i, row in enumerate(events) if row.get('measureIndex') == bar]
        closing = sorted(set(local) - set(owners))
        if len(owners) != 2 or len(local) != 4 or len(closing) != 2:
            raise ValueError('Opening harmonics require two complete quarter-note attacks')
        partners = [i for i in closing if i in compound]
        carriers = [i for i in closing if fourth_partners.get(i) == 'up'
                    and events[i].get('compoundHarmonicFourth') is True]
        if (len(partners) != 1 or len(carriers) != 1 or set(partners) | set(carriers) != set(closing)
                or any(events[i].get('startBeat') != starts[bar] + 1 for i in closing)):
            raise ValueError('Opening harmonics require a stopped and open pair on the second beat')
        if any(i >= len(notes) or events[i].get('sourceNoteIndex') != i
                or type(events[i].get('y')) not in (int, float) for i in owners):
            raise ValueError('Opening harmonics require retained pitch owners')
        lower, upper = sorted(owners, key=lambda i: events[i]['y'], reverse=True)
        if (events[lower].get('x') != events[upper].get('x')
                or events[lower].get('openStringMidi') == events[upper].get('openStringMidi')
                or notes[upper]['staffStep'] != notes[partners[0]]['staffStep']
                or events[upper].get('midi') != events[partners[0]].get('midi')
                or events[lower].get('writtenMidi') != events[carriers[0]].get('harmonicTouchMidi')):
            raise ValueError('Opening touch heads do not agree with their printed partners')
        for i, placement in zip((lower, upper), ('below', 'above'), strict=True):
            event, note = events[i], notes[i]
            written, string, sound = (event.get(k) for k in ('writtenMidi', 'openStringMidi', 'midi'))
            if (not _ordinary_compound_quarter(note, 1)
                    or event.get('startBeat') != starts[bar] or event.get('durationBeats') != 1
                    or event.get('staffIndex') != 0 or event.get('staffCount') != 1
                    or any(event.get(k) for k in ('clefInferred', 'tiedFromPrevious', 'durationFallback', 'octaveShift'))
                    or type(written) is not int or string not in (55, 62, 69, 76)
                    or event.get('harmonicTouchMidi') != written
                    or sound != string + {4: 28, 5: 24, 7: 19, 9: 28}.get(written - string, -128)):
                raise ValueError('Invalid opening harmonic quarter')
            diatonic = note['clefBottomDiatonic'] + note['staffStep']
            sound_step = diatonic + {4: 14, 5: 11, 7: 7, 9: 11}[written - string]
            accepted[i] = _harmonic_spelling(diatonic, written), _harmonic_spelling(sound_step, sound)
            if 'openingPairFingering' in event:
                fingers[i] = _compound_harmonic_fingering(
                    dict(compoundHarmonicFingering=event['openingPairFingering']), True, placement)
        stop = carriers[0]
        stops[stop] = _compound_harmonic_fingering(
            dict(compoundHarmonicFingering=events[stop].get('openingHarmonicStoppedFingering')), False, 'below')
    return accepted, fingers, stops


def _independent_open_harmonic_quarters(page, notes, compound):
    """Validate both complete attacks of a closed mixed harmonic bar before engraving either."""
    events = page['events']
    groups = defaultdict(list)
    for index, event in enumerate(events):
        if event.get('mixedHarmonicQuarter') is True:
            groups[event['measureIndex']].append(index)
    result = {}
    below = set()
    stopped = {i for i, event in enumerate(events) if event.get('sharedStoppedHarmonicPair') is True}
    validated = set()
    reversed_stopped = set()
    hands = {}
    for bar, owners in groups.items():
        spans = page['measureBeats']
        if (type(bar) is not int or not 0 <= bar < len(spans) or spans[bar] != 2
                or bar in page.get('printedMeasureContinuations', ())
                or bar + 1 in page.get('printedMeasureContinuations', ())
                or len(owners) != 4
                or any(rest['measureIndex'] == bar for rest in page['score'].get('rests', ()))
                or any(event['measureIndex'] == bar and index not in owners
                       for index, event in enumerate(events))):
            raise ValueError('Mixed harmonics require a complete closed two-quarter bar')
        onset = ticks(sum(spans[:bar]))
        first = [i for i in owners if ticks(events[i]['startBeat']) == onset]
        second = [i for i in owners if ticks(events[i]['startBeat']) == onset + DIVISIONS]
        if len(first) != 2 or len(second) != 2:
            raise ValueError('Mixed harmonics require two proved attacks on consecutive quarter beats')
        reversed_order = set(first) <= stopped
        if reversed_order:
            first, second = second, first
        if any(i in compound or events[i].get('artificialHarmonicThird') is True for i in first):
            raise ValueError('A pair of open-string owners is required')
        if any(i in stopped for i in owners):
            if not set(second) <= stopped or any(i in stopped for i in first):
                raise ValueError('A stopped harmonic pair requires two complete owners')
            hands.update(_mixed_stopped_pair(page, notes, events, first, second, reversed_order))
            validated.update(second)
            if reversed_order:
                reversed_stopped.update(second)
        elif (sum(events[i].get('artificialHarmonicThird') is True
                  or events[i].get('compoundHarmonicFourth') is True for i in second) != 1
              or sum(i in compound for i in second) != 1):
            raise ValueError('Mixed harmonics require a stopped and open quarter at the second onset')
        if (events[first[0]].get('openStringMidi') == events[first[1]].get('openStringMidi')
                or events[first[0]].get('x') != events[first[1]].get('x')
                or {events[i]['staffIndex'] for i in owners} != {0}
                or {events[i]['staffCount'] for i in owners} != {1}):
            raise ValueError('Mixed harmonic attacks require separate strings on a single staff')
        below.add(max(first, key=lambda i: events[i]['y']))
        for i in first:
            if i >= len(notes):
                raise ValueError('Open harmonic has no written source')
            event, note = events[i], notes[i]
            written, sounding, string = (event.get(k) for k in ('writtenMidi', 'midi', 'openStringMidi'))
            if (not _ordinary_compound_quarter(note, 1 if reversed_order else -1) or event.get('sourceNoteIndex') != i
                    or any(event.get(k) != note.get(k) for k in ('measureIndex', 'staffIndex', 'staffCount'))
                    or event.get('clefInferred') or event.get('durationFallback') or event.get('tiedFromPrevious')
                    or event.get('boundaryTies', 0) or event.get('octaveShift', 0)
                    or event.get('durationBeats') != 1
                    or any(type(midi) is not int or not 0 <= midi <= 127 for midi in (written, sounding))
                    or written != event.get('harmonicTouchMidi') or string not in (55, 62, 69, 76)
                    or sounding - string != {4: 28, 5: 24, 7: 19, 9: 28}.get(written - string)):
                raise ValueError('Invalid independent written open-string quarter')
            diatonic = note['clefBottomDiatonic'] + note['staffStep']
            sound_step = diatonic + {4: 14, 5: 11, 7: 7, 9: 11}[written - string]
            result[i] = _harmonic_spelling(diatonic, written), _harmonic_spelling(sound_step, sounding)
    if validated != stopped:
        raise ValueError('Stopped harmonics outside a closed mixed bar')
    return result, below, hands, reversed_stopped


def _beamed_open_string_spelling(event, owner, source, key):
    written, sounding, string = (event.get(k) for k in ('writtenMidi', 'midi', 'openStringMidi'))
    placement = event.get('writtenPlacement') or {}
    duration = event.get('durationBeats')
    beam_depth = {.25: 2, .5: 1}.get(duration)
    if (source is None or event.get('sourceNoteIndex') != owner
            or type(written) is not int or type(sounding) is not int or type(string) is not int
            or written != event.get('harmonicTouchMidi') or string not in (55, 62, 69, 76)
            or source.get('clefBottomDiatonic') != 30 or type(source.get('staffStep')) is not int
            or any(source.get(k) != event.get(k) for k in ('measureIndex', 'staffIndex', 'staffCount'))
            or source.get('staffIndex') != 0 or source.get('staffCount') != 1
            or source.get('stemDirection') not in (-1, 1) or beam_depth is None
            or source.get('beamCount') != beam_depth
            or source.get('unbeamedDurationBeats') != 0 or source.get('augmentationDots') != 0
            or source.get('tupletDivisor') != 1 or source.get('writtenAccidental') != 2
            or source.get('octaveShift', 0) or source.get('harmonicSemitones', 0)
            or source.get('openStringTouch') is not None or source.get('crossStaffBeam')
            or source.get('tiedFromPrevious') or source.get('boundaryTies', 0)
            or event.get('tiedFromPrevious') or event.get('durationFallback')
            or event.get('tupletActualNotes') or event.get('octaveShift', 0)
            or placement.get('durationBeats') != duration
            or placement.get('principalIndex') != -1 or type(key) is not int or not -7 <= key <= 7):
        raise ValueError('Short open-string harmonic requires an owned printed beam')
    interval = written - string
    if sounding - string != {4: 28, 5: 24, 7: 19, 9: 28}.get(interval):
        raise ValueError('Open-string harmonic tuning disagrees with the touching pitch')
    step = source['clefBottomDiatonic'] + source['staffStep']
    letter, alteration, octave = _harmonic_spelling(step, written)
    accidental = 1 if key > 0 and letter in 'FCGDAEB'[:key] else (
        -1 if key < 0 and letter in 'BEADGCF'[:-key] else 0)
    if alteration != accidental:
        raise ValueError('Open-string harmonic touch disagrees with its written key')
    sound_step = step + {4: 14, 5: 11, 7: 7, 9: 11}[interval]
    return (letter, alteration, octave), _harmonic_spelling(sound_step, sounding)


def emit_note(measure, duration, voice, event=None, chord=False, stop=False, start=False, flats=False,
              gliss_start=False, gliss_stop=False, slide_start=False, slide_stop=False, fragment_end=None, rest_symbols=(), full_measure=False,
              articulation_flags=0, attack_fragment=True, grace=None, bow_direction=None):
    artificial = event.get('retainedArtificialHarmonic') if event else None
    open_touch = event.get('retainedCompoundOpenHarmonic') if event else None
    written_harmonic = artificial or open_touch
    third = event.get('artificialHarmonicThird') is True if event else False
    fourth_stem = event.get('retainedCompoundFourthStem') if event else None
    fourth_touch_stem = event.get('retainedCompoundFourthTouchStem') if event else None
    beamed_fourth = event and event.get('beamedArtificialHarmonicFourth') is True
    beamed_triplet = beamed_fourth and _beamed_fourth_triplet(event)
    dotted_fourth = beamed_fourth and _dotted_beamed_fourth(event)
    third_hook_fourth = beamed_fourth and _third_hook_fourth(event)
    beamed_open = bool(open_touch and event.get('beamedOpenStringHarmonic') is True)
    closing_half = bool(artificial and event.get('closingHarmonicHalf') is True)
    whole_fourth = bool(artificial and event.get('stemlessWholeArtificialHarmonicFourth') is True)
    if written_harmonic:
        harmonic_duration = (DIVISIONS // 3 if beamed_triplet else 3 * DIVISIONS // 8 if dotted_fourth
                             else DIVISIONS // 8 if third_hook_fourth
                             else 4 * DIVISIONS if whole_fourth
                             else 2 * DIVISIONS if closing_half
                             else ticks(event['durationBeats']) if beamed_open
                             else DIVISIONS // 4 if beamed_fourth else DIVISIONS)
        if (duration != harmonic_duration or duration != event['end'] - event['start']
                or chord and not (third or fourth_stem or open_touch) or stop or start or grace is not None
                or not attack_fragment or event.get('durationFallback') or event.get('retainedTremolo')):
            raise ValueError('Printed harmonic requires one complete written attack')
        carrier = element(measure, 'note', **{'print-object': 'no'})
        if chord:
            element(carrier, 'chord')
        _pitch_element(carrier, artificial[2] if artificial else open_touch[1])
        element(carrier, 'duration', duration)
        element(carrier, 'voice', voice)
        if beamed_triplet or dotted_fourth or third_hook_fourth or beamed_open or closing_half or whole_fourth:
            note_type(carrier, duration, event)
        else:
            element(carrier, 'type', '16th' if beamed_fourth else 'quarter')
        _harmonic_role(carrier, 'artificial' if artificial else 'natural', 'sounding-pitch')
    node = element(measure, 'note')
    if written_harmonic:
        # The hidden carriers sound; full-size cue notes retain the printed heads.
        element(node, 'cue')
        element(node, 'chord')
    if grace is not None:
        element(node, 'grace', **{('steal-time-previous' if grace['afterGrace'] else 'steal-time-following'): grace['stealPercent']})
    if chord and not written_harmonic:
        element(node, 'chord')
    if event is None:
        element(node, 'rest', **({'measure': 'yes'} if full_measure else {}))
    elif event_kind(event) == 'UNPITCHED':
        identity = element(node, 'unpitched')
        element(identity, 'display-step', event['displayStep'])
        element(identity, 'display-octave', event['displayOctave'])
    else:
        midi = event['midi']
        names = (('C', 0), ('D', -1), ('D', 0), ('E', -1), ('E', 0), ('F', 0),
                 ('G', -1), ('G', 0), ('A', -1), ('A', 0), ('B', -1), ('B', 0)) if flats else (
                 ('C', 0), ('C', 1), ('D', 0), ('D', 1), ('E', 0), ('F', 0),
                 ('F', 1), ('G', 0), ('G', 1), ('A', 0), ('A', 1), ('B', 0))
        step, alter = names[midi % 12]
        step, alter, octave = event.get('retainedSpelling') or (step, alter, midi // 12 - 1)
        _pitch_element(node, written_harmonic[0] if written_harmonic else (step, alter, octave))
    if grace is None:
        element(node, 'duration', duration)
    for active, kind in ((stop, 'stop'), (start, 'start')):
        if active:
            element(node, 'tie', type=kind)
    if event and event.get('durationFallback'):
        element(node, 'footnote', 'Estimated duration from interpretation')
    element(node, 'voice', voice)
    if full_measure:
        element(node, 'type', 'whole')
    else:
        paired = event.get('retainedTremolo') if event else None
        if paired and (grace is not None or stop or start or duration != event['end'] - event['start']):
            raise ValueError('Paired tremolo requires an unsplit original endpoint')
        note_type(node, duration * 2 if paired else duration, event)
        if paired and paired.get('canonicalClockPolicy'):
            modification = node.find('time-modification')
            if modification is None:
                modification = element(node, 'time-modification')
                element(modification, 'actual-notes', 2)
                element(modification, 'normal-notes', 1)
            else:
                actual_node = modification.find('actual-notes')
                actual_node.text = str(int(actual_node.text) * 2)

    if event and event.get('retainedVisualSize') == 'CUE' and node.find('type') is not None:
        node.find('type').set('size', 'grace-cue' if grace is not None else 'cue')
    if grace is not None and node.find('type') is None:
        raise ValueError('Grace note has no exact written type')
    if event and attack_fragment and event.get('retainedAccidental') is not None:
        mark = ET.Element('accidental')
        mark.text = event['retainedAccidental']
        modification = node.find('time-modification')
        node.insert(list(node).index(modification) if modification is not None else len(node), mark)
    mixed = event and event.get('mixedHarmonicQuarter') is True
    closing_stem = 'up' if event and (event.get('closingHarmonicQuarter') is True or closing_half) else None
    printed_stem = event.get('retainedMixedHarmonicStem', 'down') if mixed else (event.get('retainedBeamedHarmonicStem') if beamed_fourth
                                         else fourth_stem or fourth_touch_stem or closing_stem)
    if printed_stem or beamed_open:
        element(node, 'stem', printed_stem or event['retainedBeamedOpenStem'])
    if event and (event.get('retainedTremolo') or {}).get('printedFilled'):
        if event['retainedTremolo'].get('printedStem') in (-1, 1):
            element(node, 'stem', 'down' if event['retainedTremolo']['printedStem'] < 0 else 'up')
        element(node, 'notehead', 'normal', filled='yes')
    if event is not None and event_kind(event) == 'UNPITCHED':
        element(node, 'notehead', 'x')
    effect = event.get('guitarEffect') if event and attack_fragment else None
    ornament_flags = event.get('retainedOrnamentFlags', 0) if event and attack_fragment else 0
    trill_wave_start = event.get('trillWaveStart') if event and attack_fragment else None
    trill_wave_stop = event.get('trillWaveStop') if event and fragment_end == event['end'] else None
    symbols = [mark for mark in (event.get('releaseSymbols', []) if event else rest_symbols)
               if mark['release'] == fragment_end]
    paired = event.get('retainedTremolo') if event else None
    arpeggio = bool(event and event.get('retainedArpeggio') and attack_fragment and grace is None)
    natural_harmonic = bool(event and event.get('retainedNaturalHarmonic') and attack_fragment and grace is None)
    fall_off = bool(event and event.get('retainedFallOff') and fragment_end == event['end'] and grace is None)
    parenthesis_left = event.get('retainedParenthesisStart') if event and attack_fragment and grace is None else None
    parenthesis_right = (event.get('retainedParenthesisStop')
                         if event and event.get('retainedParenthesisStop') and grace is None
                         and fragment_end == event['end'] else None)
    fingering = event.get('retainedFingering') if event and attack_fragment and grace is None else None
    if stop or start or effect or symbols or gliss_start or gliss_stop or slide_start or slide_stop or articulation_flags or ornament_flags or trill_wave_start or trill_wave_stop or paired or arpeggio or natural_harmonic or fall_off or parenthesis_left or parenthesis_right or bow_direction is not None or fingering is not None:
        notation = element(node, 'notations')
        if arpeggio:
            element(notation, 'arpeggiate')
        for endpoint, left in ((parenthesis_left, True), (parenthesis_right, False)):
            if endpoint is not None:
                f32 = parenthesized_note_group.f32
                decimal = lambda value: format(Decimal(str(f32(value))), 'f')
                # The reference staff is 7 mm high. MusicXML font sizes use points.
                size_points = endpoint['height'] / 1.6 * (7 * 72 / 25.4)
                element(notation, 'other-notation', 'music-sheets-parenthesis-group:' + endpoint['id'],
                        type='start' if left else 'stop', number=endpoint['number'],
                        id=endpoint['id'] + ('-left' if left else '-right'),
                        smufl='noteheadParenthesisLeft' if left else 'noteheadParenthesisRight',
                        **{'relative-x': decimal(endpoint['x'] * 10),
                           'relative-y': decimal(-endpoint['y'] * 10),
                           'font-size': decimal(size_points)})
        if natural_harmonic:
            element(element(notation, 'technical'), 'harmonic')
            element(notation.find('technical/harmonic'), 'natural')
        if fall_off:
            articulations = notation.find('articulations')
            if articulations is None:
                articulations = element(notation, 'articulations')
            element(articulations, 'falloff', **{'line-shape': 'curved', 'line-type': 'solid'})
        if paired and not chord:
            element(element(notation, 'ornaments'), 'tremolo', paired['strokes'], type=paired['type'])
        for active, kind in ((stop, 'stop'), (start, 'start')):
            if active:
                element(notation, 'tied', type=kind)
        for active, kind in ((gliss_stop, 'stop'), (gliss_start, 'start')):
            if active:
                element(notation, 'glissando', type=kind,
                        number=event['gliss_'+kind], **{'line-type':'wavy'})
        for active, kind in ((slide_stop, 'stop'), (slide_start, 'start')):
            if active:
                element(notation, 'slide', type=kind, number=event['slide_'+kind], **{'line-type':'solid'})
        if effect:
            guitar_export.write(notation, effect)
        if articulation_flags:
            articulation_export.write(notation, articulation_flags)
        if bow_direction is not None:
            bow_export.write(notation, bow_direction)
        if fingering is not None:
            fingering_export.write(notation, fingering)
        if ornament_flags or trill_wave_start or trill_wave_stop:
            ornament_export.write(notation, ornament_flags, trill_wave_start, trill_wave_stop)
        for symbol in symbols:
            kind = symbol['kind']
            if kind == 'FERMATA':
                element(notation, 'fermata', type='inverted' if symbol.get('inverted') else 'upright')
            else:
                articulations = notation.find('articulations')
                if articulations is None:
                    articulations = element(notation, 'articulations')
                element(articulations, 'caesura' if kind == 'CAESURA' else 'breath-mark',
                        None if kind == 'CAESURA' else symbol.get('value', 'comma'))
    fingers = (event.get('retainedMixedHarmonicFingering')
               or event.get('retainedCompoundHarmonicFingering')) if event else None
    if open_touch:
        node.find('type').set('size', 'full')
        head = ET.Element('notehead', {'filled': 'no'})
        head.text = 'diamond'
        notations = node.find('notations')
        node.insert(list(node).index(notations) if notations is not None else len(node), head)
        technical = _harmonic_role(node, 'natural', 'touching-pitch')
        if fingers:
            base_finger, touch_finger, side = fingers
            element(technical, 'fingering', touch_finger, placement=side)
            element(technical, 'fingering', base_finger, placement=side)
        elif beamed_open:
            element(technical, 'fingering', '0', placement='below')
        elif (mixed or fourth_touch_stem and technical.find('fingering') is None
              and not event.get('retainedNaturalHarmonicWithoutZero')):
            element(technical, 'fingering', '0',
                    placement='below' if (event.get('mixedHarmonicLowerZero')
                                          or event.get('retainedCompoundFourthLowerZero')) else 'above')
    if artificial:
        node.find('type').set('size', 'full')
        base_technical = _harmonic_role(node, 'artificial', 'base-pitch')
        if fingers:
            element(base_technical, 'fingering', fingers[0], placement=fingers[2])
        touched = element(measure, 'note')
        if 'retainedHarmonicHeadOffset' in event:
            offset = str(event['retainedHarmonicHeadOffset'])
            node.set('relative-x', offset)
            touched.set('relative-x', offset)
        element(touched, 'cue')
        element(touched, 'chord')
        _pitch_element(touched, artificial[1])
        element(touched, 'duration', duration)
        element(touched, 'voice', voice)
        if beamed_triplet or dotted_fourth or third_hook_fourth or closing_half or whole_fourth:
            note_type(touched, duration, event)
            touched.find('type').set('size', 'full')
        else:
            element(touched, 'type', '16th' if beamed_fourth else 'quarter', size='full')
        if beamed_fourth and event.get('retainedHarmonicTouchAccidental') is not None:
            mark = element(touched, 'accidental', event['retainedHarmonicTouchAccidental'])
            if beamed_triplet:
                touched.remove(mark)
                touched.insert(list(touched).index(touched.find('time-modification')), mark)
        elif mixed and fingers and not third and not event.get('retainedReversedStoppedPair'):
            element(touched, 'accidental', 'sharp')
        if mixed or fourth_stem or beamed_fourth or closing_stem:
            element(touched, 'stem', printed_stem)
        element(touched, 'notehead', 'diamond',
                **({'filled': 'no'} if mixed or fourth_stem or beamed_fourth or closing_stem or whole_fourth
                   else {'filled': 'yes'} if third else {}))
        technical = _harmonic_role(touched, 'artificial', 'touching-pitch')
        if fingers:
            element(technical, 'fingering', fingers[1], placement=fingers[2])
    return node


def emit_rest_gap(measure, start, end, voice, symbols, literal_edges=()):
    boundaries = sorted({start, end}
                        | {value for s in symbols for value in (s['start'], s['release']) if start < value < end}
                        | {value for value in literal_edges if start < value < end})
    for a, b in zip(boundaries, boundaries[1:]):
        cursor = a
        for duration in duration_parts(b-a):
            cursor += duration
            emit_note(measure, duration, voice, fragment_end=cursor, rest_symbols=symbols)


def _write_inferred_tuplets(elements, eligible):
    """Give complete, contiguous ratio groups explicit visual endpoints."""
    group = []
    ratio = None
    elapsed = 0
    printed = False
    for note in elements:
        if note.tag == 'note' and (note.find('chord') is not None or note.find('grace') is not None):
            continue
        mark = note.find('notations/tuplet')
        modification = note.find('time-modification')
        candidate = None
        if mark is not None:
            printed = mark.get('type') == 'start'
        elif (note.tag == 'note' and not printed and modification is not None
              and (note in eligible or note.find('rest') is not None)):
            actual = int(modification.findtext('actual-notes'))
            normal = int(modification.findtext('normal-notes'))
            if actual != normal:
                candidate = actual, normal
        if candidate != ratio or candidate is None:
            group.clear()
            elapsed = 0
            ratio = candidate
        if candidate is None:
            continue
        group.append(note)
        elapsed += int(note.findtext('duration'))
        denominator, remainder = divmod(DIVISIONS * 4 * candidate[1], elapsed)
        # Require an exact binary base unit. Mixed written values may add up to
        # that unit, including tied fragments and rests.
        if (len(group) >= 2 and not remainder and denominator in (1, 2, 4, 8, 16, 32, 64)
                and any(member in eligible for member in group)):
            normal_type = ('whole', 'half', 'quarter', 'eighth', '16th', '32nd',
                           '64th')[denominator.bit_length() - 1]
            for member in group:
                if member.findtext('type') != normal_type or member.find('dot') is not None:
                    element(member.find('time-modification'), 'normal-type', normal_type)
            for endpoint, kind in ((group[0], 'start'), (group[-1], 'stop')):
                notations = endpoint.find('notations')
                if notations is None:
                    notations = element(endpoint, 'notations')
                attrs = {'bracket': 'yes', 'show-number': 'actual'} if kind == 'start' else {}
                element(notations, 'tuplet', type=kind, number=1, **attrs)
            group.clear()
            elapsed = 0


def _join_printed_measures(bars, continuations, full_rests, rest_groups):
    """Project verified system fragments after resolving their physical source anchors."""
    full = {rest['barIndex'] for rest in full_rests}
    for group in rest_groups:
        full.update(range(group['firstBar'], group['firstBar'] + group['measureCount']))
    projected, physical_to_output = [], []
    for i, bar in enumerate(bars):
        if i not in continuations:
            projected.append(bar)
        else:
            first = projected[-1]
            if (i - 1 in full or i in full or bar['resetTempo']
                    or first['meter'] != bar['meter'] or first['key'] != bar['key']):
                raise ValueError('MusicXML system continuation changes the written measure context')
            length = first['length'] + bar['length']
            if length > ticks(first['meter'][0] * 4 / first['meter'][1]):
                raise ValueError('MusicXML system continuation exceeds the written meter')

            def tempo_at(tempo, physical):
                if '_writtenBarOffset' in tempo:
                    return tempo
                source_onset = tempo['_sourceTempoOnset']
                onset = (ticks(source_onset) if source_onset is not None
                         else round(physical['length'] * tempo['positionInMeasure']))
                return dict(tempo, _writtenBarOffset=physical['start'] - first['start'] + onset)

            projected[-1] = dict(first, length=length, rightBarline=bar.get('rightBarline'), tempos=[
                *(tempo_at(tempo, first) for tempo in first['tempos']),
                *(tempo_at(tempo, bar) for tempo in bar['tempos']),
            ])
        physical_to_output.append(len(projected) - 1)
    for rest in full_rests:
        rest['barIndex'] = physical_to_output[rest['barIndex']]
    for group in rest_groups:
        group['firstBar'] = physical_to_output[group['firstBar']]
    return projected, physical_to_output


def write_musicxml(document, path, *, meter=(4, 4), key_fifths=0, bpm=None):
    """Write uncompressed .musicxml; each decoded staff becomes a concert-pitch part.

    Derived voices, gap rests and enharmonic spellings are reconstructed. Original
    Engraving and guitar string/fret placement are reconstructed only where supported.
    Expressive symbols preserve written evidence; preview holds never lengthen notation.
    """
    from .boundary_ties import resolve_boundary_ties
    for page in document['pages']:
        for event in page['events']:
            event_kind(event)
            validate_unpitched(event)
        for row in page.get('score', {}).get('rests', []):
            if rest_kind(row) == 'FULL_MEASURE':
                full_measure_span(page, row)
    document = resolve_boundary_ties(document)
    from .printed_tempo_spans import resolve_pages, preserve_end, SOURCE, GRADUAL
    document = dict(document, pages=resolve_pages(document['pages']))
    from .navigation import tempo_restart_pages
    tempo_restarts = tempo_restart_pages(document['pages'])
    from . import slur_export
    retained_slurs = slur_export.retained_spans(document['pages'], include_candidates=True)
    retained_slur_ties = set()
    requested_slur_owners = {owner for _, a, b in retained_slurs for owner in (a, b)}
    slur_attacks = {}
    printed_string_attacks = {}
    printed_string_count = 0
    meter = tuple(document.get('initialMeter', meter))
    key_fifths = document.get('initialKeyFifths', key_fifths)
    bpm = document.get('initialBpm', 120) if bpm is None else bpm
    if len(meter) != 2 or not all(isinstance(n, int) and 1 <= n <= 32 for n in meter) or meter[1] & (meter[1]-1):
        raise ValueError('Invalid MusicXML initial meter')
    if not isinstance(key_fifths, int) or not -7 <= key_fifths <= 7 or not math.isfinite(bpm) or not 15 <= bpm <= 400:
        raise ValueError('Invalid MusicXML key or tempo')
    bow_position_directions, string_continuations = [], []
    bars, events, expressions, full_rests, literal_rests = [], [], [], [], []
    first_number = 1
    for page in document['pages']:
        if not page['measureBeats']:
            continue
        score = page['score']
        first = score.get('firstMeasureNumber', 0)
        if type(first) is int:
            first_number = max(1, first)
            if score.get('openingPickup') is True and 0 <= first <= 1:
                first_number = 0
        break
    parenthesis_level_ends = [float("-inf")] * 17
    printed_rest_groups = []
    printed_triplets = []
    printed_beams = []
    continuations = set()
    octave_owned = {}
    accidental_source_marks = set()
    from . import octave_shift_export
    octave_measure_pages = {}
    printed_octaves = []
    offset = 0
    current_meter, current_key = meter, key_fifths
    for page_index, page in enumerate(document['pages']):
        score = page['score']
        local_start = 0
        first_bar = len(bars)
        continuation_rows = page.get('printedMeasureContinuations', [])
        if not isinstance(continuation_rows, list) or any(
                type(i) is not int or not 1 <= i < len(page['measureBeats'])
                for i in continuation_rows) or len(set(continuation_rows)) != len(continuation_rows):
            raise ValueError('Invalid MusicXML system continuation index')
        if continuation_rows:
            regions = score.get('measures', [])
            if (len(regions) != len(page['measureBeats'])
                    or any(not 0 <= regions[i-1].get('bottom', -1)
                           < regions[i].get('top', -1) <= 1 for i in continuation_rows)):
                raise ValueError('MusicXML system continuation requires successive systems')
            continuations.update(first_bar + i for i in continuation_rows)
        from . import multi_rest_wire
        for group in multi_rest_wire.checked_score(score):
            printed_rest_groups.append(dict(group, firstBar=first_bar+group['firstMeasureIndex']))
        from . import source_tempo_clock
        keys = {change['measureIndex']: change for change in score.get('keyChanges', [])}
        meters = {change['measureIndex']: change for change in score.get('meterChanges', [])}
        tempos_by_measure = defaultdict(list)
        for change in score.get('tempoChanges', []):
            tempos_by_measure[change['measureIndex']].append(change)
        barline_rows = page.get('printedBarlineStyles', [])
        barline_styles = {}
        if not isinstance(barline_rows, list):
            raise ValueError('Invalid MusicXML printed barline styles')
        for row in barline_rows:
            if (not isinstance(row, dict) or type(row.get('measureIndex')) is not int
                    or not 0 <= row['measureIndex'] < len(page['measureBeats'])
                    or row['measureIndex'] in barline_styles
                    or row.get('style') not in ('light-light', 'light-heavy')
                    or row['measureIndex'] + 1 in continuation_rows):
                raise ValueError('Invalid MusicXML printed barline style ownership')
            barline_styles[row['measureIndex']] = row['style']
        # Source anchors use the unrounded beat clock, separate from bar lengths in ticks.
        measure_starts = list(accumulate(page['measureBeats'], initial=0))
        for first, last, staff, count, shift, bounds in octave_shift_export.retained(page, continuation_rows):
            printed_octaves.append((first_bar + first, first_bar + last, staff, count, shift, bounds))
        for index, beats in enumerate(page['measureBeats']):
            length = ticks(beats)
            if length <= 0:
                raise ValueError('MusicXML measure duration must be positive')
            if index in keys:
                current_key = keys[index]['fifths']
            if index in meters:
                change = meters[index]
                current_meter = (change['numerator'], change['denominator'])
            bar = dict(start=offset+local_start, length=length, meter=current_meter, key=current_key,
                       sourcePageIndex=page_index,
                       resetTempo=index == 0 and page_index in tempo_restarts,
                       tempos=[dict(c, _sourceTempoOnset=source_tempo_clock.onset(page, c))
                               for c in tempos_by_measure.get(index, ())])
            if index in barline_styles:
                bar['rightBarline'] = barline_styles[index]
            bars.append(bar)
            local_start += length
        for index, chords in _written_open_harmonics(
                page, measure_starts, continuation_rows, bars[first_bar:]).items():
            bars[first_bar + index]['writtenOpenHarmonics'] = chords
        for row in score.get('rests', []):
            if rest_kind(row) == 'FULL_MEASURE':
                bar_index = first_bar+row['measureIndex']
                bar = bars[bar_index]
                full_rests.append(dict(row, barIndex=bar_index, sourcePageIndex=page_index,
                                       start=bar['start'], end=bar['start']+bar['length'],
                                       restIdentity=len(full_rests), releaseSymbols=[]))
        # Literal rest columns retain their written subdivisions in proved silence slots.
        from .performance import _rest_column_span
        for row in score.get('rests', []):
            if rest_kind(row) != 'LITERAL' or not all(key in row for key in
                    ('measureIndex', 'staffIndex', 'staffCount', 'positionInMeasure', 'durationBeats')):
                continue
            m = row['measureIndex']
            if not isinstance(m, int) or not 0 <= m < len(page['measureBeats']):
                continue
            span = _rest_column_span(page, m, row['staffIndex'], row['staffCount'],
                                     row['positionInMeasure'], 0)
            if span is None:
                continue
            bar = bars[first_bar+m]
            start = bar['start']+ticks(span[0]['quarterBeatOffset'])
            end = bar['start']+ticks(span[1]['quarterBeatOffset'])
            if bar['start'] <= start < end <= bar['start']+bar['length']:
                literal_rests.append(dict(row, start=start, end=end))
        source_notes = score.get('notes', [])
        beam = score.get('beamSource')
        if beam:
            beam = checked_beams(beam, len(source_notes))
            groups = beam_export.bind(beam, page['events'])
            first_event = len(events)
            for group, (identities, bridges, hooks) in zip(beam['groups'], groups, strict=True):
                columns = [tuple(first_event + index for index in col) for col in identities]
                printed_beams.append((columns, bridges, hooks))
                if len(columns) != 3 or bridges != [1, 1] or not all(columns):
                    continue
                pattern = []
                for col in identities:
                    members = [source_notes[page['events'][index]['sourceNoteIndex']] for index in col]
                    if any(n.get('tupletDivisor') != 3 or n.get('tupletNormalNotes') != 2 for n in members):
                        break
                    figures = {(n.get('beamCount'), n.get('augmentationDots')) for n in members}
                    if len(figures) != 1:
                        break
                    pattern.append(figures.pop())
                tuplet_mark = group.get('tupletMark')
                dotted = pattern == [(1, 1), (2, 0), (1, 0)]
                if pattern in ([(1, 0), (2, 0), (1, 0)], [(1, 0)] * 3) or dotted and tuplet_mark is not None:
                    printed_triplets.append((columns, pattern, first_bar, len(page['measureBeats']),
                                             tuplet_mark))
        first_event = len(events)
        written = grace_export.plan(page, ticks)
        paired_tremolos = tremolo_export.retained(page)
        printed_accidentals = spelling_export.printed_accidentals(page)
        prefixed_accidentals = spelling_export.cancellation_prefixes(page)
        printed_fingerings = fingering_export.retained(page)
        printed_strings = printed_string_export.retained(page)
        printed_string_count += len(printed_strings)
        shared_terminal = _terminal_shared_artificial_harmonics(page, source_notes)
        shared_stopped = {i for i, note in enumerate(page['events'])
                          if note.get('sharedStoppedHarmonicPair') is True}
        shared_offsets = {}
        shared_quarters, shared_quarter_fingers = _shared_artificial_fourths(
            page, source_notes, shared_offsets)
        upstem_bars = _upstem_compound_harmonic_bars(page)
        opening_lower_fingers = {}
        compound_open, fourth_partners, fourth_touches = _compound_open_harmonics(
            page, source_notes, set(shared_terminal) | set(shared_quarters) | shared_stopped,
            upstem_bars, opening_lower_fingers)
        fourth_partners.update(shared_quarters)
        fourth_partners.update(shared_terminal)
        fourth_partners.update(dict.fromkeys(shared_stopped, 'down'))
        independent_open, lower_zeros, mixed_fingers, reversed_stopped = _independent_open_harmonic_quarters(
            page, source_notes, compound_open)
        opening_open, opening_fingers, opening_stop_fingers = _opening_open_harmonics(
            page, source_notes, compound_open, fourth_partners)
        compound_open.update(independent_open)
        compound_open.update(opening_open)
        for note_index, n in enumerate(page['events']):
            if event_kind(n) == 'PITCHED' and (not isinstance(n['midi'], int) or not 0 <= n['midi'] <= 127):
                raise ValueError('MusicXML note pitch must be a MIDI integer in 0..127')
            start, duration = ticks(n['startBeat']), ticks(n['durationBeats'])
            placement = written.get(note_index)
            if placement is not None:
                start, duration = placement['start'], placement['duration']
            is_grace = placement is not None and placement['grace']
            if duration <= 0 or (start if is_grace else start + duration) > local_start + 2:
                raise ValueError('MusicXML note duration lies outside the page timeline')
            measure_index = n.get('measureIndex')
            spelling_key = (bars[first_bar+measure_index]['key']
                            if type(measure_index) is int and 0 <= measure_index < len(page['measureBeats'])
                            else None)
            spelling = spelling_export.retained(page, note_index, spelling_key,
                                                 printed_accidentals.get(note_index))
            events.append(dict(n, start=offset+start, end=offset+(start if is_grace else min(local_start,start+duration)), tie_stop=False, tie_start=False,
                               sourceIdentity=len(events), retainedClef=clef_export.retained(page, note_index),
                               retainedArticulations=articulation_export.retained(page, note_index),
                               retainedBow=bow_export.retained(page, note_index),
                               retainedFingering=printed_fingerings.get(note_index),
                               retainedPrintedString=printed_strings.get(note_index),
                               retainedVisualSize=visual_size.retained(page, note_index),
                               retainedSpelling=spelling,
                               hasSourceAccidental=note_index in printed_accidentals,
                               retainedAccidental=spelling_export.printed_mark(printed_accidentals, note_index, spelling, prefixed_accidentals),
                               retainedOrnamentFlags=ornament_export.retained(page, note_index),
                               retainedTremolo=paired_tremolos.get(note_index), retainedArpeggio=False, retainedNaturalHarmonic=False))
            owner = n.get('sourceNoteIndex')
            actual, normal = n.get('tupletActualNotes'), n.get('tupletNormalNotes', 1)
            tuplet_source = source_notes[owner] if type(owner) is int and 0 <= owner < len(source_notes) else {}
            events[-1]['retainedTupletRatio'] = (
                actual in (2, 3, 4, 5, 6, 7) and actual != normal
                and (tuplet_source.get('tupletDivisor', actual), tuplet_source.get('tupletNormalNotes', normal))
                == (actual, normal)
                and not n.get('durationFallback') and note_index not in paired_tremolos)
            third = n.get('artificialHarmonicThird') is True
            beamed_fourth = n.get('beamedArtificialHarmonicFourth') is True
            if beamed_fourth and n.get('artificialHarmonicFourth') is not True:
                raise ValueError('Beamed artificial fourth is missing its stopped head')
            if n.get('closingHarmonicQuarter') is True:
                source = source_notes[note_index] if 0 <= note_index < len(source_notes) and owner == note_index else None
                if (source is None or n.get('artificialHarmonicFourth') is not True or beamed_fourth or third
                        or n.get('artificialHarmonicFifth') is True
                        or not _ordinary_compound_quarter(source) or n.get('durationBeats') != 1
                        or source.get('staffIndex') != 0 or source.get('staffCount') != 1
                        or type(measure_index) is not int or not 0 <= measure_index < len(page['measureBeats'])
                        or page['measureBeats'][measure_index] != 1
                        or n.get('startBeat') != sum(page['measureBeats'][:measure_index])
                        or sum(event['measureIndex'] == measure_index for event in page['events']) != 1):
                    raise ValueError('Closing harmonic requires an isolated written quarter')
            if n.get('closingHarmonicHalf') is True:
                source = source_notes[note_index] if 0 <= note_index < len(source_notes) and owner == note_index else None
                if (source is None or n.get('artificialHarmonicFourth') is not True or beamed_fourth or third
                        or n.get('artificialHarmonicFifth') is True or n.get('durationBeats') != 2
                        or source.get('unbeamedDurationBeats') != 2 or source.get('beamCount') != 0
                        or source.get('stemDirection') != 1 or source.get('staffIndex') != 0
                        or source.get('staffCount') != 1 or source.get('tupletDivisor', 1) != 1
                        or any(source.get(k) for k in ('augmentationDots','crossStaffBeam','articulations',
                                                      'tiedFromPrevious','boundaryTies'))):
                    raise ValueError('Closing harmonic half requires a shared upstem and a complete half note')
            if n.get('stemlessWholeArtificialHarmonicFourth') is True:
                source = source_notes[note_index] if 0 <= note_index < len(source_notes) and owner == note_index else None
                if (source is None or n.get('artificialHarmonicFourth') is not True or third or beamed_fourth
                        or n.get('artificialHarmonicFifth') is True or n.get('durationBeats') != 4
                        or source.get('unbeamedDurationBeats') != 4 or source.get('beamCount') != 0
                        or source.get('stemDirection') != 0 or source.get('tupletDivisor', 1) != 1
                        or any(source.get(k) for k in ('augmentationDots','crossStaffBeam',
                                                      'tiedFromPrevious','boundaryTies'))):
                    raise ValueError('Stemless harmonic fourth requires one complete written whole note')
            if 'printedHarmonicTouchAccidental' in n and not beamed_fourth:
                raise ValueError('Printed touching accidental requires an owned beamed fourth')
            if third or n.get('artificialHarmonicFourth') is True or n.get('artificialHarmonicFifth') is True:
                source = (source_notes[owner]
                          if type(owner) is int and owner == note_index and 0 <= owner < len(source_notes)
                          else None)
                engraved = _closed_artificial_spelling(n, source, third)
                events[-1]['retainedArtificialHarmonic'] = engraved
                if beamed_fourth:
                    events[-1]['retainedHarmonicTouchAccidental'] = _beamed_fourth_spelling(n, source, engraved)
                    events[-1]['retainedBeamedHarmonicStem'] = 'up' if source['stemDirection'] == 1 else 'down'
                    if 'compoundHarmonicFingering' in n:
                        events[-1]['retainedCompoundHarmonicFingering'] = _compound_harmonic_fingering(
                            n, False, 'above')
                if (beamed_fourth or note_index in shared_terminal or n.get('sharedDownstemHarmonicQuarter') is True
                        or (note_index in shared_quarters and third and source.get('writtenAccidental') == -2)):
                    events[-1]['retainedAccidental'] = spelling_export.printed_mark(
                        printed_accidentals, note_index, engraved[0], prefixed_accidentals)
            if n.get('beamedOpenStringHarmonic') is True:
                if (note_index in compound_open or note_index >= len(source_notes)
                        or events[-1].get('retainedArtificialHarmonic')):
                    raise ValueError('Short open-string harmonic has a competing written role')
                events[-1]['retainedCompoundOpenHarmonic'] = _beamed_open_string_spelling(
                    n, note_index, source_notes[note_index], spelling_key)
                events[-1]['retainedBeamedOpenStem'] = (
                    'up' if source_notes[note_index]['stemDirection'] == 1 else 'down')
            elif note_index in compound_open:
                events[-1]['retainedCompoundOpenHarmonic'] = compound_open[note_index]
            if note_index in fourth_partners:
                events[-1]['retainedCompoundFourthStem'] = fourth_partners[note_index]
            if note_index in fourth_touches:
                stem, lower = fourth_touches[note_index]
                events[-1]['retainedCompoundFourthTouchStem'] = stem
                if lower:
                    events[-1]['retainedCompoundFourthLowerZero'] = True
            if note_index in upstem_bars:
                natural = note_index in compound_open
                if natural:
                    _, lower = fourth_touches[note_index]
                else:
                    lower = n.get('artificialHarmonicFifth') is not True
                placement = 'below' if lower else 'above'
                events[-1]['retainedCompoundHarmonicFingering'] = _compound_harmonic_fingering(
                    n, natural, placement)
            if note_index in opening_lower_fingers:
                pair = opening_lower_fingers[note_index]
                if pair is None:
                    events[-1]['retainedNaturalHarmonicWithoutZero'] = True
                else:
                    events[-1]['retainedCompoundHarmonicFingering'] = pair
            if note_index in shared_quarter_fingers:
                events[-1]['retainedCompoundHarmonicFingering'] = shared_quarter_fingers[note_index]
            if shared_offsets.get(note_index):
                events[-1]['retainedHarmonicHeadOffset'] = shared_offsets[note_index]
            if note_index in opening_open:
                event = events[-1]
                event['mixedHarmonicQuarter'] = True
                event['retainedMixedHarmonicStem'] = 'up'
                if note_index in opening_fingers:
                    event['retainedMixedHarmonicFingering'] = opening_fingers[note_index]
            if note_index in opening_stop_fingers:
                events[-1]['retainedCompoundHarmonicFingering'] = opening_stop_fingers[note_index]
            if n.get('mixedHarmonicQuarter') is True:
                event = events[-1]
                event['mixedHarmonicQuarter'] = True
                if note_index in independent_open and source_notes[note_index].get('stemDirection') == 1:
                    event['retainedMixedHarmonicStem'] = 'up'
                event['mixedHarmonicLowerZero'] = note_index in lower_zeros
                engraved_harmonic = event.get('retainedArtificialHarmonic') or event.get('retainedCompoundOpenHarmonic')
                event['retainedAccidental'] = spelling_export.printed_mark(
                    printed_accidentals, note_index, engraved_harmonic[0], prefixed_accidentals)
                if note_index in mixed_fingers:
                    event['retainedReversedStoppedPair'] = note_index in reversed_stopped
                    event['retainedMixedHarmonicFingering'] = mixed_fingers[note_index]
                    if note_index in shared_stopped and third and note_index not in reversed_stopped:
                        event['retainedAccidental'] = 'natural'
            if type(owner) is int and 0 <= owner < len(source_notes):
                events[-1]['retainedFollowingRest'] = source_notes[owner].get('followingRestBeats', 0)
            if is_grace:
                events[-1].update(grace=True, graceOrdinal=placement['ordinal'], afterGrace=placement['after'],
                                  stealPercent=placement['steal'], graceWrittenDuration=duration,
                                  principalSourceIdentity=first_event+placement['principal'])
        string_continuations.extend(printed_string_export.continuations(
            page, events[first_event:], bars[first_bar:]))
        from . import technique_export, bow_position_export
        bow_position_directions.extend(bow_position_export.plan(
            page, events[first_event:], bars[first_bar:]))
        expressions.extend(technique_export.plan(page, events[first_event:], bars[first_bar:], f'page:{page_index}'))
        from . import dynamic_export
        expressions.extend(dynamic_export.plan(page, events[first_event:], bars[first_bar:], f'page:{page_index}'))
        from . import hairpin_export
        expressions.extend(hairpin_export.plan(page, events[first_event:], bars[first_bar:], f'page:{page_index}'))
        for source, target, _ in straight_slide.bindings(page):
            # This global endpoint identity is an internal token, not a MusicXML number.
            token = first_event + target
            events[first_event+source]['slide_start'] = token
            events[first_event+target]['slide_stop'] = token
        from .performance import (
            _column_members, _rest_owned, _rest_span, _rest_identity, _resolve_pedal, _anchor, _tempo_rest_start,
        )
        def absolute(anchor):
            if anchor is None:
                return None
            m, q = anchor['measureIndex'], anchor['quarterBeatOffset']
            if not isinstance(m, int) or not 0 <= m <= len(page['measureBeats']):
                raise ValueError('Expression anchor outside MusicXML page')
            if not isinstance(q, (int, float)) or not math.isfinite(q) or q < 0 or q > (page['measureBeats'][m] if m < len(page['measureBeats']) else 0):
                raise ValueError('Expression anchor outside MusicXML measure')
            return offset+ticks(measure_starts[m]+q)
        from .arpeggio_notation import plan as arpeggio_plan
        from .neutral_rest_caption import is_caption, start as caption_start
        harmonic_marks = [m for m in score.get('expressiveEvents', [])
                          if m.get('kind') == 'NATURAL_HARMONIC']
        harmonic_owner_counts = defaultdict(int)
        for harmonic_mark in harmonic_marks:
            owner_index = natural_harmonic_owner_index(harmonic_mark, score.get('notes', []))
            if owner_index is not None:
                harmonic_owner_counts[owner_index] += 1
        fall_off_marks = [m for m in score.get('expressiveEvents', [])
                          if m.get('kind') == 'FALL_OFF']
        fall_off_owner_counts = defaultdict(int)
        for fall_off_mark in fall_off_marks:
            owner_index = fall_off_owner_index(fall_off_mark, score.get('notes', []))
            if owner_index is not None:
                fall_off_owner_counts[owner_index] += 1
        for group_mark, pair, members in parenthesized_note_group.retained(page):
            first, last = events[first_event + members[0]], events[first_event + members[-1]]
            number = next((n for n in range(1, 17) if parenthesis_level_ends[n] <= first['start']), None)
            if number is None:
                continue
            parenthesis_level_ends[number] = last['end']
            group_id = parenthesized_note_group.group_id(group_mark, pair)
            lx, ly, rx, ry, lh, rh = pair.geometry
            first['retainedParenthesisStart'] = dict(id=group_id, number=number, x=lx, y=ly, height=lh)
            last['retainedParenthesisStop'] = dict(id=group_id, number=number, x=rx, y=ry, height=rh)
        for supplied in score.get('expressiveEvents', []):
            if (supplied.get('kind') == 'PARENTHESIZED_NOTE_GROUP'
                    or dynamic_export.compound_binding(supplied) is not None):
                continue
            if supplied.get('kind') == 'NATURAL_HARMONIC':
                if (supplied.get('scope') not in ('NOTE', 'UNRESOLVED')
                        or supplied.get('end') is not None
                        or not isinstance(supplied.get('targetEventId'), str)):
                    continue
                owner_index = natural_harmonic_owner_index(supplied, score.get('notes', []))
                if owner_index is None or harmonic_owner_counts.get(owner_index) != 1:
                    continue
                matching = [owner_index]
                source = first_event + owner_index
                owner = events[source]
                evidence = supplied.get('evidence', [])
                note_identity = score['notes'][matching[0]]
                if (owner.get('measureIndex') != note_identity.get('measureIndex')
                        or owner.get('staffIndex') != supplied.get('staffIndex')
                        or owner.get('staffCount') != supplied.get('staffCount')
                        or owner.get('sourceNoteIndex') != matching[0]
                        or owner.get('durationFallback', False)
                        or not any(e.get('sourceId') == 'printed-natural-harmonic'
                                   and e.get('staffIndex') == owner.get('staffIndex')
                                   and e.get('staffCount') == owner.get('staffCount')
                                   for e in evidence)):
                    continue
                if supplied.get('scope') == 'NOTE':
                    start_anchor = supplied['start']
                    measure = owner['measureIndex']
                    quarter_beat = start_anchor['quarterBeatOffset']
                    if (measure >= len(page['measureBeats'])
                            or not 0 <= quarter_beat < page['measureBeats'][measure]):
                        continue
                    measure_start = measure_starts[measure]
                    expected = offset + ticks(measure_start + quarter_beat)
                    if (expected != owner['start']
                            or not offset + ticks(measure_start) <= owner['start']
                            < offset + ticks(measure_start + page['measureBeats'][measure])):
                        continue
                owner['retainedNaturalHarmonic'] = True
                continue
            if supplied.get('kind') == 'FALL_OFF':
                if (supplied.get('scope') not in ('NOTE', 'UNRESOLVED')
                        or supplied.get('end') is not None
                        or not isinstance(supplied.get('targetEventId'), str)):
                    continue
                owner_index = fall_off_owner_index(supplied, score.get('notes', []))
                if owner_index is None or fall_off_owner_counts.get(owner_index) != 1:
                    continue
                matching = [owner_index]
                source = first_event + owner_index
                owner = events[source]
                evidence = supplied.get('evidence', [])
                note_identity = score['notes'][matching[0]]
                if (owner.get('measureIndex') != note_identity.get('measureIndex')
                        or owner.get('staffIndex') != supplied.get('staffIndex')
                        or owner.get('staffCount') != supplied.get('staffCount')
                        or owner.get('sourceNoteIndex') != matching[0]
                        or owner.get('durationFallback', False)
                        or not any(e.get('sourceId') == 'printed-fall-off'
                                   and e.get('staffIndex') == owner.get('staffIndex')
                                   and e.get('staffCount') == owner.get('staffCount')
                                   for e in evidence)):
                    continue
                if supplied.get('scope') == 'NOTE':
                    start_anchor = supplied['start']
                    measure = owner['measureIndex']
                    quarter_beat = start_anchor['quarterBeatOffset']
                    if (measure >= len(page['measureBeats'])
                            or not 0 <= quarter_beat < page['measureBeats'][measure]):
                        continue
                    measure_start = measure_starts[measure]
                    expected = offset + ticks(measure_start + quarter_beat)
                    if (expected != owner['start']
                            or not offset + ticks(measure_start) <= owner['start']
                            < offset + ticks(measure_start + page['measureBeats'][measure])):
                        continue
                owner['retainedFallOff'] = True
                continue
            if supplied.get('kind') == 'ARPEGGIO':
                for source_index in arpeggio_plan(supplied, page):
                    events[first_event+source_index]['retainedArpeggio'] = True
                continue  # A source-owned note symbol, never a generic words direction.
            mark = _resolve_pedal(dict(supplied), page)
            mark.pop('_neutralRestCaption', None)
            if is_caption(mark):
                mark['start'], mark['end'] = caption_start(mark, page), None
                mark['scope'] = 'PART' if mark['start'] is not None else 'UNRESOLVED'
                mark['_neutralRestCaption'] = mark['start'] is not None
            elif _rest_owned(mark):
                span = _rest_span(mark, page)
                mark['start'], mark['end'] = span if span else (None, None)
                mark['scope'] = 'REST' if span else 'UNRESOLVED'
                if span:
                    m, staff, count, position = _rest_identity(mark)
                    owned_full = [r for r in full_rests if r['sourcePageIndex'] == page_index
                                  and r['measureIndex'] == m and r['staffIndex'] == staff
                                  and r['staffCount'] == count and abs(r['positionInMeasure']-position) <= .018]
                    if len(owned_full) == 1:
                        mark['fullRestIdentity'] = owned_full[0]['restIdentity']
            if mark.get('start') is None:
                tempo_start = _tempo_rest_start(mark, page)
                if tempo_start is not None:
                    mark['start'], mark['scope'] = tempo_start, 'SCORE'
            if cue_caption.owns(mark):
                mark['start'], mark['end'], mark['scope'] = None, None, 'UNRESOLVED'
            mark['identity'] = f'page:{page_index}/'+mark['eventId']
            members = _column_members(mark, page)
            mark['ownerIds'] = [first_event+i for i in members]
            owned = [events[first_event+i] for i in members]
            exact_column = owned and all(
                not n.get('durationFallback', False) and n['start'] == owned[0]['start']
                and (mark['kind'] == 'TEMPO_MARKING' or n['end'] == owned[0]['end']) for n in owned)
            if exact_column and mark['kind'] == 'FERMATA':
                # A pickup can shorten the decoded measure after the cached
                # fermata coordinates were calculated. The proved head owns both edges.
                mark['startTick'], mark['endTick'] = owned[0]['start'], owned[0]['end']
                expressions.append(mark)
                continue
            mark['startTick'], mark['endTick'] = absolute(mark.get('start')), absolute(mark.get('end'))
            if exact_column:
                mark['startTick'] = owned[0]['end'] if mark['kind'] in ('BREATH', 'CAESURA') else owned[0]['start']
                if cue_caption.owns(mark):
                    mark['scope'] = 'PART'
                local_starts = [0]
                for duration in page['measureBeats']:
                    local_starts.append(local_starts[-1] + duration)
                resolved_start = _anchor((mark['startTick'] - offset) / DIVISIONS, local_starts)
                if mark['kind'] in GRADUAL:
                    mark['endTick'] = absolute(preserve_end(mark, resolved_start, page))
            elif mark.get('scope') == 'UNRESOLVED':
                mark['startTick'] = None
            if mark['startTick'] is not None:
                expressions.append(mark)
        offset += local_start
    if not bars:
        raise ValueError('No interpreted measures to export')
    physical_bars = bars
    physical_to_output = list(range(len(bars)))
    if continuations:
        bars, physical_to_output = _join_printed_measures(
            bars, continuations, full_rests, printed_rest_groups)
    from .sudden_dynamic import combine as combine_sudden_dynamics
    expressions = combine_sudden_dynamics(expressions)
    root = ET.Element('score-partwise', version='4.0')
    element(element(root, 'work'), 'work-title', document.get('inputName', 'Interpreted score'))
    identification = element(root, 'identification')
    element(element(identification, 'encoding'), 'software', 'Sheet Music Recognition')
    misc = element(identification, 'miscellaneous')
    element(misc, 'miscellaneous-field', 'Concert-pitch reconstruction; recognition and estimated timing require review.', name='interpretation')
    part_list = element(root, 'part-list')
    beamed_owners = {owner for columns, _, _ in printed_beams for column in columns for owner in column}
    rendered_beam_columns = {}
    bar_starts = [bar['start'] for bar in bars]
    bar_ends = [bar['start'] + bar['length'] for bar in bars]
    bar_indexes = {bar['start']: physical_to_output[index]
                   for index, bar in enumerate(physical_bars)}

    def direction_bar(tick, allow_final=False):
        index = bisect_right(bar_starts, tick) - 1
        if index >= 0 and (tick < bar_ends[index] or allow_final and tick == bar_ends[-1]):
            return index
        return None

    def written_direction_bar(start, tick):
        index = bar_indexes.get(start)
        if index is not None and start <= tick <= bar_ends[index]:
            return index
        return None

    def physical_staff(row):
        staff = row.get('staffIndex', 0)
        return staff, row.get('staffCount', staff+1)
    if full_rests:
        staffs = sorted({physical_staff(n) for n in events}
                        | {physical_staff(r) for r in full_rests}
                        | {physical_staff(m) for m in expressions if m.get('scope') == 'REST'})
    else:
        # Preserve the existing literal-only reconstruction and part identities.
        staffs = sorted({n.get('staffIndex', 0) for n in events}
                        | {m['staffIndex'] for m in expressions if m.get('scope') == 'REST'}) or [0]
    open_harmonic_measures = []
    octave_staff_spans = {}
    for identity in staffs:
        staff, count = identity if full_rests else (identity, None)
        same_staff = lambda row: physical_staff(row) == identity if full_rests else row.get('staffIndex', 0) == staff
        ambiguous_index = bool(full_rests) and sum(key[0] == staff for key in staffs) > 1
        part_id = f'P{staff+1}C{count}' if ambiguous_index else f'P{staff+1}'
        name = f'Staff {staff+1} of {count}' if ambiguous_index else f'Staff {staff+1}'
        element(element(part_list, 'score-part', id=part_id), 'part-name', name)
        part = element(root, 'part', id=part_id)
        notes = sorted((n for n in events if same_staff(n)), key=lambda n: (n['start'], notation_order(n)))
        staff_spans = [(physical_to_output[first], physical_to_output[last], shift, bounds)
                       for first, last, owner, staff_count, shift, bounds in printed_octaves
                       if same_staff({'staffIndex': owner, 'staffCount': staff_count})]
        clef_changes = clef_export.plan(notes)
        emitted_clefs = set()
        silent = [r for r in full_rests if same_staff(r)]
        rests_by_bar = defaultdict(list)
        for row in silent:
            rests_by_bar[row['barIndex']].append(row)
        rest_groups_by_bar = defaultdict(list)
        for group in printed_rest_groups:
            if same_staff(group):
                rest_groups_by_bar[group['firstBar']].append(group)
        literal_edges = {tick for row in literal_rests if same_staff(row)
                         for tick in (row['start'], row['end'])}
        for index, n in enumerate(notes):
            if n.get('grace', False):
                continue
            gliss = n.get('glissando')
            if gliss is None:
                continue
            if (not isinstance(gliss,dict) or gliss.get('style') != 'white_keys'
                    or type(gliss.get('targetMidi')) is not int or not 0 <= gliss['targetMidi'] <= 127):
                raise ValueError('Unsupported glissando notation')
            targets = [target for target in notes if not target.get('grace', False) and abs(target['start']-n['end']) <= 2]
            if len(targets) == 1 and event_kind(targets[0]) == 'PITCHED' and targets[0]['midi'] == gliss['targetMidi']:
                n['gliss_start'] = index % 16 + 1
                targets[0]['gliss_stop'] = n['gliss_start']
        marks = sorted((m for m in expressions if m.get('scope') == 'SCORE' or same_staff(m)),
                       key=lambda m: (m['startTick'], 0 if m['kind'] == 'PEDAL_UP' else 1))
        dash_numbers = {}
        occupied = []
        for mark in marks:
            if mark['kind'] not in GRADUAL or mark.get('endTick') is None or mark['endTick'] <= mark['startTick'] or not any(e['sourceId'] == SOURCE for e in mark.get('evidence', [])):
                continue
            occupied = [(end, number) for end, number in occupied if end > mark['startTick']]
            number = next((n for n in range(1, 17) if n not in {number for _, number in occupied}), None)
            if number is None:
                raise ValueError('Too many overlapping printed tempo extensions')
            dash_numbers[mark['identity']] = number
            occupied.append((mark['endTick'], number))
        occupied = []
        for mark in marks:
            if not mark.get('_hairpin'):
                continue
            occupied = [(end, number) for end, number in occupied if end > mark['startTick']]
            number = next((n for n in range(1, 17) if n not in {number for _, number in occupied}), None)
            if number is None:
                raise ValueError('Too many overlapping hairpins')
            mark['_hairpinNumber'] = str(number)
            occupied.append((mark['endTick'], number))
        rendered, rest_symbols = set(), []
        for mark in marks:
            if mark['kind'] not in ('FERMATA', 'BREATH', 'CAESURA'):
                continue
            release = mark['endTick'] if mark['kind'] == 'FERMATA' else mark['startTick']
            if release is None:
                continue
            symbol = dict(kind=mark['kind'], start=mark['startTick'], release=release,
                          value='tick' if 'tick' in mark.get('qualifierText', '') else 'comma',
                          inverted=mark.get('qualifierText') == 'fermata inverted')
            if mark.get('scope') == 'REST' and mark['kind'] == 'FERMATA':
                owned = [r for r in silent if r['restIdentity'] == mark.get('fullRestIdentity')]
                if owned:
                    owned[0]['releaseSymbols'].append(symbol)
                    rendered.add(mark['identity'])
                elif release > mark['startTick'] and not any(n['start'] < release and n['end'] > mark['startTick'] for n in notes):
                    rest_symbols.append(symbol)
                    rendered.add(mark['identity'])
                continue
            eligible = [n for n in notes if not n.get('grace', False) and n['end'] == release and not n.get('durationFallback', False)
                        and (not mark['ownerIds'] or n['sourceIdentity'] in mark['ownerIds'])]
            if eligible:
                selected = (min if symbol['inverted'] else max)(eligible, key=(
                    (lambda n: display_position(n)) if any(event_kind(n) == 'UNPITCHED' for n in eligible)
                    else (lambda n: n['midi'])))
                selected.setdefault('releaseSymbols', []).append(symbol)
                rendered.add(mark['identity'])
        directions_by_bar = defaultdict(list)
        string_lines_by_bar = defaultdict(list)
        occupied_brackets = defaultdict(list)
        bow_starts = {}
        for mark in sorted(bow_position_directions, key=lambda row: row['start']):
            if not same_staff(mark) or 'connector' not in mark:
                continue
            key = mark['barStart'], mark['staffIndex'], mark['staffCount'], mark['lineNumber']
            if mark['connector'] == 'start':
                bow_starts[key] = mark['start']
            else:
                occupied_brackets[int(mark['lineNumber'])].append((bow_starts.pop(key), mark['start']))
        for first, stop in sorted(string_continuations, key=lambda pair: pair[0]['start']):
            if not same_staff(first):
                continue
            number = next((n for n in range(1, 17) if all(
                end < first['start'] or start > stop['start'] for start, end in occupied_brackets[n])), None)
            if number is None:
                raise ValueError('Too many overlapping printed string continuations')
            indexes = [written_direction_bar(row['barStart'], row['start']) for row in (first,stop)]
            if None in indexes:
                raise ValueError('Unresolved printed string continuation in MusicXML')
            occupied_brackets[number].append((first['start'], stop['start']))
            for index, row in zip(indexes, (first,stop)):
                string_lines_by_bar[index].append(dict(row, lineNumber=str(number)))
        bow_positions_by_bar = defaultdict(list)
        for mark in bow_position_directions:
            if same_staff(mark):
                index = written_direction_bar(mark['barStart'], mark['start'])
                if index is not None:
                    bow_positions_by_bar[index].append(mark)
        for mark in marks:
            if mark['identity'] not in rendered:
                tick = mark['startTick']
                if mark.get('_hairpin'):
                    index = written_direction_bar(mark['_hairpinStartBarStart'], tick)
                elif mark.get('_absoluteDynamic'):
                    index = written_direction_bar(mark['_absoluteDynamicBarStart'], tick)
                elif mark.get('_playingInstruction'):
                    # A terminal after-grace belongs to its original written bar.
                    index = written_direction_bar(mark['_playingInstructionBarStart'], tick)
                else:
                    index = direction_bar(tick, allow_final=mark['kind'] == 'PEDAL_UP')
                if index is not None:
                    directions_by_bar[index].append(('start', mark))
            if mark.get('_hairpin'):
                index = written_direction_bar(mark['_hairpinEndBarStart'], mark['endTick'])
                if index is not None:
                    directions_by_bar[index].append(('wedge', mark))
            if mark['identity'] in dash_numbers:
                index = direction_bar(mark['endTick'], allow_final=True)
                if index is not None:
                    directions_by_bar[index].append(('dashes', mark))
        from .tie_owners import releasing_links
        tie_rows = [dict(start=n['start'], end=n['end'], lane=(staff, n.get('midi')),
                         tied=n.get('tiedFromPrevious', False),
                         eligible=not n.get('grace', False) and event_kind(n) == 'PITCHED') for n in notes]
        tie_links = releasing_links(tie_rows, lambda row: [(row['lane'], 2)])
        for current, earlier in tie_links.items():
            prior, n = notes[earlier], notes[current]
            prior['tie_start'] = True
            n['tie_stop'] = True
            retained_slur_ties.add((prior['sourceIdentity'], n['sourceIdentity']))
        ornament_export.bind_trill_waves(notes, tie_links)
        groups = defaultdict(list)
        for n in notes:
            if not n.get('grace', False):
                groups[n['start'], n['end'], 0 if event_kind(n) == 'UNPITCHED' else n.get('octaveShift', 0)].append(n)
        voice_ends = []
        for (start, end, octave_shift), chord in sorted(groups.items()):
            voice = next((i for i, at in enumerate(voice_ends) if at <= start), len(voice_ends))
            if voice == len(voice_ends):
                voice_ends.append(end)
            else:
                voice_ends[voice] = end
            for n in chord:
                n['voice'] = voice + 1
        _retain_printed_beam_voices(printed_beams, groups, bar_starts, bar_ends)
        by_source = {n['sourceIdentity']: n for n in notes}
        mixed_edges = {}
        mixed_bars = set()
        for columns, pattern, first_bar, page_bar_count, tuplet_mark in printed_triplets:
            if any(owner not in by_source for column in columns for owner in column):
                continue
            members = [[by_source[owner] for owner in column] for column in columns]
            first = members[0][0]
            key = first['voice'], first['measureIndex']
            if type(key[1]) is not int or not 0 <= key[1] < page_bar_count:
                continue
            physical_bar_index = first_bar + key[1]
            bar = physical_bars[physical_bar_index]
            bar_index = physical_to_output[physical_bar_index]
            cursor = first['start']
            if not bar['start'] <= cursor < bar['start'] + bar['length']:
                continue
            edges = {}
            for col, (level, dots), edge in zip(members, pattern, ('start', None, 'stop')):
                duration = DIVISIONS // (3 if level == 1 else 6)
                if dots:
                    duration += duration // 2
                end = cursor + duration
                if any((n['voice'], n['measureIndex']) != key or n['start'] != cursor
                       or n['end'] != end or n.get('grace', False)
                       or n.get('tupletActualNotes') != 3 or n.get('tupletNormalNotes') != 2
                       for n in col):
                    break
                if edge:
                    edges[key[0], cursor, end] = edge, tuplet_mark
                cursor = end
            else:
                if cursor <= bar['start'] + bar['length']:
                    mixed_edges.update(edges)
                    if pattern == [(1, 0), (2, 0), (1, 0)]:
                        mixed_bars.add((key[0], bar_index))

        grace_groups = defaultdict(list)
        for n in notes:
            if n.get('grace', False):
                owner = by_source.get(n['principalSourceIdentity'])
                if owner is None or owner.get('grace', False):
                    raise ValueError('Grace principal is outside exported part')
                n['voice'] = owner['voice']
                grace_groups[n['principalSourceIdentity'], n['afterGrace']].append(n)
        def emit_graces(measure, bar_index, chord, after, anchor, flats):
            pending = [g for n in chord if anchor == (n['end'] if after else n['start'])
                       for g in grace_groups.get((n['sourceIdentity'], after), ())]
            pending.sort(key=lambda g: (g['graceOrdinal'], g['sourceIdentity']))
            for position, g in enumerate(pending):
                emitted = emit_note(measure, g['graceWrittenDuration'], g['voice'], g, flats=flats,
                                    articulation_flags=g['retainedArticulations'], grace=g, bow_direction=g.get('retainedBow'))
                octave_owned[emitted] = 0 if event_kind(g) == 'UNPITCHED' else g.get('octaveShift', 0)
                if g['hasSourceAccidental']:
                    accidental_source_marks.add(emitted)
                owner = g['sourceIdentity']
                if owner in requested_slur_owners:
                    slur_attacks[owner] = emitted
                if owner in beamed_owners:
                    rendered_beam_columns[frozenset({owner})] = beam_export.WrittenColumn(
                        emitted, part_id, bar_index, g['voice'], anchor, anchor,
                        (g['principalSourceIdentity'], after, position))
        sounding = [n for n in notes if not n.get('grace', False)]
        next_note = 0
        present = []
        for index, bar in enumerate(bars):
            measure = element(part, 'measure', number=first_number+index)
            octave_measure_pages[measure] = bar['sourcePageIndex']
            if 'writtenOpenHarmonics' in bar and same_staff({'staffIndex': 0, 'staffCount': 1}):
                open_harmonic_measures.append((measure, bar['writtenOpenHarmonics']))
            attributes = element(measure, 'attributes')
            element(attributes, 'divisions', DIVISIONS)
            element(element(attributes, 'key'), 'fifths', bar['key'])
            time = element(attributes, 'time')
            numerator, denominator = bar['meter']
            if ticks(numerator*4/denominator) != bar['length']:
                # Retain actual timeline for pickups or unusual inferred bars.
                measure.set('implicit', 'yes')
            element(time, 'beats', numerator)
            element(time, 'beat-type', denominator)
            if bar['start'] in clef_changes:
                clef_export.write(attributes, clef_changes[bar['start']])
                emitted_clefs.add(bar['start'])
            for group in rest_groups_by_bar.get(index, ()):
                element(element(attributes, 'measure-style'), 'multiple-rest', group['measureCount'])
            tempos = list(bar['tempos'])
            if (index == 0 or bar['resetTempo']) and not any(
                    t.get('_writtenBarOffset', t['positionInMeasure']) == 0 for t in tempos):
                tempos.insert(0, dict(bpm=bpm, positionInMeasure=0))
            for tempo in tempos:
                unit, dots, marked_bpm = tempo_mark(tempo)
                direction = element(measure, 'direction', placement='above')
                metronome = element(element(direction, 'direction-type'), 'metronome')
                element(metronome, 'beat-unit', unit)
                for _ in range(dots):
                    element(metronome, 'beat-unit-dot')
                element(metronome, 'per-minute', format(Decimal(str(marked_bpm)).normalize(), 'f'))
                source_onset = tempo.get('_sourceTempoOnset')
                tempo_offset = tempo.get('_writtenBarOffset')
                if tempo_offset is None:
                    tempo_offset = (ticks(source_onset) if source_onset is not None
                                    else round(bar['length'] * tempo['positionInMeasure']))
                element(direction, 'offset', tempo_offset, sound='yes')
                element(direction, 'sound', tempo=str(tempo['bpm']))
            a, b = bar['start'], bar['start']+bar['length']
            for continuation in string_lines_by_bar.get(index, ()):
                printed_string_export.write_continuation(measure, continuation, continuation['start'] - a)
            for bow_position in bow_positions_by_bar.get(index, ()):
                bow_position_export.write(measure, bow_position, bow_position['start'] - a)
            for action, mark in directions_by_bar.get(index, ()):
                if action == 'start':
                    expression_direction(measure, mark, mark['startTick']-a)
                    if mark['identity'] in dash_numbers:
                        dash = element(measure, 'direction', placement='above')
                        element(element(dash, 'direction-type'), 'dashes', type='start', number=str(dash_numbers[mark['identity']]))
                        element(dash, 'offset', mark['startTick']-a)
                elif action == 'wedge':
                    stop = element(measure, 'direction', placement='below')
                    element(element(stop, 'direction-type'), 'wedge', type='stop',
                            number=mark['_hairpinNumber'], **mark.get('_hairpinGeometry', {}))
                    element(stop, 'offset', mark['endTick']-a)
                else:
                    dash = element(measure, 'direction', placement='above')
                    element(element(dash, 'direction-type'), 'dashes', type='stop', number=str(dash_numbers[mark['identity']]))
                    element(dash, 'offset', mark['endTick']-a)
            # Carry sustained notes forward and consume each onset in score order.
            present = [n for n in present if n['end'] > a]
            while next_note < len(sounding) and sounding[next_note]['start'] < b:
                n = sounding[next_note]
                next_note += 1
                if n['end'] > a:
                    present.append(n)
            bar_rests = rests_by_bar.get(index, ())
            voices = sorted({n['voice'] for n in present}) or ([] if bar_rests else [1])
            rest_voices = {max(1000, len(voice_ends))+i+1: row for i, row in enumerate(bar_rests)}
            voices.extend(rest_voices)
            for vi, voice in enumerate(voices):
                if vi:
                    element(element(measure, 'backup'), 'duration', bar['length'])
                if voice in rest_voices:
                    row = rest_voices[voice]
                    emit_note(measure, bar['length'], voice, fragment_end=b,
                              rest_symbols=row['releaseSymbols'], full_measure=True)
                    continue
                first_voice_element = len(measure)
                eligible_tuplets = set()
                cursor = a
                segments = defaultdict(list)
                for n in present:
                    if n['voice'] == voice:
                        segments[max(a,n['start']),min(b,n['end'])].append(n)
                ordered = sorted(segments.items())
                pair_edges = {}
                for i, ((start, end), chord) in enumerate(ordered[:-1]):
                    if (start, end) in pair_edges:
                        continue
                    (next_start, next_end), following = ordered[i + 1]
                    if (end != next_start or end - start != DIVISIONS // 2
                            or next_end - next_start != end - start):
                        continue
                    if all(n['start'] == onset and n['end'] == release
                           and n.get('tupletActualNotes', 1) == n.get('tupletNormalNotes', 1) == 2
                           for onset, release, heads in ((start, end, chord),
                                                         (next_start, next_end, following))
                           for n in heads):
                        pair_edges[start, end] = 'start'
                        pair_edges[next_start, next_end] = 'stop'
                for (start, end), chord in ordered:
                    if start > cursor:
                        emit_rest_gap(measure, cursor, start, voice, rest_symbols, literal_edges)
                    fragment_start = start
                    for duration in duration_parts(end-start, chord):
                        fragment_end = fragment_start + duration
                        if any(fragment_start == n['start'] for n in chord) and start in clef_changes and start not in emitted_clefs:
                            clef_export.write(element(measure, 'attributes'), clef_changes[start])
                            emitted_clefs.add(start)
                        emit_graces(measure, index, chord, False, fragment_start, bar['key']<0)
                        articulation_flags = articulation_export.chord_marks(chord, fragment_start)
                        bow_directions = bow_export.chord_marks(chord, fragment_start)
                        for ci, n in enumerate(chord):
                            emitted = emit_note(measure, duration, voice, n, chord=ci>0,
                                      stop=fragment_start > n['start'] or n['tie_stop'],
                                      start=fragment_end < n['end'] or n['tie_start'], flats=bar['key']<0,
                                      gliss_start=fragment_end==n['end'] and 'gliss_start' in n,
                                      gliss_stop=fragment_start==n['start'] and 'gliss_stop' in n,
                                      slide_start=fragment_end==n['end'] and 'slide_start' in n,
                                      slide_stop=fragment_start==n['start'] and 'slide_stop' in n,
                                      fragment_end=fragment_end, articulation_flags=articulation_flags[ci],
                                      attack_fragment=fragment_start==n['start'], bow_direction=bow_directions[ci])
                            mark = n.get('retainedPrintedString')
                            if fragment_start == n['start'] and mark is not None:
                                owner = n['sourceIdentity']
                                if owner in printed_string_attacks:
                                    raise ValueError('A printed string mark has two written attacks')
                                printed_string_attacks[owner] = emitted, mark
                            edge = pair_edges.get((fragment_start, fragment_end))
                            tuplet_mark = None
                            if edge is None:
                                edge, tuplet_mark = mixed_edges.get(
                                    (voice, fragment_start, fragment_end), (None, None))
                            if ci == 0 and edge is not None:
                                notations = emitted.find('notations')
                                if notations is None:
                                    notations = element(emitted, 'notations')
                                tuplet = element(notations, 'tuplet', type=edge, number=1)
                                if edge == 'start' and tuplet_mark in ('NUMBERED', 'NUMBERED_CURVED_BELOW'):
                                    tuplet.set('show-number', 'actual')
                                    if tuplet_mark == 'NUMBERED_CURVED_BELOW':
                                        tuplet.set('placement', 'below')
                                        tuplet.set('bracket', 'yes')
                                        tuplet.set('line-shape', 'curved')
                                elif edge == 'start' and tuplet_mark == 'UNNUMBERED':
                                    tuplet.set('show-number', 'none')
                                    tuplet.set('bracket', 'no')
                            if ci == 0 and n['retainedTupletRatio'] and all(head['retainedTupletRatio']
                                               and (head['tupletActualNotes'], head.get('tupletNormalNotes', 1))
                                               == (n['tupletActualNotes'], n.get('tupletNormalNotes', 1))
                                               for head in chord[1:]):
                                eligible_tuplets.add(emitted)
                            octave_owned[emitted] = 0 if event_kind(n) == 'UNPITCHED' else n.get('octaveShift', 0)
                            if n['hasSourceAccidental']:
                                accidental_source_marks.add(emitted)
                            if fragment_start == n['start'] and n['sourceIdentity'] in requested_slur_owners:
                                slur_attacks[n['sourceIdentity']] = emitted
                            if (ci == 0 and n['sourceIdentity'] in beamed_owners
                                    and start == fragment_start and end == fragment_end
                                    and all(head['start'] == start and head['end'] == end
                                            and not head.get('durationFallback', False)
                                            and not head.get('retainedTremolo')
                                            and head.get('retainedVisualSize') != 'CUE'
                                            for head in chord)):
                                owners = frozenset(head['sourceIdentity'] for head in chord)
                                rendered_beam_columns[owners] = beam_export.WrittenColumn(
                                    emitted, part_id, index, voice, start, end)
                        emit_graces(measure, index, chord, True, fragment_end, bar['key']<0)
                        fragment_start = fragment_end
                    cursor = end
                if cursor < b:
                    last_chord = ordered[-1][1] if ordered else ()
                    rest_values = {n.get('retainedFollowingRest', 0) for n in last_chord}
                    printed = ticks(rest_values.pop()) if len(rest_values) == 1 else 0
                    tail = b - cursor - printed
                    if (voice, index) in mixed_bars and tail == DIVISIONS // 6:
                        if printed:
                            emit_rest_gap(measure, cursor, cursor + printed, voice, rest_symbols, literal_edges)
                        forward = element(measure, 'forward')
                        element(forward, 'duration', tail)
                        element(forward, 'voice', voice)
                    else:
                        emit_rest_gap(measure, cursor, b, voice, rest_symbols, literal_edges)
                if eligible_tuplets:
                    _write_inferred_tuplets(measure[first_voice_element:], eligible_tuplets)
            if bar.get('rightBarline'):
                element(element(measure, 'barline', location='right'), 'bar-style', bar['rightBarline'])
        if staff_spans:
            emitted_measures = part.findall('measure')
            octave_staff_spans[part] = [(emitted_measures[first:last + 1], shift, bounds)
                                        for first, last, shift, bounds in staff_spans]
    beam_export.write(printed_beams, rendered_beam_columns)
    if sum(map(len, octave_staff_spans.values())) != len(printed_octaves):
        raise ValueError('A printed octave span has no unique exported staff')
    octave_shift_export.apply(root, octave_owned, octave_measure_pages, octave_staff_spans)
    first_score = document['pages'][0].get('score', {})
    known_meter = 'initialMeter' in document or any(
        c.get('measureIndex') == 0 for c in first_score.get('meterChanges', []))
    known_key = 'initialKeyFifths' in document or any(
        c.get('measureIndex') == 0 for c in first_score.get('keyChanges', []))
    part_nodes = {note: part for part in root.findall('part') for note in part.iter('note')}
    exported_slur_parts = {owner: part_nodes.get(note) for owner, note in slur_attacks.items()}
    retained_slurs = slur_export.classify(retained_slurs, document['pages'], events,
                                        retained_slur_ties, known_meter and known_key,
                                        exported_slur_parts)
    slur_export.apply(root, retained_slurs, slur_attacks, events)
    slide_numbering.assign(root)
    if len(printed_string_attacks) != printed_string_count:
        raise ValueError('A printed string mark has no written attack')
    for note, mark in printed_string_attacks.values():
        printed_string_export.write(note, mark)
    for measure, chords in open_harmonic_measures:
        _write_open_harmonics(measure, chords)
    spelling_export.fill_required_accidentals(root, accidental_source_marks, octave_owned)
    ET.indent(root)
    ET.ElementTree(root).write(Path(path), encoding='utf-8', xml_declaration=True)
