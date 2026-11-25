# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from sheet_music_recognition.fingering_export import retained
from sheet_music_recognition.musicxml import write_musicxml


def crossing_page():
    identity = dict(measureIndex=0, staffIndex=0, staffCount=1)
    return dict(measureBeats=[4, 4], score=dict(notes=[identity]),
                printedFingerings=[dict(noteIndex=0, digit=0, placement='BELOW')],
                events=[dict(identity, sourceNoteIndex=0, startBeat=3, durationBeats=2, midi=69)])


class PrintedFingeringTests(unittest.TestCase):
    def test_fingering_appears_on_first_fragment_only(self):
        page = crossing_page()
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'score.musicxml'
            write_musicxml(dict(pages=[page]), path)
            first, second = ET.parse(path).getroot().findall('part/measure')
        self.assertEqual({0: (('0', 'below'),)}, retained(page))
        self.assertIsNotNone(first.find('note[tie]'))
        fingerings = first.findall('note/notations/technical/fingering')
        self.assertEqual([('0', 'below')], [(f.text, f.get('placement')) for f in fingerings])
        self.assertFalse(second.findall('note/notations/technical/fingering'))

    def test_invalid_or_changed_owner_is_rejected(self):
        for case, mutate in {
            'duplicate event': lambda page: page['events'].append(dict(page['events'][0])),
            'foreign owner': lambda page: page['printedFingerings'][0].update(noteIndex=3),
            'cue': lambda page: page['score']['notes'][0].update(visualSize='CUE'),
            'invalid digit': lambda page: page['printedFingerings'][0].update(digit=True),
            'invalid placement': lambda page: page['printedFingerings'][0].update(placement='RIGHT'),
            'missing owner': lambda page: page['events'][0].pop('sourceNoteIndex'),
            'wrong measure': lambda page: page['events'][0].update(measureIndex=1),
        }.items():
            page = crossing_page()
            mutate(page)
            with self.subTest(case=case), self.assertRaises(ValueError):
                retained(page)


    def test_ordinary_fingering_and_parenthesized_endpoint_share_one_side(self):
        page = crossing_page()
        first = page['events'][0]
        first.update(startBeat=0, durationBeats=1)
        page['score']['notes'].append(dict(page['score']['notes'][0]))
        page['events'].append(dict(first, sourceNoteIndex=1, startBeat=1, midi=71))
        page['printedFingerings'] = [
            dict(noteIndex=0, digit=3, placement='ABOVE'),
            dict(noteIndex=0, digit=2, placement='ABOVE'),
            dict(noteIndex=1, digit=3, placement='ABOVE'),
        ]
        group = dict(startNoteIndex=0, endNoteIndex=1, placement='ABOVE', startDigit=3)
        page['printedFingeringGroups'] = [group]
        with tempfile.TemporaryDirectory() as directory:
            xml = Path(directory) / 'score.musicxml'
            write_musicxml(dict(pages=[page]), xml)
            marks = ET.parse(xml).getroot().findall('part/measure/note/notations/technical/fingering')
        self.assertEqual([('(3', 'above'), ('2', 'above'), ('3)', 'above')],
                         [(mark.text, mark.get('placement')) for mark in marks])
        group.pop('startDigit')
        with self.assertRaisesRegex(ValueError, 'endpoint digit'):
            retained(page)
