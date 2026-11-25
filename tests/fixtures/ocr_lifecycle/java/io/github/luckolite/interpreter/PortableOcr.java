// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.nio.FloatBuffer;
import java.util.List;

public final class PortableOcr {
    public interface Inference {
        float[][] detect(float[] input, int width, int height) throws Exception;

        float[][] recognize(float[] input, int width, int height) throws Exception;

        List<String> dictionary();
    }

    interface DirectDetectorInference extends Inference {
        float[][] detectDirect(FloatBuffer input, int width, int height) throws Exception;
    }
}
