// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A separate unmetered opening system can contain several short, fully written examples. */
final class PrintedOpeningSixteenthExamples {
    private PrintedOpeningSixteenthExamples() {}

    static void apply(ScorePageInterpretation score, byte[] labels, byte[] normalized,
            byte[] original, int width, int height, float[] beats) {
        var regions = score.measures();
        if (regions.size() < 4 || regions.size() != beats.length
                || labels == null || normalized == null || original == null
                || labels.length != (long) width * height
                || normalized.length != labels.length || original.length != labels.length
                || score.openingPickup()) return;
        var first = regions.get(0);
        float gap = (first.bottom() - first.top()) * height / 8.2f;
        if (gap < 10 || gap > 26) return;
        int end = 1;
        while (end < regions.size()
                && Math.abs(regions.get(end).top() - first.top()) * height < gap * .4f) end++;
        if (end < 3 || end > 6 || end == regions.size()
                || (regions.get(end).top() - first.bottom()) * height < gap * 10) return;
        final int followingSystem = end;
        if (score.rests().stream().anyMatch(r -> r.measureIndex() < followingSystem)
                || score.meterChanges().stream().anyMatch(m -> m.measureIndex() < followingSystem))
            return;
        var bars = new ArrayList<List<ScoreNoteEvent>>(end);
        for (int i = 0; i < end; i++) bars.add(new ArrayList<>());
        for (var note : score.notes())
            if (note.measureIndex() < end) bars.get(note.measureIndex()).add(note);
        for (int i = 0; i < end; i++) {
            var notes = bars.get(i);
            notes.sort(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure));
            var bar = regions.get(i);
            if (beats[i] != 4 || bar.cadenceQuarterBeats() != 0 || notes.size() != 8
                    || (bar.right() - bar.left()) * width < gap * 22) return;
            if (i > 0) {
                float seam = (bar.left() - regions.get(i - 1).right()) * width;
                if (seam < gap * .3f || seam > gap * 1.65f) return;
            }
            if (!validNotes(notes, bar, width, gap)) return;
        }
        if (!printed(normalized, labels, width, height, regions, bars, gap)
                || original != normalized && !printed(original, labels, width, height, regions, bars, gap))
            return;
        for (int i = 0; i < end; i++) beats[i] = 2;
    }

    private static boolean validNotes(List<ScoreNoteEvent> notes, MeasureRegion bar, int width, float gap) {
        float span = (bar.right() - bar.left()) * width;
        int clef = notes.get(0).clefBottomDiatonic();
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.staffIndex() != 0 || n.staffCount() != 1 || n.clefBottomDiatonic() != clef
                    || n.beamCount() != 2 || n.unbeamedDurationBeats() != 0 || n.augmentationDots() != 0
                    || n.tupletDivisor() != 1 || n.tupletNormalNotes() != 1 || n.tiedFromPrevious()
                    || n.boundaryTies() != 0 || n.crossStaffBeam() || n.leadingRestBeats() != 0
                    || n.followingRestBeats() != 0 || (n.articulations() & NoteOrnament.GRACE) != 0)
                return false;
            if (i > 0) {
                float step = (n.positionInMeasure() - notes.get(i - 1).positionInMeasure()) * span;
                if (step < gap * 1.8f || step > gap * 6.5f) return false;
            }
        }
        return true;
    }

    private static boolean printed(byte[] gray, byte[] labels, int width, int height,
            List<MeasureRegion> regions, List<List<ScoreNoteEvent>> bars, float gap) {
        var first = regions.get(0);
        float opening = first.left() * width;
        var begin = staffAt(gray, width, height, first, opening + 2 * gap, gap);
        if (begin == null || CommonTimeMeter.readHeader(
                labels, gray, width, height, Math.round(opening), Math.round(begin.top()), begin.gap()) != 0)
            return false;
        var next = regions.get(bars.size());
        float header = next.left() * width;
        var nextStaff = staffAt(gray, width, height, next, header + 2 * gap, gap);
        if (nextStaff == null || CommonTimeMeter.readHeader(
                labels, gray, width, height,
                Math.round(header), Math.round(nextStaff.top()), nextStaff.gap()) != 4) return false;
        for (int i = 0; i < bars.size(); i++) {
            var bar = regions.get(i);
            float edge = bar.right() * width;
            if (i + 1 < bars.size()) {
                var frame = staffAt(gray, width, height, bar, edge - gap, gap);
                if (frame == null) return false;
                float seam = (edge + regions.get(i + 1).left() * width) * .5f;
                int rule = PrintedCadenceDurations.boundaryColumn(gray, width, frame, seam, false, false);
                if (rule < 0 || ClosedHeadBarlineGuard.attached(
                        gray, width, height, rule, Math.round(frame.top()), Math.round(frame.bottom()), frame.gap())
                        || DottedDyadMeasureSeparator.continuesBeyondStaff(
                                gray, width, height, rule, Math.round(frame.top()),
                                Math.round(frame.bottom()), frame.gap())) return false;
            } else if (!openEnd(gray, width, height, bar, edge, gap)) return false;
            if (!twoGroups(gray, width, height, bar, bars.get(i), gap)) return false;
        }
        return true;
    }

    private static boolean openEnd(byte[] gray, int width, int height, MeasureRegion bar, float edge, float gap) {
        float x0 = edge - 7 * gap, x1 = edge - 3 * gap;
        var a = staffAt(gray, width, height, bar, x0, gap);
        var b = staffAt(gray, width, height, bar, x1, gap);
        if (a == null || b == null || Math.abs(a.bottom() - b.bottom()) > gap * .28f
                || Math.abs(a.gap() - b.gap()) > gap * .12f
                || PrintedCadenceDurations.boundaryColumn(gray, width, b, edge, true, false) >= 0)
            return false;
        var track = StaffPitchTrack.fromVerifiedSamples(
                List.of(new float[]{x0,a.bottom(),a.gap()}, new float[]{x1,b.bottom(),b.gap()}));
        return ClosingTripletRhythm.printedEnd(gray, width, height, bar, track, b.gap());
    }

    private static PlayingTechniqueDetector.Staff staffAt(
            byte[] gray, int width, int height, MeasureRegion bar, float x, float gap) {
        float floor = bar.bottom() * height - gap * 2.1f;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, x, floor, gap);
        if (rails == null) return null;
        return new PlayingTechniqueDetector.Staff(rails[0] - 4 * rails[1], rails[0], rails[1], 0, 1);
    }

    private static boolean twoGroups(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes, float gap) {
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        var x = new float[notes.size()];
        var y = new float[notes.size()];
        for (int i = 0; i < notes.size(); i++) {
            x[i] = left + span * notes.get(i).positionInMeasure();
            y[i] = notes.get(i).pageY() * height;
        }
        int firstDirection = notes.get(0).stemDirection(), secondDirection = notes.get(4).stemDirection();
        if (firstDirection == 0 || secondDirection == 0) return false;
        for (int i = 0; i < notes.size(); i++) {
            int expected = i < 4 ? firstDirection : secondDirection;
            int stored = notes.get(i).stemDirection();
            if (stored != 0 && stored != expected) return false;
        }
        return !PrintedTupletBeamOwner.connectedHeads(gray, width, height, x[3], y[3], x[4], y[4], gap, 1)
                && !PrintedTupletBeamOwner.connectedHeads(gray, width, height, x[3], y[3], x[4], y[4], gap, -1)
                && thickPair(gray, width, height, x, y, 0, firstDirection, gap)
                && thickPair(gray, width, height, x, y, 4, secondDirection, gap);
    }

    private static boolean thickPair(byte[] gray, int width, int height, float[] x, float[] y,
            int first, int direction, float gap) {
        float closest = y[first];
        for (int i = first + 1; i <= first + 3; i++)
            closest = direction > 0 ? Math.min(closest, y[i]) : Math.max(closest, y[i]);
        for (int depth = Math.round(gap * .65f); depth <= Math.round(gap * 2.8f); depth++) {
            int inner = Math.round(closest) - direction * depth;
            for (int d = Math.round(gap * .80f); d <= Math.round(gap * 1.16f); d++) {
                int outer = inner - direction * d;
                if (connectedRails(gray, width, height, x, first, gap, inner, outer)
                        && stemContacts(gray, width, height, x, y, first, gap, direction, inner))
                    return true;
            }
        }
        return false;
    }

    private static boolean connectedRails(byte[] gray, int width, int height, float[] x,
            int first, float gap, int inner, int outer) {
        if (Math.min(inner, outer) < 1 || Math.max(inner, outer) >= height - 1) return false;
        int middle = (inner + outer) / 2;
        for (int i = first; i < first + 3; i++) {
            int l = Math.round(x[i] + gap * .85f), r = Math.round(x[i + 1] - gap * .85f);
            if (l < 0 || r >= width || r <= l) return false;
            int thick = 0, clear = 0;
            for (int col = l; col <= r; col++) {
                if (core(gray, width, col, inner) && core(gray, width, col, outer)) thick++;
                if (!core(gray, width, col, middle)) clear++;
            }
            if (thick < (r - l + 1) * .91f || clear < (r - l + 1) * .75f) return false;
        }
        return true;
    }

    private static boolean core(byte[] gray, int width, int col, int row) {
        for (int dy = -1; dy <= 1; dy++)
            if ((gray[(row + dy) * width + col] & 255) >= 165) return false;
        return true;
    }

    private static boolean stemContacts(byte[] gray, int width, int height, float[] x, float[] y,
            int first, float gap, int direction, int inner) {
        for (int i = first; i <= first + 3; i++) {
            int from = Math.round(y[i] - direction * gap * .42f);
            int top = Math.min(from, inner), bottom = Math.max(from, inner);
            if (top < 0 || bottom >= height || bottom - top < gap * .2f
                    || bottom - top > gap * 6.5f) return false;
            int lo = Math.round(x[i] + direction * gap * .5f - gap * .22f);
            int hi = Math.round(x[i] + direction * gap * .5f + gap * .22f);
            if (lo < 0 || hi >= width) return false;
            boolean attached = false;
            for (int col = lo; col <= hi && !attached; col++) {
                int ink = 0, blank = 0;
                for (int row = top; row <= bottom; row++) {
                    if ((gray[row * width + col] & 255) < 165) {
                        ink++;
                        blank = 0;
                    } else if (++blank > Math.max(1, Math.round(gap * .15f))) break;
                }
                attached = ink >= (bottom - top + 1) * .92f;
            }
            if (!attached) return false;
        }
        return true;
    }
}
