// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import ai.onnxruntime.FakeOrt;
import ai.onnxruntime.OrtException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FusedDetectorLifecycleControls {
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static void checkClosed(int sessions, int options) {
        check(FakeOrt.sessions.size() == sessions, "Unexpected session count");
        check(FakeOrt.sessions.stream().allMatch(s -> s.closeCalls == 1), "Session ownership");
        check(FakeOrt.optionsCreated == options && FakeOrt.optionsCloseCalls == options,
                "Options ownership");
    }

    private static void windows(Path stage) throws Exception {
        System.setProperty("os.name", "Windows 11");
        String library = Files.createFile(stage.resolve("fused-test.dll")).toString();
        FakeOrt.reset();
        System.setProperty("os.arch", "aarch64");
        try (var ignored = OnnxOcrInference.openFusedDetectorWindows("detector", "recognizer", library)) {
            throw new AssertionError("Expected x64 requirement");
        } catch (UnsupportedOperationException expected) {
            check(FakeOrt.events.isEmpty(), "Unsupported host opened runtime resources");
        }

        System.setProperty("os.arch", "amd64");
        FakeOrt.reset();
        try (var ignored = OnnxOcrInference.openFusedDetectorWindows("detector", "recognizer", library)) {
            throw new AssertionError("Expected fused graph requirement");
        } catch (IOException expected) {
            checkClosed(0, 0);
        }

        FakeOrt.reset();
        FakeOrt.requestFusedDetector = true;
        try (var model = OnnxOcrInference.openFusedDetectorWindows("detector", "recognizer", library)) {
            check(FakeOrt.optionsCloseCalls == 1, "Detector registration ended before inference");
            check(FakeOrt.events.stream().filter(e -> e.startsWith("register:")).count() == 1,
                    "The stock recognizer should use the stock runtime");
            check(FakeOrt.sessionSettings.get(0).get("intra").equals(2)
                    && FakeOrt.sessionSettings.get(1).get("intra").equals(4), "Thread counts changed");
            model.detect(new float[3], 1, 1);
            model.recognize(new float[3], 1, 1);
        }
        checkClosed(2, 2);

        FakeOrt.reset();
        FakeOrt.requestFusedDetector = true;
        var failure = new OrtException("injected recognizer failure");
        FakeOrt.recognizerCreateFailure = failure;
        try (var ignored = OnnxOcrInference.openFusedDetectorWindows("detector", "recognizer", library)) {
            throw new AssertionError("Expected recognizer failure");
        } catch (OrtException actual) {
            check(actual == failure, "Model error identity changed");
            checkClosed(1, 2);
        }
        System.out.println("WINDOWS_FUSED_LIFECYCLE_PASS cases=4");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[1].equals("windows")) {
            windows(Path.of(args[0]));
            return;
        }
        System.setProperty("java.vm.name", "Dalvik");
        System.setProperty("os.arch", "aarch64");
        Path stage = Path.of(args[0]);
        String fused = Files.createFile(stage.resolve("fused-test.so")).toString();

        FakeOrt.reset();
        FakeOrt.requestFusedDetector = true;
        try (var ignored = new OnnxOcrInference("detector", "recognizer")) {
            throw new AssertionError("Expected default loader to reject the fused artifact");
        } catch (IOException expected) {
            checkClosed(0, 0);
        }

        FakeOrt.reset();
        FakeOrt.requestFusedDetector = true;
        try (var ignored = OnnxOcrInference.openPackedArm64(
                "detector", "packed", stage.resolve("packed-test.so").toString())) {
            throw new AssertionError("Expected packed-only library to reject the fused artifact");
        } catch (IOException expected) {
            checkClosed(0, 0);
        }

        FakeOrt.reset();
        FakeOrt.requestFusedDetector = true;
        try (var model = OnnxOcrInference.openPackedArm64("detector", "packed", fused)) {
            check(FakeOrt.optionsCloseCalls == 1, "Custom options closed before model inference");
            model.detect(new float[3], 1, 1);
            model.recognize(new float[3], 1, 1);
            check(FakeOrt.events.stream().filter(e -> e.startsWith("register:")).count() == 2,
                    "Each custom session must register its own options");
        }
        checkClosed(2, 3);

        for (int threads : new int[] {2, 4}) {
            FakeOrt.reset();
            FakeOrt.requestFusedDetector = true;
            var failure = new OrtException("injected registration failure");
            FakeOrt.failRegistrationThreads = threads;
            FakeOrt.libraryRegisterFailure = failure;
            try (var ignored = OnnxOcrInference.openPackedArm64("detector", "packed", fused)) {
                throw new AssertionError("Expected registration failure");
            } catch (OrtException actual) {
                check(actual == failure, "Registration error identity changed");
                checkClosed(threads == 2 ? 0 : 1, threads == 2 ? 2 : 3);
            }
        }

        System.out.println("FUSED_LIFECYCLE_PASS cases=5");
    }
}
