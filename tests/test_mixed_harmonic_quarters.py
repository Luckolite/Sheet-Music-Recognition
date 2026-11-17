# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0

from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase
from xml.etree import ElementTree

from sheet_music_recognition.musicxml import write_musicxml


def mixed_bar():
    note = dict(measureIndex=0, staffIndex=0, staffCount=1, clefBottomDiatonic=30,
                stemDirection=-1, unbeamedDurationBeats=1, beamCount=0, writtenAccidental=2)
    touch = dict(staffStep=12, pageY=.36, writtenAccidental=1)
    notes = [
        dict(note, staffStep=7, pageY=.46, positionInMeasure=.25),
        dict(note, staffStep=10, pageY=.40, positionInMeasure=.25),
        dict(note, staffStep=1, pageY=.58, positionInMeasure=.72,
             harmonicSemitones=28, openStringTouch=touch, writtenAccidental=0),
        dict(note, staffStep=12, pageY=.36, positionInMeasure=.72, writtenAccidental=1),
    ]
    event = dict(measureIndex=0, staffIndex=0, staffCount=1, durationBeats=1,
                 mixedHarmonicQuarter=True, tiedFromPrevious=False, clefInferred=False,
                 durationFallback=False)
    events = [
        dict(event, sourceNoteIndex=0, startBeat=0, midi=88, writtenMidi=76, harmonicTouchMidi=76,
             openStringMidi=69, x=450, y=138),
        dict(event, sourceNoteIndex=1, startBeat=0, midi=100, writtenMidi=81, harmonicTouchMidi=81,
             openStringMidi=76, x=450, y=120),
        dict(event, sourceNoteIndex=2, startBeat=1, midi=93, writtenMidi=65, harmonicTouchMidi=69,
             artificialHarmonicThird=True, x=544, y=174),
        dict(event, sourceNoteIndex=3, startBeat=1, midi=104, writtenMidi=85, harmonicTouchMidi=85,
             openStringMidi=76, x=544, y=108),
    ]
    return dict(width=1000, height=300, measureBeats=[2], totalBeats=2,
                printedAccidentals=[dict(noteIndex=2, accidental=0), dict(noteIndex=3, accidental=1)],
                score=dict(notes=notes, measures=[dict(left=.4, right=.6, top=.3, bottom=.7)],
                           meterChanges=[dict(measureIndex=0, numerator=2, denominator=4)]),
                events=events)


class PrintedMixedHarmonicQuartersTest(TestCase):
    def test_two_printed_downstem_attacks_and_three_open_zero_marks(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / 'mixed.musicxml'
            write_musicxml(dict(pages=[mixed_bar()]), path)
            notes = ElementTree.parse(path).findall('./part/measure/note')
        self.assertEqual(len(notes), 9)
        self.assertEqual(sum(n.find('chord') is None for n in notes), 2)
        self.assertTrue(all(n.findtext('duration') == '10080' for n in notes))
        visible = [n for n in notes if n.find('cue') is not None]
        self.assertEqual([n.findtext('stem') for n in visible], ['down'] * 5)
        self.assertEqual([n.findtext('accidental') for n in visible], [None, None, 'natural', None, 'sharp'])
        marks = [n.find('notations/technical/fingering') for n in visible]
        self.assertEqual([(n.text, n.get('placement')) for n in marks if n is not None],
                         [('0', 'below'), ('0', 'above'), ('0', 'above')])

    def test_abstains_from_incomplete_or_mismatched_harmonic_owners(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / 'mixed.musicxml'
            for index, field, value in ((1, 'openStringMidi', 69), (1, 'durationBeats', .5),
                                        (2, 'startBeat', .75), (3, 'mixedHarmonicQuarter', False)):
                with self.subTest(field=field):
                    source = mixed_bar()
                    source['events'][index][field] = value
                    with self.assertRaises(ValueError):
                        write_musicxml(dict(pages=[source]), path)
