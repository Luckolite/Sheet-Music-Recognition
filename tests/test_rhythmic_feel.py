# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from sheet_music_recognition.midi import performance_events


def _command(bar, feel, caption):
    return dict(kind='UNRESOLVED_DIRECTION', scope='SCORE',
                start=dict(measureIndex=bar, quarterBeatOffset=0),
                qualifierText='eighth-feel-v1:' + feel,
                evidence=[dict(sourceId='printed-eighth-feel', printedText=caption)])


def _score(changes):
    events, notes = [], []
    for bar, beat, length, options in (
            (0, 0., .5, {}), (0, .5, .5, {}),
            (1, 1., .5, {}), (1, 1.5, .5, {}),
            (2, 2.5, .5, {'leadingRestBeats': .5}),
            (3, 3., 1/3, {'tupletDivisor': 3}),
            (3, 3 + 1/3, 1/3, {'tupletDivisor': 3}),
            (3, 3 + 2/3, 1/3, {'tupletDivisor': 3})):
        source = dict(measureIndex=bar, staffIndex=0, staffCount=1, articulations=0)
        events.append(dict(source, midi=60+len(notes), startBeat=beat, durationBeats=length,
                           sourceNoteIndex=len(notes), tiedFromPrevious=False))
        notes.append(dict(source, **options))
    page = dict(events=events, measureBeats=[1.] * 4, totalBeats=4.,
                score=dict(notes=notes, tempoChanges=[], expressiveEvents=changes))
    return dict(pages=[page])


class RhythmicFeelTests(unittest.TestCase):
    def test_midi_swing_respects_straight_returns_rests_and_tuplets(self):
        doc = _score([_command(0, 'swing', 'Rock Swing'),
                      _command(1, 'straight', 'Play straight'),
                      _command(2, 'swing', 'Back to swing')])
        before = copy.deepcopy(doc)
        _, messages, _ = performance_events(doc)
        attacks = [tick for tick, _, message in messages if message[0] & 0xf0 == 0x90]
        self.assertEqual([0, 320, 480, 720, 1280, 1440, 1600, 1760], attacks)
        self.assertEqual(before, doc)

    def test_unrelated_text_leaves_the_eighths_straight(self):
        doc = _score([_command(0, 'swing', 'The Swing Eighths Story')])
        _, messages, _ = performance_events(doc)
        attacks = [tick for tick, _, message in messages if message[0] & 0xf0 == 0x90]
        self.assertEqual([0, 240, 480, 720, 1200, 1440, 1600, 1760], attacks)
