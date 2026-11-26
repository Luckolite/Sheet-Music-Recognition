// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package ai.onnxruntime;

public final class OrtEnvironment {
    private static final OrtEnvironment INSTANCE = new OrtEnvironment();

    public static OrtEnvironment getEnvironment() {
        return INSTANCE;
    }

    public String getVersion() {
        return "1.25.1";
    }

    public OrtSession createSession(String path, OrtSession.SessionOptions options)
            throws OrtException {
        FakeOrt.events.add("create:" + path);
        FakeOrt.captureSettings(path, options);
        FakeOrt.throwOrt(
                path.equals("detector")
                        ? FakeOrt.detectorCreateFailure
                        : FakeOrt.recognizerCreateFailure);
        OrtSession session = new OrtSession(path, options);
        FakeOrt.sessions.add(session);
        return session;
    }
}
