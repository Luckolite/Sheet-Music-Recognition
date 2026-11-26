// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.*;

/** Optional runtime binding shared by the desktop and Android validation builds. */
public final class OnnxOcrInference implements PortableOcr.DirectDetectorInference, AutoCloseable {
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession detector, recognizer;
    private final List<String> dictionary;
    private final OrtSession.SessionOptions recognizerOptions, detectorOptions;
    private static String pinnedLibrary;
    private static final String PACKED_LIBRARY_SHA =
            "33e277d9de0a25cbe2568f220d0765dd53772c40efa8100ff900acd73be5d054";
    private static final String FUSED_LIBRARY_SHA =
            "eb51cd1c2164b2db1fa342cb2d4ca41760f3a0f5c49ce11133886e674ef43c7f";
    private static final String DETECTOR_V4_SHA =
            "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9";
    private static final String DETECTOR_V5_SHA =
            "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae";
    private static final String FUSED_DETECTOR_SHA =
            "60a49903b55f3333ffe3bfc9b52fc93d791a4b9bf33d7373387924abfd18f1d4";

    public OnnxOcrInference(String detectorPath, String recognizerPath) throws Exception {
        this(detectorPath, recognizerPath, false, null, false);
    }

    /** Own only the recognizer when text crops have already been located. */
    public static OnnxOcrInference recognizerOnly(String recognizerPath) throws Exception {
        return new OnnxOcrInference(null, recognizerPath, true, null, false);
    }

    /** Open the optional Android ARM64 CPU recognizer with a generated graph and custom operations. */
    public static OnnxOcrInference openPackedArm64(
            String detectorPath, String recognizerPath, String libraryPath) throws Exception {
        if (!"Dalvik".equals(System.getProperty("java.vm.name"))
                || !"aarch64".equals(System.getProperty("os.arch")))
            throw new UnsupportedOperationException("Packed OCR requires Android ARM64");
        var library = java.nio.file.Path.of(libraryPath).toRealPath().toString();
        String digest = verify(library, Set.of(PACKED_LIBRARY_SHA, FUSED_LIBRARY_SHA));
        return new OnnxOcrInference(
                detectorPath, recognizerPath, false, library, digest.equals(FUSED_LIBRARY_SHA));
    }

