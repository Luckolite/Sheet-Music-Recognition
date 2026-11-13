# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Model-free actual reader/parser/CLI operations fed by the isolated real Main bridge."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from threading import Event
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image
from sheet_music_recognition import cli, reader
from sheet_music_recognition.typed_performance import unpitched_intervals
import test_main_typed_cli as bridge_fixture
from test_main_typed_cli import CORE, PACKAGE, jdk

REAL_RUN = subprocess.run
REAL_POPEN = subprocess.Popen

class TypedFrontendCliTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        bridge_fixture.MainTypedCliTests.setUpClass()
        cls.backend = bridge_fixture.MainTypedCliTests.classes
    @classmethod
    def tearDownClass(cls):
        bridge_fixture.MainTypedCliTests.tearDownClass()
        bridge_fixture.MainTypedCliTests.doClassCleanups()
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='typed-frontend-', dir=os.environ.get('MAIN_TYPED_OUTPUT_ROOT'))
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.image = self.folder/'original.png'
        Image.new('L',(8,8),255).save(self.image)
        self.annotations = self.folder/'annotations.json'
        self.annotations.write_text('[{}]',encoding='utf-8')
        self.output = self.folder/'decoded.json'
        # The real interpret method runs; only unavailable inference and its resize input are supplied.
        self.engine = reader.Interpreter.__new__(reader.Interpreter)
        self.engine.jar = Path('unused-isolated-fixture.jar')
        self.engine.model_sha256 = 'original-synthetic-no-model-used'
        self.engine.ocr = None
        self.engine.paper_whitening = reader.PaperBackgroundWhitening()
        self.fixture = 1
        def predict(gray):
            labels = np.zeros_like(gray)
            labels.flat[0] = self.fixture
            return labels
        self.engine.predict = predict
    def bridge_run(self,args,**kwargs):
        if '-jar' in args:
            return REAL_RUN([jdk('java'),'-cp',str(self.backend)+os.pathsep+CORE,
                    PACKAGE+'.Main',*map(str,args[args.index('-jar')+2:])],**kwargs)
        return REAL_RUN(args,**kwargs)
    def invoke(self,*exports,reject=None):
        args=['sheet-music-recognition',str(self.image),'-o',str(self.output),'--annotations',str(self.annotations),*map(str,exports)]
        errors=io.StringIO()
        with patch('sys.argv',args), patch.object(cli,'Interpreter',return_value=self.engine), \
             patch.object(reader,'grayscale',return_value=np.full((8,8),255,dtype=np.uint8)), \
             patch.object(reader.subprocess,'run',side_effect=self.bridge_run), \
             contextlib.redirect_stderr(errors):
            if reject:
                with self.assertRaises(SystemExit) as result:cli.main()
                self.assertEqual(1,result.exception.code)
                self.assertIn(reject,errors.getvalue())
            else:cli.main()
        return json.loads(self.output.read_text(encoding='utf-8'))
    def test_actual_reader_json_parse_preserves_kind_metadata_and_source_indices(self):
        value=self.invoke()
        page=value['pages'][0]
        self.assertEqual([1,2],[e['sourceNoteIndex'] for e in page['events'][1:]])
        self.assertEqual(['UNPITCHED']*2,[e['kind'] for e in page['events'][1:]])
        self.assertEqual('UNPITCHED',page['score']['notes'][1]['kind'])
        self.assertEqual(1,page['sourcePage'])
        self.assertEqual(4,page['totalBeats'])
        self.assertEqual([4],page['measureBeats'])
        self.assertEqual(['unpitched:0:1','unpitched:0:2'],[n['sourceId'] for n in unpitched_intervals(value)])
        for e in page['events'][1:]:self.assertNotIn('midi',e)
    def test_image_frontend_forwards_page_zero_and_retains_deferred_source(self):
        self.fixture=0
        page=self.invoke()['pages'][0]
        source=page['score']['slurSource']
        self.assertEqual([],source['spans'])
        self.assertEqual(0,source['candidates'][0]['start']['pageIndex'])
        self.assertEqual([{'pageIndex':0,'noteIndex':i} for i in range(2)],source['noteOrigins'])
    def test_pdf_selection_forwards_physical_pages_without_renumbering(self):
        self.fixture=0
        self.image=self.folder/'original-six-pages.pdf'
        import pypdfium2 as pdfium
        pdf=pdfium.PdfDocument.new()
        try:
            for _ in range(6):
                pdf.new_page(8,8).close()
            pdf.save(self.image)
        finally:
            pdf.close()
        self.annotations.write_text('[{},{}]',encoding='utf-8')
        value=self.invoke('--pages','2,6')
        self.assertEqual([2,6],[p['sourcePage'] for p in value['pages']])
        self.assertEqual([1,5],[p['score']['slurSource']['candidates'][0]['start']['pageIndex']
                              for p in value['pages']])
        progress = []
        with patch.object(reader,'grayscale',return_value=np.full((8,8),255,dtype=np.uint8)), \
             patch.object(reader.subprocess,'run',side_effect=self.bridge_run), \
             patch.object(self.engine,'interpret',wraps=self.engine.interpret), \
             patch('sheet_music_recognition.pdf_annotations.tuning_header') as header, \
             patch('sheet_music_recognition.pdf_annotations.tab_words') as native_words:
            document = self.engine.interpret_document(
                self.image, pages=[2, 6], annotations=[{}, {}],
                progress=lambda *event: progress.append(event),
            )
            header.assert_not_called()
            native_words.assert_not_called()
        self.assertEqual(value, document)
        self.assertEqual([(1, 2, 2), (2, 2, 6)], progress)
        with patch.object(self.engine, 'interpret') as interpret:
            for selection in ([], [0], [7], [2, 2], [6, 2], [True]):
                with self.assertRaises(ValueError):
                    self.engine.interpret_document(self.image, pages=selection)
            with self.assertRaisesRegex(ValueError, 'Annotation count'):
                self.engine.interpret_document(self.image, pages=[2, 6], annotations=[{}])
            interpret.assert_not_called()
    def test_invalid_page_identity_rejects_before_pixels_or_inference(self):
        with patch.object(reader,'grayscale') as pixels:
            for page in (-1,100001,True,1.5,'2'):
                with self.assertRaisesRegex(ValueError,'page_index'):
                    self.engine.interpret(None,page_index=page)
            pixels.assert_not_called()

    def test_pdf_prefetch_overlaps_decode_and_carries_key_and_meter_in_order(self):
        import pypdfium2 as pdfium

        source = self.folder / 'overlap.pdf'
        pdf = pdfium.PdfDocument.new()
        try:
            for _ in range(2):
                pdf.new_page(8, 8).close()
            pdf.save(source)
        finally:
            pdf.close()

        decoding = Event()
        next_ready = Event()
        contexts = []
        progress = []

        def prepare(_image, _width, supplied):
            if supplied['page'] == 2:
                self.assertTrue(decoding.wait(5))
                next_ready.set()
            return supplied['page']

        def decode(prepared, meter, key, index, instrument):
            contexts.append((prepared, meter, key, index, instrument))
            if index == 0:
                decoding.set()
                self.assertTrue(next_ready.wait(5))
            return dict(events=[], measureBeats=[], totalBeats=0,
                        score=dict(notes=[], measures=[], keyChanges=[dict(fifths=-2)],
                                   meterChanges=[dict(measureIndex=0, numerator=3, denominator=8)]))

        with patch.object(self.engine, '_prepare_page', side_effect=prepare), \
             patch.object(self.engine, '_decode_page', side_effect=decode):
            result = self.engine.interpret_document(
                source, annotations=[{'page': 1}, {'page': 2}],
                progress=lambda *event: progress.append(event))
        self.assertEqual([(1, (4, 4), 0, 0, None), (2, (3, 8), -2, 1, None)], contexts)
        self.assertEqual([(1, 2, 1), (2, 2, 2)], progress)
        self.assertEqual([1, 2], [page['sourcePage'] for page in result['pages']])

        def fail_second(image, width, supplied):
            if supplied['page'] == 2:
                raise ValueError('second page preparation failed')
            return prepare(image, width, supplied)

        for decoder_fails in (False, True):
            with self.subTest(decoder_fails=decoder_fails):
                next_ready.set()
                progress.clear()
                contexts.clear()
                outcome = ValueError('first page decode failed') if decoder_fails else decode
                expected = 'first page decode failed' if decoder_fails else 'second page preparation failed'
                with patch.object(self.engine, '_prepare_page', side_effect=fail_second), \
                     patch.object(self.engine, '_decode_page', side_effect=outcome):
                    with self.assertRaisesRegex(ValueError, expected):
                        self.engine.interpret_document(
                            source, annotations=[{'page': 1}, {'page': 2}],
                            progress=lambda *event: progress.append(event))
                self.assertEqual([] if decoder_fails else [(1, 2, 1)], progress)

    def test_raster_ocr_overlaps_prediction_and_whitening(self):
        ocr_started = Event()
        whitening_done = Event()
        gray = np.full((8, 8), 255, dtype=np.uint8)
        words = [{'text': 'più', 'left': .1, 'top': .1, 'right': .2, 'bottom': .2}]

        def read_annotations(image, supplied):
            self.assertIs(image, gray)
            self.assertIsNone(supplied)
            ocr_started.set()
            self.assertTrue(whitening_done.wait(5))
            return {'words': words}

        def predict(image):
            self.assertTrue(ocr_started.wait(5))
            return np.zeros_like(image)

        def correct(image, labels):
            whitening_done.set()
            return reader.WhiteningResult(image, False, 'raw', 'raw')

        with patch.object(reader, 'grayscale', return_value=gray), \
             patch.object(self.engine, '_read_annotations', side_effect=read_annotations), \
             patch.object(self.engine, 'predict', side_effect=predict), \
             patch.object(self.engine.paper_whitening, 'correct', side_effect=correct):
            prepared = self.engine._prepare_page(None, 64, None)
        self.assertEqual({'words': words}, prepared.annotations)

    def test_failed_ocr_does_not_leave_a_worker_running(self):
        gray = np.full((8, 8), 255, dtype=np.uint8)
        with patch.object(reader, 'grayscale', return_value=gray), \
             patch.object(self.engine, '_read_annotations', side_effect=ValueError('bad OCR')):
            with self.assertRaisesRegex(ValueError, 'bad OCR'):
                self.engine._prepare_page(None, 64, None)
            prepared = self.engine._prepare_page(None, 64, {})
        self.assertEqual({}, prepared.annotations)
    def test_direct_reader_without_page_identity_preserves_legacy_unknown_source(self):
        self.fixture=0
        with patch.object(reader,'grayscale',return_value=np.full((8,8),255,dtype=np.uint8)), \
             patch.object(reader.subprocess,'run',side_effect=self.bridge_run):
            page=self.engine.interpret(None,annotations={})
        self.assertEqual([],page['score']['slurSource']['candidates'])
        self.assertEqual([None,None],page['score']['slurSource']['noteOrigins'])
    def test_actual_cli_xml_preserves_all_heads_and_written_chord_clocks(self):
        path=self.folder/'written.musicxml'
        value=self.invoke('--musicxml',path)
        doc=ET.parse(path)
        self.assertEqual(2,len(doc.findall('.//unpitched')))
        self.assertEqual(1,len(doc.findall('.//pitch')))
        cross=[n for n in doc.findall('.//note') if n.find('unpitched') is not None]
        self.assertEqual(['x','x'],[n.findtext('notehead') for n in cross])
        self.assertEqual(['10080','10080'],[n.findtext('duration') for n in cross])
        self.assertEqual(['E','E'],[n.findtext('unpitched/display-step') for n in cross])
        self.assertEqual([0,0],[e['startBeat'] for e in value['pages'][0]['events'][1:]])
    def test_actual_reader_retains_neutral_dead_without_inventing_pitch(self):
        self.fixture=13
        value=self.invoke();event=value['pages'][0]['events'][0]
        self.assertEqual('UNPITCHED',event['kind']);self.assertEqual(0,event['sourceNoteIndex'])
        self.assertEqual(dict(type='dead',semitones=0,vibrato=False,palmMute=False),event['guitarEffect'])
        self.assertNotIn('midi',event)
        self.assertEqual(1,event['durationBeats'])
    def test_raw_octave_shift_reader_failure_preserves_existing_json(self):
        self.fixture=10
        self.output.write_text('{"existing":"json"}',encoding='utf-8')
        value=self.invoke(reject='source octave shifts need explicit instrument semantics')
        self.assertEqual(dict(existing='json'),value)
    def test_default_cli_midi_rejects_typed_notes_without_replacing_output(self):
        path=self.folder/'preview.mid';path.write_bytes(b'existing-midi')
        value=self.invoke('--midi',path,reject='explicit UnpitchedMidiPreview policy')
        self.assertEqual(b'existing-midi',path.read_bytes())
        self.assertEqual('UNPITCHED',value['pages'][0]['events'][1]['kind'])
    def test_default_cli_mp3_rejects_before_encoder_and_preserves_output(self):
        path=self.folder/'preview.mp3';path.write_bytes(b'existing-mp3')
        encoder_calls=[]
        def launch(args,*values,**options):
            if args[0]=='unused-ffmpeg':
                encoder_calls.append(args)
                raise AssertionError('Unsupported raw U must not launch the MP3 encoder')
            return REAL_POPEN(args,*values,**options)
        with patch('sheet_music_recognition.audio.find_ffmpeg',return_value='unused-ffmpeg'), \
             patch('sheet_music_recognition.audio.subprocess.Popen',side_effect=launch):
            value=self.invoke('--mp3',path,reject='explicit UnpitchedAudioPreview policy')
            self.assertEqual([],encoder_calls)
        self.assertEqual(b'existing-mp3',path.read_bytes())
        self.assertEqual('UNPITCHED',value['pages'][0]['events'][1]['kind'])

if __name__=='__main__':unittest.main()
