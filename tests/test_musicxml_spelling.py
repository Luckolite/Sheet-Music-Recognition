# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Original scalar heads: enharmonic spelling, auxiliary identity and clock invariants."""
import copy
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from sheet_music_recognition.musicxml import write_musicxml
from sheet_music_recognition.spelling_export import retained


def document(diatonic=29, accidental=-1, midi=61, shift=0, bottom=30):
    head = dict(measureIndex=0, staffIndex=0, staffCount=1, clefBottomDiatonic=bottom,
                staffStep=diatonic-bottom, writtenAccidental=accidental, octaveShift=shift,
                articulations=32 | 1024)
    event = dict(measureIndex=0, staffIndex=0, staffCount=1, midi=midi,
                 startBeat=0, durationBeats=1)
    return dict(pages=[dict(measureBeats=[4], events=[event], score=dict(notes=[head]))])


def reconstructed(events, beats=(4,)):
    return dict(pages=[dict(measureBeats=beats, score={}, events=[
        dict(midi=pitch, startBeat=start, durationBeats=duration)
        for pitch, start, duration in events])])


class MusicXmlSpellingTests(unittest.TestCase):
    def export(self, doc):
        before = copy.deepcopy(doc)
        with tempfile.TemporaryDirectory(prefix='original-spelling-') as folder:
            path = Path(folder)/'score.musicxml'
            write_musicxml(doc, path)
            root = ET.parse(path).getroot()
        self.assertEqual(before, doc)
        return root

    def pitch(self, doc):
        pitch = self.export(doc).find('.//pitch')
        return pitch.findtext('step'), int(pitch.findtext('alter', '0')), int(pitch.findtext('octave'))

    def test_flat_principal_preserves_diatonic_ornament_neighbor(self):
        root = self.export(document())
        self.assertEqual('D', root.findtext('.//pitch/step'))
        self.assertEqual('-1', root.findtext('.//pitch/alter'))
        self.assertEqual('natural', root.findtext('.//accidental-mark'))
        # An upper natural belongs to E above Db, not D above an invented C#.
        self.assertEqual('10080', root.findtext('.//note[pitch]/duration'))

    def test_double_accidentals_and_octave_crossings(self):
        for d, a, midi, expected in [(28,-1,59,('C',-1,4)), (27,1,60,('B',1,3)),
                                      (28,3,62,('C',2,4)), (29,-2,60,('D',-2,4))]:
            with self.subTest(expected=expected):
                self.assertEqual(expected, self.pitch(document(d,a,midi)))

    def test_explicit_natural_overrides_key(self):
        doc = document(31,0,65)
        doc['pages'][0]['score']['keyChanges'] = [dict(measureIndex=0,fifths=1)]
        self.assertEqual(('F',0,4), self.pitch(doc))

    def test_source_accidental_is_written_once_on_a_tied_attack(self):
        doc = document(31, 0, 65)
        page = doc['pages'][0]
        page['score']['keyChanges'] = [dict(measureIndex=0, fifths=1)]
        page['printedAccidentals'] = [dict(noteIndex=0, accidental=0)]
        page['events'].append(dict(page['events'][0], startBeat=1, tiedFromPrevious=True))
        page['score']['notes'].append(dict(page['score']['notes'][0]))
        notes = self.export(doc).findall('.//note[pitch]')
        self.assertEqual(['natural', None], [n.findtext('accidental') for n in notes])
        self.assertEqual(['start', 'stop'], [n.find('tie').get('type') for n in notes])

    def test_reconstructed_accidentals_carry_and_reset_per_octave_and_measure(self):
        score = reconstructed([(61, 0, 1), (61, 1, 1), (73, 2, 1), (60, 3, 1),
                               (61, 4, 1), (72, 5, 1)], (4, 4))
        notes = self.export(score).findall('.//note[pitch]')
        self.assertEqual(['sharp', None, 'sharp', 'natural', 'sharp', None],
                         [note.findtext('accidental') for note in notes])
        self.assertEqual(['1', '1', '1', None, '1', None],
                         [note.findtext('pitch/alter') for note in notes])

    def test_key_signature_change_sets_new_accidental_default(self):
        score = reconstructed([(66, 0, 1), (65, 1, 1), (65, 2, 1), (66, 3, 1),
                               (66, 4, 1)], (4, 4))
        score['pages'][0]['score']['keyChanges'] = [dict(measureIndex=0, fifths=1),
                                                   dict(measureIndex=1, fifths=0)]
        notes = self.export(score).findall('.//note[pitch]')
        self.assertEqual([None, 'natural', None, 'sharp', 'sharp'],
                         [note.findtext('accidental') for note in notes])

    def test_reconstructed_accidentals_follow_time_across_voices(self):
        score = reconstructed([(60, 0, 2), (61, 1, 1), (60, 2, 2)])
        notes = self.export(score).findall('.//note[pitch]')
        self.assertEqual([('1', None), ('1', 'natural'), ('2', 'sharp')],
                         [(note.findtext('voice'), note.findtext('accidental')) for note in notes])

    def test_tied_continuation_does_not_carry_to_a_new_measure(self):
        score = reconstructed([(61, 3, 2), (61, 5, 1)], (4, 4))
        notes = self.export(score).findall('.//note[pitch]')
        self.assertEqual(['sharp', None, 'sharp'], [note.findtext('accidental') for note in notes])

    def test_printed_mark_is_placed_before_tuplet_and_not_repeated_on_split(self):
        doc = document()
        page = doc['pages'][0]
        page['printedAccidentals'] = [dict(noteIndex=0, accidental=-1)]
        page['events'][0].update(durationBeats=5/3, tupletActualNotes=3, tupletNormalNotes=2)
        notes = self.export(doc).findall('.//note[pitch]')
        self.assertEqual(['flat', None], [n.findtext('accidental') for n in notes])
        tags = [child.tag for child in notes[0]]
        self.assertLess(tags.index('accidental'), tags.index('time-modification'))

    def test_conflicting_or_unowned_mark_is_not_written(self):
        doc = document()
        page = doc['pages'][0]
        page['printedAccidentals'] = [dict(noteIndex=0, accidental=1)]
        self.assertIsNone(self.export(doc).find('.//note/accidental'))
        page['printedAccidentals'][0]['accidental'] = -1
        page['events'][0]['sourceNoteIndex'] = 1
        self.assertIsNone(self.export(doc).find('.//note/accidental'))

    def test_inferred_clef_spelling_requires_a_mark_and_agreement_with_midi(self):
        for staff, count, step, midi, sign, expected in (
                (0, 1, 6, 75, 1, ('D', '1', '5', 'sharp')),
                (1, 2, 0, 44, 1, ('G', '1', '2', 'sharp')),
                (0, 1, 13, 86, 0, ('D', '0', '6', 'natural'))):
            with self.subTest(staff=staff, count=count, step=step):
                doc = document(30 + step, sign, midi)
                page = doc['pages'][0]
                note = page['score']['notes'][0]
                note.update(clefBottomDiatonic=-1, staffIndex=staff, staffCount=count)
                event = page['events'][0]
                event.update(clefInferred=True, staffIndex=staff, staffCount=count, sourceNoteIndex=0)
                page['printedAccidentals'] = [dict(noteIndex=0, accidental=sign)]
                n = self.export(doc).find('.//note[pitch]')
                self.assertEqual(expected, (n.findtext('pitch/step'), n.findtext('pitch/alter', '0'),
                                            n.findtext('pitch/octave'), n.findtext('accidental')))
                self.assertIsNone(retained(page, 0, 0))
                event['midi'] += 1
                self.assertIsNone(self.export(doc).find('.//note/accidental'))

    def test_invalid_printed_mark_rows_raise(self):
        for rows in (None, [{'noteIndex': 1, 'accidental': 0}],
                     [{'noteIndex': 0, 'accidental': 2}], [{'noteIndex': True, 'accidental': 0}],
                     [{'noteIndex': 0, 'accidental': False}]):
            doc = document()
            doc['pages'][0]['printedAccidentals'] = rows
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                self.export(doc)

    def test_cancellation_sharp_keeps_its_literal_print_without_changing_pitch(self):
        doc = document(28, 1, 61)
        page = doc['pages'][0]
        page['events'].append(dict(page['events'][0], startBeat=1))
        page['score']['notes'].append(dict(page['score']['notes'][0]))
        page['printedAccidentals'] = [
            dict(noteIndex=0, accidental=1, naturalPrefix=True),
            dict(noteIndex=1, accidental=1),
        ]
        notes = self.export(doc).findall('.//note[pitch]')
        self.assertEqual(['natural-sharp', 'sharp'], [n.findtext('accidental') for n in notes])
        self.assertEqual(['1', '1'], [n.findtext('pitch/alter') for n in notes])
        page['events'][0]['midi'] = 62
        self.assertEqual([None, 'sharp'], [n.findtext('accidental')
                          for n in self.export(doc).findall('.//note[pitch]')])

    def test_cancellation_flat_keeps_its_literal_print_and_sounding_pitch(self):
        doc = document(29, -1, 61)
        page = doc['pages'][0]
        page['printedAccidentals'] = [dict(noteIndex=0, accidental=-1, naturalPrefix=True)]
        note = self.export(doc).find('.//note[pitch]')
        self.assertEqual(('D', '-1', 'natural-flat'),
                         (note.findtext('pitch/step'), note.findtext('pitch/alter'), note.findtext('accidental')))

    def test_cancellation_prefix_requires_an_explicit_flat_or_sharp(self):
        for sign, prefix in ((0, True), (1, 1), (1, False)):
            doc = document()
            doc['pages'][0]['printedAccidentals'] = [
                dict(noteIndex=0, accidental=sign, naturalPrefix=prefix)]
            with self.subTest(sign=sign, prefix=prefix), self.assertRaises(ValueError):
                self.export(doc)

    def test_from_key_uses_resolved_bar_key(self):
        for key,d,midi,expected in [(1,31,66,('F',1,4)),(-1,34,70,('B',-1,4)),
                                    (7,27,60,('B',1,3)),(-7,28,59,('C',-1,4))]:
            doc = document(d,2,midi)
            doc['pages'][0]['score']['keyChanges'] = [dict(measureIndex=0,fifths=key)]
            self.assertEqual(expected, self.pitch(doc))

    def test_octave_shift_and_supported_clefs(self):
        for bottom in (18,22,24,30,37):
            self.assertEqual(('D',-1,5), self.pitch(document(29,-1,73,1,bottom)))

    def test_edited_pitch_uses_existing_fallback(self):
        self.assertEqual(('D',0,4), self.pitch(document(midi=62)))

    def test_later_measure_key_and_page_inheritance(self):
        doc = document(34,2,70)
        page = doc['pages'][0]
        page['measureBeats'] = [4,4]
        page['score']['keyChanges'] = [dict(measureIndex=1,fifths=-1)]
        page['events'][0].update(measureIndex=1,startBeat=4)
        page['score']['notes'][0]['measureIndex'] = 1
        following = document(34,2,70)['pages'][0]
        doc['pages'].append(following)
        self.assertEqual([('B','-1'),('B','-1')],
                         [(p.findtext('step'),p.findtext('alter'))
                          for p in self.export(doc).findall('.//pitch')])

    def test_tied_fragments_keep_spelling_and_exact_clock(self):
        doc = document()
        doc['pages'][0]['measureBeats'] = [4,4]
        doc['pages'][0]['events'][0]['durationBeats'] = 5
        root = self.export(doc)
        notes = root.findall('.//note[pitch]')
        self.assertEqual([('D','-1','4')]*2,
                         [(n.findtext('pitch/step'),n.findtext('pitch/alter'),
                           n.findtext('pitch/octave')) for n in notes])
        self.assertEqual(5*10080,sum(int(n.findtext('duration')) for n in notes))
        self.assertEqual(['start','stop'],[n.find('tie').get('type') for n in notes])
        self.assertEqual(1,len(root.findall('.//trill-mark')))

    def test_unproved_source_mapping_does_not_borrow_spelling(self):
        for mode in ('count','index','staff','measure','inferred','unknown','missing'):
            doc = document(); page = doc['pages'][0]
            if mode == 'count': page['score']['notes'].append(copy.deepcopy(page['score']['notes'][0]))
            elif mode == 'index': page['events'][0]['sourceNoteIndex'] = 1
            elif mode == 'staff': page['score']['notes'][0]['staffIndex'] = 1
            elif mode == 'measure': page['score']['notes'][0]['measureIndex'] = 1
            elif mode == 'inferred': page['events'][0]['clefInferred'] = True
            elif mode == 'unknown': page['score']['notes'][0]['clefBottomDiatonic'] = -1
            else: del page['score']['notes'][0]['staffStep']
            self.assertIsNone(retained(page,0,0),mode)
            self.assertEqual(('C',1,4), self.pitch(doc),mode)

    def test_invalid_source_types_are_rejected(self):
        for key,value in [('staffStep',True),('writtenAccidental',4),('octaveShift',0.5)]:
            doc = document(); doc['pages'][0]['score']['notes'][0][key] = value
            with self.assertRaises(ValueError): self.export(doc)

    def test_unpitched_identity_is_not_respelt(self):
        page = document()['pages'][0]
        page['events'][0] = dict(kind='UNPITCHED',displayStep='C',displayOctave=4)
        self.assertIsNone(retained(page,0,0))

    def test_legacy_head_without_source_geometry_keeps_fallback(self):
        doc = document(); doc['pages'][0]['score']['notes'] = []
        self.assertEqual(('C',1,4), self.pitch(doc))
