# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0

from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase
from xml.etree import ElementTree

from sheet_music_recognition.musicxml import write_musicxml


def stopped_and_open(stem):
    common = dict(measureIndex=0, staffIndex=0, staffCount=1, clefBottomDiatonic=30,
                  stemDirection=stem, unbeamedDurationBeats=1, beamCount=0, writtenAccidental=2,
                  positionInMeasure=.38)
    natural = dict(common, staffStep=6, pageY=.42)
    note = dict(common, staffStep=0, pageY=.6, harmonicSemitones=24,
                openStringTouch=dict(staffStep=6, pageY=.42, writtenAccidental=2))
    beat = dict(measureIndex=0, staffIndex=0, staffCount=1, startBeat=0, durationBeats=1, x=476)
    events = [
        dict(beat, sourceNoteIndex=0, y=180, midi=88, writtenMidi=64, harmonicTouchMidi=69,
             artificialHarmonicFourth=True, compoundHarmonicFourth=True),
        dict(beat, sourceNoteIndex=1, y=126, midi=93, writtenMidi=74, harmonicTouchMidi=74,
             openStringMidi=69),
    ]
    return dict(width=1000, height=300, measureBeats=[1], totalBeats=1,
                score=dict(notes=[note, natural],
                           measures=[dict(left=.4, right=.6, top=.3, bottom=.7)]),
                events=events)


class PrintedCompoundFourthHarmonicTest(TestCase):
    def test_two_sounding_pitches_and_three_printed_heads_share_one_quarter(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / 'compound.musicxml'
            for stem in (-1, 1):
                with self.subTest(stem=stem):
                    write_musicxml(dict(pages=[stopped_and_open(stem)]), path)
                    notes = ElementTree.parse(path).findall('./part/measure/note')
                    self.assertEqual(len(notes), 5)
                    self.assertEqual(sum(n.find('chord') is None for n in notes), 1)
                    self.assertTrue(all(n.findtext('duration') == '10080' for n in notes))
                    playing = [n for n in notes if n.get('print-object') == 'no']
                    self.assertEqual([(n.findtext('pitch/step'), n.findtext('pitch/octave'))
                                      for n in playing], [('E', '6'), ('A', '6')])
                    printed = [n for n in notes if n.find('cue') is not None]
                    self.assertEqual([n.findtext('stem') for n in printed],
                                     ['up' if stem > 0 else 'down'] * 3)
                    self.assertEqual([n.findtext('notehead') for n in printed], [None, 'diamond', 'diamond'])
                    self.assertEqual([n.find('notehead').get('filled') for n in printed[1:]], ['no', 'no'])
                    self.assertEqual([n.findtext('notations/technical/fingering') for n in printed],
                                     [None, None, '0'])

    def test_refuses_split_shaft_wrong_open_string_and_mismatched_onset(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / 'compound.musicxml'
            for branch, field, value in (('notes', 'stemDirection', -1), ('events', 'startBeat', .25),
                                         ('events', 'openStringMidi', 62), ('events', 'x', 483)):
                with self.subTest(field=field):
                    doc = stopped_and_open(1)
                    owners = doc['score'][branch] if branch == 'notes' else doc[branch]
                    owners[1][field] = value
                    with self.assertRaises(ValueError):
                        write_musicxml(dict(pages=[doc]), path)
