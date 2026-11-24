# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0

from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase
from xml.etree import ElementTree

from sheet_music_recognition.musicxml import write_musicxml


def stopped_and_open(stem):
    common = dict(measureIndex=0, staffIndex=0, staffCount=1, clefBottomDiatonic=30,
                  stemDirection=stem, unbeamedDurationBeats=1, beamCount=0, writtenAccidental=2,
                  positionInMeasure=.38)
    natural = dict(common, staffStep=6, pageY=.42)
    note = dict(common, staffStep=0, pageY=.6, harmonicSemitones=24,
                openStringTouch=dict(staffStep=6, pageY=.42, writtenAccidental=2))
    beat = dict(measureIndex=0, staffIndex=0, staffCount=1, startBeat=0, durationBeats=1, x=476)
    events = [
        dict(beat, sourceNoteIndex=0, y=180, midi=88, writtenMidi=64, harmonicTouchMidi=69,
             artificialHarmonicFourth=True, compoundHarmonicFourth=True),
        dict(beat, sourceNoteIndex=1, y=126, midi=93, writtenMidi=74, harmonicTouchMidi=74,
             openStringMidi=69),
    ]
    return dict(width=1000, height=300, measureBeats=[1], totalBeats=1,
                score=dict(notes=[note, natural],
                           measures=[dict(left=.4, right=.6, top=.3, bottom=.7)]),
                events=events)


