# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Original293 note-layout contracts: the style byte never displaces legacy fields."""
import copy
import struct
import unittest
from sheet_music_recognition import guide_writer as wire

def score():
    note=dict(measureIndex=0,positionInMeasure=.2,staffStep=0,staffIndex=0,staffCount=1,
              pageY=.4,tiedFromPrevious=False,augmentationDots=0,beamCount=0,
              writtenAccidental=2,unbeamedDurationBeats=2.,tupletDivisor=1,followingRestBeats=0.,
              articulations=0,clefBottomDiatonic=30,crossStaffBeam=False,leadingRestBeats=0.,
              compactOpening=False,octaveShift=0,boundaryTies=0,tupletNormalNotes=1,stemDirection=0,
              kind='PITCHED',visualSize='CUE')
    return dict(measures=[dict(left=.1,right=.9,top=.2,bottom=.6)],firstMeasureNumber=1,
                notes=[note,dict(note,positionInMeasure=.8,visualSize='FULL')],keyChanges=[],
                tempoChanges=[],meterChanges=[dict(measureIndex=0,numerator=4,denominator=4)],
                rests=[],techniqueChanges=[],dynamicChanges=[],playbackDirections=[],expressiveEvents=[])

def legacy_full_note_layout(raw):
    """For legacy-field assertions only: strip293 FULL bytes and retain all trailing sections."""
    if struct.unpack_from('>i',raw)[0]!=293:
        raise AssertionError('Expected an explicit293 fixture')
    measures=struct.unpack_from('>i',raw,4)[0]
    count_at=8+16*measures+4
    count=struct.unpack_from('>i',raw,count_at)[0];start=count_at+4
    out=bytearray(raw[:start])
    for i in range(count):
        at=start+i*81
        if raw[at+80]!=0:raise AssertionError('A CUE style cannot be discarded by a legacy fixture')
        out.extend(raw[at:at+80])
    out.extend(raw[start+count*81:]);out[:4]=struct.pack('>i',292)
    return bytes(out)

class Guide293VisualSizeTests(unittest.TestCase):
    def test_style_byte_follows_kind_and_preserves_every_legacy_section(self):
        s=score();before=copy.deepcopy(s);new=wire.encode(s,293)
        self.assertEqual(s,before);self.assertEqual(293,struct.unpack('>i',new[:4])[0])
        self.assertEqual(1,new[32+80]);self.assertEqual(0,new[32+81+80])
        # Removing the two new bytes yields the exact292 layout, including all trailing sections.
        old=bytearray(b for i,b in enumerate(new)if i not in (32+80,32+81+80))
        old[:4]=struct.pack('>i',292)
        legacy=copy.deepcopy(s)
        for n in legacy['notes']:n['visualSize']='FULL'
        self.assertEqual(wire.encode(legacy,292),bytes(old))
    def test_unsupported_style_values_are_rejected_without_coercion(self):
        for value in (None,True,1,'cue','GRACE',{},[]):
            with self.subTest(value=value):
                s=score();s['notes'][0]['visualSize']=value
                with self.assertRaisesRegex(ValueError,'visual size'):wire.encode(s,293)
    def test_older_layout_cannot_silently_drop_cue_style(self):
        with self.assertRaisesRegex(ValueError,'guide293'):wire.encode(score(),292)
    def test_absent_style_defaults_to_full_and_legacy_explicit_full_is_unchanged(self):
        s=score()
        for n in s['notes']:n.pop('visualSize')
        raw=wire.encode(s)
        self.assertEqual(wire.GUIDE_VERSION,struct.unpack_from('>i',raw)[0]);self.assertIn(293,wire.SUPPORTED_GUIDE_VERSIONS)
        self.assertEqual(0,raw[33+80]);self.assertEqual(0,raw[33+88+80])
        full=copy.deepcopy(s)
        for n in full['notes']:n['visualSize']='FULL'
        self.assertEqual(wire.encode(s,292),wire.encode(full,292))

class Guide301OpenStringTouchTests(unittest.TestCase):
    def test_open_string_touch_follows_offset_and_legacy_writer_refuses_it(self):
        s = score()
        s["notes"][0]["openStringTouch"] = dict(staffStep=-2, pageY=.5, writtenAccidental=0)
        raw = wire.encode(s, 301)
        self.assertEqual(301, struct.unpack_from(">i", raw)[0])
        self.assertEqual((-2, .5, 0), struct.unpack_from(">bfb", raw, 32 + 82))
        self.assertEqual((127, 0, 0), struct.unpack_from(">bfb", raw, 32 + 88 + 82))
        with self.assertRaisesRegex(ValueError, "guide301"):
            wire.encode(s, 300)

    def test_touch_must_be_bounded_and_belong_to_a_pitched_anchor(self):
        for change in (dict(staffStep=36), dict(pageY=float("nan")), dict(writtenAccidental=4)):
            with self.subTest(change=change):
                s = score()
                s["notes"][0]["openStringTouch"] = dict(dict(staffStep=0, pageY=.5, writtenAccidental=2), **change)
                with self.assertRaisesRegex(ValueError, "open-string touch"):
                    wire.encode(s)
        s = score()
        s["notes"][0].update(kind="UNPITCHED", openStringTouch=dict(staffStep=0, pageY=.5, writtenAccidental=2))
        with self.assertRaisesRegex(ValueError, "open-string touch owner"):
            wire.encode(s)


class Guide302CadenceTests(unittest.TestCase):
    def test_cadence_capacity_follows_measure_geometry(self):
        s = score()
        s["measures"][0]["cadenceQuarterBeats"] = 2
        raw = wire.encode(s, 302)
        self.assertEqual(2, raw[24])
        with self.assertRaisesRegex(ValueError, "guide302"):
            wire.encode(s, 301)

    def test_bad_cadence_capacity_is_refused(self):
        for value in (True, -1, 3, 1.5):
            with self.subTest(value=value):
                s = score()
                s["measures"][0]["cadenceQuarterBeats"] = value
                with self.assertRaisesRegex(ValueError, "cadence duration"):
                    wire.encode(s, 302)


if __name__=='__main__':unittest.main()
