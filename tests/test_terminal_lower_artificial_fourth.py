# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
from copy import deepcopy
from pathlib import Path
from tempfile import TemporaryDirectory
from xml.etree import ElementTree as ET
import unittest

from sheet_music_recognition import musicxml


def terminal_open_fourth():
    width = height = 1600
    staff = dict(measureIndex=1, staffIndex=0, staffCount=1)
    base = dict(staff, staffStep=4, pageY=.75, positionInMeasure=.4,
                clefBottomDiatonic=30, unbeamedDurationBeats=1,
                beamCount=0, stemDirection=-1, writtenAccidental=2)
    lower = dict(base, staffStep=2, pageY=.76)
    stopped = dict(base, harmonicSemitones=24,
                   openStringTouch=dict(staffStep=2, pageY=.76, writtenAccidental=2))
    events = [
        dict(staff, x=1200., y=.75*height, startBeat=2., durationBeats=1., sourceNoteIndex=0,
             writtenMidi=70, harmonicTouchMidi=75, midi=94, artificialHarmonicFourth=True,
             compoundHarmonicFourth=True),
        dict(staff, x=1200., y=.76*height, startBeat=2., durationBeats=1., sourceNoteIndex=1,
             writtenMidi=67, harmonicTouchMidi=67, openStringMidi=62, midi=86),
    ]
    return dict(width=width, height=height, measureBeats=[2., 1.], totalBeats=3.,
                score=dict(notes=[stopped, lower], rests=[],
                           measures=[dict(left=.1, right=.6, top=.68, bottom=.83),
                                     dict(left=.61, right=.9, top=.68, bottom=.83,
                                          cadenceQuarterBeats=1)]), events=events)


class TerminalLowerFourthTest(unittest.TestCase):
    def test_closed_quarter_writes_lower_zero_and_both_playback_pitches(self):
        with TemporaryDirectory() as wd:
            out = Path(wd) / 'terminal.musicxml'
            musicxml.write_musicxml(dict(inputName='Synthetic', initialMeter=[2,4],
                                         pages=[terminal_open_fourth()]), out)
            written = ET.parse(out).findall('part/measure')[-1]
        self.assertEqual(2, len(written.findall("note[@print-object='no']/pitch")))
        self.assertEqual(2, len(written.findall('note/notations/technical/harmonic/touching-pitch')))
        self.assertEqual(1, len(written.findall("note/notations/technical/fingering[@placement='below']")))
        self.assertEqual(['0'], [f.text for f in written.findall('note/notations/technical/fingering')])
        self.assertFalse(written.findall('note/tie'))

    def test_terminal_owner_and_quarter_capacity_are_required(self):
        row = terminal_open_fourth()
        for mode in ('duration', 'shaft', 'ownership'):
            with self.subTest(mode=mode):
                bad = deepcopy(row)
                if mode == 'duration':
                    bad['measureBeats'][1] = 2
                elif mode == 'shaft':
                    for n in bad['score']['notes']:
                        n['stemDirection'] = 1
                else:
                    bad['events'][1]['sourceNoteIndex'] = 0
                with self.assertRaises(ValueError):
                    musicxml._compound_open_harmonics(bad, bad['score']['notes'])


if __name__ == '__main__':
    unittest.main()
