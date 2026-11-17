# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
from xml.etree import ElementTree

from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase

from sheet_music_recognition.musicxml import write_musicxml


def page():
    note = dict(measureIndex=0, staffIndex=0, staffCount=1, clefBottomDiatonic=30,
                positionInMeasure=.5, writtenAccidental=2, unbeamedDurationBeats=1,
                beamCount=0, stemDirection=1)
    touch = dict(staffStep=3, pageY=.4, writtenAccidental=2)
    notes = [dict(note, staffStep=-4, pageY=.6, harmonicSemitones=28, openStringTouch=touch),
             dict(note, staffStep=3, pageY=.4)]
    event = dict(measureIndex=0, staffIndex=0, staffCount=1, startBeat=0, durationBeats=1,
                 x=500, durationFallback=False, tiedFromPrevious=False, clefInferred=False)
    events = [dict(event, sourceNoteIndex=0, midi=85, writtenMidi=57,
                   harmonicTouchMidi=61, artificialHarmonicThird=True, y=180),
              dict(event, sourceNoteIndex=1, midi=81, writtenMidi=69,
                   harmonicTouchMidi=69, openStringMidi=62, y=120)]
    return dict(width=1000, height=300, measureBeats=[1], totalBeats=1,
                score=dict(notes=notes, measures=[dict(left=.4, right=.6, top=.3, bottom=.7,
                                                       cadenceQuarterBeats=1)],
                           meterChanges=[dict(measureIndex=0, numerator=2, denominator=4)],
                           keyChanges=[dict(measureIndex=0, fifths=3)]), events=events)


class CompoundTerminalHarmonicsTest(TestCase):
    def test_two_sounding_carriers_and_three_written_heads(self):
        with TemporaryDirectory() as temp:
            path = Path(temp) / 'harmonics.musicxml'
            write_musicxml(dict(pages=[page()]), path)
            notes = ElementTree.parse(path).findall('./part/measure/note')
        self.assertEqual(len(notes), 5)
        self.assertEqual(sum(n.find('chord') is None for n in notes), 1)
        self.assertTrue(all(n.findtext('duration') == '10080' for n in notes))
        self.assertEqual({n.findtext('pitch/step') for n in notes if n.get('print-object') == 'no'}, {'A', 'C'})
        self.assertEqual(sum(n.find('cue') is not None for n in notes), 3)
        self.assertEqual({n.find('notehead').get('filled') for n in notes if n.find('notehead') is not None},
                         {'no', 'yes'})
        roles = [tuple(h.tag for h in n.find('notations/technical/harmonic')) for n in notes]
        self.assertEqual(set(roles), {
            ('artificial', 'sounding-pitch'), ('artificial', 'base-pitch'),
            ('artificial', 'touching-pitch'), ('natural', 'sounding-pitch'), ('natural', 'touching-pitch'),
        })

    def test_rejects_inconsistent_open_string_event(self):
        controls = [('octaveShift', 1), ('y', 150), ('startBeat', .25)]
        with TemporaryDirectory() as temp:
            for field, value in controls:
                with self.subTest(field=field):
                    source = page()
                    source['events'][1][field] = value
                    with self.assertRaisesRegex(
                            ValueError, '(?:Compound harmonic|Invalid compound|Compound open string)'):
                        write_musicxml(dict(pages=[source]), Path(temp) / 'invalid.musicxml')
