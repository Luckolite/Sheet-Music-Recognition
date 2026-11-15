// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Refine only a nearby meter staff from complete local printed rails. */
final class MeterCropStaff {
    private static final float[] PHASE_SHIFTS = {-.3f, -.6f, .3f, .6f};

    static MeterChangeDetector.Crop refine(byte[] gray, int w, int h, MeterChangeDetector.Crop c) {
        if (c == null
                || gray == null
                || w <= 0
                || h <= 0
                || gray.length != (long) w * h
                || !Float.isFinite(c.gap())
                || c.gap() < 6
                || c.gap() > Math.min(w, h) / 8f
                || c.left() < 0
                || c.right() > w
                || c.top() < 0
                || c.bottom() > h
                || c.left() >= c.right()
                || c.top() >= c.bottom()) return c;
        float hint = c.firstLine() + 4 * c.gap();
        var a = PrintedDirectionStaff.printedRules(gray, w, h, c.left() - c.gap(), hint, c.gap());
        var b = PrintedDirectionStaff.printedRules(gray, w, h, c.right() + c.gap(), hint, c.gap());
        if (a == null && b == null) return clippedPhase(gray, w, h, c);
        if (a != null
                && b != null
                && (Math.abs((a[0] - 4 * a[1]) - (b[0] - 4 * b[1])) > c.gap() * .15f
                        || Math.abs(a[1] - b[1]) > c.gap() * .06f)) return c;
        float bottom = a == null ? b[0] : b == null ? a[0] : (a[0] + b[0]) * .5f;
        float gap = a == null ? b[1] : b == null ? a[1] : (a[1] + b[1]) * .5f;
        int first = Math.round(bottom - 4 * gap);
        // Two agreeing five-rule witnesses can prove that the existing crop clips an
        // outer staff rule. Correct its phase without altering raster values.
        float last = first + 4 * gap;
        if (a != null && b == null
                && first >= c.top() && Math.abs(first - c.firstLine()) <= c.gap() * .35f
                && Math.abs(gap - c.gap()) <= c.gap() * .12f
                && last >= c.bottom() && last - c.bottom() <= c.gap() * .15f)
            return extendClippedLowerRule(gray, w, h, c, first, gap, last);
        boolean clipped =
                a != null
                        && b != null
                        && ((first < c.top() && c.top() - first <= c.gap() * .25f)
                                || (last >= c.bottom() && last - c.bottom() <= c.gap() * .25f));
        if (Math.abs(first - c.firstLine()) > c.gap() * (clipped ? .5f : .35f)
                || Math.abs(gap - c.gap()) > c.gap() * .12f
                || first < 0
                || last >= h
                || first < c.top() - (clipped ? c.gap() * .25f : 0)
                || last >= c.bottom() + (clipped ? c.gap() * .25f : 0)) return c;
        int cropTop = c.top(), cropBottom = c.bottom();
        if (a != null && b != null && first != c.firstLine()) {
            // Empty padding displaced by a proved staff correction is not numeral evidence.
            int pad = Math.max(2, Math.round(gap * .25f));
            int trimTop = Math.min(c.bottom() - 1, Math.max(c.top(), first - pad));
            int trimBottom =
                    Math.max(trimTop + 1, Math.min(c.bottom(), Math.round(last) + pad + 1));
            if (clearPadding(gray, w, c.left(), c.right(), c.top(), trimTop)) cropTop = trimTop;
            if (clearPadding(gray, w, c.left(), c.right(), trimBottom, c.bottom())) cropBottom = trimBottom;
        }
        return new MeterChangeDetector.Crop(c.left(), cropTop, c.right(), cropBottom, first, gap);
    }

    /** One complete prefix profile and a continuous clipped rule prove a shallow lower extension. */
    private static MeterChangeDetector.Crop extendClippedLowerRule(
            byte[] gray, int w, int h, MeterChangeDetector.Crop crop, int first, float gap, float last) {
        int rail = Math.round(last);
        int bottom = rail + Math.max(2, Math.round(gap * .25f)) + 1;
        if (bottom > h) return crop;
        int dark = 0;
        for (int x = crop.left(); x < crop.right(); x++)
            if ((gray[rail * w + x] & 255) < 155) dark++;
        int proofRight = Math.min(w, crop.right() + Math.max(1, Math.round(gap * .12f)));
        if (dark < (crop.right() - crop.left()) * .80f
                || !stackedInk(gray, w, crop.left(), proofRight, crop.top(), bottom, first, gap))
            return crop;
        return new MeterChangeDetector.Crop(crop.left(), crop.top(), crop.right(), bottom, first, gap);
    }

