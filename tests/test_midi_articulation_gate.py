# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import copy
import unittest

from sheet_music_recognition.midi import performance_events


def document(flags, tied=False):
    owner = dict(measureIndex=0, staffIndex=0, staffCount=1)
    events = [dict(owner, midi=60, startBeat=0., durationBeats=1., tiedFromPrevious=False)]
    notes = [dict(owner, articulations=flags)]
    if tied:
        events.append(dict(events[0], startBeat=1., tiedFromPrevious=True))
        notes.append(dict(owner, articulations=0))
    return dict(pages=[dict(events=events, measureBeats=[4.], totalBeats=4.,
                           score=dict(notes=notes, tempoChanges=[]))])


def releases(doc):
    _, messages, _ = performance_events(doc)
    return [tick for tick, _, message in messages if message[0] & 0xf0 == 0x80]


class MidiArticulationGateTests(unittest.TestCase):
    def test_recognized_short_marks_gate_only_sounding_duration(self):
        for flags, release in ((0, 480), (8, 480), (4, 240), (12, 360), (28, 134)):
            with self.subTest(flags=flags):
                doc = document(flags)
                before = copy.deepcopy(doc)
                self.assertEqual([release], releases(doc))
                self.assertEqual(before, doc)

    def test_gate_is_applied_after_tie_ownership(self):
        self.assertEqual([480], releases(document(4, tied=True)))

    def test_short_release_keeps_original_track_extent(self):
        doc = document(4)
        doc['pages'][0]['events'][0]['startBeat'] = 3.5
        _, _, end_tick = performance_events(doc)
        self.assertEqual(2160, end_tick)

    def test_unbound_event_does_not_borrow_or_supply_a_gate(self):
        doc = document(4)
        doc['pages'][0]['events'][0].update(sourceNoteIndex=3, _articulationGate=.28)
        self.assertEqual([480], releases(doc))
