// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0

package io.github.luckolite.interpreter;

/** Looks for the narrow, asymmetric mark mistaken for one dot of a closed half dyad. */
final class TerminalHalfSpeck {
    private TerminalHalfSpeck() {}

    static boolean proves(
            byte[] gray,
            int width,
            int height,
            float lowerX,
            float lowerY,
            float higherX,
            float higherY,
            float gap) {
        if (gray == null
                || gray.length != (long) width * height
                || gap < 10
                || gap > 26
                || lowerY - higherY < gap * 1.7f
                || lowerY - higherY > gap * 3.2f) return false;
        if (hasDotBesideHigherHead(gray, width, height, higherX, higherY, gap)) return false;

        int x0 = Math.round(lowerX + gap * .83f), x1 = Math.round(lowerX + gap * 2.08f);
        int y0 = Math.round(lowerY - gap * .4f), y1 = Math.round(lowerY + gap * .65f);
        var silhouette =
                RawNumeralComponents.capture(
                        gray, width, height, x0, y0, x1, y1, 153, () -> {}, () -> {});
        RawNumeralComponents.Component mark = null;
        for (var e : silhouette.components()) {
            if (e.left <= x0 || e.right >= x1 || e.top <= y0 || e.bottom >= y1) continue;
            if (mark != null) return false;
            mark = e;
        }
        if (mark == null) return false;
        int w = mark.right - mark.left + 1, h = mark.bottom - mark.top + 1;
        if (w < gap * .28f
                || w > gap * .42f
                || h < gap * .43f
                || h > gap * .60f
                || w > h * .79f
                || mark.size() < w * h * .54f
                || mark.size() > w * h * .77f) return false;
        float center = (mark.left + mark.right) * .5f;
        if (center - lowerX < gap * 1.34f || center - lowerX > gap * 1.70f) return false;
        int l = mark.right, r = mark.left, t = mark.bottom, b = mark.top, total = 0;
        for (int y = mark.top; y <= mark.bottom; y++)
            for (int x = mark.left; x <= mark.right; x++) {
                if ((gray[y * width + x] & 255) >= 90) continue;
                l = Math.min(l, x);
                r = Math.max(r, x);
                t = Math.min(t, y);
                b = Math.max(b, y);
                total++;
            }
        int diameter = r - l + 1, heightStrong = b - t + 1;
        return diameter >= 3
                && heightStrong >= h * .80f
                && diameter <= heightStrong * .64f
                && total >= mark.size() * .48f
                && total <= mark.size() * .73f;
    }

    private static boolean hasDotBesideHigherHead(
            byte[] gray, int width, int height, float x, float y, float gap) {
        int left = Math.round(x + gap * .80f), right = Math.round(x + gap * 2.13f);
        int top = Math.round(y - gap * .78f), bottom = Math.round(y + gap * .78f);
        var frame =
                RawNumeralComponents.capture(
                        gray, width, height, left, top, right, bottom, 179, () -> {}, () -> {});
        for (var e : frame.components())
            if (e.left > left
                    && e.right < right
                    && e.top > top
                    && e.bottom < bottom
                    && e.size() >= gap * gap * .032f) return true;
        return false;
    }
}
