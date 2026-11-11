# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Additive route fixtures reach the navigation branch; earlier empty-route fixtures retained."""
import unittest
from unittest.mock import patch
from test_continuous_slide_context import document, occurrence
from sheet_music_recognition.midi import performed_document, performance_events
from sheet_music_recognition.slide_performance import prepare_slides, resolve_slides
from sheet_music_recognition.navigation import _project_one, project_navigation


class ContinuousSlideNavigationQualifiedTests(unittest.TestCase):
    def project(self, route):
        d=document()
        d['pages'][0]['score']['playbackDirections']=[dict(measureBoundary=0,kind=4)]
        with patch('sheet_music_recognition.navigation._route',return_value=dict(complete=True,diagnostics=[],occurrences=route)):
            return resolve_slides(_project_one(prepare_slides(d),120))

    def test_reached_jump_into_target_abstains(self):
        d=self.project([occurrence('jump',1,2,0.)])
        self.assertFalse(any(n.get('_continuousSlide')for n in d['pages'][0]['events']))

    def test_reached_repeated_route_has_two_original_attacks(self):
        d=self.project([occurrence('a',0,1,0.),occurrence('b',1,2,3.),
                        occurrence('c',0,1,6.),occurrence('d',1,2,9.)])
        targets=[n for n in d['pages'][0]['events']if n.get('_continuousSlide')]
        self.assertEqual([1.,7.],[n['startBeat']for n in targets])
        self.assertEqual([5.,5.],[n['durationBeats']for n in targets])

    def test_actual_java_repeat_route_preserves_two_whole_targets(self):
        d=document()
        d['pages'][0]['score']['playbackDirections']=[dict(measureBoundary=0,kind=4),dict(measureBoundary=2,kind=5)]
        played=resolve_slides(project_navigation(prepare_slides(d),120))
        targets=[n for n in played['pages'][0]['events']if n.get('_continuousSlide')]
        self.assertEqual([1.,7.],[n['startBeat']for n in targets])
        self.assertEqual([5.,5.],[n['durationBeats']for n in targets])

    def test_linear_and_repeating_arrangements_keep_their_own_slide_visits(self):
        linear = document()['pages'][0]
        repeated = document()['pages'][0]
        for page in (linear, repeated):
            page['score']['firstMeasureNumber'] = 1
        repeated['score']['playbackDirections'] = [
            dict(measureBoundary=0, kind=4), dict(measureBoundary=2, kind=5)]
        for pages, expected_starts in (([linear, repeated], [[1.], [1., 7.]]),
                                       ([repeated, linear], [[1., 7.], [1.]])):
            with self.subTest(expected_starts=expected_starts):
                source = dict(pages=pages)
                played, _, _ = performed_document(source)
                self.assertEqual(expected_starts, [
                    [note['startBeat'] for note in page['events'] if note.get('_continuousSlide')]
                    for page in played['pages']])
                _, messages, _ = performance_events(source)
                self.assertEqual(3, sum(message[0] & 0xf0 == 0x90 and message[1] == 74
                                        for _, _, message in messages))
                self.assertTrue(any(message[0] & 0xf0 == 0xe0 for _, _, message in messages))
