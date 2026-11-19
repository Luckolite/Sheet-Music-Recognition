# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""RapidOCR model loading for score pages with optional printed text."""

from threading import Lock

from rapidocr import RapidOCR
from rapidocr.main import CalRecBoxes, LoadImage, TextClassifier, TextDetector, TextRecognizer


def _use_bounded_detector(cfg):
    return (cfg.get("model_path") is None
            and (cfg.ocr_version.value, cfg.lang_type.value, cfg.model_type.value) == ("PP-OCRv6", "ch", "small")
            and cfg.task_type.value == "det"
            and not any(cfg.engine_cfg.get(key, False) for key in
                        ("use_cuda", "use_dml", "use_cann", "use_coreml")))


def _bind_detector_output(engine):
    import numpy as np
    from onnxruntime import RunOptions

    session = engine.session
    input_name = session.get_inputs()[0].name
    output_name = session.get_outputs()[0].name
    options = RunOptions()
    options.add_run_config_entry("memory.enable_memory_arena_shrinkage", "cpu:0")

    def run(tensor):
        # The default detector returns one score per spatial input position.
        # RapidOCR preprocesses into a contiguous batch aligned to 32 pixels.
        if (tensor.ndim != 4 or tensor.shape[:2] != (1, 3) or tensor.dtype != np.float32
                or not tensor.flags.c_contiguous or any(size < 32 or size % 32 for size in tensor.shape[2:])):
            return engine(tensor)
        scores = np.empty((1, 1, *tensor.shape[2:]), np.float32)
        binding = session.io_binding()
        binding.bind_cpu_input(input_name, tensor)
        binding.bind_output(output_name, "cpu", 0, np.float32, scores.shape, scores.ctypes.data)
        session.run_with_iobinding(binding, options)
        return scores

    return run


def _detector_with_worker_mode(cfg):
    from omegaconf import flag_override
    from rapidocr.inference_engine.onnxruntime.main import OrtInferSession

    bounded = _use_bounded_detector(cfg)

    class DetectorSession(OrtInferSession):
        @staticmethod
        def _init_sess_opts(settings):
            options = OrtInferSession._init_sess_opts(settings)
            options.add_session_config_entry("session.set_denormal_as_zero", "1")
            if bounded:
                # A cached memory pattern would hold an extra activation slab.
                options.enable_cpu_mem_arena = True
                options.enable_mem_pattern = False
                options.add_session_config_entry("session.use_device_allocator_for_initializers", "1")
            return options

    # Keep RapidOCR's model resolution, checksum and provider selection. Its
    # session hook accepts a prepared ONNX session in this detector config.
    session = DetectorSession(cfg).session
    local = cfg.copy()
    with flag_override(local, "allow_objects", True):
        local.session = session
    detector = TextDetector(local)
    if bounded and session.get_providers() == ["CPUExecutionProvider"]:
        # Bind output outside the arena so the run can release unused workspace.
        detector.session = _bind_detector_output(detector.session)
    return detector


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
