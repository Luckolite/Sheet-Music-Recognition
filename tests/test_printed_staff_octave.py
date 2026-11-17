# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import copy
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import xml.etree.ElementTree as ET

from sheet_music_recognition.musicxml import write_musicxml


def score():
    rows = [(0, 1, 1), (.5, 2, 1), (2.5, 1.5, 1),
            (4, 3, 1), (6, 2, 1), (8, 4, 0)]
    notes = []
    events = []
    for start, duration, octave in rows:
        common = dict(measureIndex=int(start // 4), staffIndex=0, staffCount=1,
                      clefBottomDiatonic=30, octaveShift=octave)
        notes.append(dict(common, staffStep=2, writtenAccidental=2))
        events.append(dict(common, midi=67 + 12 * octave, startBeat=start, durationBeats=duration))
    page = dict(measureBeats=[4, 4, 4], events=events, score=dict(notes=notes))
    return dict(pages=[page])


def staff_mark(first, last, shift=1):
    return dict(firstMeasureIndex=first, lastMeasureIndex=last,
                staffIndex=0, staffCount=1, octaveShift=shift)


class PrintedStaffOctaveTest(unittest.TestCase):
    def write(self, document):
        before = copy.deepcopy(document)
        with TemporaryDirectory() as directory:
            output = Path(directory) / 'score.musicxml'
            write_musicxml(document, output)
            result = ET.parse(output).getroot()
        self.assertEqual(document, before)
        return result

    def test_literal_span_covers_all_exported_voices_until_last_release(self):
        document = score()
        ordinary = self.write(document)
        document['pages'][0]['printedOctaveSpans'] = [staff_mark(0, 1)]
        literal = self.write(document)
        measures = literal.findall('part/measure')
        actual = []
        for number, measure in enumerate(measures, 1):
            for i, direction in enumerate(measure):
                tag = direction.find('direction-type/octave-shift')
                if tag is None:
                    continue
                self.assertIsNone(direction.find('voice'))
                actual.append((number, tag.get('type'), tag.get('number')))
                owner = measure[i + 1 if tag.get('type') == 'down' else i - 1]
                self.assertEqual('note', owner.tag)
                self.assertIsNotNone(owner.find('pitch'))
                self.assertEqual('1' if number == 1 else '2', owner.findtext('voice'))
        self.assertEqual([(1, 'down', '1'), (2, 'stop', '1')], actual)
        self.assertTrue(literal.findall('.//backup'))
        for left, right in zip(ordinary.findall('.//note'), literal.findall('.//note'), strict=True):
            self.assertEqual(ET.tostring(left), ET.tostring(right))
        self.assertGreater(len(ordinary.findall('.//octave-shift')), len(actual))

    def test_source_partial_span_uses_attack_offsets_across_overlapping_voices(self):
        document = score()
        page, = document['pages']
        rows = [(0, .75, 0), (.5, .75, 0), (1, .75, 1), (1.25, .5, 1),
                (1.5, .75, 1), (2, .5, 0), (3, 1, 0)]
        notes, attacks = [], []
        for start, duration, shift in rows:
            common = dict(measureIndex=0, staffIndex=0, staffCount=1,
                          clefBottomDiatonic=30, octaveShift=shift)
            notes.append(dict(common, staffStep=2, writtenAccidental=2))
            attacks.append(dict(common, midi=67 + 12 * shift, startBeat=start, durationBeats=duration))
        page.update(measureBeats=[4], events=attacks, score=dict(notes=notes))
        ordinary = self.write(document)
        self.assertGreater(len(ordinary.findall('.//octave-shift')), 2)
        page['printedOctaveSpans'] = [
            dict(staff_mark(0, 0), firstOnsetTicks=10080, followingOnsetTicks=20160)]
        literal = self.write(document)
        self.assertTrue(literal.findall('.//backup'))
        actual = [(node.find('direction-type/octave-shift').get('type'),
                   node.findtext('offset'), node.findtext('voice'))
                  for node in literal.findall('.//direction')
                  if node.find('direction-type/octave-shift') is not None]
        self.assertEqual([('down', '10080', None), ('stop', '20160', None)], actual)
        self.assertEqual([ET.tostring(n) for n in ordinary.findall('.//note')],
                         [ET.tostring(n) for n in literal.findall('.//note')])

        page['printedOctaveSpans'][0]['followingOnsetTicks'] = 20161
        with self.assertRaisesRegex(ValueError, 'conflicts with a written attack'):
            self.write(document)

    def test_contract_rejects_unowned_or_split_staff_ranges(self):
        document = score()
        page, = document['pages']
        page['printedOctaveSpans'] = [staff_mark(0, 2)]
        with self.assertRaisesRegex(ValueError, 'conflicts with a written pitch'):
            self.write(document)

        page['printedOctaveSpans'] = [staff_mark(0, 1), staff_mark(1, 1)]
        with self.assertRaisesRegex(ValueError, 'overlap'):
            self.write(document)

        page['printedOctaveSpans'] = [staff_mark(0, 1)]
        page['printedMeasureContinuations'] = [2]
        page['score']['measures'] = [
            dict(top=.05, bottom=.2), dict(top=.05, bottom=.2), dict(top=.6, bottom=.8)]
        with self.assertRaisesRegex(ValueError, 'bounds'):
            self.write(document)

    def test_an_inferred_voice_closes_before_a_following_explicit_staff_range(self):
        document = score()
        document['pages'][0]['printedOctaveSpans'] = [staff_mark(1, 1)]
        root = self.write(document)
        candidate = root.findall('part/measure')[1]
        marks = [(direction.findtext('voice'), shift.get('type'))
                 for direction in candidate.findall('direction')
                 if (shift := direction.find('direction-type/octave-shift')) is not None]
        self.assertEqual([(None, 'down'), (None, 'stop')], [row for row in marks if row[0] is None])
        self.assertEqual('stop', marks[0][1])
        self.assertIsNotNone(marks[0][0])
        self.assertFalse(root.findall('part/measure')[2].findall('direction/direction-type/octave-shift'))
