# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Constructed controls for the cumulative Guide303–307 layouts."""
from copy import deepcopy
import struct
import unittest

from sheet_music_recognition import beam_source, guide_writer, measure_continuations


def original_score():
    measures = [dict(left=.1, right=.9, top=top, bottom=top + .12)
                for top in (.1, .35, .6)]
    notes = [
        dict(measureIndex=0, positionInMeasure=x, staffStep=i, staffIndex=0, staffCount=1,
             pageY=.2, tiedFromPrevious=False, augmentationDots=0, beamCount=1,
             writtenAccidental=0, unbeamedDurationBeats=0, tupletDivisor=3, tupletNormalNotes=2,
             followingRestBeats=0, articulations=0, clefBottomDiatonic=30, crossStaffBeam=False,
             leadingRestBeats=0, compactOpening=False, octaveShift=0, stemDirection=1)
        for i, x in enumerate((.1, .3, .5))
    ]
    origins = [dict(pageIndex=0, noteIndex=i) for i in range(len(notes))]
    group = dict(id="original", columns=[dict(members=[a]) for a in origins], bridges=[1, 1], hooks=[])
    return dict(measures=measures, notes=notes, firstMeasureNumber=1, keyChanges=[], tempoChanges=[],
                meterChanges=[], rests=[], techniqueChanges=[], dynamicChanges=[], playbackDirections=[],
                expressiveEvents=[], beamSource=dict(groups=[group], detachedColumns=[], noteOrigins=origins))


class Guide307WireTest(unittest.TestCase):
    def test_required_sections_are_present_even_without_rows(self):
        score = original_score()
        veteran = guide_writer.encode(score)
        self.assertEqual(302, struct.unpack_from(">i", veteran)[0])
        continuation = struct.pack(">iii", 0x4D434E31, 4, 0)
        self.assertEqual(veteran[4:] + continuation, guide_writer.encode(score, 303)[4:])
        self.assertEqual(veteran[4:] + continuation, guide_writer.encode(score, 304)[4:])
        self.assertEqual(veteran[4:] + continuation + bytes(4), guide_writer.encode(score, 305)[4:])
        self.assertEqual(veteran[4:] + continuation + bytes(8), guide_writer.encode(score, 306)[4:])

    def test_three_beat_capacity_in_new_compatible_formats(self):
        score = original_score()
        score["measures"][2]["cadenceQuarterBeats"] = 3
        self.assertEqual(guide_writer.encode(score), guide_writer.encode(score, 302))
        with self.assertRaisesRegex(ValueError, "guide304"):
            guide_writer.encode(score, 303)
        self.assertEqual(3, guide_writer.encode(score, 304)[8 + 16 + 2 * 17])

    def test_continuation_roman_and_accidental_owners_with_both_tuplet_marks(self):
        score = original_score()
        rows = [dict(secondMeasureIndex=1, firstTicks=30240, secondTicks=10080)]
        expected = struct.pack(">iiiiii", 0x4D434E31, 16, 1, 1, 30240, 10080)
        self.assertEqual(rows, measure_continuations.decode(expected, score["measures"]))
        score["printedMeasureContinuations"] = rows
        score["measures"][2]["cadenceQuarterBeats"] = 3
        score["printedAccidentals"] = [dict(noteIndex=0, accidental=0)]
        score["pendingRomanStrings"] = [dict(noteIndex=1, number=2, placement="BELOW")]
        end = expected + struct.pack(">iibiiBB", 1, 0, 0, 1, 1, 2, 1)
        pre = guide_writer.encode(score, 306)
        self.assertTrue(pre.endswith(end))
        for mark, code in (("NUMBERED", 1), ("UNNUMBERED", 2)):
            with self.subTest(mark=mark):
                score["beamSource"]["groups"][0]["tupletMark"] = mark
                tail = beam_source.encode_source(score["beamSource"], 3, engraved_tuplets=True)
                self.assertEqual(score["beamSource"], beam_source.decode_source(tail, 3, engraved_tuplets=True))
                self.assertEqual(code, tail[4 + 4 + 4 + 8 + 4 + 3 * 12 + 4 + 2 * 4 + 4])
                guide = guide_writer.encode(score, 307)
                self.assertIn(tail, guide)
                self.assertTrue(guide.endswith(end))
                with self.assertRaises(ValueError):
                    guide_writer.encode(score, 306)
                broken = bytearray(tail)
                broken[4 + 4 + 4 + 8 + 4 + 3 * 12 + 4 + 2 * 4 + 4] = 3
                with self.assertRaises(ValueError):
                    beam_source.decode_source(bytes(broken), 3, engraved_tuplets=True)

    def test_guide308_printed_natural_sharp_preserves_owner_and_rejects_downgrade(self):
        score = original_score()
        score["notes"][0]["writtenAccidental"] = 1
        score["printedAccidentals"] = [dict(noteIndex=0, accidental=1)]
        plain = guide_writer.encode(score, 307)
        self.assertEqual(plain[4:], guide_writer.encode(score, 308)[4:])
        section = struct.pack(">iibi", 1, 0, 1, 0)
        self.assertTrue(plain.endswith(section))

        score["printedAccidentals"][0]["naturalPrefix"] = True
        expected = struct.pack(">iibi", 1, 0, 4, 0)
        styled = guide_writer.encode(score, 308)
        self.assertEqual(plain[4:-len(section)] + expected, styled[4:])
        with self.assertRaisesRegex(ValueError, "guide308"):
            guide_writer.encode(score, 307)

    def test_guide_profiles_project_natural_flat_to_printed_flat(self):
        score = original_score()
        score["notes"][0]["writtenAccidental"] = -1
        score["printedAccidentals"] = [dict(noteIndex=0, accidental=-1)]
        for version in range(305, 309):
            with self.subTest(version=version):
                plain = guide_writer.encode(score, version)
                score["printedAccidentals"][0]["naturalPrefix"] = True
                self.assertEqual(plain, guide_writer.encode(score, version))
                del score["printedAccidentals"][0]["naturalPrefix"]

    def test_bad_owners_and_unrepresentable_new_metadata_are_rejected(self):
        score = original_score()
        for key, rows, version in [
            ("pendingRomanStrings", [dict(noteIndex=0, number=2, placement="ABOVE")], 305),
            ("pendingRomanStrings", [dict(noteIndex=0, number=True, placement="ABOVE")], 306),
            ("pendingRomanStrings", [dict(noteIndex=3, number=1, placement="ABOVE")], 306),
            ("printedAccidentals", [dict(noteIndex=0, accidental=1)], 305),
            ("printedAccidentals", [dict(noteIndex=0, accidental=0)], 304),
            ("printedMeasureContinuations", [dict(secondMeasureIndex=0, firstTicks=1, secondTicks=1)], 307),
        ]:
            with self.subTest(key=key, version=version):
                changed = deepcopy(score)
                changed[key] = rows
                with self.assertRaises(ValueError):
                    guide_writer.encode(changed, version)
