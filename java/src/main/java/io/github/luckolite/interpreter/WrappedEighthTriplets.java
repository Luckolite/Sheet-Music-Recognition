// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Verifies three undotted, single-rail groups in a three-beat system fragment. */
final class WrappedEighthTriplets {
    private WrappedEighthTriplets() {}

    private static final class ShaftQuotaExceeded extends RuntimeException {}

    private record Ribbon(float coverage, int leftY, int rightY) {}

    static WrappedTripletMeasure.Half half(byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> candidates) {
        if (candidates.size() != 9) return null;
        return singleRailGroups(gray, width, height, bar, notes, candidates, false);
    }

    /** Printed groups of three eighths fill a physically closed bar with a quarter-note beat. */
    static List<ScoreNoteEvent> closeQuarterBeatBars(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            List<MeasureRegion> regions,
            List<ScoreMeterChange> meters,
            byte[] gray,
            int width,
            int height) {
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
            if (meter == null
                    || meter.denominator() != 4
                    || meter.numerator() != 4 && meter.numerator() != 6
                    || withRests.get(m)
                    || owners.size() != 3 * meter.numerator()
                    || owners.stream().map(notes::get).anyMatch(n -> n.beamCount() != 1)
                    || owners.stream().map(notes::get).allMatch(n -> n.tupletDivisor() == 3))
                continue;
            var bar = regions.get(m);
            var proof = singleRailGroups(gray, width, height, bar, notes, owners, true);
            if (proof == null
                    || !WrappedMeasureBoundaries.confirmsClosed(
                            gray, width, height, bar, proof.x()[0], proof.x()[owners.size() - 1]))
                continue;
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
        PrintedBeamShaftPixels.Frame contrast = null;
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
            if (i > 0
                    && (x[i] - x[i - 1] < gap * 1.65f
                            || x[i] - x[i - 1] > gap * (closedBar ? 3.85f : 3.4f))) return null;
            var source = PrintedBeamShafts.at(gray, width, height, x[i], y[i], gap, budget);
            if (closedBar && source.isEmpty() && !budget.refused()) {
                if (contrast == null)
                    contrast =
                            new PrintedBeamShaftPixels.Frame(
                                    gray,
                                    gray,
                                    width,
                                    height,
                                    0,
                                    units -> {
                                        if (!budget.take(units)) throw new ShaftQuotaExceeded();
                                    },
                                    true);
                try {
                    source =
                            PrintedBeamShafts.at(
                                    gray, width, height, x[i], y[i], gap, budget, contrast);
                } catch (ShaftQuotaExceeded exhausted) {
                    return null;
                }
            }
            if (closedBar && i % 3 == 1 && source.isEmpty() && !budget.refused()) {
                shafts.add(null);
                continue;
            }
            if (source.size() != 1 || source.get(0).direction() != -n.stemDirection()) return null;
            shafts.add(source.get(0));
        }
        if (x.length == 18) {
            for (int middle = 1; middle < x.length; middle += 3) {
                var earlier = shafts.get(middle - 1);
                var axis = shafts.get(middle);
                var later = shafts.get(middle + 1);
                if (earlier == null
                        || axis == null
                        || later == null
                        || axis.pixels() == null
                        || earlier.direction() != axis.direction()
                        || later.direction() != axis.direction()) continue;
                if (singleRail(gray, width, height, earlier, axis, gap)
                        && singleRail(gray, width, height, axis, later, gap)) continue;
                var continued = continueMiddleShaft(gray, width, height, earlier, axis, later, gap);
                if (continued != null) shafts.set(middle, continued);
            }
        }
        for (int i = 0; i + 1 < x.length; i++) {
            var dot =
                    WrappedTripletDotInk.uniqueCore(gray, width, height, x[i], y[i], x[i + 1], gap);
            if (dot != null
                    && (x.length != 18
                            || !headConnectedLedger(gray, width, height, x[i], y[i], dot, gap)))
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

    /** A compact ledger cap can fill the same dark window as an augmentation dot. */
    private static boolean headConnectedLedger(
            byte[] gray,
            int width,
            int height,
            float headX,
            float headY,
            WrappedTripletDotInk.Center core,
            float gap) {
        int left = Math.round(headX - gap), right = Math.round(core.x() + gap * .4f);
        int top = Math.round(Math.min(core.y(), headY) - gap * .65f);
        int bottom = Math.round(Math.max(core.y(), headY) + gap * .65f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        var graph =
                RawNumeralComponents.capture(
                        gray, width, height, left, top, right, bottom, 100, () -> {}, () -> {});
        int radius = Math.max(1, Math.round(gap * .13f));
        int cx = Math.round(headX), cy = Math.round(headY);
        int leftLedger = Math.round(headX - gap * .60f);
        int dark = 0;
        for (int x = leftLedger; x < cx; x++) {
            for (int y = core.y() - 2; y <= core.y() + 2; y++) {
                if ((gray[y * width + x] & 255) > 120) continue;
                dark++;
                break;
            }
        }
        if (dark < (cx - leftLedger) * .83f) return false;
        if (!taperingLedgerTip(gray, width, headX, core, gap)) return false;
        for (var glyph : graph.components()) {
            if (core.x() < glyph.left
                    || core.x() > glyph.right
                    || core.y() < glyph.top
                    || core.y() > glyph.bottom
                    || glyph.left > leftLedger + 1
                    || glyph.size() < gap * gap * .55f) continue;
            int center = 0;
            boolean containsCore = false;
            for (int i = 0; i < glyph.size(); i++) {
                int pixel = glyph.pixel(i), x = pixel % width, y = pixel / width;
                containsCore |= x == core.x() && y == core.y();
                if (Math.abs(x - cx) <= radius && Math.abs(y - cy) <= radius) center++;
            }
            if (containsCore && center >= radius * radius * 4) return true;
        }
        return false;
    }

    private static boolean taperingLedgerTip(
            byte[] gray, int width, float headX, WrappedTripletDotInk.Center core, float gap) {
        int prior = Integer.MAX_VALUE;
        int thinFrom = Math.round(headX + gap * .74f);
        int right = core.x() + Math.round(gap * .28f);
        int radius = Math.round(gap * .37f);
        int thicknessAtCore = 0, occupied = 0;
        for (int x = thinFrom; x <= right; x++) {
            int thickness = 0;
            for (int y = core.y() - radius; y <= core.y() + radius; y++)
                if ((gray[y * width + x] & 255) < 155) thickness++;
            if (prior != Integer.MAX_VALUE && thickness > prior + 1) return false;
            if (x <= core.x() && thickness == 0) return false;
            if (x == core.x()) thicknessAtCore = thickness;
            if (x > core.x() && thickness > 0) occupied++;
            prior = thickness;
        }
        return thicknessAtCore <= gap * .35f && occupied >= 2;
    }

    /** Keep a head-connected middle shaft only if its ink reappears at both flanks' real rail. */
    private static PrintedBeamShafts.Shaft continueMiddleShaft(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft earlier,
            PrintedBeamShafts.Shaft axis,
            PrintedBeamShafts.Shaft later,
            float gap) {
        int x0 = (earlier.left() + earlier.right()) / 2;
        int x = (axis.left() + axis.right()) / 2;
        int x1 = (later.left() + later.right()) / 2;
        int direction = axis.direction();
        if (!(x0 < x && x < x1) || !singleRail(gray, width, height, earlier, later, gap))
            return null;
        int cap =
                Math.round(
                        earlier.endY()
                                + (later.endY() - earlier.endY()) * (x - x0) / (float) (x1 - x0));
        int remaining = (cap - axis.endY()) * direction;
        if (remaining < gap * .75f || remaining > gap * 2.25f) return null;
        int endpoint = cap - direction * Math.round(gap * .15f);
        int shoulder = Math.round(gap * .25f);
        int first = axis.endY() + direction;
        if (first < 0
                || first >= height
                || endpoint < 0
                || endpoint >= height
                || axis.left() < 0
                || axis.right() >= width
                || x < shoulder
                || x + shoulder >= width) return null;
        int missing = 0;
        for (int y = first; direction * (endpoint - y) >= 0; y += direction) {
            boolean ink = false;
            for (int column = axis.left(); column <= axis.right(); column++)
                ink |= (gray[y * width + column] & 255) < 160;
            if (!ink && ++missing > 2) return null;
        }
        for (int column = x - shoulder; column <= x + shoulder; column++)
            if ((gray[endpoint * width + column] & 255) >= 155) return null;
        var revised =
                new PrintedBeamShafts.Shaft(
                        axis.left(), axis.right(), axis.startY(), cap, direction, axis.columns());
        return singleRail(gray, width, height, earlier, revised, gap)
                        && singleRail(gray, width, height, revised, later, gap)
                ? revised
                : null;
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
