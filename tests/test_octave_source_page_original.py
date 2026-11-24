# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import unittest
import xml.etree.ElementTree as ET

from sheet_music_recognition.octave_shift_export import apply


def scene(pages, notes):
    root = ET.Element("score-partwise")
    part = ET.SubElement(root, "part")
    measures = [ET.SubElement(part, "measure", number=str(i + 1)) for i in range(len(pages))]
    owners = {}
    for index, voice, shift in notes:
        note = ET.SubElement(measures[index], "note")
        ET.SubElement(note, "voice").text = str(voice)
        owners[note] = shift
    return root, measures, owners, dict(zip(measures, pages))


def shifts(measure):
    return [mark.get("type") for mark in measure.findall("direction/direction-type/octave-shift")]


class OctaveSourcePageTest(unittest.TestCase):
    def test_reused_voice_after_an_entire_page_stops_on_the_owned_note(self):
        root, bars, owned, pages = scene([0, 1, 2], [(0, 4, 2), (1, 1, 0), (2, 4, 0)])
        apply(root, owned, pages)
        self.assertEqual([["down", "stop"], [], []], [shifts(bar) for bar in bars])
        self.assertEqual(["direction", "note", "direction"], [node.tag for node in bars[0]])
        self.assertEqual("4", bars[0][-1].findtext("voice"))

    def test_a_different_shift_reuses_the_same_voice_and_direction_number(self):
        root, bars, owned, pages = scene([0, 1, 2], [(0, 4, 2), (1, 1, 0), (2, 4, 1)])
        apply(root, owned, pages)
        self.assertEqual([["down", "stop"], [], ["down", "stop"]], [shifts(bar) for bar in bars])

    def test_same_shift_on_either_side_of_an_unoccupied_page_uses_two_spans(self):
        notes = [(0, 4, 2), (1, 1, 0), (2, 4, 2)]
        root, bars, owned, pages = scene([0, 1, 2], notes)
        apply(root, owned, pages)
        self.assertEqual([["down", "stop"], [], ["down", "stop"]], [shifts(bar) for bar in bars])

    def test_adjacent_page_continuation_stays_byte_identical(self):
        notes = [(0, 4, 2), (1, 4, 2), (2, 4, 0)]
        root, bars, owned, pages = scene([0, 1, 1], notes)
        legacy, _, legacy_owned, _ = scene([0, 1, 1], notes)
        apply(root, owned, pages)
        apply(legacy, legacy_owned)
        self.assertEqual([["down"], [], ["stop"]], [shifts(bar) for bar in bars])
        self.assertEqual(ET.tostring(legacy), ET.tostring(root))

    def test_unrelated_voices_can_span_both_adjacent_source_pages(self):
        root, bars, owned, pages = scene([0, 1, 2],
              [(0, 4, 2), (0, 2, 1), (1, 2, 1), (2, 2, 1), (2, 4, 0)])
        apply(root, owned, pages)
        marks = [(i, elem.findtext("voice"),
                  elem.find("direction-type/octave-shift").get("type"))
                 for i, measure in enumerate(bars) for elem in measure.findall("direction")]
        self.assertEqual([(0, "4", "down"), (0, "4", "stop"), (0, "2", "down"),
                          (2, "2", "stop")], marks)


    def test_voice_reused_after_skipped_measures_on_next_page(self):
        notes = [(0, 3, 2), (1, 1, 0), (2, 1, 0), (3, 3, 0)]
        root, bars, owned, pages = scene([0, 1, 1, 1], notes)
        apply(root, owned, pages)
        self.assertEqual([["down", "stop"], [], [], []], [shifts(bar) for bar in bars])
        self.assertEqual(["direction", "note", "direction"], [node.tag for node in bars[0]])

    def test_sparse_voice_within_one_page_keeps_its_original_stop(self):
        notes = [(0, 3, 2), (1, 1, 0), (2, 3, 0)]
        root, bars, owned, pages = scene([0, 0, 0], notes)
        apply(root, owned, pages)
        self.assertEqual([["down"], [], ["stop"]], [shifts(bar) for bar in bars])

    def test_changed_scope_after_page_turn_stops_at_the_previous_owned_note(self):
        notes = [(0, 3, 2), (1, 3, 0)]
        root, bars, owned, pages = scene([0, 1], notes)
        apply(root, owned, pages)
        self.assertEqual([["down", "stop"], []], [shifts(bar) for bar in bars])
        self.assertEqual("stop", bars[0][-1].find("direction-type/octave-shift").get("type"))
