// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
// Original failure-injection fixtures, no inference or trained models.
package ai.onnxruntime;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

public final class FakeOrt {
    public static final List<String> events = new ArrayList<>();
    public static final List<OrtSession> sessions = new ArrayList<>();
    public static Throwable verifyFailure,
            optionsNewFailure,
            configFailure,
            detectorCreateFailure,
            recognizerCreateFailure,
            optionsCloseFailure,
            dictionaryFailure,
            detectorCloseFailure,
            recognizerCloseFailure,
            libraryRegisterFailure;
    public static int optionsCreated,
            optionsCloseCalls,
            tensorsCreated,
            tensorsClosed,
            resultsCreated,
            resultsClosed;
    public static boolean dictionaryWithNull;
    public static final float[][] detectorOutput = {
        {Float.intBitsToFloat(0x80000000), Float.intBitsToFloat(0x7fc12345)}
    };
    public static final float[][] recognizerOutput = {{0.1f, 0.7f, 0.2f}, {0.8f, 0.1f, 0.1f}};
    public static int[] lastInputBits;
    public static long[] lastInputShape;
    public static Throwable recognizerConfigFailure;
    public static final List<Map<String, Object>> sessionSettings = new ArrayList<>();

    public static void captureSettings(String path, OrtSession.SessionOptions options) {
        sessionSettings.add(
                Map.of(
                        "role",
                        path,
                        "intra",
                        options.intraThreads,
                        "inter",
                        options.interThreads,
                        "config",
                        Map.copyOf(options.configEntries)));
    }

    public static void reset() {
        events.clear();
        sessions.clear();
        sessionSettings.clear();
        recognizerConfigFailure = null;
        libraryRegisterFailure = null;
        verifyFailure =
                optionsNewFailure =
                        configFailure =
                                detectorCreateFailure =
                                        recognizerCreateFailure =
                                                optionsCloseFailure =
                                                        dictionaryFailure =
                                                                detectorCloseFailure =
                                                                        recognizerCloseFailure =
                                                                                null;
        optionsCreated =
                optionsCloseCalls =
                        tensorsCreated = tensorsClosed = resultsCreated = resultsClosed = 0;
        dictionaryWithNull = false;
        lastInputBits = null;
        lastInputShape = null;
    }

    static void throwOrt(Throwable t) throws OrtException {
        if (t == null) return;
        if (t instanceof OrtException e) throw e;
        if (t instanceof RuntimeException e) throw e;
        if (t instanceof Error e) throw e;
        throw new AssertionError("unsupported fake ORT failure", t);
    }

    static void throwUnchecked(Throwable t) {
        if (t == null) return;
        if (t instanceof RuntimeException e) throw e;
        if (t instanceof Error e) throw e;
        throw new AssertionError("unchecked API failure must be RuntimeException/Error", t);
    }

    private static void throwIo(Throwable t) throws IOException {
        if (t == null) return;
        if (t instanceof IOException e) throw e;
        if (t instanceof RuntimeException e) throw e;
        if (t instanceof Error e) throw e;
        throw new AssertionError("unsupported fake IO failure", t);
    }

    public static void verify(String path, Set<String> allowed) throws IOException {
        events.add("verify:" + path);
        Set<String> expected = switch (new File(path).getName()) {
            case "detector" -> Set.of(
                    "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9",
                    "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae");
            case "recognizer" -> Set.of("b20bd37c168a570f583afbc8cd7925603890efbcdc000a59e22c269d160b5f5a");
            case "packed" -> Set.of("751b79329b420192022f8e9901bbdb243c60efa627f8abbc9d08b6fb4b47f740");
            case "packed-test.so" -> Set.of("33e277d9de0a25cbe2568f220d0765dd53772c40efa8100ff900acd73be5d054");
            case "recognizer.dictionary", "packed.dictionary" ->
                    Set.of("1169fb297871f7a14d6a0f20c14af56de789c48b170e59dfb66950448e31c062");
            default -> throw new AssertionError("Unexpected artifact path " + path);
        };
        if (!allowed.equals(expected)) throw new AssertionError("allowed checksum set changed");
        throwIo(verifyFailure);
    }

    public static List<String> readDictionary(String path, Charset charset) throws IOException {
        events.add("dictionary:" + path + ":" + charset.name());
        if ((!path.equals("recognizer.dictionary") && !path.equals("packed.dictionary"))
                || !charset.name().equals("UTF-8"))
            throw new AssertionError("dictionary input changed");
        throwIo(dictionaryFailure);
        return dictionaryWithNull
                ? Arrays.asList("", null, "b")
                : new ArrayList<>(List.of("", "a", "b"));
    }
}
