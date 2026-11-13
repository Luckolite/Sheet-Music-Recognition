// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Normalized page geometry and optional proved short-cadence length. */
public record MeasureRegion(float left, float right, float top, float bottom, int cadenceQuarterBeats) {
    public MeasureRegion(float left, float right, float top, float bottom) {
        this(left, right, top, bottom, 0);
    }

    public MeasureRegion {
        if (cadenceQuarterBeats < 0 || cadenceQuarterBeats > 2)
            throw new IllegalArgumentException("Invalid printed cadence duration");
    }
}
