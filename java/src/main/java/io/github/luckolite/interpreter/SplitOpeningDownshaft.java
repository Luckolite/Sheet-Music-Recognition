// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Proves a short break in two adjacent columns of a downstem. */
final class SplitOpeningDownshaft {
    private SplitOpeningDownshaft() {}

    static PrintedBeamShafts.Shaft measure(byte[] gray, int width, int height,
            float x, float y, float gap) {
        var attached = PairedTremoloInk.stem(gray, width, height, x, y, gap, -1);
        if (attached == null || attached.endY() - y > gap * 4.3f) return null;
        int start = Math.round(y + gap * .28f);
        int seamFirst = Math.round(y + gap * .52f);
        int seamLast = Math.round(y + gap * .80f);
        int bottom = Math.min(height - 1, attached.endY() + Math.round(gap * .15f));
        if (start < 1 || attached.x() < 3 || attached.x() + 3 >= width || bottom <= seamLast) return null;

        var columns = new ArrayList<PrintedBeamShafts.Column>();
        int sharedSeam = -1;
        for (int col = attached.x() - 2; col <= attached.x() + 2; col++) {
            int seam = start;
            while (seam <= seamLast && dark(gray, width, col, seam)) seam++;
            if (seam < seamFirst || seam > seamLast) continue;
            int resumed = seam;
            while (resumed <= seam + 3 && !dark(gray, width, col, resumed)) resumed++;
            if (resumed - seam < 2 || resumed - seam > 3) continue;
            int end = resumed;
            while (end < bottom && dark(gray, width, col, end + 1)) end++;
            if (end - resumed < gap * 2.45f || Math.abs(end - attached.endY()) > 2) continue;
            if (sharedSeam != -1 && sharedSeam != seam) return null;
            sharedSeam = seam;
            columns.add(new PrintedBeamShafts.Column(col, end));
        }
        if (columns.size() != 2 || columns.get(1).x() - columns.get(0).x() != 1) return null;
        var first = columns.get(0);
        var last = columns.get(1);
        return new PrintedBeamShafts.Shaft(first.x(), last.x(), start,
                Math.min(first.endY(), last.endY()), 1, List.copyOf(columns));
    }

    private static boolean dark(byte[] gray, int width, int col, int row) {
        return (gray[row * width + col] & 255) < 165;
    }
}
