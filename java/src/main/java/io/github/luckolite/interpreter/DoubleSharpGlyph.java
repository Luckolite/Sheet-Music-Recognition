// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Compact diagonal cross: four arms, a filled centre, and open upper/lower notches. */
final class DoubleSharpGlyph {
    record Crop(int left, int top, int right, int bottom) {}

    /** Independently qualified nested crops can describe the same optical waist. */
    static boolean sameNestedInk(
            byte[] gray, byte[] source, int width, int height, Crop outer, Crop inner, float gap) {
        if (gray == null
                || source == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || source.length != gray.length
                || !Float.isFinite(gap)
                || gap < 5
                || outer.left() < 0
                || outer.top() < 0
                || outer.right() >= width
                || outer.bottom() >= height
                || inner.left() < outer.left()
                || inner.top() < outer.top()
                || inner.right() > outer.right()
                || inner.bottom() > outer.bottom()
                || inner.right() - inner.left() < 4
                || inner.bottom() - inner.top() < 4) return false;
        int x = outer.left() + (outer.right() - outer.left()) / 2;
        int y = outer.top() + (outer.bottom() - outer.top()) / 2;
        int radius = Math.max(2, Math.round(gap * .23f));
        if (x - 1 < inner.left()
                || x + 1 > inner.right()
                || Math.abs(x - (inner.left() + (inner.right() - inner.left()) / 2)) > radius
                || Math.abs(y - (inner.top() + (inner.bottom() - inner.top()) / 2)) > radius)
            return false;
        int rows = 0;
        boolean offStaffRule = false;
        for (int row = Math.max(inner.top(), y - radius);
                row <= Math.min(inner.bottom(), y + radius);
                row++) {
            int center = row * width + x;
            if (!commonDark(gray, source, center - 1)
                    || !commonDark(gray, source, center)
                    || !commonDark(gray, source, center + 1)) continue;
            rows++;
            int left = row * width + outer.left();
            int right = row * width + outer.right();
            if (!commonDark(gray, source, left) && !commonDark(gray, source, right))
                offStaffRule = true;
        }
        return rows >= 2 && offStaffRule;
    }

    private static boolean commonDark(byte[] gray, byte[] source, int pixel) {
        return (gray[pixel] & 255) < 155 && (source[pixel] & 255) < 155;
    }

    /** Read printed ink only inside an accidental-seeded, compact glyph box. */
    static boolean matchesRaw(
            byte[] gray,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            float gap) {
        int w = right - left + 1, h = bottom - top + 1;
        if (gray == null
                || left < 0
                || top < 0
                || right >= width
                || bottom >= height
                || w < 5
                || h < 5
                || w > gap * 1.4f
                || h > gap * 1.4f) return false;
        int threshold =
                BeamInkThreshold.at(gray, width, height, (left + right) / 2, top, bottom, gap);
        // A crop through a natural, sharp or slur is not a compact accidental.
        // Real cross arms terminate at their box; long vertical continuations do not.
        int reachEnd = Math.max(3, Math.round(gap * .4f));
        for (int x = left; x <= right; x++)
            for (int side = 0; side < 2; side++) {
                int direction = side == 0 ? -1 : 1;
                int edge = direction < 0 ? top : bottom, run = 0;
                for (int d = 0; d <= reachEnd; d++) {
                    int y = edge + direction * d;
                    if (y < 0 || y >= height || (gray[y * width + x] & 255) >= threshold) break;
                    run++;
                }
                if (run > reachEnd) return false;
            }
        byte[] ink = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((gray[(top + y) * width + left + x] & 255) < threshold) ink[y * w + x] = 1;
        if (matches(ink, w, h, 0, 0, w - 1, h - 1, (byte) 1, gap)) return true;
        // Small crosses can have their waist filled by a staff rule. The rule
        // must independently continue on BOTH sides, and both open arm notches remain.
        if (w < gap * .55f || h < gap * .55f || w > h * 1.5f || h > w * 1.5f) return false;
        int upper = 0, lower = 0, cx = (w - 1) / 2;
        for (int y = 0; y < h; y++)
            if (ink[y * w + cx] == 0) {
                int a = cx, b = cx;
                while (a > 0 && ink[y * w + a - 1] == 0) a--;
                while (b + 1 < w && ink[y * w + b + 1] == 0) b++;
                int l = 0, r = 0;
                for (int x = 0; x < a; x++) l += ink[y * w + x];
                for (int x = b + 1; x < w; x++) r += ink[y * w + x];
                if (b - a + 1 >= 2 && l >= w * .2f && r >= w * .2f) {
                    if (y < h * .35f) upper++;
                    if (y >= h * .65f) lower++;
                }
            }
        if (upper < (w <= gap * .9f ? 1 : 2) || lower < 2 || ink[(h / 2) * w + cx] == 0)
            return false;
        int reach = Math.max(3, Math.round(gap * .65f));
        if (left < reach || right + reach >= width) return false;
        for (int y = top + h / 3; y <= top + 2 * h / 3; y++) {
            int l = 0, r = 0;
            for (int d = 1; d <= reach; d++) {
                if ((gray[y * width + left - d] & 255) < threshold) l++;
                if ((gray[y * width + right + d] & 255) < threshold) r++;
            }
            if (l >= reach * .9f && r >= reach * .9f) return true;
        }
        return false;
    }

