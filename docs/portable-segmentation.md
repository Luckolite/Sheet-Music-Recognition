# Optional desktop ONNX segmentation

The optional `NativeSegmentation` adapter runs the desktop conversion of the
original Music Sheets v3 model. It returns one class byte per grayscale pixel
and verifies the model checksum before creating its session. It uses
`max(2, min(4, availableProcessors))` intra-operation threads, matching the
existing managed-processing cap. Other session options, pixel packing, tiling,
edge ownership, class validation and exact-white prediction reuse are unchanged.
The separate Python reader retains its existing v4 model and execution policy.

This adapter is outside the default JDK-only Java core. No ONNX runtime binary,
converted model, private score, phone data or new mandatory dependency is added.
Supply the official `com.microsoft.onnxruntime:onnxruntime:1.25.1` desktop JAR and
an already verified local conversion to build and run the generated controls:

```sh
python scripts/build_java.py
python scripts/build_portable_segmentation.py --onnx-jar /path/to/onnxruntime-1.25.1.jar \
  --model /path/to/music_sheets_v3_float16.onnx --java /path/to/java
```

Omit `--model` to compile only. Both supplied artifacts are checksum-checked.
The separate runtime option supports a Java installation with a compatible
Windows C++ runtime; it does not modify the build JDK or download anything.
The parity test compares every label byte with an independently compiled copy
of the previous two-thread adapter on six original generated rasters, repeated
calls and unchanged caller arrays. Shapes include padded, tile-boundary,
overlapping and narrow pages; patterns include white, shaded, ruled and random
pixels. This is output compatibility evidence, not a recognition-accuracy claim.

## Artifact lineage

| Artifact | SHA-256 |
| --- | --- |
| Original Apache-2.0 v3 TFLite | `9504caccc669ee1cf2fcdf5963f77d26102f44714b2bca28dfe7d4a62eed0af8` |
| Existing v3 ONNX conversion | `e436efe12ddc598add9540378d6772622a2ad9d7bdb1f9d4c0ab87e3144402a7` |
| Official ONNX Runtime 1.25.1 desktop JAR | `749793ebed63743fec853d093da7987a86ea5cd592d54fba898cd3233100c381` |

The ONNX artifact was converted from that exact TFLite with tf2onnx 1.16.1,
opset 16. It stores the same weights and retains float32 grayscale inputs and
int64 class outputs. This change does not reconvert, train, quantize or replace
any artifact. The original weights remain part of the project's own synthetic
training lineage; see the [model card](../models/MODEL_CARD.md) and
[lineage](../models/lineage.json).

A local desktop paired test on one 5.8-megapixel page completed segmentation in
33.4 rather than 48.8 seconds, with exact complete labels in four paired runs.
The median elapsed time fell 31.5%; process CPU time rose 34.0%. This measured
packing, inference and merging with identical model/runtime artifacts. It excludes
OCR, later interpretation, network transfer and phone processing. Results depend
on CPU, page and concurrency. Increasing threads does not establish universally
identical internal floating-point operations; complete labels and downstream
page output are checked separately before desktop release. A private comparison
of 22 complete pages produced identical pixel-label hashes and every serialized
page field; the source images and decoded private data are not distributed.

## Optional DirectML worker on Windows

Use a separate Python environment with `onnxruntime-directml==1.23.0`, NumPy,
and Pillow. Add the checkout's `src` directory to that environment's
`PYTHONPATH`. Use `onnx==1.19.1` to build the companion graph from the pinned
desktop conversion. The builder verifies both artifact hashes.

```sh
python scripts/build_directml_guard.py /models/music_sheets_v3_float16.onnx /models/guarded-margin.onnx
python optional/segmentation/directml_worker.py /models/music_sheets_v3_float16.onnx gray.npy labels.npy --adapter 1
```

Choose the DirectML adapter index for the machine. The worker accepts a
two-dimensional `uint8` grayscale array and writes a `uint8` label array with
the same shape. The companion graph adds a boolean flag for small class-score
margins. The worker reruns affected tiles with the checked CPU model and merges
them using the stock tile ownership rules.

The companion artifact SHA-256 is
`fbd4c8a767538ff53eca8c10507d68ab81e60bd584a54cb2321f60101b3ba282`.
The retry threshold is empirical. Qualify a device and workload against the
stock Java segmentation before enabling this provider in a host application.

Hosts can also start `directml_worker.py --segmentation-service MODEL ROOT
--adapter INDEX` to reuse the session for up to two pages. Create one private
subdirectory per request under `ROOT`, save its input as `page.npy`, and
write the directory's 32 lowercase hexadecimal characters followed by a
newline to stdin. The helper publishes `labels.npy` by atomic rename. Hosts
should enforce a deadline and close stdin when they finish using the session.