    private OnnxOcrInference(
            String detectorPath, String recognizerPath, boolean recognizerOnly,
            String packedLibrary, boolean supportsFusedDetector)
            throws Exception {
        if (packedLibrary != null && !"1.25.1".equals(environment.getVersion()))
            throw new UnsupportedOperationException("Packed OCR requires ONNX Runtime 1.25.1");
        boolean fusedDetector = !recognizerOnly && verify(
                detectorPath,
                supportsFusedDetector
                        ? Set.of(DETECTOR_V4_SHA, DETECTOR_V5_SHA, FUSED_DETECTOR_SHA)
                        : Set.of(DETECTOR_V4_SHA, DETECTOR_V5_SHA)).equals(FUSED_DETECTOR_SHA);
        verify(
                recognizerPath,
                Set.of(packedLibrary == null
                        ? "b20bd37c168a570f583afbc8cd7925603890efbcdc000a59e22c269d160b5f5a"
                        : "751b79329b420192022f8e9901bbdb243c60efa627f8abbc9d08b6fb4b47f740"));
        verify(
                recognizerPath + ".dictionary",
                Set.of("1169fb297871f7a14d6a0f20c14af56de789c48b170e59dfb66950448e31c062"));
        OrtSession openedDetector = null, openedRecognizer = null;
        OrtSession.SessionOptions packedOptions = null, registeredDetectorOptions = null;
        try {
            try (var options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(2);
                options.setInterOpNumThreads(1);
                // Keep spinning during a run, then release idle workers between runs.
                options.addConfigEntry("session.force_spinning_stop", "1");
                if (!recognizerOnly) {
                    if (fusedDetector) {
                        registeredDetectorOptions = new OrtSession.SessionOptions();
                        registeredDetectorOptions.setIntraOpNumThreads(2);
                        registeredDetectorOptions.setInterOpNumThreads(1);
                        registeredDetectorOptions.addConfigEntry("session.force_spinning_stop", "1");
                        pinLibrary(packedLibrary);
                        registeredDetectorOptions.registerCustomOpLibrary(packedLibrary);
                    }
                    openedDetector = environment.createSession(
                            detectorPath, registeredDetectorOptions == null ? options : registeredDetectorOptions);
                }
                if (packedLibrary == null) {
                    options.setIntraOpNumThreads(4);
                    openedRecognizer = environment.createSession(recognizerPath, options);
                } else {
                    packedOptions = new OrtSession.SessionOptions();
                    packedOptions.setIntraOpNumThreads(4);
                    packedOptions.setInterOpNumThreads(1);
                    packedOptions.addConfigEntry("session.force_spinning_stop", "1");
                    packedOptions.setMemoryPatternOptimization(false);
                    pinLibrary(packedLibrary);
                    packedOptions.registerCustomOpLibrary(packedLibrary);
                    openedRecognizer = environment.createSession(recognizerPath, packedOptions);
                }
            }
            dictionary =
                    List.copyOf(
                            java.nio.file.Files.readAllLines(
                                    new java.io.File(recognizerPath + ".dictionary").toPath(),
                                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception | Error failure) {
            closeAfterFailure(openedDetector, failure);
            closeAfterFailure(openedRecognizer, failure);
            closeAfterFailure(packedOptions, failure);
            closeAfterFailure(registeredDetectorOptions, failure);
            throw failure;
        }
        detector = openedDetector;
        recognizer = openedRecognizer;
        recognizerOptions = packedOptions;
        detectorOptions = registeredDetectorOptions;
    }

    private static synchronized void pinLibrary(String library) {
        if (pinnedLibrary == null) {
            // The registered custom operation domain borrows code from the library.
            System.load(library);
            pinnedLibrary = library;
        } else if (!pinnedLibrary.equals(library)) {
            throw new IllegalStateException("Packed OCR already uses a different library path");
        }
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource != null)
            try {
                resource.close();
            } catch (Exception | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
    }

    private static String verify(String path, Set<String> allowed) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var input = new java.io.FileInputStream(path)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        var hex = new StringBuilder();
        for (byte b : digest.digest())
            hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        String checksum = hex.toString();
        if (!allowed.contains(checksum))
            throw new java.io.IOException("Unrecognized OCR artifact checksum");
        return checksum;
    }

    public List<String> dictionary() {
        return dictionary;
    }

    public float[][] detect(float[] input, int width, int height) throws Exception {
        return detectDirect(FloatBuffer.wrap(input), width, height);
    }

    public float[][] detectDirect(FloatBuffer input, int width, int height) throws Exception {
        if (detector == null)
            throw new IllegalStateException("OCR detector is unavailable in recognizer-only mode");
        try (var tensor =
                        OnnxTensor.createTensor(
                                environment, input, new long[] {1, 3, height, width});
                var result =
                        detector.run(Map.of(detector.getInputNames().iterator().next(), tensor))) {
            return ((float[][][][]) result.get(0).getValue())[0][0];
        }
    }

    public float[][] recognize(float[] input, int width, int height) throws Exception {
        try (var tensor =
                        OnnxTensor.createTensor(
                                environment,
                                FloatBuffer.wrap(input),
                                new long[] {1, 3, height, width});
                var result =
                        recognizer.run(
                                Map.of(recognizer.getInputNames().iterator().next(), tensor))) {
            var output = ((float[][][]) result.get(0).getValue())[0];
            if (output.length > 0 && output[0].length != dictionary.size())
                throw new IllegalArgumentException(
                        "Model vocabulary="
                                + output[0].length
                                + " dictionary="
                                + dictionary.size());
            return output;
        }
    }

    public void close() throws OrtException {
        try {
            if (detector != null) detector.close();
        } catch (OrtException | RuntimeException | Error failure) {
            closeAfterFailure(recognizer, failure);
            closeAfterFailure(recognizerOptions, failure);
            closeAfterFailure(detectorOptions, failure);
            throw failure;
        }
        try {
            recognizer.close();
        } catch (OrtException | RuntimeException | Error failure) {
            closeAfterFailure(recognizerOptions, failure);
            closeAfterFailure(detectorOptions, failure);
            throw failure;
        }
        try {
            if (recognizerOptions != null) recognizerOptions.close();
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(detectorOptions, failure);
            throw failure;
        }
        if (detectorOptions != null) detectorOptions.close();
    }
}
