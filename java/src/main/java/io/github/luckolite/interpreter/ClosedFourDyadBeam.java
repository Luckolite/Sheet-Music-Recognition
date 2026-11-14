// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Original ink proof for one closed, twice-beamed group of four shared chord shafts. */
final class ClosedFourDyadBeam {
    private ClosedFourDyadBeam() {}

    record Head(float x, float top, float bottom) {}
    private record Stem(int left, int right, int topTip) {}

    static boolean proves(byte[] gray, int width, int height, Head[] heads, float gap, int direction) {
        if (gap < 8 || gap > 32 || direction == 0) return false;
        var budget = new PrintedBeamShafts.Budget(100_000);
        Stem[] stems = new Stem[heads.length];
        for (int i = 0; i < heads.length; i++) {
            var head = heads[i];
            if (head.bottom - head.top < gap * .60f || head.bottom - head.top > gap * 3.1f) return false;
            if (i > 0 && (head.x - heads[i - 1].x < gap * 1.35f
                    || head.x - heads[i - 1].x > gap * 4.7f)) return false;
            var upper = PrintedBeamShafts.at(gray, width, height, head.x, head.top, gap, budget);
            var lower = PrintedBeamShafts.at(gray, width, height, head.x, head.bottom, gap, budget);
            if (upper.size() != 1 || lower.size() != 1) return false;
            var a = upper.get(0);
            var b = lower.get(0);
            int left = Math.max(a.left(), b.left()), right = Math.min(a.right(), b.right());
            if (a.direction() != direction || b.direction() != direction || left > right
                    || Math.abs(a.endY() - b.endY()) > gap * .18f) return false;
            stems[i] = new Stem(left, right, a.endY());
        }
        int x0 = (stems[0].left + stems[0].right) / 2;
        int x1 = (stems[3].left + stems[3].right) / 2;
        float slope = (stems[3].topTip - stems[0].topTip) / (float)(x1 - x0);
        if (Math.abs(slope) > .4f) return false;
        for (int offset = 1; offset <= Math.round(gap * .30f); offset++) {
            float center = stems[0].topTip - direction * offset;
            if (!closed(gray, width, height, x0, x1, center, slope, gap)) continue;
            for (int sep = Math.round(gap * .45f); sep <= Math.round(gap * 1.05f); sep++) {
                float inner = center - direction * sep;
                if (closed(gray, width, height, x0, x1, inner, slope, gap)
                        && separated(gray, width, height, x0, stems, center, inner, slope, gap))
                    return true;
            }
        }
        return false;
    }

    private static boolean closed(byte[] gray, int width, int height, int first, int last,
            float center, float slope, float gap) {
        int radius = Math.max(1, Math.round(gap * .075f));
        int full = 0;
        for (int x = first; x <= last; x++)
            if (core(gray, width, height, x, center + slope * (x - first), radius)) full++;
        if (full < (last - first + 1) * .94f) return false;
        int begin = Math.round(gap * .4f), end = Math.round(gap * .9f);
        for (int side : new int[]{-1, 1}) {
            int edge = side < 0 ? first : last;
            int again = 0;
            for (int i = begin; i <= end; i++) {
                int x = edge + side * i;
                if (x < 0 || x >= width) return false;
                if (core(gray, width, height, x, center + slope * (x - first), radius)) again++;
            }
            if (again > (end - begin + 1) * .20f) return false;
        }
        return true;
    }

    private static boolean separated(byte[] gray, int width, int height, int x0, Stem[] stems,
            float outer, float inner, float slope, float gap) {
        int margin = Math.round(gap * .23f);
        for(int i = 1; i < stems.length; i++) {
            int left = stems[i-1].right + margin, right = stems[i].left - margin;
            int blank = 0;
            for (int x = left; x <= right; x++)
                if (!core(gray, width, height, x, (outer + inner) * .5f + slope * (x-x0), 0)) blank++;
            if (right-left < margin || blank < (right-left+1)*.75f) return false;
        }
        return true;
    }

    private static boolean core(byte[] gray, int width, int height, int x, float y, int radius) {
        int row = Math.round(y);
        if (row-radius < 0 || row+radius >= height) return false;
        for (int yy = row-radius; yy <= row+radius; yy++)
            if ((gray[yy*width+x] & 255) >= 162) return false;
        return true;
    }
}