    private static boolean clearPadding(byte[] gray, int width, int left, int right,
            int top, int bottom) {
        for (int row = top * width, end = bottom * width; row < end; row += width)
            for (int at = row + left, limit = row + right; at < limit; at++)
                if ((gray[at] & 255) < 245) return false;
        return true;
    }

    /** Two agreeing complete staff witnesses may recover a clipped outer rule.
     * The ordinary nearby refinement above is unchanged for un-clipped crops. */
    private static MeterChangeDetector.Crop clippedPhase(
            byte[] gray, int w, int h, MeterChangeDetector.Crop c) {
        float[] accepted = null;
        int agreeing = 0;
        for (float shift : PHASE_SHIFTS) {
            float hint = c.firstLine() + (4 + shift) * c.gap();
            var a =
                    PrintedDirectionStaff.printedRules(
                            gray, w, h, c.left() - c.gap(), hint, c.gap());
            var b =
                    PrintedDirectionStaff.printedRules(
                            gray, w, h, c.right() + c.gap(), hint, c.gap());
            if (a == null || b == null) continue;
            float af = a[0] - 4 * a[1], bf = b[0] - 4 * b[1];
            if (Math.abs(af - bf) > c.gap() * .15f || Math.abs(a[1] - b[1]) > c.gap() * .06f)
                return c;
            float bottom = (a[0] + b[0]) * .5f, gap = (a[1] + b[1]) * .5f;
            float first = bottom - 4 * gap;
            if (accepted != null
                    && (Math.abs(accepted[0] - first) > c.gap() * .15f
                            || Math.abs(accepted[1] - gap) > c.gap() * .06f)) return c;
            accepted = new float[] {first, gap};
            agreeing++;
        }
        if (agreeing < 2) return c;
        int first = Math.round(accepted[0]);
        float gap = accepted[1], last = first + 4 * gap;
        boolean clippedTop = first < c.top() && c.top() - first <= c.gap() * .5f;
        boolean clippedBottom = last >= c.bottom() && last - c.bottom() <= c.gap() * .5f;
        if ((!clippedTop && !clippedBottom)
                || Math.abs(first - c.firstLine()) > c.gap() * .75f
                || Math.abs(gap - c.gap()) > c.gap() * .12f
                || first < 0
                || last >= h) return c;
        int pad = Math.max(2, Math.round(gap * .25f));
        int top = Math.min(c.top(), first - pad);
        int bottom = Math.max(c.bottom(), Math.round(last) + pad + 1);
        if (top < 0
                || bottom > h
                || !stackedInk(gray, w, c.left(), c.right(), top, bottom, first, gap)) return c;
        return new MeterChangeDetector.Crop(c.left(), top, c.right(), bottom, first, gap);
    }

    /** A phase search must not turn empty staff rails into a numeral crop. */
    private static boolean stackedInk(
            byte[] gray, int w, int left, int right, int top, int bottom, int first, float gap) {
        int middle = Math.round(first + 2 * gap);
        int railRadius = Math.max(1, Math.round(gap * .12f));
        int[] rails = new int[5];
        for (int line = 0; line < rails.length; line++) rails[line] = Math.round(first + line * gap);
        float centerA = 0, centerB = 0;
        for (int half = 0; half < 2; half++) {
            int minX = right, maxX = left - 1, minY = bottom, maxY = top - 1, count = 0;
            for (int y = half == 0 ? top : middle; y < (half == 0 ? middle : bottom); y++) {
                boolean rule = false;
                for (int rail : rails) {
                    if (Math.abs(y - rail) <= railRadius) {
                        rule = true;
                        break;
                    }
                }
                if (rule) continue;
                for (int x = left; x < right; x++)
                    if ((gray[y * w + x] & 255) < 150) {
                        count++;
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
            }
            int width = maxX - minX + 1, height = maxY - minY + 1;
            if (count < gap * 3
                    || width < gap * .55f
                    || width > gap * 2.5f
                    || height < gap * .9f
                    || height > gap * 2.5f
                    || minX <= left
                    || maxX >= right - 1
                    || minY <= top
                    || maxY >= bottom - 1) return false;
            float center = (minX + maxX) * .5f;
            if (half == 0) centerA = center;
            else centerB = center;
        }
        return Math.abs(centerA - centerB) <= gap * .3f;
    }
}
