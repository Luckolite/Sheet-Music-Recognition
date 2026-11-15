// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/** A closed common-time bar can prove its four single-rail octave triplet groups. */
final class ClosedOctaveTripletBars {
    private ClosedOctaveTripletBars() {}

    static List<ScoreNoteEvent> apply(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            List<MeasureRegion> measures,
            List<ScoreMeterChange> meters,
            byte[] gray,
            int width,
            int height) {
        if (notes.isEmpty()
                || measures.isEmpty()
                || gray == null
                || gray.length != (long) width * height) return notes;
        var byMeasure = new ArrayList<List<Integer>>(measures.size());
        for (int i = 0; i < measures.size(); i++) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) byMeasure.get(notes.get(i).measureIndex()).add(i);
        var hasRest = new BitSet(measures.size());
        for (var rest : rests) hasRest.set(rest.measureIndex());
        List<ScoreNoteEvent> result = null;
        int next = 0;
        // Four quarters are the reader's inherited beat window until source meter overrides it.
        ScoreMeterChange meter = new ScoreMeterChange(0, 4, 4);
        for (int m = 0; m < measures.size(); m++) {
            while (next < meters.size() && meters.get(next).measureIndex() <= m)
                meter = meters.get(next++);
            var owners = byMeasure.get(m);
            if (meter.numerator() != 4
                    || meter.denominator() != 4
                    || hasRest.get(m)
                    || owners.size() != 24
                    || measures.get(m).cadenceQuarterBeats() != 0
                    || !ownsBar(notes, owners, measures, m, gray, width, height)) continue;
            if (result == null) result = new ArrayList<>(notes);
            for (int owner : owners) {
                var note = notes.get(owner);
                if (note.tupletDivisor() != 3) result.set(owner, note.withTupletRatio(3, 2));
            }
        }
        return result == null ? notes : List.copyOf(result);
    }

    private static boolean ownsBar(
            List<ScoreNoteEvent> notes,
            List<Integer> unsorted,
            List<MeasureRegion> measures,
            int index,
            byte[] gray,
            int width,
            int height) {
        var bar = measures.get(index);
        var owners =
                unsorted.stream()
                        .map(notes::get)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        float left = bar.left() * width, span = bar.right() * width - left;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (!Float.isFinite(left + span + gap)
                || gap < 10
                || gap > 25
                || span < gap * 30
                || span > gap * 40) return false;
        int clef = owners.get(0).clefBottomDiatonic();
        if (clef == ScoreNoteEvent.CLEF_UNKNOWN) return false;
        int seeds = seededDyads(owners);
        if (Integer.bitCount(seeds) != 3) return false;
        float first = octaveCenter(owners, 0, left, span);
        float last = octaveCenter(owners, 22, left, span);
        boolean closed =
                WrappedMeasureBoundaries.confirmsClosed(gray, width, height, bar, first, last);
        boolean interior = closed && oneSeededTriplet(seeds);
        float previous = 0, upperX = 0, upperY = 0, lowerX = 0, lowerY = 0;
        boolean startsSystem = false;
        var budget = new PrintedBeamShafts.Budget(280_000);
        PrintedBeamShafts.Shaft prior = null;
        for (int i = 0; i < owners.size(); i += 2) {
            var a = owners.get(i);
            var b = owners.get(i + 1);
            var upper = a.pageY() < b.pageY() ? a : b;
            var lower = upper == a ? b : a;
            if (!ordinary(upper, clef)
                    || !ordinary(lower, clef)
                    || upper.staffStep() - lower.staffStep() != 7
                    || upper.tupletDivisor() != lower.tupletDivisor()) return false;
            float x0 = left + span * upper.positionInMeasure(), y0 = height * upper.pageY();
            float x1 = left + span * lower.positionInMeasure(), y1 = height * lower.pageY();
            float offset = Math.abs(x0 - x1);
            if (offset > gap * (interior ? .21f : .18f)
                    || y1 - y0 < gap * 3.15f
                    || y1 - y0 > gap * 3.9f) return false;
            float x = (x0 + x1) * .5f;
            if (i != 0
                    && (x - previous < gap * 1.75f
                            || x - previous > gap * 4.1f
                            || competingDot(
                                    gray,
                                    width,
                                    height,
                                    upperX,
                                    upperY,
                                    x,
                                    gap,
                                    startsSystem || interior)
                            || competingDot(
                                    gray,
                                    width,
                                    height,
                                    lowerX,
                                    lowerY,
                                    x,
                                    gap,
                                    startsSystem || interior))) return false;
            var u = PrintedBeamShafts.at(gray, width, height, x0, y0, gap, budget);
            var v = PrintedBeamShafts.at(gray, width, height, x1, y1, gap, budget);
            if (v.isEmpty() && startsSystem && !budget.refused()) {
                v = contrastShaft(gray, width, height, x1, y1, gap, budget);
            }
            if (v.size() != 1 || budget.refused()) return false;
            var low = v.get(0);
            if (low.direction() != 1) return false;
            if (i == 0) {
                startsSystem =
                        WrappedMeasureBoundaries.confirmsShaftBoundedInitialStaffBar(
                                gray, width, height, measures, index, x, low.left());
            }
            if (u.size() > 1) return false;
            var high = u.isEmpty() ? null : u.get(0);
            boolean shared =
                    high != null
                            && high.direction() == 1
                            && Math.min(high.right(), low.right())
                                    >= Math.max(high.left(), low.left())
                            && Math.abs(high.endY() - low.endY()) <= gap * .15f;
            // A larger centroid offset still requires both owners to resolve the same shaft.
            if (offset > gap * .18f && !shared) return false;
            if (!shared
                    && (!(startsSystem || interior)
                            || high != null && high.direction() != 1
                            || !continuesUpperShaft(gray, width, height, x0, y0, low, gap)))
                return false;
            if (prior != null
                    && !WrappedEighthTriplets.matchesRailBridge(
                            gray, width, height, prior, low, gap, i % 6 != 0)) return false;
            upperX = x0;
            upperY = y0;
            lowerX = x1;
            lowerY = y1;
            prior = low;
            previous = x;
        }
        float next = left + span + gap * .5f;
        return !competingDot(
                        gray, width, height, upperX, upperY, next, gap, startsSystem || interior)
                && !competingDot(
                        gray, width, height, lowerX, lowerY, next, gap, startsSystem || interior)
                && (closed
                        || startsSystem
                                && WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                                        gray, width, height, bar, previous));
    }

    private static float octaveCenter(
            List<ScoreNoteEvent> owners, int first, float left, float span) {
        return ((left + span * owners.get(first).positionInMeasure())
                        + (left + span * owners.get(first + 1).positionInMeasure()))
                * .5f;
    }

    private static int seededDyads(List<ScoreNoteEvent> owners) {
        int seeds = 0;
        for (int i = 0; i < owners.size(); i += 2) {
            if (owners.get(i).tupletDivisor() == 3) seeds |= 1 << (i / 2);
        }
        return seeds;
    }

    private static boolean oneSeededTriplet(int seeds) {
        int first = Integer.numberOfTrailingZeros(seeds);
        return first % 3 == 0 && seeds == (0b111 << first);
    }

    private static boolean competingDot(
            byte[] gray,
            int width,
            int height,
            float headX,
            float headY,
            float nextX,
            float gap,
            boolean allowLinkedMarks) {
        var dot = WrappedTripletDotInk.uniqueCore(gray, width, height, headX, headY, nextX, gap);
        return dot != null
                && (!allowLinkedMarks
                        || !WrappedEighthTriplets.headConnectedLedger(
                                        gray, width, height, headX, headY, dot, gap, .40f)
                                && !OctaveMarkDetector.dotBelongsToDashOrigin(
                                        gray, width, height, headX, headY, dot, gap));
    }

    private static List<PrintedBeamShafts.Shaft> contrastShaft(
            byte[] gray,
            int width,
            int height,
            float x,
            float y,
            float gap,
            PrintedBeamShafts.Budget budget) {
        var contrast =
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
            return PrintedBeamShafts.at(gray, width, height, x, y, gap, budget, contrast);
        } catch (ShaftQuotaExceeded exhausted) {
            return List.of();
        }
    }

    private static final class ShaftQuotaExceeded extends RuntimeException {}

    /** Faint intersections may interrupt an upper read while the common lower shaft remains literal. */
    private static boolean continuesUpperShaft(
            byte[] gray,
            int width,
            int height,
            float headX,
            float headY,
            PrintedBeamShafts.Shaft lower,
            float gap) {
        int axis = (lower.left() + lower.right()) / 2;
        int left = axis - 2,
                right = axis + 1,
                top = Math.round(headY + gap * .2f),
                bottom = lower.startY();
        if (left < 0
                || right >= width
                || top < 0
                || bottom >= height
                || bottom - top < gap * 2.8f
                || headX - axis < gap * .42f
                || headX - axis > gap * .78f) return false;
        int soft = 0, run = 0;
        for (int y = top; y <= bottom; y++) {
            int minimum = 255;
            for (int x = left; x <= right; x++)
                minimum = Math.min(minimum, gray[y * width + x] & 255);
            if (minimum >= 235) return false;
            if (minimum >= 160) {
                if (++soft > 4 || ++run > 3) return false;
            } else run = 0;
        }
        return true;
    }

    private static boolean ordinary(ScoreNoteEvent note, int clef) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffCount() == 1
                && note.staffIndex() == 0
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.stemDirection() == -1
                && note.beamCount() == 1
                && note.unbeamedDurationBeats() == 0
                && note.augmentationDots() == 0
                && !note.crossStaffBeam()
                && note.leadingRestBeats() == 0
                && note.followingRestBeats() == 0
                && !note.tiedFromPrevious()
                && note.boundaryTies() == 0
                && note.clefBottomDiatonic() == clef
                && (note.articulations() & NoteOrnament.GRACE) == 0
                && (note.tupletDivisor() == 1 && note.tupletNormalNotes() == 1
                        || note.tupletDivisor() == 3 && note.tupletNormalNotes() == 2);
    }
}
