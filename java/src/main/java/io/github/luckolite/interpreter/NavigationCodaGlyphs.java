// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Staff-local raw candidates; the full crossed ring and four holes must verify a coda. */
final class NavigationCodaGlyphs {
    private NavigationCodaGlyphs() {}

    static List<ScoreNavigationDetector.Glyph> detect(
            byte[] gray, int width, int height, List<PlayingTechniqueDetector.Staff> staffs) {
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || staffs == null) return List.of();
        var result = new ArrayList<ScoreNavigationDetector.Glyph>();
        var components = new PrintedInkComponents();
        for (var staff : staffs) {
            float gap = staff.gap();
            if (gap < 5 || !Float.isFinite(gap)) continue;
            // Boundary ownership accepts a sign ending six gaps above the staff.
            // Leave room for the full cross above the ring at that distance.
            int top = Math.max(0, Math.round(staff.top() - gap * 12));
            int bottom = Math.min(height - 1, Math.round(staff.top() - gap * .5f));
            if (bottom <= top) continue;
            for (int threshold = 80; threshold <= 140; threshold += 60) {
                if (threshold == 140 && !hasIntermediateInk(gray, top * width, (bottom + 1) * width))
                    break;
                components.scan(gray, width, 0, top, width, bottom + 1, threshold, (l, t, endX, endY, count) -> {
                    int r = endX - 1, b = endY - 1;
                    int w = endX - l, h = endY - t;
                    if (t == top
                            || b == bottom
                            || staff.top() - b > gap * 6
                            || !CodaGlyphInk.fits(w, h, gap)) return true;
                    if (!CodaGlyphInk.matches(gray, width, height, l, t, r, b, gap))
                        return true;
                    float cx = (l + r) * .5f / width, cy = (t + b) * .5f / height;

                    for (var prior : result) {
                        float priorY = (prior.top() + prior.bottom()) * .5f;
                        if (Math.abs(prior.centerX() - cx) * width < gap
                                && Math.abs(priorY - cy) * height < gap) return true;
                    }
                    result.add(
                            new ScoreNavigationDetector.Glyph(
                                    ScorePlaybackDirection.Kind.CODA,
                                    cx,
                                    t / (float) height,
                                    b / (float) height));
                    return true;
                });
            }
        }
        return List.copyOf(result);
    }

    private static boolean hasIntermediateInk(byte[] gray, int start, int end) {
        for (int p = start; p < end; p++) {
            int tone = gray[p] & 255;
            if (tone > 80 && tone <= 140) return true;
        }
        return false;
    }
}
