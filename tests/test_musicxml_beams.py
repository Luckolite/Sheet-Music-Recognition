# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Literal beam rails and hooks in the rendered MusicXML voice."""

import unittest

from tests import test_musicxml_duration_parts as duration_fixture

event = duration_fixture.event


def anchor(index):
    return dict(pageIndex=0, noteIndex=index)


def fixture(columns, bridges, hooks=(), **extra):
    owners = sorted(i for column in columns for i in column)
    return dict(
        notes=[{} for _ in owners],
        beamSource=dict(
            groups=[dict(id='printed', columns=[dict(members=list(map(anchor, c))) for c in columns],
                         bridges=list(bridges), hooks=list(hooks))],
            detachedColumns=[], noteOrigins=list(map(anchor, owners))),
        **extra)


def beam_values(note):
    return [(int(beam.get('number')), beam.text) for beam in note.findall('beam')]


class BeamExportTests(unittest.TestCase):
    def test_two_secondary_runs_and_hook_on_shared_chord_stems(self):
        source = fixture(((0, 1), (2,), (3,), (4,)), (3, 1, 3),
                         (dict(columnIndex=1, level=3, direction='BACKWARD'),))
        events = [event(0, .25, 60, sourceNoteIndex=0),
                  event(0, .25, 64, sourceNoteIndex=1),
                  event(.25, .125, 62, sourceNoteIndex=2),
                  event(.375, .25, 64, sourceNoteIndex=3),
                  event(.625, .25, 65, sourceNoteIndex=4)]
        root = duration_fixture.WrittenDurationPartsTests().export(events, **source)
        notes = root.findall('.//note[pitch]')
        self.assertEqual([False, True, False, False, False], [n.find('chord') is not None for n in notes])
        self.assertEqual([[(1, 'begin'), (2, 'begin')], [],
                          [(1, 'continue'), (2, 'end'), (3, 'backward hook')],
                          [(1, 'continue'), (2, 'begin')], [(1, 'end'), (2, 'end')]],
                         list(map(beam_values, notes)))

    def test_missing_owner_breaks_the_group_without_bridging_the_gap(self):
        source = fixture(((0,), (1,), (2,), (3,)), (3, 3, 3))
        events = [event(.25 * index, .25, 60 + index, sourceNoteIndex=index) for index in (0, 2, 3)]
        root = duration_fixture.WrittenDurationPartsTests().export(events, **source)
        self.assertEqual([[], [(1, 'begin'), (2, 'begin')], [(1, 'end'), (2, 'end')]],
                         list(map(beam_values, root.findall('.//note[pitch]'))))

    def test_short_written_notes_cannot_receive_an_unsupported_beam_level(self):
        source = fixture(((0,), (1,)), (3,))
        events = [event(0, .5, sourceNoteIndex=0), event(.5, .5, sourceNoteIndex=1)]
        root = duration_fixture.WrittenDurationPartsTests().export(events, **source)
        self.assertEqual([[(1, 'begin')], [(1, 'end')]],
                         list(map(beam_values, root.findall('.//note[pitch]'))))

    def test_bar_boundary_gaps_and_extra_chord_heads_never_create_connections(self):
        cases = [
            ([event(3.5, .5, sourceNoteIndex=0), event(4, .5, sourceNoteIndex=1)], (4, 4)),
            ([event(0, .5, sourceNoteIndex=0), event(.75, .5, sourceNoteIndex=1)], (4,)),
            ([event(0, .5, sourceNoteIndex=0), event(0, .5, 64), event(.5, .5, sourceNoteIndex=1)], (4,)),
            ([event(0, .5, sourceNoteIndex=0), event(.5, .5, sourceNoteIndex=1, durationFallback=True)], (4,)),
        ]
        source = fixture(((0,), (1,)), (1,))
        for events, beats in cases:
            with self.subTest(events=events):
                root = duration_fixture.WrittenDurationPartsTests().export(events, beats, **source)
                self.assertEqual([], root.findall('.//beam'))

    def test_beam_precedes_note_notations(self):
        source = fixture(((0,), (1,)), (1,))
        source['notes'] = [dict(measureIndex=0, staffIndex=0, staffCount=1, articulations=i)
                           for i in (1, 0)]
        events = [event(0, .5, sourceNoteIndex=0), event(.5, .5, sourceNoteIndex=1)]
        first = duration_fixture.WrittenDurationPartsTests().export(events, **source).find('.//note')
        self.assertEqual(['beam', 'notations'], [child.tag for child in first][-2:])

    def test_full_beam_group_keeps_available_voice_after_independent_sustain(self):
        source = fixture(((0,), (1,)), (3,))
        events = [event(0, 1.25, 55), event(1, .25, 60, sourceNoteIndex=0),
                  event(1.25, .25, 62, sourceNoteIndex=1)]
        root = duration_fixture.WrittenDurationPartsTests().export(events, **source)
        first, last = root.findall('.//note[beam]')
        self.assertEqual(first.findtext('voice'), last.findtext('voice'))
        self.assertEqual([(1, 'begin'), (2, 'begin')], beam_values(first))
        self.assertEqual([(1, 'end'), (2, 'end')], beam_values(last))

    def test_beam_voice_does_not_displace_an_independent_attack(self):
        source = fixture(((0,), (1,)), (3,))
        events = [event(0, 1.25, 55), event(1, .25, 60, sourceNoteIndex=0),
                  event(1.25, .25, 62, sourceNoteIndex=1), event(1.375, .125, 67)]
        root = duration_fixture.WrittenDurationPartsTests().export(events, **source)
        self.assertEqual([], root.findall('.//beam'))

    def test_matching_voice_numbers_in_separate_parts_do_not_join(self):
        source = fixture(((0,), (1,)), (1,))
        events = [event(0, .5, sourceNoteIndex=0),
                  dict(event(.5, .5, sourceNoteIndex=1), staffIndex=1, staffCount=2)]
        self.assertEqual([], duration_fixture.WrittenDurationPartsTests().export(events, **source).findall('.//beam'))
