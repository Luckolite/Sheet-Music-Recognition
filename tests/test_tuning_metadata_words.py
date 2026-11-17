# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
import unittest
from unittest.mock import Mock
from sheet_music_recognition.pdf_annotations import tab_words, tuning_words


class TablatureMetadataTextTest(unittest.TestCase):
    def test_native_tempo_units_and_same_line_tuning_headers(self):
        from sheet_music_recognition.pdf_annotations import _TOKEN, _tuning_header

        marks = "\ueca3 \ueca4 \ueca5 \ueca6 \ueca7 \ueca8 \uecb7 = 132"
        self.assertEqual(marks.split(), _TOKEN.findall(marks))
        self.assertEqual(
            "Tuning: B1-E2-A2-D3-G3-B3-E4\nTuning: D2, A2, D3, G3, A3, D4",
            _tuning_header("Tuning: B1-E2-A2-D3-G3-B3-E4\r\n"
                           "Other text\r\nTuning: D2, A2, D3, G3, A3, D4"),
        )


class TuningMetadataWordsTests(unittest.TestCase):
    def test_document_header_uses_one_canonical_metadata_box(self):
        header = 'Tuning : D A D G B E'
        expected = [dict(text=header, left=.01, top=.001, right=.9, bottom=.009)]
        self.assertEqual(expected, tuning_words(header))
        first = tuning_words(header)
        first[0]['text'] = 'changed caller copy'
        self.assertEqual(expected, tuning_words(header))

    def test_empty_header_never_invents_tuning(self):
        self.assertEqual([], tuning_words(''))

    def test_normal_tab_words_reuses_the_canonical_document_metadata(self):
        page = Mock()
        textpage = page.get_textpage.return_value
        textpage.get_text_range.return_value = ''
        page.get_width.return_value = 500
        page.get_height.return_value = 400
        header = 'Tuning : D A D G B E'
        self.assertEqual(tuning_words(header), tab_words(page, header))
        textpage.close.assert_called_once_with()

    def test_normal_page_without_document_header_stays_empty(self):
        page = Mock()
        textpage = page.get_textpage.return_value
        textpage.get_text_range.return_value = ''
        page.get_width.return_value = 500
        page.get_height.return_value = 400
        self.assertEqual([], tab_words(page))
        textpage.close.assert_called_once_with()
