// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Bounded raw-ink proof of a closed beam group with no unowned interior stem. */
final class CompleteBeamGroupInk {
    static boolean proves(
            byte[] gray, int w, int h, float[] xs, float[] ys, float gap, int direction) {
        if (gray == null
                || w < 1
                || h < 1
                || gray.length != (long) w * h
                || xs == null
                || ys == null
                || xs.length != ys.length
                || xs.length < 2
                || xs.length > 32
                || !Float.isFinite(gap)
                || gap < 6
                || Math.abs(direction) != 1) return false;
        var stems = new ArrayList<PairedTremoloInk.Stem>();
        for (int i = 0; i < xs.length; i++) {
            if (i > 0 && xs[i] - xs[i - 1] < gap * .7f) return false;
            var s = PairedTremoloInk.stem(gray, w, h, xs[i], ys[i], gap, direction);
            if (s == null) return false;
            stems.add(s);
        }
        return provesShafts(gray, w, h, stems, gap, direction);
    }

    /** Raw closed-group property only. Callers must independently own every supplied full shaft. */
    static boolean provesShafts(
            byte[] gray,
            int w,
            int h,
            List<PairedTremoloInk.Stem> stems,
            float gap,
            int direction) {
        if (gray == null
                || w < 1
                || h < 1
                || gray.length != (long) w * h
                || stems == null
                || stems.size() < 2
                || stems.size() > 32
                || !Float.isFinite(gap)
                || gap < 6
                || Math.abs(direction) != 1) return false;
        for (int i = 0; i < stems.size(); i++) {
            var stem = stems.get(i);
            if (stem == null
                    || stem.x() < 0
                    || stem.x() >= w
                    || stem.headY() < 0
                    || stem.headY() >= h
                    || stem.endY() < 0
                    || stem.endY() >= h
                    || i > 0 && stem.x() <= stems.get(i - 1).x()) return false;
        }
        var first = stems.get(0);
        var last = stems.get(stems.size() - 1);
        if (last.x() - first.x() < gap || last.x() - first.x() > gap * 40) return false;
        float slope = (last.endY() - first.endY()) / (float) (last.x() - first.x());
        if (Math.abs(slope) > .35f) return false;
        int inset = Math.max(1, Math.round(gap * .12f));
        for (var stem : stems) {
            float line = first.endY() + (stem.x() - first.x()) * slope;
            if (Math.abs(stem.endY() - line) > gap * .2f) return false;
        }
        int railStart = first.x() + 2, railEnd = last.x() - 1;
        int totalRail = railEnd - railStart, core = Math.max(2, Math.round(gap * .12f));
        if (totalRail <= 0) return false;
        if (!thickRail(gray, w, h, first, slope, railStart, railEnd, core, direction, 0)) {
            // Test one complete interior ray; never combine it column by column with the edge.
            long work = (long) totalRail * core;
            if (work > (long) w * h
                    || !thickRail(gray, w, h, first, slope, railStart, railEnd, core, direction, inset))
                return false;
        }
        // A group continuing past either outer owner is incomplete.
        int firstDistance = Math.round(gap * .35f), lastDistance = (int) gap;
        int samples = lastDistance - firstDistance + 1;
        if (samples <= 0) return false;
        float maximumDark = samples * .2f;
        for (int side = -1; side <= 1; side += 2) {
            int edge = side < 0 ? first.x() : last.x(), dark = 0;
            for (int d = firstDistance; d <= lastDistance; d++) {
                int x = edge + side * d;
                int y = Math.round(first.endY() + (x - first.x()) * slope) + direction * inset;
                if (x < 0 || x >= w || y < 0 || y >= h) return false;
                if ((gray[y * w + x] & 255) < 165 && ++dark > maximumDark) return false;
            }
        }
        for (int i = 1; i < stems.size(); i++) {
            var a = stems.get(i - 1);
            var b = stems.get(i);
            if (!BeamInkConnectivity.connected(
                    gray, w, h, a.x(), a.endY() + direction * inset,
                    b.x(), b.endY() + direction * inset, gap)) return false;
        }
        // Trace every vertical shaft from the beam back toward the heads, including shafts
        // whose head the model missed. Every long shaft must belong to a supplied owner.
        float ownershipRadius = Math.max(2, gap * .2f);
        int owner = 0;
        for (int x = first.x() + 1; x < last.x(); x++) {
            while (x - stems.get(owner).x() > ownershipRadius) owner++;
            if (Math.abs(stems.get(owner).x() - x) <= ownershipRadius) continue;
            int y0 = Math.round(first.endY() + (x - first.x()) * slope) + direction * inset;
            int misses = 0;
            for (int d = 0; d <= gap * 3; d++) {
                int y = y0 + direction * d;
                if (y < 0 || y >= h) break;
                if ((gray[y * w + x] & 255) < 165) {
                    if (d >= gap * 2) return false;
                    misses = 0;
                } else if (++misses > 1) break;
            }
        }
        return true;
    }

    private static boolean thickRail(
            byte[] gray, int w, int h, PairedTremoloInk.Stem first, float slope,
            int start, int end, int core, int direction, int inset) {
        float required = (end - start) * .9f;
        int thick = 0;
        for (int x = start; x < end; x++) {
            int edge = Math.round(first.endY() + (x - first.x()) * slope);
            int d = 0;
            for (; d < core; d++) {
                long y = (long) edge + (long) direction * (inset + (long) d);
                if (y < 0 || y >= h || (gray[(int) (y * w + x)] & 255) >= 165) break;
            }
            if (d == core && ++thick >= required) return true;
            if (thick + (end - x - 1) < required) return false;
        }
        return false;
    }
}
