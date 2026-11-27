# Shared page OCR pipeline (experimental)

`PortableOcr` is a Java-only detector/recognizer pipeline accepting ARGB pixels
and an `Inference` implementation. Windows and Android execute the same resize,
normalization, connected-component extraction, stacked-row splitting, CTC decoding,
and word construction. This Java pipeline is separate from the Python reader's
automatic RapidOCR path. This class does not download or activate a Java OCR engine.

The caller supplies detector probabilities, recognizer probabilities and a pinned
dictionary (including blank index zero). It must manage runtime/session ownership,
timeouts, and model verification. The recognizer expects 48-pixel BGR input,
zero padding to at least 320 pixels, and CTC probability output. Detector inputs
use BGR ImageNet normalization with maximum side 2048 and multiples of 32.

## Evidence and limits

- Original text-only fixtures at 18, 28 and 42 pixels: 123/123 tokens matched
  for both tested detector candidates with the Latin v5 recognizer.
- Isolated dynamics, fret numbers and stacked meter: mobile v4 detector missed
  `f` and `1`, and recognized `0` as `o`. Mobile v5 plus stacked-row separation
  matched 21/21 dark-print tokens. Faint-print v5 matched 19/21 exactly (one
  missing zero and a capitalization mismatch). No global digit substitution.
- Complete output (including all estimated symbol boxes) matched between Windows
  Java and an ARM64 Android phone on an original isolated-markings page and one
  private real-score raster. Private evidence is not included in this repository.
- These checks establish limited parity, not general OCR or musical accuracy.
  A full interpretation migration still requires downstream regression review.
- Boxes are axis-aligned. Rotated text and skew are not yet validated. Symbol
  boxes are estimated from CTC alignment, **not measured character contours**.

Candidate artifacts were evaluated from RapidOCR's versioned `v3.9.2` model
registry, without modifying weights:

| Artifact | SHA-256 |
| --- | --- |
| ch_PP-OCRv4_det_mobile.onnx | d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9 |
| ch_PP-OCRv5_det_mobile.onnx | 4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae |
| latin_PP-OCRv5_rec_mobile.onnx | b20bd37c168a570f583afbc8cd7925603890efbcdc000a59e22c269d160b5f5a |
| Explicit UTF-8 Latin dictionary | 1169fb297871f7a14d6a0f20c14af56de789c48b170e59dfb66950448e31c062 |

The explicit dictionary avoids relying on runtime-specific metadata string
decoding. Validate its length against the output vocabulary. Android ONNX Runtime
1.23.2 crashed on a tested Snapdragon/Android 16 device; 1.25.1 passed these
checks. Windows Java also used 1.25.1 with a compatible C++ runtime. These are
evaluation dependencies, not additions to the JDK-only default package.

## Optional DirectML detector service on Windows

