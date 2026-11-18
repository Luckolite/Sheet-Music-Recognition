# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Engraved tuplet numerals are independent of the written rhythmic ratio."""
import copy
from pathlib import Path
import tempfile
import unittest
from xml.etree import ElementTree as ET

from sheet_music_recognition import beam_source
from sheet_music_recognition.musicxml import write_musicxml
from tests import test_musicxml_beams as fixture
from tests.test_musicxml_tuplet_parts import event


def document(mark=None):
    source = fixture.fixture(((0,), (1,), (2,)), (1, 1))
    source['notes'] = [dict(beamCount=1, augmentationDots=0, tupletDivisor=3,
                            tupletNormalNotes=2, followingRestBeats=0) for _ in range(3)]
    if mark is not None:
        source['beamSource']['groups'][0]['tupletMark'] = mark
    events = [dict(event(1 / 3, midi=60+i, start=i / 3), sourceNoteIndex=i)
              for i in range(3)]
    return dict(pages=[dict(measureBeats=[4], events=events, score=source)])


class EngravedTupletTests(unittest.TestCase):
    def test_numbered_unnumbered_and_unknown_keep_identical_notes_and_beams(self):
        expected = {
            None: {},
            'NUMBERED': {'show-number': 'actual'},
            'UNNUMBERED': {'show-number': 'none', 'bracket': 'no'},
        }
        snapshots = []
        with tempfile.TemporaryDirectory() as temp:
            for mark, attributes in expected.items():
                path = Path(temp) / 'output.musicxml'
                source = document(mark)
                original = copy.deepcopy(source)
                write_musicxml(source, path)
                self.assertEqual(original, source)
                root = ET.parse(path).getroot()
                notes = root.findall('.//note[pitch]')
                self.assertEqual([[(1, 'begin')], [(1, 'continue')], [(1, 'end')]],
                                 list(map(fixture.beam_values, notes)))
                self.assertEqual([('3', '2')] * 3,
                                 [(n.findtext('time-modification/actual-notes'),
                                   n.findtext('time-modification/normal-notes')) for n in notes])
                start, stop = root.findall('.//tuplet')
                self.assertEqual(dict(type='start', number='1', **attributes), start.attrib)
                self.assertEqual(dict(type='stop', number='1'), stop.attrib)
                for key in attributes:
                    del start.attrib[key]
                snapshots.append(ET.tostring(root))
        self.assertEqual([snapshots[0]] * 3, snapshots)

    def test_metadata_is_strict_and_old_guide_cannot_discard_it(self):
        literal = document('UNNUMBERED')['pages'][0]['score']['beamSource']
        with self.assertRaisesRegex(ValueError, 'cannot preserve'):
            beam_source.encode_source(literal, 3)
        for mark in (None, False, 'VISIBLE', 3):
            bad = copy.deepcopy(literal)
            bad['groups'][0]['tupletMark'] = mark
            with self.assertRaisesRegex(ValueError, 'Invalid engraved triplet group'):
                beam_source.checked(bad, 3)
        bad = copy.deepcopy(literal)
        bad['groups'][0]['bridges'][0] = 3
        with self.assertRaisesRegex(ValueError, 'Invalid engraved triplet group'):
            beam_source.checked(bad, 3)
        del literal['groups'][0]['tupletMark']
        self.assertEqual(literal, beam_source.decode_source(beam_source.encode_source(literal, 3), 3))

    def test_optional_mark_cannot_attach_to_ordinary_notes(self):
        source = document('NUMBERED')
        for note in source['pages'][0]['score']['notes']:
            note['tupletDivisor'] = note['tupletNormalNotes'] = 1
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'ordinary.musicxml'
            write_musicxml(source, path)
            self.assertEqual([], ET.parse(path).findall('.//tuplet'))
