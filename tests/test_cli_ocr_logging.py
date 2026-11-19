# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0

import logging
import unittest
from unittest.mock import Mock, patch

from sheet_music_recognition import cli


class CliOcrLoggingTest(unittest.TestCase):
    def test_redirected_filter_keeps_warnings_and_is_removed_on_failure(self):
        logger = logging.getLogger("RapidOCR")
        previous = tuple(logger.filters)
        stderr = Mock()
        stderr.isatty.return_value = False
        info = logging.makeLogRecord({"levelno": logging.INFO})
        warning = logging.makeLogRecord({"levelno": logging.WARNING})

        with patch.object(cli.sys, "stderr", stderr):
            with self.assertRaisesRegex(ValueError, "failed read"):
                with cli._ocr_diagnostics():
                    self.assertFalse(logger.filter(info))
                    self.assertTrue(logger.filter(warning))
                    raise ValueError("failed read")

        self.assertEqual(tuple(logger.filters), previous)

    def test_interactive_progress_keeps_the_library_configuration(self):
        logger = logging.getLogger("RapidOCR")
        previous = tuple(logger.filters)
        stderr = Mock()
        stderr.isatty.return_value = True

        with patch.object(cli.sys, "stderr", stderr), cli._ocr_diagnostics():
            self.assertEqual(tuple(logger.filters), previous)