The [isolated desktop DirectML worker](portable-segmentation.md#optional-directml-worker-on-windows)
can also serve the pinned mobile v5 detector from the same environment.

```sh
python optional/segmentation/directml_worker.py --detector-service /models/ch_PP-OCRv5_det_mobile.onnx detector.map --adapter 1
```

The host owns a private 5,242,880-byte scratch mapping for the lifetime of the
service. Write a normalized little-endian float32 BGR tensor at offset zero,
then send its width and height as two unsigned big-endian 32-bit integers.
Supported widths and heights are 480×128, 2048×64, 2048×96, and 2048×160.
Wait for the one-byte `K` acknowledgement before reading the float32 map at
offset 3,932,160 or reusing the input area. Close stdin and wait for the helper
before deleting the mapping.

The host must impose a request timeout, fall back to the stock detector on
failure, and apply CPU parity checks for its chosen hardware and workload.
The evaluated host also retried maps on CPU when foreground probability or
component confidence was within 0.0003 of the existing OCR thresholds.

## Optional desktop evaluation

Build the normal Java core first. Obtain the desktop `onnxruntime-1.25.1.jar`
from the official Maven coordinate `com.microsoft.onnxruntime:onnxruntime:1.25.1`.
Download the pinned models from the registry paths
`onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx` and
`onnx/PP-OCRv5/rec/latin_PP-OCRv5_rec_mobile.onnx` under
`https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/`.
The binding verifies all model/dictionary hashes before opening model sessions.

For text crops that the caller has already located,
`OnnxOcrInference.recognizerOnly(recognizerPath)` verifies the same recognizer and
adjacent dictionary, then opens only the recognizer session. It keeps the same
recognition thread configuration and inference/CTC output contract. Calling
`detect` on this explicitly selected mode throws `IllegalStateException` before
allocating a tensor. The two-path constructor remains available for complete
page OCR and verifies both models; no caller selects line-only behavior implicitly.

With the optional Python `onnxruntime` package installed, export the explicit
dictionary beside the recognizer:

```sh
python scripts/export_ocr_dictionary.py /path/to/latin_PP-OCRv5_rec_mobile.onnx
python scripts/build_portable_ocr.py --onnx-jar /path/to/onnxruntime-1.25.1.jar
```

Use the printed classpath with Java class
`io.github.luckolite.interpreter.PortableOcrProbe`, followed by detector path,
recognizer path and image path. It prints two timed passes and TSV rows containing
base64 UTF-8 text plus original-pixel left/top/right/bottom coordinates.
`-Docr.symbols=true` prints estimated symbols instead of words.
This evaluation command does not create interpretation caches or sync anything.

Windows must have a compatible Visual C++ runtime. Some JDK installations carry
old private runtime DLLs that shadow a newer system installation; an ONNX library
load failure is not evidence of model failure. Use a compatible supported JDK/runtime
installation rather than replacing DLLs inside another application's installation.

To check the optional binding against the previous session configuration, run
the generated-tensor regression after the normal Java build:

```sh
python scripts/test_portable_ocr.py --onnx-jar /path/to/onnxruntime-1.25.1.jar \
  --detector /path/to/ch_PP-OCRv5_det_mobile.onnx \
  --recognizer /path/to/latin_PP-OCRv5_rec_mobile.onnx
```

Repeat `--detector` with the supported v4 detector path to check both artifacts.
`--java /path/to/java` selects a runtime separately from the build JDK.
The test compares every returned probability float bit on 27 generated tensors
per detector, including repeated calls, the dictionary and unchanged caller
inputs. It uses the original two-intra/one-inter-thread configuration as its
reference. No images, model downloads or interpretation caches are involved.
This checks output compatibility on generated inputs, not OCR accuracy.

The same command also runs `OnnxRecognizerOnlyParity` once for the recognizer.
Nine original generated tensors, each read twice, compare the full binding and
recognizer-only mode for every probability bit, dictionary, CTC text/tokens and
confidence, and unchanged caller input. It also checks that detection is rejected
in line-only mode. This is an optional native gate using the supplied models and
runtime; it adds no runtime or model dependency to the ordinary Java/Python gates.

The normal Python unittest suite also runs lifecycle fault controls
against a fake public ORT API in an isolated temporary classpath. It checks
partial constructor acquisition, options/dictionary failures, attempted cleanup,
primary/suppressed exception identity, immutable dictionaries and unchanged
inference input bits and caller arrays. The temporary test copy substitutes
checksum and dictionary IO and native library loading. Reversing the three hooks
restores the exact binding.
These controls require a JDK but no runtime binaries, models or images. They prove
Java ownership and failure behavior, not native resource release after a failed
native close or model accuracy.

Model lineage and redistribution terms:
[RapidOCR registry](https://github.com/RapidAI/RapidOCR/blob/main/python/rapidocr/default_models.yaml),
[RapidOCR model licensing](https://github.com/RapidAI/RapidOCR/blob/main/README.md),
[PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR),
[ONNX Runtime](https://github.com/microsoft/onnxruntime).
RapidOCR documents converted artifacts under the upstream Apache-2.0 terms.
No third-party model artifacts, fonts, runtime binaries, or private scans are
included by this change.

## Optional Android ARM64 recognizer

Android callers using ONNX Runtime 1.25.1 can open
`OnnxOcrInference.openPackedArm64(detectorPath, packedRecognizerPath, libraryPath)`.
Generate the recognizer with Python ONNX 1.19.1 and NumPy 1.26.2. Build the custom
operations with Zig 0.15.2 and the headers extracted from the Android 1.25.1 AAR.

```sh
python optional/ocr/native/generate_packed_recognizer.py latin_PP-OCRv5_rec_mobile.onnx packed.onnx
python optional/ocr/native/build_packed_recognizer.py /path/to/zig /path/to/ort-headers libscore_reader_packed_ocr.so
```

Copy the original pinned dictionary to `packed.onnx.dictionary`. Keep one copy
of the native library at a stable absolute path for the process. The loader
verifies the model, dictionary and library before opening the packed recognizer.
The detector uses two inference threads and the recognizer uses four. The
generated graph compresses repeated activations on 320-pixel inputs and retains
the original operations for other input widths. The existing Java constructors
and the Python CLI continue to use their usual models.

For the exact-affine detector, run
`optional/ocr/native/generate_fused_detector.py` with the pinned stock v5 detector
and an output path. Pass `--with-fused-detector` to `build_packed_recognizer.py`
to generate the companion ARM64 library. Supply the generated detector and
companion library to the same `openPackedArm64` entry point.

## Optional Windows x64 detector

Build the DLL using `optional/ocr/native/build_fused_detector_windows.py` with
Zig 0.15.2 and the same ONNX Runtime 1.25.1 headers. Call
`OnnxOcrInference.openFusedDetectorWindows(detectorPath, recognizerPath, libraryPath)`
with the generated exact-affine detector and the pinned stock recognizer.

The generators derive both graphs from checksum-pinned RapidOCR PP-OCRv5
models. The native builders pin the ONNX Runtime headers and verify the generated
libraries. The repository ships the generator and native source code.

With two detector threads and four recognizer threads, the Samsung ARM64
comparison matched all 24,251,536 output values bit for bit. The detector took
16.6% less time and the mixed full-page and strip OCR batch took 14.1% less time.
On Windows x64 the detector-only comparison matched all 62,232,576 output values
and took 16.6% less time. Both complete saved-segmentation score replays matched
their stock controls. Whole-score timing varied by page.

## Optional local worker

The same optional build also provides `NativeOcrServer` on loopback port 45925.
Its parent supplies a random 64-hex credential on stdin and keeps stdin open;
closing the pipe stops the worker. Clients use `NativeOcrWire.exchange` with the
same credential and `NativeDecoderBuild.SOURCE_SHA256`. Identity is checked before
reading raster data. Compressed packets, expanded pixels, result records and the
three-worker request queue are bounded. Buffered gzip avoids per-pixel native
compressor calls. This endpoint is for a trusted local machine, not internet use.

`NativeOcrRoundTrip MODEL_DIRECTORY SYNTHETIC_IMAGE [JAVA_EXECUTABLE]` tests local
and remote evidence equality, three concurrent clients, identity rejection and
parent shutdown. The caller supplies the synthetic image; no private score is
distributed. Android integration separately uses lazy verified assets, page-scoped
evidence reuse and the identical local engine when the PC is unavailable. Those
lifecycle/cache adapters are not part of this standalone distribution.

Validation includes exact packaged Android ARM/emulator versus Windows page
evidence and a downstream comparison of notes, measures, meter and tempo on one
private page. This is not a claim of whole-library equivalence or ground-truth
accuracy. Pale tiny markings and rotated crops remain evaluation limitations.
The standalone CLI uses its installed Python OCR dependency automatically; it does
not start this optional Java OCR provider.
