# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0

import unittest

from sheet_music_recognition.printed_tab_capo import apply_capo


def evidence(source, page, payload):
    return dict(eventId=f'{source}:{page}:0', kind='UNRESOLVED_DIRECTION', scope='UNRESOLVED',
                staffIndex=0, staffCount=1, start=dict(measureIndex=0, quarterBeatOffset=0),
                end=dict(measureIndex=1, quarterBeatOffset=0), targetEventId=payload,
                evidence=[dict(sourceId=source, pageIndex=page)])


def page(physical, number, capo, profile='0=8'):
    return dict(sourcePage=physical, measureBeats=[4], events=[dict(sourceNoteIndex=0, midi=56)],
                score=dict(notes=[dict(measureIndex=0, staffIndex=0, staffCount=1,
                                       clefBottomDiatonic=30, staffStep=2,
                                       octaveShift=-1, writtenAccidental=2)],
                           expressiveEvents=[evidence('printed-tab-capo:v1', physical, f'{number}:{capo}'),
                                             evidence('printed-tab-capo-row:v1', physical, profile)]))


class PrintedCapoTest(unittest.TestCase):
    def test_capo_carries_and_explicit_zero_stops_it(self):
        pages = [page(3, 1, 3), page(4, 2, -2), page(5, 3, 0), page(6, 4, -2)]
        result = apply_capo(dict(pages=pages))['pages']
        self.assertEqual([p['events'][0]['midi'] for p in result], [58, 58, 56, 56])
        self.assertEqual([p['score'] for p in result], [p['score'] for p in pages])
        self.assertTrue(all(p['events'][0]['midi'] == 56 for p in pages))

    def test_ambiguity_conflict_and_page_gap_preserve_pitch(self):
        pages = [page(3, 1, 3, '0=7,-3=7'), page(5, 2, -2),
                 page(6, 3, 3, '0=1,-3=8'), page(7, 4, -1), page(8, 5, -2)]
        doc = dict(pages=pages)
        self.assertIs(apply_capo(doc), doc)