class PrintedCompoundFourthHarmonicTest(TestCase):
    def test_two_sounding_pitches_and_three_printed_heads_share_one_quarter(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / 'compound.musicxml'
            for stem in (-1, 1):
                with self.subTest(stem=stem):
                    write_musicxml(dict(pages=[stopped_and_open(stem)]), path)
                    notes = ElementTree.parse(path).findall('./part/measure/note')
                    self.assertEqual(len(notes), 5)
                    self.assertEqual(sum(n.find('chord') is None for n in notes), 1)
                    self.assertTrue(all(n.findtext('duration') == '10080' for n in notes))
                    playing = [n for n in notes if n.get('print-object') == 'no']
                    self.assertEqual([(n.findtext('pitch/step'), n.findtext('pitch/octave'))
                                      for n in playing], [('E', '6'), ('A', '6')])
                    printed = [n for n in notes if n.find('cue') is not None]
                    self.assertEqual([n.findtext('stem') for n in printed],
                                     ['up' if stem > 0 else 'down'] * 3)
                    self.assertEqual([n.findtext('notehead') for n in printed], [None, 'diamond', 'diamond'])
                    self.assertEqual([n.find('notehead').get('filled') for n in printed[1:]], ['no', 'no'])
                    self.assertEqual([n.findtext('notations/technical/fingering') for n in printed],
                                     [None, None, '0'])

    def test_refuses_split_shaft_wrong_open_string_and_mismatched_onset(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / 'compound.musicxml'
            for branch, field, value in (('notes', 'stemDirection', -1), ('events', 'startBeat', .25),
                                         ('events', 'openStringMidi', 62), ('events', 'x', 483)):
                with self.subTest(field=field):
                    doc = stopped_and_open(1)
                    owners = doc['score'][branch] if branch == 'notes' else doc[branch]
                    owners[1][field] = value
                    with self.assertRaises(ValueError):
                        write_musicxml(dict(pages=[doc]), path)


def beamed_fourths():
    owners = [dict(pageIndex=0, noteIndex=i) for i in range(2)]
    common = dict(measureIndex=0, staffIndex=0, staffCount=1)
    notes = [dict(common, staffStep=step, clefBottomDiatonic=30, stemDirection=-1,
                  beamCount=2, harmonicSemitones=24, positionInMeasure=position)
             for step, position in ((7, .25), (8, .75))]
    events = [dict(common, sourceNoteIndex=i, startBeat=i / 4, durationBeats=.25,
                   midi=base + 24, writtenMidi=base, harmonicTouchMidi=base + 5,
                   artificialHarmonicFourth=True, beamedArtificialHarmonicFourth=True,
                   printedHarmonicTouchAccidental=accidental)
              for i, (base, accidental) in enumerate(((75, -1), (78, 0)))]
    beams = dict(groups=[dict(id='beam', columns=[dict(members=[a]) for a in owners],
                              bridges=[3], hooks=[])],
                 detachedColumns=[], noteOrigins=owners)
    return dict(width=1000, height=300, measureBeats=[.5], totalBeats=.5,
                score=dict(notes=notes, beamSource=beams,
                           measures=[dict(left=.4, right=.6, top=.3, bottom=.7)]),
                events=events)


def triplet_fourths():
    from copy import deepcopy
    page = beamed_fourths()
    notes, events = page['score']['notes'], page['events']
    notes.append(deepcopy(notes[1]))
    events.append(dict(events[1], sourceNoteIndex=2))
    for i, (note, event) in enumerate(zip(notes, events)):
        note.update(beamCount=1, tupletDivisor=3, tupletNormalNotes=2, augmentationDots=0,
                    positionInMeasure=(i + .5) / 3)
        event.update(startBeat=i / 3, durationBeats=1 / 3, tupletActualNotes=3, tupletNormalNotes=2)
    anchors = [dict(pageIndex=0, noteIndex=i) for i in range(3)]
    page['score']['beamSource'] = dict(
        noteOrigins=anchors, detachedColumns=[], groups=[dict(
            id='triplet', columns=[dict(members=[a]) for a in anchors],
            bridges=[1, 1], hooks=[], tupletMark='UNNUMBERED')])
    page.update(measureBeats=[1], totalBeats=1)
    return page


class PrintedBeamedFourthTest(TestCase):
    def test_sounding_carrier_and_two_written_roles_share_each_sixteenth(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / 'beamed.musicxml'
            write_musicxml(dict(pages=[beamed_fourths()]), path)
            notes = ElementTree.parse(path).findall('./part/measure/note[pitch]')
            self.assertEqual(len(notes), 6)
            self.assertEqual([n.find('chord') is None for n in notes], [True, False, False] * 2)
            self.assertEqual([n.get('print-object') for n in notes], ['no', None, None] * 2)
            self.assertEqual([n.findtext('type') for n in notes], ['16th'] * 6)
            self.assertEqual([n.findtext('duration') for n in notes], ['2520'] * 6)
            stopped = notes[1::3]
            touching = notes[2::3]
            self.assertEqual([n.findtext('accidental') for n in touching], ['flat', 'natural'])
            self.assertEqual([n.findtext('accidental') for n in stopped], [None, None])
            self.assertEqual([n.find('notehead').get('filled') for n in touching], ['no', 'no'])
            self.assertEqual([[b.text for b in n.findall('beam')] for n in stopped],
                             [['begin', 'begin'], ['end', 'end']])
            self.assertEqual([n.findall('beam') for n in touching], [[], []])

    def test_eighth_triplet_carriers_and_written_roles_keep_the_same_clock(self):
        with TemporaryDirectory() as tmp:
            output = Path(tmp) / 'triplet.musicxml'
            write_musicxml(dict(pages=[triplet_fourths()]), output)
            notes = ElementTree.parse(output).findall('./part/measure/note[pitch]')
            self.assertEqual([n.findtext('duration') for n in notes], ['3360'] * 9)
            self.assertEqual([n.findtext('type') for n in notes], ['eighth'] * 9)
            self.assertEqual([(n.findtext('time-modification/actual-notes'),
                               n.findtext('time-modification/normal-notes')) for n in notes],
                             [('3', '2')] * 9)
            self.assertEqual([n.get('print-object') for n in notes], ['no', None, None] * 3)
            self.assertEqual([n.findtext('notehead') for n in notes[2::3]], ['diamond'] * 3)
            self.assertEqual([n.findtext('beam') for n in notes[1::3]], ['begin', 'continue', 'end'])
            tuplet = notes[1].find('notations/tuplet')
            self.assertEqual((tuplet.get('show-number'), tuplet.get('bracket')), ('none', 'no'))
            self.assertEqual(notes[2].findtext('accidental'), 'flat')

    def test_eighth_triplet_fourth_requires_its_written_ratio_and_single_rail(self):
        for family, key, value in (('events', 'tupletNormalNotes', 1),
                                   ('notes', 'tupletDivisor', 1), ('notes', 'beamCount', 2)):
            with self.subTest(family=family, key=key), TemporaryDirectory() as tmp:
                page = triplet_fourths()
                entries = page['events'] if family == 'events' else page['score']['notes']
                entries[0][key] = value
                with self.assertRaises(ValueError):
                    write_musicxml(dict(pages=[page]), Path(tmp) / 'invalid-triplet.musicxml')

    def test_stem_directions_belong_to_the_corresponding_stopped_and_touching_pair(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / 'mixed-stems.musicxml'
            page = beamed_fourths()
            page['score']['notes'][1]['stemDirection'] = 1
            del page['score']['beamSource']
            write_musicxml(dict(pages=[page]), path)
            notes = ElementTree.parse(path).findall('./part/measure/note[cue]')
            self.assertEqual([n.findtext('stem') for n in notes], ['down', 'down', 'up', 'up'])

    def test_tenuto_stays_on_the_visible_stopped_beamed_harmonic(self):
        with TemporaryDirectory() as tmp:
            page = beamed_fourths()
            page['score']['notes'][0]['articulations'] = 8
            output = Path(tmp) / 'tenuto.musicxml'
            write_musicxml(dict(pages=[page]), output)
            notes = ElementTree.parse(output).findall('./part/measure/note[pitch]')
            self.assertEqual([n.find('notations/articulations/tenuto') is not None for n in notes],
                             [False, True, False, False, False, False])
            self.assertEqual(notes[2].findtext('accidental'), 'flat')

    def test_rejects_wrong_touch_accidental_or_written_clock(self):
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / 'invalid.musicxml'
            for name, value in (('printedHarmonicTouchAccidental', 0), ('durationBeats', .5)):
                with self.subTest(name=name):
                    page = beamed_fourths()
                    page['events'][0][name] = value
                    with self.assertRaises(ValueError):
                        write_musicxml(dict(pages=[page]), path)
