// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;

/** A conservative visible-ink test shared by all candidate spans of one tie. */
final class TieShoulderWindow {
    private static final java.lang.invoke.VarHandle WORDS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(long[].class, java.nio.ByteOrder.nativeOrder());
    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final long BYTE_ONES = 0x0101010101010101L;
    private final int left, right;
    private final int[] latestStart, earliestEnd;

    static TieShoulderWindow create(byte[] labels, byte[] gray, int width, int height,
            float centerY, float gap, int side, int left, int right) {
        if (gray == null || gray.length != (long) width * height || labels == null
                || labels.length != gray.length || !(gap > 0 && gap <= 64)
                || !(centerY >= 0 && centerY < height) || side < -1 || side > 1
                || left < 0 || right >= width || right < left || right >= (1 << 23)
                || right - left >= 1536) return null;
        return new TieShoulderWindow(labels, gray, width, height, centerY, gap, side, left, right);
    }

    private TieShoulderWindow(byte[] labels, byte[] gray, int width, int height,
            float centerY, float gap, int side, int left, int right) {
        this.left = left;
        this.right = right;
        latestStart = new int[right - left + 1];
        earliestEnd = new int[latestStart.length];
        Arrays.fill(latestStart, left);
        Arrays.fill(earliestEnd, right);

        int radius = Math.max(1, Math.round(gap * .1f));
        int top = Math.max(0, Math.round(centerY - (side <= 0 ? gap * 3f : 0)) - radius - 1);
        int bottom = Math.min(height - 1, Math.round(centerY + (side >= 0 ? gap * 3f : 0)) + radius + 1);
        int firstRule = Math.round(centerY - gap * 3.2f), lastRule = Math.round(centerY + gap * 3.2f);
        for (int y = top; y <= bottom; y++) {
            int row = y * width;
            for (int x = left; x <= right;) {
                if (x <= right - 7 && (gray[row + x] & 255) > 225) {
                    long word = (long) WORDS.get(gray, row + x);
                    // Foreground cutoff 226. Setting the high bits prevents borrows across bytes.
                    if ((((word | HIGH_BITS) - BYTE_ONES * (226 - 128)) & word & HIGH_BITS) == HIGH_BITS) {
                        x += Long.BYTES;
                        continue;
                    }
                }
                int start = x;
                int tone = gray[row + x] & 255;
                if (y >= firstRule && y <= lastRule && tone <= 225) {
                    // Every adaptive cutoff includes the darkest runs. A lighter
                    // uniform run is either entirely paper or entirely ink, so it
                    // cannot supply a visible stroke to a span contained within it.
                    if (tone <= 80) {
                        while (x < right && (gray[row + x + 1] & 255) <= 80) x++;
                    } else {
                        while (x < right && (gray[row + x + 1] & 255) == tone) x++;
                    }
                    int end = x++;
                    // A rule covering every candidate span supplies no visible ink.
                    if (start == left && end == right) continue;
                    for (int col = start; col <= end; col++) {
                        if (labels[row + col] == OmrMeasurePostProcessor.NOTEHEAD) continue;
                        int i = col - left;
                        latestStart[i] = Math.max(latestStart[i], start);
                        earliestEnd[i] = Math.min(earliestEnd[i], end);
                    }
                } else {
                    if ((gray[row + x] & 255) <= 225 && labels[row + x] != OmrMeasurePostProcessor.NOTEHEAD) {
                        latestStart[x - left] = right + 1;
                        earliestEnd[x - left] = left - 1;
                    }
                    x++;
                }
            }
        }
    }

    boolean excludes(int a, int b) {
        if (a < left || b > right || b <= a) return false;
        // The curve proof requires three visible samples on each ten-sample shoulder.
        int span = b - a;
        return !maySupply(a, Math.round(a + (9.5f / 50f) * span), a, b)
                || !maySupply(Math.round(a + (40.5f / 50f) * span), b, a, b);
    }

    boolean excludesInsets(int maxInset) {
        // All trial spans lie inside the original endpoints. Cover both the
        // endpoint displacement and the entire shoulder of the widest span.
        int reach = Math.min(right - left, maxInset + (right - left) / 4 + 2);
        return !maySupply(left, left + reach, left, right)
                || !maySupply(right - reach, right, left, right);
    }

    boolean excludesShorterSpans(int near, int span, boolean outgoing) {
        if (span <= 0) return false;
        int a = outgoing ? near : near - span, b = outgoing ? near + span : near;
        if (a < left || b > right) return false;
        // The near shoulder ends at 19% of the span. Cover that entire shoulder
        // for every shorter span, allowing for pixel rounding at the endpoint.
        int shoulder = Math.min(span, span / 4 + 2);
        return outgoing ? !maySupply(a, a + shoulder, a, b) : !maySupply(b - shoulder, b, a, b);
    }

    private boolean maySupply(int first, int last, int a, int b) {
        for (int x = first - left; x <= last - left; x++)
            if (latestStart[x] > a || earliestEnd[x] < b) return true;
        return false;
    }
}
