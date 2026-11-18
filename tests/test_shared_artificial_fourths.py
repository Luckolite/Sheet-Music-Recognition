# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
from copy import deepcopy
from pathlib import Path
from tempfile import TemporaryDirectory
from xml.etree import ElementTree as ET
import unittest

from sheet_music_recognition import musicxml

def shared_fourths():
    width = height = 1600
    events = []
    notes = []
    for i, (x, y, step, written) in enumerate(((296., 448., 6, 73), (296.6, 410., 11, 82))):
        geo = dict(measureIndex=0, staffIndex=0, staffCount=1)
        events.append(dict(geo, x=x, y=y, midi=written+24, writtenMidi=written,
                           harmonicTouchMidi=written+5, artificialHarmonicFourth=True,
                           startBeat=1., durationBeats=1., sourceNoteIndex=i))
        notes.append(dict(geo, staffStep=step, pageY=y/height, positionInMeasure=.44+i*.001,
                          clefBottomDiatonic=30, beamCount=0, unbeamedDurationBeats=1,
                          stemDirection=-1, harmonicSemitones=24))
    return dict(width=width, height=height, measureBeats=[2.], totalBeats=2.,
                score=dict(notes=notes, measures=[dict(left=.1, right=.4, top=.21, bottom=.32)]),
                events=events)

class SharedFourthTests(unittest.TestCase):
    def test_shared_artificial_downshaft(self):
        page = shared_fourths()
        with TemporaryDirectory() as d:
            score = Path(d)/'test.musicxml'
            musicxml.write_musicxml(dict(inputName='Synthetic', initialMeter=[2,4], pages=[page]), score)
            notes=ET.parse(score).findall('part/measure/note')
        sounding = [n for n in notes if n.get('print-object')=='no' and n.find('pitch') is not None]
        visible = [n for n in notes if n.find('cue') is not None]
        self.assertEqual(2,len(sounding))
        self.assertEqual(4,len(visible))
        self.assertEqual(['down']*4,[n.findtext('stem') for n in visible])
        self.assertEqual(['no']*2,[n.find('notehead').get('filled') for n in visible if n.find('notehead') is not None])
        self.assertEqual(['10080']*6,[n.findtext('duration') for n in sounding+visible])
        self.assertIsNone(sounding[0].find('chord'))
        self.assertIsNotNone(sounding[1].find('chord'))

    def test_source_closed_terminal_quarter_keeps_shared_downshaft(self):
        page = shared_fourths()
        page['measureBeats'] = [1.]
        page['totalBeats'] = 1.
        page['score']['measures'][0]['cadenceQuarterBeats'] = 1
        for event in page['events']:
            event['startBeat'] = 0.
        with TemporaryDirectory() as d:
            score = Path(d) / 'terminal.musicxml'
            musicxml.write_musicxml(dict(inputName='Synthetic', initialMeter=[2, 4], pages=[page]), score)
            visible = [n for n in ET.parse(score).findall('part/measure/note') if n.find('cue') is not None]
            self.assertEqual(['down'] * 4, [n.findtext('stem') for n in visible])
            self.assertEqual(['quarter'] * 4, [n.findtext('type') for n in visible])
        source = page['score']['notes']
        for missing in ('cadence', 'closure', 'onset'):
            case = deepcopy(page)
            if missing == 'cadence':
                del case['score']['measures'][0]['cadenceQuarterBeats']
            elif missing == 'closure':
                case['measureBeats'].append(1.)
                case['score']['measures'].append(dict(case['score']['measures'][0]))
            else:
                for event in case['events']:
                    event['startBeat'] = 1.
            self.assertEqual({}, musicxml._shared_artificial_fourths(case, source))

    def test_incompatible_owner_remains_ineligible(self):
        page = shared_fourths()
        for case in ('shaft', 'owner', 'stem', 'duration', 'third-member'):
            with self.subTest(case=case):
                alternative = deepcopy(page)
                if case == 'shaft':
                    alternative['events'][1]['x'] += 5
                elif case == 'owner':
                    alternative['events'][1]['sourceNoteIndex'] = 0
                elif case == 'stem':
                    alternative['score']['notes'][1]['stemDirection'] = 1
                elif case == 'duration':
                    alternative['events'][1]['durationBeats'] = .5
                else:
                    fake = dict(alternative['events'][0], sourceNoteIndex=2,
                                artificialHarmonicFourth=False, midi=85)
                    alternative['events'].append(fake)
                self.assertEqual({},musicxml._shared_artificial_fourths(alternative, alternative['score']['notes']))

if __name__=='__main__':
    unittest.main()
