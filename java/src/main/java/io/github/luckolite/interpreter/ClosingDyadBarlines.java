// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Distinguishes a full-height downstem in a closing beamed dyad from a barline. */
final class ClosingDyadBarlines {
    private ClosingDyadBarlines() {}

    record Dyad(float x, float upper, float lower) {}

    static List<MeasureRegion> join(
            List<MeasureRegion> measures, byte[] labels, byte[] gray, byte[] raw, int width, int height) {
        if (measures.size() < 2 || raw == null || raw.length != gray.length) return measures;
        var terminal = measures.get(measures.size() - 1);
        List<MeasureRegion> result = null;
        for (int i = 0; i < measures.size(); i++) {
            var left = measures.get(i);
            if (i + 1 < measures.size()) {
                var right = measures.get(i + 1);
                float gap = (left.bottom() - left.top()) * height / 8.2f;
                float from = left.right() * width, to = right.left() * width;
                if (gap >= 10 && gap <= 25 && to - from >= .30f * gap && to - from <= .73f * gap
                        && Math.abs(terminal.top() - left.top()) * height <= .35f * gap
                        && Math.abs(left.top() - right.top()) * height <= .35f * gap
                        && Math.abs(left.bottom() - right.bottom()) * height <= .35f * gap
                        && (left.right() - left.left()) * width >= 8 * gap
                        && (right.right() - right.left()) * width >= 8 * gap
                        && left.cadenceQuarterBeats() == 0 && right.cadenceQuarterBeats() == 0) {
                    var bar = new MeasureRegion(left.left(), right.right(),
                            Math.min(left.top(), right.top()), Math.max(left.bottom(), right.bottom()));
                    if (ownsSeam(bar, from, to, gap, labels, gray, raw, width, height)) {
                        if (result == null) result = new ArrayList<>(measures.subList(0, i));
                        result.add(bar);
                        i++;
                        continue;
                    }
                }
            }
            if (result != null) result.add(left);
        }
        return result == null ? measures : List.copyOf(result);
    }

    private static boolean ownsSeam(
            MeasureRegion bar, float from, float to, float guess,
            byte[] labels, byte[] gray, byte[] raw, int width, int height) {
        float cut = (from + to) * .5f;
        var normalizedStaff = staff(bar, cut, guess, gray, width, height);
        var printedStaff = staff(bar, cut, guess, raw, width, height);
        if (normalizedStaff == null || printedStaff == null
                || Math.abs(normalizedStaff.bottom() - printedStaff.bottom()) > .24f * guess
                || Math.abs(normalizedStaff.gap() - printedStaff.gap()) > .12f * guess) return false;
        var analysis = OmrScoreInterpreter.analyze(labels, gray, width, height, List.of(bar), raw);
        if (!analysis.rests().isEmpty()) return false;
        float start = bar.left() * width, span = (bar.right() - bar.left()) * width;
        var near = analysis.notes().stream()
                .filter(n -> Math.abs(start + span * n.positionInMeasure() - cut) <= 5.8f * guess)
                .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                .toList();
        var chords = new ArrayList<Dyad>();
        for (int i = 0; i + 1 < near.size(); i++) {
            var a = near.get(i);
            var b = near.get(i + 1);
            float x0 = start + span * a.positionInMeasure(), x1 = start + span * b.positionInMeasure();
            if (x1 - x0 > .23f * guess) continue;
            float y0 = a.pageY() * height, y1 = b.pageY() * height;
            int steps = Math.abs(a.staffStep() - b.staffStep());
            if (!sixteenth(a) || !sixteenth(b) || steps < 2 || steps > 4) continue;
            chords.add(new Dyad((x0 + x1) * .5f, Math.min(y0, y1), Math.max(y0, y1)));
            i++;
        }
        for (int i = 0; i < chords.size(); i++) {
            var chord = chords.get(i);
            if (chord.x() - cut < .36f * guess || chord.x() - cut > .93f * guess) continue;
            if (i + 1 < chords.size()
                    && ownsBothPlanes(chord, chords.get(i + 1), from, to,
                            normalizedStaff, printedStaff, gray, raw, width, height)) return true;
            if (i > 0 && ownsBothPlanes(chord, chords.get(i - 1), from, to,
                    normalizedStaff, printedStaff, gray, raw, width, height)) return true;
        }
        return false;
    }

