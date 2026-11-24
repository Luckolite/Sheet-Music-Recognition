// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Separates a natural's connected crossbar from a preceding head's augmentation dot. */
final class NaturalCrossbarDotOwnership {
    private NaturalCrossbarDotOwnership() {}

    static boolean owns(byte[] gray, int width, float previousX, float x, float y, float gap,
            WrappedTripletDotInk.Center dot) {
        var spines = AccidentalSlurSpines.prove(gray, width, previousX, x, y, gap, 0);
        if (spines.size() != 2) return false;
        var left = spines.get(0);
        var right = spines.get(1);
        if (dot.x() <= left.right() || dot.x() >= right.left()
                || dot.y() < Math.max(left.top(), right.top())
                || dot.y() > Math.min(left.bottom(), right.bottom())) return false;
        for (int col = left.right(); col <= right.left(); col++)
            if ((gray[dot.y() * width + col] & 255) >= 145) return false;
        return true;
    }
}
