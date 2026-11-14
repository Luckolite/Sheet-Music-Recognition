# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from xml.etree import ElementTree

from sheet_music_recognition.musicxml import DIVISIONS, write_musicxml


class SystemContinuationMusicXmlTests(unittest.TestCase):
    def page(self):
        return {
            "measureBeats": [2, 2, 4],
            "printedMeasureContinuations": [1],
            "score": {
                "measures": [
                    {"left": .1, "right": .9, "top": .1, "bottom": .2},
                    {"left": .1, "right": .6, "top": .4, "bottom": .5},
                    {"left": .6, "right": .9, "top": .4, "bottom": .5},
                ],
                "tempoChanges": [
                    {"measureIndex": 0, "positionInMeasure": .5, "bpm": 99},
                    {"measureIndex": 1, "positionInMeasure": 0, "bpm": 112},
                    {"measureIndex": 1, "positionInMeasure": .75, "bpm": 122},
                ],
            },
            "events": [
                {"startBeat": 0, "durationBeats": 2, "midi": 60},
                {"startBeat": 2, "durationBeats": 2, "midi": 62},
                {"startBeat": 4, "durationBeats": 4, "midi": 64},
            ],
        }

    def write(self, page):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "score.musicxml"
            write_musicxml({"pages": [page]}, path)
            return ElementTree.parse(path).getroot().findall("part/measure")

    def test_system_span_joins_once_and_keeps_all_tempo_positions(self):
        page = self.page()
        source = deepcopy(page)
        first, second = self.write(page)
        self.assertEqual(source, page)
        self.assertEqual([note.findtext("pitch/step") for note in first.findall("note")],
                         ["C", "D"])
        self.assertEqual([int(note.findtext("duration")) for note in first.findall("note")],
                         [2 * DIVISIONS, 2 * DIVISIONS])
        self.assertEqual(second.findtext("note/pitch/step"), "E")
        self.assertEqual([int(direction.findtext("offset")) for direction in first.findall("direction")],
                         [0, DIVISIONS, 2 * DIVISIONS, 7 * DIVISIONS // 2])
        self.assertIsNone(first.get("implicit"))

    def test_unflagged_physical_measures_keep_existing_output(self):
        page = self.page()
        del page["printedMeasureContinuations"]
        one, two, three = self.write(page)
        self.assertEqual([node.findtext("note/pitch/step") for node in (one, two, three)],
                         ["C", "D", "E"])
        self.assertEqual([node.get("implicit") for node in (one, two, three)], ["yes", "yes", None])

    def test_ambiguous_boundary_does_not_merge(self):
        for flag in ([0], [1, 1], [True], [2], None):
            with self.subTest(flag=flag):
                page = self.page()
                page["printedMeasureContinuations"] = flag
                with self.assertRaisesRegex(ValueError, "continuation"):
                    self.write(page)

    def test_changed_meter_does_not_merge(self):
        page = self.page()
        page["score"]["meterChanges"] = [{"measureIndex": 1, "numerator": 3, "denominator": 4}]
        with self.assertRaisesRegex(ValueError, "continuation changes"):
            self.write(page)

    def test_barline_is_attached_to_the_last_physical_fragment(self):
        page = self.page()
        page["printedBarlineStyles"] = [
            {"measureIndex": 1, "style": "light-light"},
            {"measureIndex": 2, "style": "light-heavy"},
        ]
        measures = self.write(page)
        self.assertEqual(["light-light", "light-heavy"],
                         [measure.findtext("barline/bar-style") for measure in measures])
        self.assertEqual(["right", "right"],
                         [measure.find("barline").get("location") for measure in measures])
        self.assertTrue(all(measure[-1].tag == "barline" for measure in measures))

    def test_barline_within_a_continuation_is_rejected(self):
        page = self.page()
        page["printedBarlineStyles"] = [{"measureIndex": 0, "style": "light-light"}]
        with self.assertRaisesRegex(ValueError, "barline style ownership"):
            self.write(page)


if __name__ == "__main__":
    unittest.main()
