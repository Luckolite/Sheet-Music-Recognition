// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.HashMap;
import java.util.List;

/** A complete quarter-rest contour cannot also subdivide its printed measure. */
final class PrintedQuarterRestBoundary {
    private record Position(int x, float top, float bottom, float gap) {}

    private final byte[] gray;
    private final int width, height;
    private final HashMap<Position, Boolean> decisions = new HashMap<>();

    /** Shared by the semantic and regional passes over one read-only staff raster. */
    private PrintedQuarterRestBoundary(byte[] gray, int width, int height) {
        this.gray = gray;
        this.width = width;
        this.height = height;
    }

    static PrintedQuarterRestBoundary forRaster(byte[] gray, int width, int height) {
        return gray == null ? null : new PrintedQuarterRestBoundary(gray, width, height);
    }

    boolean owns(int x, float top, float bottom, float gap) {
        return decisions.computeIfAbsent(
                new Position(x, top, bottom, gap),
                p -> owns(gray, width, height, p.x(), p.top(), p.bottom(), p.gap()));
    }

    static boolean owns(byte[] gray, int w, int h, float x, float top, float bottom, float gap) {
        if (gray == null
                || w < 1
                || h < 1
                || gray.length != (long) w * h
                || !Float.isFinite(x)
                || !Float.isFinite(top)
                || !Float.isFinite(bottom)
                || !Float.isFinite(gap)
                || gap < 6
                || gap > Math.min(w, h) / 8f
                || Math.abs(bottom - top - gap * 4) > gap * .25f) return false;
        return agreesAcrossCrops(gray, w, h, x, top, bottom, gap)
                || stackedInk(gray, w, h, x, top, bottom, gap)
                        && agreesAcrossCrops(gray, w, h, x, top + 2 * gap, bottom + 2 * gap, gap);
    }

    private static boolean agreesAcrossCrops(
            byte[] gray, int w, int h, float x, float top, float bottom, float gap) {
        var narrow = read(gray, w, h, x, top, bottom, gap, 4);
        if (narrow == null) return false;
        var wide = read(gray, w, h, x, top, bottom, gap, 5);
        return wide != null
                && Math.abs(narrow[0] - wide[0]) <= gap * .15f
                && Math.abs(narrow[1] - wide[1]) <= gap * .15f
                && Math.abs(narrow[2] - wide[2]) <= gap * .15f;
    }

    /** Two printed voices can place matching quarter glyphs in one column. The ordinary
     * single-staff crop excludes their outer tips, so certify the pair on untouched ink. */
    private static boolean stackedInk(
            byte[] gray, int w, int h, float x, float top, float bottom, float gap) {
        int center = Math.round(x), radius = Math.round(gap * .85f);
        int first = Math.round(top - gap * .65f), rows = Math.round(gap * 3.1f);
        int shift = Math.round(gap * 3);
        if (center - radius < 0 || center + radius >= w || first < 0
                || first + rows + shift > h) return false;
        // A real thin rule beside or through the rests still has its own continuous axis.
        for (int col = center - radius; col <= center + radius; col++) {
            boolean continuous = true;
            for (int y = Math.round(top); y <= Math.round(bottom); y++)
                if ((gray[y * w + col] & 255) >= 195) {
                    continuous = false;
                    break;
                }
            if (continuous) return false;
        }
        int upperInk = 0, lowerInk = 0, matched = 0, beforeTop = 0, afterBottom = 0;
        int ruleRadius = Math.max(1, Math.round(gap * .095f));
        for (int y = first; y < first + rows; y++) {
            if (nearPrintedRule(y, top, gap, ruleRadius)
                    || nearPrintedRule(y + shift, top, gap, ruleRadius)) continue;
            for (int col = center - radius; col <= center + radius; col++) {
                boolean upper = (gray[y * w + col] & 255) < 155;
                boolean lower = (gray[(y + shift) * w + col] & 255) < 155;
                if (upper) upperInk++;
                if (lower) lowerInk++;
                if (upper && lower) {
                    matched++;
                    if (y < top - ruleRadius) beforeTop++;
                    if (y + shift > bottom + ruleRadius) afterBottom++;
                }
            }
        }
        return matched >= gap * gap * .58f
                && matched >= (upperInk + lowerInk - matched) * .88f
                && beforeTop >= gap * .55f && afterBottom >= gap * .55f;
    }

    private static boolean nearPrintedRule(int y, float top, float gap, int radius) {
        int rule = Math.round((y - top) / gap);
        return rule >= 0 && rule <= 4 && Math.abs(y - (top + rule * gap)) <= radius;
    }

    private static float[] read(
            byte[] gray, int w, int h, float x, float top, float bottom, float gap, int radius) {
        int left = Math.round(x - gap * radius), right = Math.round(x + gap * radius);
        int first = Math.round(top - gap * 2), last = Math.round(bottom + gap * 2);
        if (left < 0 || right >= w || first < 0 || last >= h) return null;
        int cw = right - left + 1, ch = last - first + 1;
        byte[] crop = new byte[cw * ch];
        for (int y = 0; y < ch; y++)
            System.arraycopy(gray, (first + y) * w + left, crop, y * cw, cw);
        var staff = new SixteenthRestDetector.Staff(top - first, bottom - first, gap, 0, 1);
        var rests =
                SixteenthRestDetector.detect(
                        crop,
                        cw,
                        ch,
                        List.of(new MeasureRegion(0, 1, 0, 1)),
                        List.of(staff),
                        List.of());
        float[] found = null;
        for (var rest : rests) {
            if (rest.kind() != ScoreRestEvent.Kind.LITERAL || rest.durationBeats() != 1) continue;
            float cx = left + rest.positionInMeasure() * cw;
            float cy = first + rest.pageY() * ch, extent = rest.pageHeight() * ch;
            if (Math.abs(cx - x) > gap * .4f
                    || extent < gap * 2.1f
                    || extent > gap * 3.6f
                    || cy - extent * .5f < top - gap * .25f
                    || cy + extent * .5f > bottom + gap * .1f) continue;
            if (found != null) return null;
            found = new float[] {cx, cy, extent};
        }
        return found;
    }
}