    private static boolean sixteenth(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffIndex() == 0 && note.staffCount() == 1 && !note.crossStaffBeam()
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.beamCount() == 2 && note.stemDirection() == -1
                && note.augmentationDots() == 0 && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static RawStaffLineDetector.StaffLines staff(
            MeasureRegion region, float x, float gap, byte[] ink, int width, int height) {
        float bottom = region.bottom() * height - 2.0f * gap;
        for (float offset : new float[] {-3.8f, -2.25f, 2.75f, 3.75f}) {
            var witness = PrintedDirectionStaff.printedRules(ink, width, height, x + gap * offset, bottom, gap);
            if (witness == null) continue;
            int[] rows = new int[5];
            for (int row = 0; row < 5; row++) rows[row] = Math.round(witness[0] - (4 - row) * witness[1]);
            return new RawStaffLineDetector.StaffLines(rows, witness[1]);
        }
        return null;
    }

    private static boolean ownsBothPlanes(
            Dyad chord, Dyad neighbor, float from, float to,
            RawStaffLineDetector.StaffLines normalizedStaff, RawStaffLineDetector.StaffLines printedStaff,
            byte[] gray, byte[] raw, int width, int height) {
        float advance = Math.abs(chord.x() - neighbor.x());
        if (advance < 1.8f * normalizedStaff.gap() || advance > 4.55f * normalizedStaff.gap()) return false;
        return attachedToRail(chord, neighbor, from, to, normalizedStaff, gray, width, height)
                && attachedToRail(chord, neighbor, from, to, printedStaff, raw, width, height);
    }

    static boolean attachedToRail(
            Dyad chord, Dyad neighbor, float from, float to, RawStaffLineDetector.StaffLines staff,
            byte[] ink, int width, int height) {
        float gap = staff.gap();
        var owner = shaft(chord, ink, width, height, gap);
        if (owner == null || owner.left() < from - .12f * gap || owner.right() > to + .12f * gap
                || owner.startY() > staff.top() + .25f * gap
                || owner.endY() < staff.bottom() + .20f * gap
                || owner.endY() > staff.bottom() + 1.45f * gap) return false;
        boolean occupied = false;
        for (int x = (int) Math.ceil(from); x <= (int) Math.floor(to); x++) {
            if (!BeamedFlatBarline.seamRule(ink, width, height, x, staff, gap)) continue;
            if (x < owner.left() || x > owner.right()) return false;
            occupied = true;
        }
        if (!occupied) return false;
        var next = shaft(neighbor, ink, width, height, gap);
        if (next == null) return false;
        return chord.x() < neighbor.x()
                ? WrappedEighthTriplets.matchesBeamRails(ink, width, height, owner, next, gap, 2)
                : WrappedEighthTriplets.matchesBeamRails(ink, width, height, next, owner, gap, 2);
    }

    private static PrintedBeamShafts.Shaft shaft(Dyad chord, byte[] ink, int width, int height, float gap) {
        var budget = new PrintedBeamShafts.Budget(65_000);
        var upper = PrintedBeamShafts.at(ink, width, height, chord.x(), chord.upper(), gap, budget);
        var lower = PrintedBeamShafts.at(ink, width, height, chord.x(), chord.lower(), gap, budget);
        if (budget.refused() || upper.size() != 1 || lower.size() != 1) return null;
        var a = upper.get(0);
        var b = lower.get(0);
        return a.direction() == 1 && b.direction() == 1 && a.left() <= b.right() && b.left() <= a.right()
                && Math.abs(a.endY() - b.endY()) <= .23f * gap ? a : null;
    }
}
