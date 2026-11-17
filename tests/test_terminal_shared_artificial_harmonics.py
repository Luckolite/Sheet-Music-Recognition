# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
from copy import deepcopy
from pathlib import Path
from tempfile import TemporaryDirectory
from xml.etree import ElementTree as ET
import unittest

from sheet_music_recognition import musicxml


def final_artificial_pair():
    width, height = 1600, 1400
    staff = dict(measureIndex=1, staffIndex=0, staffCount=1)
    defaults = dict(staff, positionInMeasure=.4, clefBottomDiatonic=30, unbeamedDurationBeats=1,
                    beamCount=0, stemDirection=1)
    lower = dict(defaults, staffStep=-4, pageY=.85, harmonicSemitones=28, writtenAccidental=-1)
    upper = dict(defaults, staffStep=0, pageY=.83, harmonicSemitones=24, writtenAccidental=2)
    first = dict(staff, x=1170., startBeat=2., durationBeats=1.)
    events = [
        dict(first, y=.85*height, sourceNoteIndex=0, writtenMidi=56, harmonicTouchMidi=60,
             midi=84, artificialHarmonicThird=True),
        dict(first, y=.83*height, sourceNoteIndex=1, writtenMidi=64, harmonicTouchMidi=69,
             midi=88, artificialHarmonicFourth=True),
    ]
    return dict(width=width, height=height, measureBeats=[2., 1.], totalBeats=3.,
                score=dict(notes=[lower, upper], rests=[],
                           measures=[dict(left=.1, right=.6, top=.75, bottom=.9),
                                     dict(left=.61, right=.9, top=.75, bottom=.9,
                                          cadenceQuarterBeats=1)]), events=events,
                printedAccidentals=[dict(noteIndex=0, accidental=-1)])


class TerminalSharedArtificialHarmonicsTest(unittest.TestCase):
    def test_final_quarter_writes_four_heads_and_two_sounding_pitches(self):
        with TemporaryDirectory() as tmp:
            location = Path(tmp) / 'closing.musicxml'
            musicxml.write_musicxml(dict(initialMeter=[2, 4], pages=[final_artificial_pair()]), location)
            tail = ET.parse(location).findall('part/measure')[-1]
        visible = [n for n in tail.findall('note') if n.get('print-object') != 'no']
        hidden = tail.findall("note[@print-object='no']")
        self.assertEqual(4, len(visible))
        self.assertEqual(2, len(hidden))
        self.assertEqual(2, len(tail.findall("note/notehead[@filled='no']")))
        self.assertEqual(2, len(tail.findall('note/notations/technical/harmonic/base-pitch')))
        self.assertEqual(2, len(tail.findall('note/notations/technical/harmonic/touching-pitch')))
        self.assertEqual({'up'}, {note.findtext('stem') for note in visible})
        self.assertEqual({'quarter'}, {note.findtext('type') for note in visible})
        self.assertEqual({'1'}, {note.findtext('voice') for note in visible})
        self.assertFalse(tail.findall('note/notations/technical/harmonic/natural'))
        self.assertEqual(['flat'], [node.text for node in tail.findall('note/accidental')])
        self.assertFalse(tail.findall('note/tie'))

    def test_terminal_pairs_require_shared_column_and_quarter_capacity(self):
        original = final_artificial_pair()
        for label, field, value in (('offset head', 'x', 1190.),
                                    ('long note', 'durationBeats', 2.),
                                    ('open partner', 'openStringMidi', 62),
                                    ('incorrect owner', 'sourceNoteIndex', 0)):
            with self.subTest(label=label):
                page = deepcopy(original)
                page['events'][1][field] = value
                self.assertEqual({}, musicxml._terminal_shared_artificial_harmonics(page, page['score']['notes']))
                with self.assertRaises(ValueError):
                    musicxml._compound_open_harmonics(page, page['score']['notes'])


if __name__ == '__main__':
    unittest.main()
