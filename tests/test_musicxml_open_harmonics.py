# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Literal hollow harmonics on the ordinary written and sounding export path."""

from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from sheet_music_recognition.musicxml import write_musicxml


def page(violin):
    box = dict(left=.10, right=.85, top=.15, bottom=.38)
    last = dict(left=.10, right=.85, top=.60, bottom=.90, cadenceQuarterBeats=1)
    measures = [box, last]
    events, notes = [], []
    first = dict(measureIndex=0, staffIndex=0, staffCount=1, startBeat=0, durationBeats=1,
                 sourceNoteIndex=0, x=45, y=32, midi=69)
    def add_note(event, step):
        return dict(measureIndex=event['measureIndex'], staffIndex=0, staffCount=1,
                    clefBottomDiatonic=30, staffStep=step, writtenAccidental=2,
                    positionInMeasure=.4, pageY=event['y']/100,
                    unbeamedDurationBeats=1, augmentationDots=0, beamCount=0,
                    tupletDivisor=1, tupletNormalNotes=1)
    events.append(first)
    notes.append(add_note(first, 3))
    heads=[]
    for step, written, sounding, y in ((0,64,83,83),(2,67,86,77)):
        owner = len(events) if violin else None
        if violin:
            event = dict(measureIndex=1, staffIndex=0, staffCount=1, startBeat=1,
                         durationBeats=1, sourceNoteIndex=owner, x=45, y=y, midi=sounding,
                         writtenMidi=written, harmonicTouchMidi=written)
            events.append(event)
            notes.append(add_note(event,step))
        heads.append(dict(staffStep=step,writtenMidi=written,x=45,y=y,
                          soundingMidi=sounding if violin else None,sourceNoteIndex=owner))
    return dict(width=100,height=100,totalBeats=2,measureBeats=[1,1],events=events,
                score=dict(measures=measures,notes=notes,rests=[]),
                printedOpenHarmonicChords=[dict(measureIndex=1,quarterBeatOffset=0,heads=heads)])


def export(data, target):
    write_musicxml(dict(initialMeter=[1,4],initialKeyFifths=0,pages=[data]), target)
    return ET.parse(target).findall('part/measure')


class OpenHarmonicMusicXmlTests(unittest.TestCase):
    def test_open_harmonics_keep_written_pitch_and_only_play_explicit_carriers(self):
        for violin in (False, True):
            with self.subTest(violin=violin), tempfile.TemporaryDirectory() as folder:
                self.check_harmonic_export(Path(folder), violin)

    def check_harmonic_export(self, tmp_path, violin):
        data=page(violin)
        before=export({k:v for k,v in data.items() if k!='printedOpenHarmonicChords'},tmp_path/'before.xml')
        current=export(data,tmp_path/'after.xml')
        assert ET.tostring(before[0])==ET.tostring(current[0])
        written=current[1].findall('note[cue]')
        assert [(n.findtext('pitch/step'), n.findtext('pitch/octave'), n.findtext('notehead'))
                for n in written] == [('E','4','diamond'),('G','4','diamond')]
        assert all(n.find('notehead').get('filled')=='no'
                   and n.findtext('notations/technical/fingering')=='0' for n in written)
        assert [n.find('chord') is not None for n in written]==[violin,True]
        carriers=current[1].findall("note[@print-object='no']")
        assert [(n.findtext('pitch/step'),n.findtext('pitch/octave'),
                 n.find('notations/technical/harmonic/sounding-pitch') is not None)
                for n in carriers]==([('B','5',True),('D','6',True)] if violin else [])
        assert all(n.findtext('duration')=='10080' for n in written+carriers)

    def test_writer_rejects_misaligned_owner(self):
        data = page(True)
        data['printedOpenHarmonicChords'][0]['heads'][0]['sourceNoteIndex'] = 0
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, 'does not match the written head'):
                export(data, Path(folder) / 'bad.xml')
