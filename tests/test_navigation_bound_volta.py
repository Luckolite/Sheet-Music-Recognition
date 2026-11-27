# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Document-bound volta resolution through the real Java bridge and MIDI writer."""

import copy
import tempfile
import unittest
from pathlib import Path

from sheet_music_recognition.midi import write_midi
from sheet_music_recognition.navigation import project_navigation
from test_navigation_midi import note, note_intervals


def mark(prefix, page, start, end=None, text=''):
    kind = 'ending' if prefix.startswith('printed-ending') else 'repeat'
    event_id = f'unresolved-printed-{kind}:{page}:{start}'
    return dict(eventId=event_id, kind='UNRESOLVED_DIRECTION', scope='UNRESOLVED',
                staffIndex=0, staffCount=2, targetEventId=None, strength='UNSPECIFIED', qualifierText=text,
                start=dict(measureIndex=start, quarterBeatOffset=0),
                end=None if end is None else dict(measureIndex=end, quarterBeatOffset=0),
                evidence=[dict(sourceId=prefix+event_id, pageIndex=page, visualX=.3,
                               staffIndex=0, staffCount=2, printedText=text)])


def page(source_index, first, marks):
    return dict(sourcePage=source_index+1, measureBeats=[4]*4, totalBeats=16,
                events=[note(i*4, 60+source_index*4+i, 4) for i in range(4)],
                score=dict(firstMeasureNumber=first, playbackDirections=[], expressiveEvents=marks,
                           clockContextSource=dict(origin=dict(
                               physicalPageIndex=source_index, physicalPageCount=3,
                               documentSha256='a'*64, rasterSha256='b'*64, width=10, height=10))))


class NavigationBoundVoltaTest(unittest.TestCase):
    def score(self):
        return dict(pages=[
            page(0, 1, [mark('printed-repeat-start:v1:', 0, 1)]),
            page(1, 3, [mark('printed-repeat-end:v1:', 1, 1),
                        mark('printed-ending-span:v1:false:false:', 1, 0, 1, '1.'),
                        mark('printed-ending-span:v1:true:false:', 1, 1, 2, '2.')])])

    def test_verified_adjacent_pages_play_both_endings_once(self):
        score = self.score()
        projection = project_navigation(score)
        self.assertEqual([], projection['navigationDiagnostics'])
        self.assertEqual([0, 1, 2, 3, 4, 1, 2, 3, 5, 6, 7],
                         [row['start']['measureIndex'] for row in projection['navigationOccurrences']])
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'voltas.mid'
            write_midi(score, output)
            self.assertEqual([60, 61, 62, 63, 64, 61, 62, 63, 65, 66, 67],
                             [pitch for pitch, _, _ in note_intervals(output.read_bytes())])

    def test_unbound_nonadjacent_mismatched_and_reset_sources_remain_linear(self):
        for control in ('unbound', 'physical gap', 'other document', 'reset'):
            with self.subTest(control=control):
                score = copy.deepcopy(self.score())
                end = score['pages'][1]
                origin = end['score']['clockContextSource']['origin']
                if control == 'unbound':
                    origin['rasterSha256'] = ''
                elif control == 'physical gap':
                    end['sourcePage'] = 3
                    origin['physicalPageIndex'] = 2
                elif control == 'other document':
                    origin['documentSha256'] = 'c'*64
                else:
                    end['score']['firstMeasureNumber'] = 1
                self.assertFalse(project_navigation(score).get('navigationOccurrences'))
