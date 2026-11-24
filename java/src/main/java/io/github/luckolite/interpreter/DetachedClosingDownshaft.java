package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Measures a real downward shaft terminating below a narrow paper seam under its head. */
final class DetachedClosingDownshaft {
    private DetachedClosingDownshaft() {}

    static PrintedBeamShafts.Shaft measure(byte[] gray, int width, int height, float x, float y, float gap) {
        if (gray == null || gray.length != (long) width * height || !Float.isFinite(gap)
                || !Float.isFinite(x) || !Float.isFinite(y) || gap < 12 || gap > 24) return null;
        int left = Math.round(x - gap * .79f), right = Math.round(x - gap * .43f);
        int start = Math.round(y + gap * .77f), seekEnd = Math.round(y + gap * 1.23f);
        int bottom = Math.round(y + gap * 3.7f);
        if (left < 1 || right + gap >= width || start - 8 < 0 || bottom >= height) return null;
        var columns = new ArrayList<PrintedBeamShafts.Column>();
        int firstInk = Integer.MAX_VALUE;
        for (int col = left; col <= right; col++) {
            int top = start;
            while (top <= seekEnd && !dark(gray, width, col, top)) top++;
            if (top > seekEnd) continue;
            int blank = 0;
            for (int row = top - 1; row >= top - Math.round(gap * .28f); row--)
                if (!dark(gray, width, col, row)) blank++;
            if (blank < Math.round(gap * .22f)) continue;
            int owner = Math.round(y + gap * .26f);
            if (!dark(gray, width, col, owner)
                    && !dark(gray, width, col + 1, owner)
                    && !dark(gray, width, col + 2, owner)) continue;
            int end = top, missed = 0;
            for (int row = top; row <= bottom; row++) {
                if (dark(gray, width, col, row)) {
                    end = row;
                    missed = 0;
                } else if (++missed > 1) break;
            }
            if (end >= bottom || end - top < gap * 1.68f || end - y < gap * 2.7f
                    || end - y > gap * 3.42f) continue;
            firstInk = Math.min(firstInk, top);
            columns.add(new PrintedBeamShafts.Column(col, end));
        }
        if (columns.size() < 2 || columns.size() > 4) return null;
        var a = columns.get(0);
        var b = columns.get(columns.size() - 1);
        int low = columns.stream().mapToInt(PrintedBeamShafts.Column::endY).min().orElseThrow();
        int high = columns.stream().mapToInt(PrintedBeamShafts.Column::endY).max().orElseThrow();
        if (b.x() - a.x() + 1 != columns.size() || high - low > 2) return null;
        int exterior = b.x() + Math.round(gap * .55f);
        int empty = 0;
        for (int row = firstInk + 3; row < low - 2; row++)
            if (!dark(gray, width, exterior, row)) empty++;
        if (empty < (low - firstInk) * .62f) return null;
        return new PrintedBeamShafts.Shaft(a.x(), b.x(), firstInk, low, 1, List.copyOf(columns));
    }

    private static boolean dark(byte[] gray, int width, int x, int y) {
        return (gray[y * width + x] & 255) < 155;
    }
}
