// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Verifies three undotted, single-rail groups in a three-beat system fragment. */
final class WrappedEighthTriplets {
    private WrappedEighthTriplets() {}

    private record Ribbon(float coverage, int leftY, int rightY) {}

    static WrappedTripletMeasure.Half half(byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates) {
        if (candidates.size() != 9) return null;
        return singleRailGroups(gray, width, height, bar, notes, candidates, false);
    }

    /** Four optically distinct eighth-note groups fill a physically closed common-time bar. */
    static List<ScoreNoteEvent> closeCommonTimeBars(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, List<MeasureRegion> regions,
            List<ScoreMeterChange> meters, byte[] gray, int width, int height) {
        if (meters.isEmpty() || notes.isEmpty()) return notes;
        var bars = new ArrayList<List<Integer>>(regions.size());
        for (int i = 0; i < regions.size(); i++) bars.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) bars.get(notes.get(i).measureIndex()).add(i);
        var result = notes;
        int next = 0;
        ScoreMeterChange meter = null;
        var withRests = new java.util.BitSet(regions.size());
        for (var rest : rests) withRests.set(rest.measureIndex());
        for (int m = 0; m < regions.size(); m++) {
            while (next < meters.size() && meters.get(next).measureIndex() <= m)
                meter = meters.get(next++);
            var owners = bars.get(m);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || withRests.get(m) || owners.size() != 12
                    || owners.stream().map(notes::get).anyMatch(n -> n.beamCount() != 1)
                    || owners.stream().map(notes::get).allMatch(n -> n.tupletDivisor() == 3))
                continue;
            var bar = regions.get(m);
            var proof = closedFourGroups(gray, width, height, bar, notes, owners);
            if (proof == null || !WrappedMeasureBoundaries.confirmsClosed(
                    gray, width, height, bar, proof.x()[0], proof.x()[11])) continue;
            if (result == notes) result = new ArrayList<>(notes);
            for (int i : owners) {
                var n = notes.get(i);
                if (n.tupletDivisor() != 3) result.set(i, n.withTupletRatio(3, 2));
            }
        }
        return result == notes ? notes : List.copyOf(result);
    }

    static WrappedTripletMeasure.Half closedFourGroups(
            byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates) {
        if (candidates.size() != 12) return null;
        return singleRailGroups(gray, width, height, bar, notes, candidates, true);
    }

    private static WrappedTripletMeasure.Half singleRailGroups(
            byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates, boolean closedBar) {
        var owners = candidates.stream()
                .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
        float[] x = new float[owners.size()], y = new float[owners.size()];
        float left = bar.left() * width, right = bar.right() * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(gap) || gap < 10 || gap > 30) return null;
        int clef = notes.get(owners.get(0)).clefBottomDiatonic();
        int reportedExtraRail = 0;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        var budget = new PrintedBeamShafts.Budget(800_000);
        for (int i = 0; i < owners.size(); i++) {
            var n = notes.get(owners.get(i));
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffIndex() != 0 || n.staffCount() != 1
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.beamCount() < 1 || n.beamCount() > 2
                    || n.augmentationDots() != 0 || n.unbeamedDurationBeats() != 0 || n.crossStaffBeam()
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0 || n.tiedFromPrevious()
                    || n.boundaryTies() != 0 || (n.articulations() & NoteOrnament.GRACE) != 0
                    || clef == ScoreNoteEvent.CLEF_UNKNOWN || n.clefBottomDiatonic() != clef
                    || !(n.tupletDivisor() == 1
                            || n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2)
                    || Math.abs(n.stemDirection()) != 1) return null;
            if (n.beamCount() == 2 && ++reportedExtraRail > 1) return null;
            x[i] = left + (right - left) * n.positionInMeasure();
            y[i] = n.pageY() * height;
            if (i > 0 && (x[i] - x[i-1] < gap * 1.65f || x[i] - x[i-1] > gap * (closedBar ? 3.85f : 3.4f))) return null;
            var source = PrintedBeamShafts.at(gray, width, height, x[i], y[i], gap, budget);
            if (closedBar && i % 3 == 1 && source.isEmpty() && !budget.refused()) {
                shafts.add(null);
                continue;
            }
            if (source.size() != 1 || source.get(0).direction() != -n.stemDirection()) return null;
            shafts.add(source.get(0));
        }
        for (int i = 0; i + 1 < x.length; i++) {
            if (WrappedTripletDotInk.uniqueCore(gray, width, height, x[i], y[i], x[i+1], gap) != null)
                return null;
            var a = shafts.get(i);
            var b = shafts.get(i+1);
            if (a == null || b == null) {
                int middle = a == null ? i : i + 1;
                int stem = notes.get(owners.get(middle)).stemDirection();
                if (stem != notes.get(owners.get(i)).stemDirection()
                        || stem != notes.get(owners.get(i+1)).stemDirection()
                        || !PrintedTupletBeamOwner.connectedHeads(
                                gray, width, height, x[i], y[i], x[i+1], y[i+1], gap, stem)
                        || !singleRail(gray, width, height, shafts.get(middle-1), shafts.get(middle+1), gap))
                    return null;
                continue;
            }
            boolean same = a.direction() == b.direction();
            if (i % 3 == 2) {
                if (same && bestRibbon(gray, width, height, a, b, gap).coverage() > .46f) return null;
                continue;
            }
            if (!same || !singleRail(gray, width, height, a, b, gap)) return null;
        }
        return new WrappedTripletMeasure.Half(owners, x);
    }

    private static boolean singleRail(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap) {
        var ribbon = bestRibbon(gray, width, height, a, b, gap);
        if (ribbon.coverage() < .9f) return false;
        // A second rail leaves a separate dark ribbon parallel to the outer beam.
        int side = -a.direction();
        for (float separation : new float[] {.69f, .84f, .99f, 1.14f}) {
            int dy = side * Math.round(gap * separation);
            if (coverage(gray, width, height, a, b, gap, ribbon.leftY() + dy,
                    ribbon.rightY() + dy, .19f) >= .88f) return false;
        }
        return true;
    }

    private static Ribbon bestRibbon(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap) {
        var best = new Ribbon(0, a.endY(), b.endY());
        int direction = a.direction();
        for (int dy = -Math.round(gap * .28f); dy <= Math.round(gap * .28f); dy++) {
            for (int slope = -1; slope <= 1; slope++) {
                int centre = -direction * Math.round(gap * .14f);
                int leftY = a.endY() + centre + dy;
                int rightY = b.endY() + centre + dy + Math.round(slope * gap * .24f);
                float cover = coverage(gray, width, height, a, b, gap, leftY, rightY, .31f);
                int distance = Math.abs(leftY-a.endY()-centre) + Math.abs(rightY-b.endY()-centre);
                int prior = Math.abs(best.leftY()-a.endY()-centre)
                        + Math.abs(best.rightY()-b.endY()-centre);
                if (cover > best.coverage() || cover == best.coverage() && distance < prior)
                    best = new Ribbon(cover, leftY, rightY);
            }
        }
        return best;
    }

    private static float coverage(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap,
            int leftY, int rightY, float band) {
        int x0 = (a.left() + a.right()) / 2, x1 = (b.left() + b.right()) / 2;
        int padding = Math.round(gap * .45f);
        if (x0 < 0 || x1 >= width || x1 - x0 <= padding * 2) return 0;
        int hits = 0, columns = 0, radius = Math.round(gap * band);
        int thickness = Math.round(gap * .27f);
        for (int x = x0 + padding; x <= x1 - padding; x++) {
            float progress = (x - x0) / (float) (x1 - x0);
            int middle = Math.round(leftY * (1 - progress) + rightY * progress);
            if (middle - radius < 0 || middle + radius >= height) return 0;
            int run = 0;
            for (int y = middle - radius; y <= middle + radius; y++) {
                if ((gray[y * width + x] & 255) < 155) {
                    if (++run >= thickness) {
                        hits++;
                        break;
                    }
                } else run = 0;
            }
            columns++;
        }
        return hits / (float) columns;
    }
}
