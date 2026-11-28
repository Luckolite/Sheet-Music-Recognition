# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from xml.etree import ElementTree as ET

module_path = (Path(__file__).resolve().parents[1] / 'packaging' / 'lilypond'
               / 'score_reader_lilypond' / 'layouts.py')
spec = importlib.util.spec_from_file_location('sheet_layouts', module_path)
layouts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(layouts)


class SheetLayoutTests(unittest.TestCase):
    def test_original_guitar_and_single_staff_preserve_input(self):
        part = '''<part id="{id}"><measure number="1">
          <attributes><divisions>1</divisions></attributes>
          <note><pitch><step>{step}</step><octave>4</octave></pitch><duration>4</duration>
          <type>whole</type></note></measure></part>'''
        data = ('<score-partwise><part-list><score-part id="P1"><part-name>One</part-name>'
                '</score-part><score-part id="P2"><part-name>Two</part-name></score-part></part-list>'
                + part.format(id='P1', step='C') + part.format(id='P2', step='E') + '</score-partwise>')
        with TemporaryDirectory() as folder:
            source, out = Path(folder) / 'input.musicxml', Path(folder) / 'layout.musicxml'
            source.write_text(data, encoding='utf-8')
            self.assertEqual(source, layouts.prepare(source, out, 'original'))
            self.assertFalse(out.exists())
            tab = ET.parse(layouts.prepare(source, out, 'guitar'))
            self.assertEqual(['TAB', 'TAB'], [clef.text for clef in tab.findall('.//clef/sign')])
            self.assertEqual(12, len(tab.findall('.//staff-tuning')))
            single = ET.parse(layouts.prepare(source, out, 'single-staff'))
            self.assertEqual(1, len(single.findall('part')))
            self.assertEqual(['1', '2'], [n.text for n in single.findall('.//note/voice')])
            self.assertEqual('4', single.findtext('.//backup/duration'))
            self.assertEqual(data, source.read_text(encoding='utf-8'))