    /** A compact cross may include a short spur from an adjoining ledger or shaft. */
    static Crop isolatedRawCrop(byte[] gray, byte[] source, int width, int height, Crop crop, float gap) {
        if (gray == null || source == null || gray.length != (long) width * height
                || source.length != gray.length) return null;
        int trim = Math.max(1, Math.round(gap * .22f));
        // Limit the combined removal to a short fringe and keep nearly all of the known glyph.
        for (int amount = 0; amount <= trim; amount++)
            for (int left = 0; left <= amount; left++)
                for (int top = 0; top <= amount - left; top++)
                    for (int right = 0; right <= amount - left - top; right++) {
                        int bottom = amount - left - top - right;
                        var core = new Crop(crop.left + left, crop.top + top,
                                crop.right - right, crop.bottom - bottom);
                        if (matchesRaw(gray, width, height, core.left, core.top, core.right, core.bottom, gap)
                                && matchesRaw(source, width, height,
                                        core.left, core.top, core.right, core.bottom, gap)) return core;
                    }
        return null;
    }

    static boolean matches(
            byte[] pixels,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            byte label,
            float gap) {
        int w = right - left + 1, h = bottom - top + 1;
        if (left < 0
                || top < 0
                || right >= width
                || bottom >= height
                || w < 5
                || h < 5
                || w < gap * .55f
                || w > gap * 1.4f
                || h < gap * .55f
                || h > gap * 1.4f
                || w > h * 1.5f
                || h > w * 1.5f) return false;
        double[][] fill = new double[3][3];
        int[][] count = new int[3][3];
        for (int y = 0; y < h; y++) {
            int row = Math.min(2, y * 3 / h), sourceRow = (top + y) * width + left;
            for (int x = 0; x < w; x++) {
                int col = Math.min(2, x * 3 / w);
                count[row][col]++;
                if (ink(pixels[sourceRow + x], label)) fill[row][col]++;
            }
        }
        for (int y = 0; y < 3; y++)
            for (int x = 0; x < 3; x++) fill[y][x] /= Math.max(1, count[y][x]);
        boolean corners = fill[0][0] > .15 && fill[0][2] > .3 && fill[2][0] > .3 && fill[2][2] > .3;
        return corners
                && fill[1][1] > .6
                && (fill[0][1] < .4 && fill[2][1] < .4
                        || notchedCross(pixels, width, left, top, w, h, label));
    }

    /** Heavy engraving narrows the white notches but retains two arms and a pinched waist. */
    private static boolean notchedCross(
            byte[] pixels, int width, int left, int top, int w, int h, byte label) {
        int upper = 0, lower = 0, waist = 0, cx = (w - 1) / 2;
        for (int y = 0; y < h; y++) {
            int row = (top + y) * width + left;
            if (!ink(pixels[row + cx], label)) {
                int a = cx, b = cx;
                while (a > 0 && !ink(pixels[row + a - 1], label)) a--;
                while (b + 1 < w && !ink(pixels[row + b + 1], label)) b++;
                int l = 0, r = 0;
                for (int x = 0; x < a; x++) if (ink(pixels[row + x], label)) l++;
                for (int x = b + 1; x < w; x++) if (ink(pixels[row + x], label)) r++;
                if (b - a + 1 >= Math.max(2, Math.round(w * .12f))
                        && l >= w * .2f
                        && r >= w * .2f) {
                    if (y < h * .35f) upper++;
                    if (y >= h * .65f) lower++;
                }
            } else if (y >= h * .35f && y < h * .65f) {
                int l = 0, r = 0;
                for (int x = 0; x < w * .2f; x++) if (!ink(pixels[row + x], label)) l++;
                for (int x = (int) Math.ceil(w * .8f); x < w; x++)
                    if (!ink(pixels[row + x], label)) r++;
                if (l >= w * .12f && r >= w * .12f) waist++;
            }
        }
        return upper >= 2 && lower >= 2 && waist >= 2;
    }

    private static boolean ink(byte pixel, byte label) {
        return label == 0
                ? pixel == OmrMeasurePostProcessor.CLEF_OR_KEY
                        || pixel == OmrMeasurePostProcessor.SYMBOL
                : pixel == label;
    }
}
