# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import copy
import struct
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
from sheet_music_recognition.musicxml import write_musicxml, DIVISIONS
from tests.test_expressive_performance import document, expression, fermata, rest_fermata_document, tempo_rest_document


class MusicXmlExpressionTests(unittest.TestCase):
    def export(self, doc):
        original=copy.deepcopy(doc)
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'synthetic.musicxml'
            write_musicxml(doc,path)
            root=ET.parse(path).getroot()
        self.assertEqual(original,doc)
        return root

    def test_verbal_tempo_at_a_rest_keeps_only_its_printed_word(self):
        root = self.export(tempo_rest_document())
        direction = next(d for d in root.findall('.//direction')
                         if d.findtext('direction-type/words') == 'Lento')
        self.assertEqual(str(2 * DIVISIONS), direction.findtext('offset'))
        self.assertIsNone(direction.find('sound'))

    def test_metric_dots_are_structured_without_absolute_bpm(self):
        mark=expression('METRIC_MODULATION',2);mark['qualifierText']='metric-pulse-v1:0.75:1.75'
        root=self.export(document([mark]));relation=[m for m in root.findall('.//metronome') if len(m.findall('beat-unit'))==2][0]
        self.assertEqual(['eighth','quarter'],[n.text for n in relation.findall('beat-unit')])
        self.assertEqual(3,len(relation.findall('beat-unit-dot')))
        self.assertIsNone(relation.find('per-minute'))
        self.assertIsNone(relation.find('../sound'))
        self.assertEqual([],root.findall('.//words'))
        self.assertEqual(['20160','20160'],[n.text for n in root.findall('.//note/duration')])

    def test_sf_sfz_sfp_are_dynamics_with_written_offsets(self):
        for kind,name in [('SFORZANDO','sf'),('SFORZATO','sfz'),('SFORZANDO_PIANO','sfp')]:
            root=self.export(document([expression(kind,2,scope='NOTE')]))
            direction=[d for d in root.findall('.//direction') if d.find(f'direction-type/dynamics/{name}') is not None][0]
            self.assertEqual(str(2*DIVISIONS),direction.findtext('offset'))
            self.assertEqual([],root.findall('.//words'))

    def test_breath_and_fermata_are_on_written_release(self):
        for mark,tag in [(expression('BREATH',2,scope='PART'),'breath-mark'),(fermata(),'fermata')]:
            root=self.export(document([mark]));notes=root.findall('.//note')
            self.assertIsNotNone(notes[0].find('.//'+tag));self.assertIsNone(notes[1].find('.//'+tag))
            self.assertEqual(2,len(notes));self.assertEqual([],root.findall('.//words'))

    def test_owned_pickup_fermata_uses_the_written_note_instead_of_cached_anchors(self):
        mark = fermata()
        mark.update(start=dict(measureIndex=0, quarterBeatOffset=1.25),
                    end=dict(measureIndex=0, quarterBeatOffset=2.25))
        doc = document([mark])
        doc['initialMeter'] = [4, 4]
        page = doc['pages'][0]
        page.update(measureBeats=[1], totalBeats=1)
        page['events'] = [dict(page['events'][0], durationBeats=1)]
        page['score']['notes'] = page['score']['notes'][:1]
        root = self.export(doc)
        measure = root.find('part/measure')
        self.assertEqual('yes', measure.get('implicit'))
        self.assertEqual(str(DIVISIONS), measure.findtext('note/duration'))
        self.assertIsNotNone(measure.find('note/notations/fermata'))
        page['events'][0]['durationFallback'] = True
        with self.assertRaisesRegex(ValueError, 'Expression anchor outside MusicXML measure'):
            self.export(doc)

    def test_cross_bar_breath_is_only_on_final_tied_slice(self):
        doc=document([expression('BREATH',6,scope='PART')]);page=doc['pages'][0]
        page['measureBeats']=[4,4];page['totalBeats']=8;page['events']=page['events'][:1]
        page['events'][0].update(startBeat=2,durationBeats=4)
        root=self.export(doc);pitched=[n for n in root.findall('.//note') if n.find('pitch') is not None]
        self.assertIsNone(pitched[0].find('.//breath-mark'));self.assertIsNotNone(pitched[1].find('.//breath-mark'))
        self.assertEqual(['20160','20160'],[n.findtext('duration') for n in pitched])

    def test_fermata_rest_remains_silent_and_exact(self):
        doc=document([expression('FERMATA',0,2,scope='REST')]);doc['pages'][0]['events']=[]
        root=self.export(doc);notes=root.findall('.//note')
        self.assertIsNotNone(notes[0].find('rest'));self.assertIsNotNone(notes[0].find('.//fermata'))
        self.assertEqual(str(2*DIVISIONS),notes[0].findtext('duration'))
        self.assertEqual(4*DIVISIONS,sum(int(n.findtext('duration')) for n in notes));self.assertEqual([],root.findall('.//words'))

    def test_detector_owned_rest_fermata_is_written_on_rest(self):
        root=self.export(rest_fermata_document());note=root.find('part/measure/note')
        self.assertIsNotNone(note.find('rest'));self.assertIsNotNone(note.find('notations/fermata'))
        self.assertEqual(str(4*DIVISIONS),note.findtext('duration'));self.assertEqual([],root.findall('.//words'))
        self.assertEqual([],self.export(rest_fermata_document(1)).findall('.//fermata'))

    def test_owned_rest_fermata_uses_the_matched_position_for_rest_order(self):
        position = .6
        bits = struct.unpack('>i', struct.pack('>f', position))[0]
        mark = expression('FERMATA', scope='UNRESOLVED',
                          target=f'printed-rest:0:0:1:{bits}', source='fermata-rest-raw-ink')
        mark.update(start=None, end=None)
        doc = document([mark])
        page = doc['pages'][0]
        page['events'] = page['events'][:1]
        page['score']['notes'] = page['score']['notes'][:1]
        page['score']['rests'] = [
            dict(measureIndex=0, positionInMeasure=x, staffIndex=0, staffCount=1,
                 durationBeats=duration) for x, duration in ((.3, .5), (position, 1.5))
        ]
        notes = self.export(doc).findall('part/measure/note')
        self.assertEqual([2 * DIVISIONS, DIVISIONS // 2, 3 * DIVISIONS // 2],
                         [int(note.findtext('duration')) for note in notes])
        self.assertIsNotNone(notes[0].find('pitch'))
        self.assertIsNone(notes[1].find('notations/fermata'))
        self.assertIsNotNone(notes[2].find('rest'))
        self.assertIsNotNone(notes[2].find('notations/fermata'))

    def test_invalid_internal_pulse_and_unresolved_evidence_cannot_be_printed(self):
        invalid=expression('METRIC_MODULATION');invalid['qualifierText']='metric-pulse-v1:0.73:1'
        with self.assertRaises(ValueError):self.export(document([invalid]))
        mark=expression('BREATH',scope='UNRESOLVED');mark['start']=None
        root=self.export(document([mark]));self.assertEqual([],root.findall('.//breath-mark'))

    def test_printed_slowing_words_preserve_original_text(self):
        for kind,printed in [('RITARDANDO','rit.'),('RALLENTANDO','rall.'),('RITENUTO','rite')]:
            mark=expression(kind);mark['evidence'][0]['printedText']=printed
            root=self.export(document([mark]));self.assertEqual([printed],[w.text for w in root.findall('.//words')])
