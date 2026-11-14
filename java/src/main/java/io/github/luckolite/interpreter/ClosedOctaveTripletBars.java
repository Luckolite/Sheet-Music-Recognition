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
                    || !ownsBar(notes, owners, measures.get(m), gray, width, height)) continue;
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
            MeasureRegion bar,
            byte[] gray,
            int width,
            int height) {
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
        float first = 0, previous = 0, upperX = 0, upperY = 0, lowerX = 0, lowerY = 0;
        int seeds = 0;
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
            if (upper.tupletDivisor() == 3) seeds++;
            float x0 = left + span * upper.positionInMeasure(), y0 = height * upper.pageY();
            float x1 = left + span * lower.positionInMeasure(), y1 = height * lower.pageY();
            if (Math.abs(x0 - x1) > gap * .18f || y1 - y0 < gap * 3.15f || y1 - y0 > gap * 3.9f)
                return false;
            float x = (x0 + x1) * .5f;
            if (i == 0) first = x;
            else if (x - previous < gap * 1.75f
                    || x - previous > gap * 4.1f
                    || WrappedTripletDotInk.uniqueCore(gray, width, height, upperX, upperY, x, gap)
                            != null
                    || WrappedTripletDotInk.uniqueCore(gray, width, height, lowerX, lowerY, x, gap)
                            != null) return false;
            var u = PrintedBeamShafts.at(gray, width, height, x0, y0, gap, budget);
            var v = PrintedBeamShafts.at(gray, width, height, x1, y1, gap, budget);
            if (u.size() != 1 || v.size() != 1) return false;
            var high = u.get(0);
            var low = v.get(0);
            if (high.direction() != 1
                    || low.direction() != 1
                    || Math.min(high.right(), low.right()) < Math.max(high.left(), low.left())
                    || Math.abs(high.endY() - low.endY()) > gap * .15f) return false;
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
        return seeds == 3
                && WrappedTripletDotInk.uniqueCore(gray, width, height, upperX, upperY, next, gap)
                        == null
                && WrappedTripletDotInk.uniqueCore(gray, width, height, lowerX, lowerY, next, gap)
                        == null
                && WrappedMeasureBoundaries.confirmsClosed(
                        gray, width, height, bar, first, previous);
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
