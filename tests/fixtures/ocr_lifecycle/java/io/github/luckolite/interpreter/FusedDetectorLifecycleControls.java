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

    public static void main(String[] args) throws Exception {
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
