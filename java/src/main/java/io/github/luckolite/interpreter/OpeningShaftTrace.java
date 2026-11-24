// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

final class OpeningShaftTrace {
    private record Trace(int x, int length, int strong) {}

    static PrintedBeamShafts.Shaft scan(byte[] gray, int width, int height,
            float x, float y, float gap, int direction) {
        return scan(gray, width, height, x, y, gap, direction, null, null);
    }

    static PrintedBeamShafts.Shaft anchored(byte[] gray, int width, int height,
            float x, float y, float gap, PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b) {
        if (a == null || b == null || a.direction() != b.direction()) return null;
        return scan(gray, width, height, x, y, gap, a.direction(), a, b);
    }

    private static PrintedBeamShafts.Shaft scan(byte[] gray, int width, int height,
            float x, float y, float gap, int direction, PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b) {
        int start = Math.round(.22f * gap);
        int head = Math.round(y);
        float edge = x - direction * .6f * gap;
        int left = Math.round(edge - .19f * gap), right = Math.round(edge + .19f * gap);
        int limit = Math.round(8f * gap) - 1;
        if (left < 4 || right + 4 >= width || head + direction * limit < 0
                || head + direction * limit >= height) return null;
        var columns = new ArrayList<Trace>();
        for (int col = left; col <= right; col++) {
            int end = limit;
            if (a != null) {
                float first = (a.left() + a.right()) * .5f;
                float last = (b.left() + b.right()) * .5f;
                if (col <= first || col >= last) continue;
                end = Math.round(a.endY() + (b.endY() - a.endY()) * (col - first) / (last - first));
                end = (end - head) * direction;
                if (end <= start || end > limit) continue;
            }
            int strong = 0, ink = 0, blank = 0, longestBlank = 0, length = 0;
            for (int step = start; step <= end; step++) {
                int at = (head + direction * step) * width + col;
                int value = gray[at] & 255;
                int contrast = Math.max(gray[at - 4] & 255, gray[at + 4] & 255) - value;
                if (value <= 170 || value <= 240 && contrast >= 14) {
                    ink++;
                    if (value <= 170 && contrast >= 12) strong++;
                    longestBlank = Math.max(longestBlank, blank);
                    blank = 0;
                    length = step;
                } else if (++blank > Math.round(.44f * gap)) {
                    break;
                }
            }
            int samples = (a == null ? length : end) - start + 1;
            if (length >= 1.85f * gap && ink >= (a == null ? .74f : .76f) * samples
                    && strong >= (a == null ? .53f : .50f) * samples
                    && (a == null || end - length <= Math.round(.25f * gap)
                            && longestBlank <= Math.round(.44f * gap))) {
                columns.add(new Trace(col, a == null ? length : end, strong));
            }
        }
        Trace first = null, second = null;
        int bestLength = 0, bestInk = 0;
        for (int i = 1; i < columns.size(); i++) {
            Trace l = columns.get(i - 1), r = columns.get(i);
            if (r.x - l.x != 1 || Math.abs(l.length - r.length) > .35f * gap) continue;
            int length = Math.min(l.length, r.length), ink = Math.min(l.strong, r.strong);
            if (length > bestLength || length == bestLength && ink > bestInk) {
                first = l;
                second = r;
                bestLength = length;
                bestInk = ink;
            }
        }
        if (first == null) return null;
        int endpoint = head + direction * bestLength;
        return new PrintedBeamShafts.Shaft(first.x, second.x, head + direction * start, endpoint,
                direction, List.of(new PrintedBeamShafts.Column(first.x, head + direction * first.length),
                        new PrintedBeamShafts.Column(second.x, head + direction * second.length)));
    }
}
