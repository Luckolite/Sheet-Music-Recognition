# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""RapidOCR model loading for score pages with optional printed text."""

from threading import Lock

from rapidocr import RapidOCR
from rapidocr.main import CalRecBoxes, LoadImage, TextClassifier, TextDetector, TextRecognizer


def _detector_with_worker_mode(cfg):
    from omegaconf import flag_override
    from rapidocr.inference_engine.onnxruntime.main import OrtInferSession

    class DetectorSession(OrtInferSession):
        @staticmethod
        def _init_sess_opts(settings):
            options = OrtInferSession._init_sess_opts(settings)
            options.add_session_config_entry("session.set_denormal_as_zero", "1")
            return options

    # Keep RapidOCR's model resolution, checksum and provider selection. Its
    # session hook accepts a prepared ONNX session in this detector config.
    session = DetectorSession(cfg).session
    local = cfg.copy()
    with flag_override(local, "allow_objects", True):
        local.session = session
    return TextDetector(local)


class ScorePageOcr(RapidOCR):
    def _initialize(self, cfg):
        # RapidOCR 3.9.2 constructs all three models in this hook. Its normal
        # pipeline only calls the classifier and recognizer after finding text.
        self.cfg = cfg
        settings = cfg.Global
        for name in (
            "text_score", "min_height", "width_height_ratio", "max_side_len", "min_side_len",
            "use_det", "use_cls", "use_rec", "return_word_box", "return_single_char_box",
        ):
            setattr(self, name, getattr(settings, name))
        for stage in (cfg.Det, cfg.Cls, cfg.Rec):
            stage.engine_cfg = cfg.EngineConfig[stage.engine_type.value]
            stage.model_root_dir = settings.model_root_dir
        cfg.Rec.font_path = settings.font_path
        from .ocr import _detector_inference

        build_detector = TextDetector
        if cfg.Det.engine_type.value == "onnxruntime" and cfg.Det.get("session") is None:
            # Setting the worker option can also change the constructing thread.
            build_detector = _detector_inference(_detector_with_worker_mode, fallback=TextDetector)
        self.text_det = build_detector(cfg.Det)
        self.load_img = LoadImage()
        self.cal_rec_boxes = CalRecBoxes()
        self._classifier = None
        self._recognizer = None
        self._model_lock = Lock()

    @property
    def text_cls(self):
        with self._model_lock:
            if self._classifier is None:
                self._classifier = TextClassifier(self.cfg.Cls)
            return self._classifier

    @property
    def text_rec(self):
        with self._model_lock:
            if self._recognizer is None:
                self._recognizer = TextRecognizer(self.cfg.Rec)
            return self._recognizer
