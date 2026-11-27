# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from sheet_music_recognition.navigation import _auxiliary_staff_continuations, _second_only_owners


def auxiliary_mark(staff, page, opening=False):
    kind = 'printed-auxiliary-opening:v1' if opening else 'printed-auxiliary-second-pass:v1'
    return dict(eventId=f'{kind}:{page}:0:{staff}', kind='UNRESOLVED_DIRECTION', scope='VOICE',
                start=dict(measureIndex=0, quarterBeatOffset=0),
                end=dict(measureIndex=1, quarterBeatOffset=0),
                staffIndex=staff, staffCount=3,
                evidence=[dict(sourceId=kind, pageIndex=page, visualX=.1,
                               staffIndex=staff, staffCount=3,
                               printedText='' if opening else '2nd time only')])


def source_page(index, boundary, kind):
    roster = [dict(measureIndex=0, staffIndex=staff, staffCount=3) for staff in range(3)]
    events = [dict(n, midi=60 + index*3 + staff, startBeat=0, durationBeats=1,
                   sourceNoteIndex=staff, tiedFromPrevious=False)
              for staff, n in enumerate(roster)]
    origin = dict(documentSha256='a'*64, rasterSha256='b'*64, physicalPageCount=2,
                  physicalPageIndex=index, width=800, height=500)
    score = dict(notes=roster, firstMeasureNumber=10 + index,
                 playbackDirections=[dict(measureBoundary=boundary, kind=kind)],
                 clockContextSource=dict(origin=origin),
                 expressiveEvents=[auxiliary_mark(staff, index, opening=index == 1)
                                   for staff in range(2)])
    return dict(sourcePage=index+1, score=score, events=events, measureBeats=[4], totalBeats=4)


class AuxiliaryPassTest(unittest.TestCase):
    def test_only_a_complete_bound_cohort_carries_to_the_following_page(self):
        pages = [source_page(0, 0, 4), source_page(1, 1, 5)]
        for case in ('bound', 'unbound', 'incomplete'):
            with self.subTest(case=case):
                scenario = copy.deepcopy(pages)
                if case == 'unbound':
                    scenario[1]['score']['clockContextSource']['origin']['documentSha256'] = ''
                elif case == 'incomplete':
                    scenario[0]['score']['expressiveEvents'].pop()
                resolved = _auxiliary_staff_continuations(scenario)
                self.assertEqual({0, 1} if case == 'bound' else set(),
                                 _second_only_owners(resolved[1]))
                self.assertEqual(2, len(scenario[1]['score']['expressiveEvents']))
