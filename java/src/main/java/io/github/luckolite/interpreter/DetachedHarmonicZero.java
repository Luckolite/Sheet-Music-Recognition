// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;

/** Detached printed zero beside a proved harmonic head. */
final class DetachedHarmonicZero {
    private DetachedHarmonicZero() {}

    record Source(float x, float y) {}

    static Source above(byte[] gray, int w, int h, float x, float y, float gap, boolean fadedTop) {
        var ring = closed(gray, w, h, x, y - gap * 2.3f, y - gap * .63f, gap);
        if (ring != null || !fadedTop) return ring;
        return taperedOpening(gray, w, h, x, y, gap);
    }

    static Source below(byte[] gray, int w, int h, float x, float shaftTip, float gap) {
        return closed(gray, w, h, x, shaftTip + gap * 1.55f, shaftTip + gap * 3.4f, gap);
    }

    private static Source closed(byte[] gray, int w, int h, float x, float from, float to, float gap) {
        int left = Math.round(x - gap * .86f), right = Math.round(x + gap * .86f);
        int top = Math.round(from), bottom = Math.round(to);
        if (left < 0 || right >= w || top < 0 || bottom >= h || top >= bottom) return null;
        var box = new NaturalHarmonicRingProof.Box(left, top, right, bottom);
        List<NaturalHarmonicRingProof.Ring> candidates = NaturalHarmonicRingProof.rings(
                gray, w, h, box, 195, Math.round(gap * .62f), Math.round(gap * 1.38f),
                new NaturalHarmonicRingProof.Budget(220_000, 220_000));
        NaturalHarmonicRingProof.Ring selected = null;
        for (var ring : candidates) {
            if (Math.abs(ring.centerX() - x) > gap * .29f
                    || ring.counterPixels() < gap * gap * .13f) continue;
            if (selected != null) return null;
            selected = ring;
        }
        return selected == null ? null : new Source(selected.centerX(), selected.centerY());
    }

    /** Both sides turn inward at the torn top, and their bottom still joins around white paper. */
    private static Source taperedOpening(byte[] gray, int w, int h, float x, float y, float gap) {
        int left = Math.round(x - gap * 1.02f), right = Math.round(x + gap * .82f);
        int top = Math.round(y - gap * 2.16f), bottom = Math.round(y - gap * .65f);
        if (left < 0 || right >= w || top < 0 || bottom >= h || top >= bottom) return null;
        int firstY = -1, lastY = -1, tipLeft = -1, tipRight = -1;
        int innerLeft = right, innerRight = left, wideLeft = right, wideRight = left;
        int shoulders = 0, bowl = 0, toe = 0, priorLeft = -1, priorRight = -1;
        boolean ended = false;
        for (int row = top; row <= bottom; row++) {
            int first = -1, last = -1, air = 0, airWidth = 0;
            int brackets = 0, leftEnd = -1, rightStart = -1;
            for (int at = left; at <= right; at++) {
                if ((gray[row * w + at] & 255) >= 195) {
                    if (first >= 0) air++;
                    continue;
                }
                if (first < 0) first = at;
                if (air > 0) {
                    brackets++;
                    leftEnd = last;
                    rightStart = at;
                    airWidth = air;
                    air = 0;
                }
                last = at;
            }
            if (first < 0) {
                if (firstY >= 0) ended = true;
                continue;
            }
            if (ended || first == left || last == right || brackets > 1
                    || last - first + 1 > gap * 1.08f) return null;
            if (firstY < 0) {
                firstY = row;
                tipLeft = first;
                tipRight = last;
                if (brackets != 1 || leftEnd != first || rightStart != last) return null;
            } else if (Math.abs(first - priorLeft) > gap * .24f || Math.abs(last - priorRight) > gap * .24f) {
                return null;
            }
            if (brackets == 1) {
                if (bowl != 0 || airWidth < gap * .20f || airWidth > gap * .49f
                        || leftEnd - first + 1 > gap * .34f || last - rightStart + 1 > gap * .34f)
                    return null;
                if (last - first + 1 >= gap * .70f) shoulders++;
                innerLeft = Math.min(innerLeft, leftEnd);
                innerRight = Math.max(innerRight, rightStart);
                wideLeft = Math.min(wideLeft, first);
                wideRight = Math.max(wideRight, last);
            } else {
                if (shoulders < gap * .42f) return null;
                if (bowl == 0 && (first > innerLeft + 2 || last < innerRight - 2)) return null;
                bowl++;
                toe = last - first + 1;
            }
            lastY = row;
            priorLeft = first;
            priorRight = last;
        }
        if (firstY <= top || lastY >= bottom || lastY - firstY < gap * .85f
                || lastY - firstY > gap * 1.21f || bowl < 2 || bowl > gap * .28f
                || toe > gap * .35f || tipLeft - wideLeft < 2 || wideRight - tipRight < 2) return null;
        float center = (wideLeft + wideRight) * .5f;
        return Math.abs(center - x) <= gap * .33f ? new Source(center, (firstY + lastY) * .5f) : null;
    }
}
