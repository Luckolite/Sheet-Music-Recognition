// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;

final class WrappedShaftContinuation {
    private WrappedShaftContinuation() {}

    /** The literal six single-rail groups can survive locally interrupted shafts. */
    static WrappedTripletMeasure.Half half(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            List<Integer> candidates,
            boolean firstHalf) {
        if (candidates.size() != 9) return null;
        var owners =
                candidates.stream()
                        .sorted(
                                java.util.Comparator.comparingDouble(
                                        i -> notes.get(i).positionInMeasure()))
                        .toList();
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(gap) || gap < 10 || gap > 30) return null;
        float[] x = new float[9], y = new float[9];
        var shafts = new PrintedBeamShafts.Shaft[9];
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        var budget = new PrintedBeamShafts.Budget(750_000);
        for (int i = 0; i < 9; i++) {
            var note = notes.get(owners.get(i));
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.staffIndex() != 0
                    || note.staffCount() != 1
                    || note.clefBottomDiatonic() != clef
                    || note.crossStaffBeam()
                    || note.augmentationDots() != 0
                    || note.tiedFromPrevious()
                    || note.boundaryTies() != 0
                    || note.leadingRestBeats() != 0
                    || note.followingRestBeats() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0
                    || Math.abs(note.stemDirection()) != 1
                    || note.tupletDivisor() != 1
                            && (note.tupletDivisor() != 3 || note.tupletNormalNotes() != 2)
                    || !(note.beamCount() == 1 && note.unbeamedDurationBeats() == 0
                            || firstHalf
                                    && i == 0
                                    && note.beamCount() == 0
                                    && note.unbeamedDurationBeats() == 1
                                    && note.stemDirection() == 1)) return null;
            x[i] = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
            y[i] = note.pageY() * height;
            if (i > 0 && (x[i] - x[i - 1] < gap * 1.55f || x[i] - x[i - 1] > gap * 3.4f)
                    || i > 0
                            && WrappedTripletDotInk.uniqueCore(
                                            gray, width, height, x[i - 1], y[i - 1], x[i], gap)
                                    != null) return null;
            var raw = PrintedBeamShafts.at(gray, width, height, x[i], y[i], gap, budget);
            if (raw.size() > 1
                    || raw.size() == 1 && raw.get(0).direction() != -note.stemDirection())
                return null;
            if (raw.size() == 1) shafts[i] = raw.get(0);
        }
        boolean recovered = false;
        for (int i = 0; i < 9; i += 3) {
            int direction = -notes.get(owners.get(i)).stemDirection();
            if (notes.get(owners.get(i + 1)).stemDirection() != -direction
                    || notes.get(owners.get(i + 2)).stemDirection() != -direction) return null;
            var group =
                    recoverGroup(
                            gray,
                            width,
                            height,
                            gap,
                            java.util.Arrays.copyOfRange(x, i, i + 3),
                            java.util.Arrays.copyOfRange(y, i, i + 3),
                            direction,
                            java.util.Arrays.copyOfRange(shafts, i, i + 3));
            if (group == null) return null;
            recovered |= group.revised() >= 0;
            System.arraycopy(group.shafts(), 0, shafts, i, 3);
        }
        if (!recovered) return null;
        for (int i : new int[] {2, 5}) {
            if (shafts[i].direction() == shafts[i + 1].direction()
                    && !WrappedEighthTriplets.matchesRailBridge(
                            gray, width, height, shafts[i], shafts[i + 1], gap, false)) return null;
        }
        return new WrappedTripletMeasure.Half(owners, x);
    }

    static Packed recoverGroup(
            byte[] gray,
            int width,
            int height,
            float gap,
            float[] x,
            float[] y,
            int direction,
            PrintedBeamShafts.Shaft[] shafts) {
        if (linked(gray, width, height, gap, shafts)) return new Packed(shafts, -1);
        Packed revised = null;
        for (int missing = 0; missing < 3; missing++) {
            int k = missing == 0 ? 1 : 0, j = missing == 2 ? 1 : 2;
            var a = shafts[k];
            var b = shafts[j];
            if (a == null
                    || b == null
                    || !WrappedEighthTriplets.matchesRailBridge(
                            gray, width, height, a, b, gap, true)) continue;
            var repaired =
                    continueShaft(
                            gray,
                            width,
                            height,
                            gap,
                            x[missing],
                            y[missing],
                            direction,
                            a,
                            b,
                            shafts[missing]);
            if (repaired == null) continue;
            var proposal = shafts.clone();
            proposal[missing] = repaired;
            if (!linked(gray, width, height, gap, proposal)) continue;
            if (revised != null) return null;
            revised = new Packed(proposal, missing);
        }
        return revised;
    }

    record Packed(PrintedBeamShafts.Shaft[] shafts, int revised) {}

    private static boolean linked(byte[] gray, int w, int h, float g, PrintedBeamShafts.Shaft[] s) {
        return s[0] != null
                && s[1] != null
                && s[2] != null
                && WrappedEighthTriplets.matchesRailBridge(gray, w, h, s[0], s[1], g, true)
                && WrappedEighthTriplets.matchesRailBridge(gray, w, h, s[1], s[2], g, true);
    }

    private static PrintedBeamShafts.Shaft continueShaft(
            byte[] gray,
            int w,
            int h,
            float g,
            float x,
            float y,
            int direction,
            PrintedBeamShafts.Shaft first,
            PrintedBeamShafts.Shaft last,
            PrintedBeamShafts.Shaft prior) {
        float expected = x - direction * g * .60f;
        int x0 = (first.left() + first.right()) / 2, x1 = (last.left() + last.right()) / 2;
        if (x1 <= x0 || Math.abs((last.endY() - first.endY()) / (float) (x1 - x0)) > .60f)
            return null;
        int center =
                (int)
                        Math.round(
                                first.endY()
                                        + (last.endY() - first.endY())
                                                * (expected - x0)
                                                / (double) (x1 - x0));
        int start = Math.round(y + direction * g * .24f);
        int clipping = prior == null ? Integer.MAX_VALUE : (center - prior.endY()) * direction;
        if (clipping < g * .35f
                || prior != null && clipping > g * 2.6f
                || (center - start) * direction < g * 2.3f
                || (center - start) * direction > g * 8.2f) return null;
        int stop = center - direction * Math.round(g * .09f);
        int left = w, right = -1;
        for (int col = Math.round(expected - g * .24f);
                col <= Math.round(expected + g * .24f);
                col++) {
            if (col < 1 || col >= w - 1 || start < 1 || start >= h - 1 || stop < 1 || stop >= h - 1)
                return null;
            int missing = 0, run = 0, longest = 0;
            for (int row = start; (stop - row) * direction >= 0; row += direction) {
                if ((gray[row * w + col] & 255) < 170) run = 0;
                else {
                    missing++;
                    longest = Math.max(longest, ++run);
                }
            }
            if (missing > Math.round(g * .54f) || longest > Math.round(g * .29f)) continue;
            if (right >= 0 && col != right + 1) return null;
            left = Math.min(left, col);
            right = col;
        }
        if (right < left || right - left + 1 > Math.round(g * .38f)) return null;
        return new PrintedBeamShafts.Shaft(left, right, start, center, direction, List.of());
    }
}
