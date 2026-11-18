// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Preserves the actual duration of a closed four-beat run with a separate closing quarter. */
final class PrintedFiveBeatMeasure {
    private PrintedFiveBeatMeasure() {}

    static void apply(ScorePageInterpretation score, byte[] normalized, byte[] original,
            int width, int height, float[] beats) {
        if (normalized == null || original == null
                || normalized.length != (long) width * height
                || original.length != normalized.length
                || beats.length != score.measures().size()) return;
        var measures = score.measures();
        var buckets = new ArrayList<List<ScoreNoteEvent>>(beats.length);
        for (int i = 0; i < beats.length; i++) buckets.add(new ArrayList<>());
        for (var note : score.notes()) buckets.get(note.measureIndex()).add(note);
        for (int bar = 1; bar + 1 < beats.length; bar++) {
            if (beats[bar] != 4 || buckets.get(bar).size() != 17) continue;
            int index = bar;
            if (score.rests().stream().anyMatch(r -> r.measureIndex() == index)) continue;
            var notes = buckets.get(bar).stream()
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                    .toList();
            if (proves(measures.get(bar - 1), measures.get(bar), measures.get(bar + 1),
                    notes, normalized, original, width, height)) beats[bar] = 5;
        }
    }

    static boolean proves(MeasureRegion before, MeasureRegion bar, MeasureRegion after,
            List<ScoreNoteEvent> notes, byte[] normalized, byte[] original, int width, int height) {
        if (notes.size() != 17 || bar.cadenceQuarterBeats() != 0) return false;
        float left = bar.left() * width;
        float right = bar.right() * width;
        float span = right - left;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float opening = (before.right() * width + left) * .5f;
        if (gap < 10 || gap > 26 || span < 40 * gap || span > 65 * gap
                || Math.abs(before.top() - bar.top()) * height > .35f * gap
                || left - opening < .25f * gap || left - opening > .75f * gap
                || (after.top() - bar.bottom()) * height < 3 * gap) return false;
        float xEnd = left + span * notes.get(16).positionInMeasure();
        if (right - xEnd < 2 * gap || right - xEnd > 4.2f * gap) return false;
        float prior = 0;
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            boolean terminal = i == 16;
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.staffIndex() != 0 || n.staffCount() != 1
                    || n.augmentationDots() != 0 || n.tupletDivisor() != 1
                    || n.stemDirection() == 0 || n.tiedFromPrevious() || n.boundaryTies() != 0
                    || n.crossStaffBeam() || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0
                    || n.beamCount() != (terminal ? 0 : 2)
                    || n.unbeamedDurationBeats() != (terminal ? 1 : 0)) return false;
            float x = left + span * n.positionInMeasure();
            if (i == 0 ? x - left < gap || x - left > 4 * gap
                       : x - prior < 1.6f * gap || x - prior > 4.5f * gap) return false;
            prior = x;
        }
        float floor = bar.bottom() * height - gap * 2.1f;
        return provesInk(normalized, width, height, notes, left, right, opening, gap, floor)
                && provesInk(original, width, height, notes, left, right, opening, gap, floor);
    }

    private static boolean provesInk(byte[] gray, int width, int height, List<ScoreNoteEvent> notes,
            float left, float right, float opening, float gap, float floor) {
        var begin = staffAt(gray, width, height, opening, gap, floor);
        var end = staffAt(gray, width, height, right - gap * .9f, gap, floor);
        if (begin == null || end == null) return false;
        int firstRule = PrintedCadenceDurations.boundaryColumn(
                gray, width, begin, opening, true, false);
        int doubleRule = PrintedCadenceDurations.boundaryColumn(
                gray, width, end, right, false, false);
        if (firstRule < 0 || doubleRule < 0
                || !independent(gray, width, height, firstRule, begin)
                || !independent(gray, width, height, doubleRule, end)) return false;

        var budget = new PrintedBeamShafts.Budget(1_200_000);
        PrintedBeamShafts.Shaft prior = null;
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            float x = left + (right - left) * note.positionInMeasure();
            float y = note.pageY() * height;
            var shafts = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
            if (shafts.size() != 1 || budget.refused()) return false;
            var own = shafts.get(0);
            if (own.direction() != -note.stemDirection()) return false;
            if (prior != null) {
                if (i % 4 == 0) {
                    if (!WrappedEighthTriplets.matchesRailBridge(
                            gray, width, height, prior, own, gap, false)) return false;
                } else if (!twoRails(gray, width, height, prior, own, gap)) return false;
            }
            if (i == 16 && !unflaggedQuarter(gray, width, height, own, end, x, y, gap)) return false;
            prior = own;
        }
        return true;
    }

    private static PlayingTechniqueDetector.Staff staffAt(
            byte[] gray, int width, int height, float x, float gap, float floor) {
        var rails = DottedDyadMeasureSeparator.localRails(
                gray, width, height, Math.round(x), floor, gap);
        if (rails == null || Math.abs(rails[1] - gap) > gap * .16f
                || Math.abs(rails[0] - floor) > gap * .45f
                || rails[0] < 4 * rails[1] || rails[0] >= height) return null;
        return new PlayingTechniqueDetector.Staff(rails[0] - 4 * rails[1], rails[0], rails[1], 0, 1);
    }

    private static boolean independent(byte[] gray, int width, int height, int x,
            PlayingTechniqueDetector.Staff staff) {
        int top = Math.round(staff.top());
        int bottom = Math.round(staff.bottom());
        return !ClosedHeadBarlineGuard.attached(gray, width, height, x, top, bottom, staff.gap())
                && !DottedDyadMeasureSeparator.continuesBeyondStaff(
                        gray, width, height, x, top, bottom, staff.gap());
    }

    static boolean twoRails(byte[] gray, int width, int height, PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b, float gap) {
        if (a.direction() != b.direction()) return false;
        int left = shaftCenter(a);
        int right = shaftCenter(b);
        int distance = right - left;
        if (distance < 1.6f * gap || distance > 4.6f * gap
                || Math.abs(a.endY() - b.endY()) > distance * .46f) return false;
        int margin = Math.round(gap * .32f);
        int samples = distance - 2 * margin + 1;
        for (int outer = Math.round(gap * .20f); outer <= Math.round(gap * .45f); outer++) {
            for (int inner = Math.round(gap * .99f); inner <= Math.round(gap * 1.29f); inner++) {
                int both = 0;
                int clear = 0;
                int separated = 0;
                for (int x = left + margin; x <= right - margin; x++) {
                    int tip = Math.round(a.endY() + (b.endY() - a.endY()) * (x - left) / (float) distance);
                    int yOuter = tip - a.direction() * outer;
                    int yInner = tip - a.direction() * inner;
                    if (core(gray, width, height, x, yOuter) && core(gray, width, height, x, yInner))
                        both++;
                    int middle = (yInner + yOuter) / 2;
                    if (!core(gray, width, height, x, middle)) separated++;
                    if (middle >= 0 && middle < height && (gray[middle * width + x] & 255) >= 190)
                        clear++;
                }
                // A thin staff rule can cross the paper between the thick beam cores.
                if (both >= samples * .92f && separated >= samples * .60f
                        && clear >= samples * .24f) return true;
            }
        }
        return false;
    }

    private static int shaftCenter(PrintedBeamShafts.Shaft shaft) {
        return (shaft.left() + shaft.right()) / 2;
    }

    private static boolean core(byte[] gray, int width, int height, int x, int y) {
        return y > 0 && y < height - 1
                && (gray[(y - 1) * width + x] & 255) < 165
                && (gray[y * width + x] & 255) < 165
                && (gray[(y + 1) * width + x] & 255) < 165;
    }

    private static boolean unflaggedQuarter(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft shaft, PlayingTechniqueDetector.Staff staff,
            float x, float y, float gap) {
        if (!FilledSparseBeamHead.completeInterior(gray, width, height, x, y, gap)) return false;
        int direction = -shaft.direction();
        float tipStart = direction > 0 ? y - 1.5f * gap : y;
        return ArtificialHarmonics.unflaggedQuarterTip(gray, width, height, shaftCenter(shaft),
                tipStart, y, shaft.endY(), direction, staff);
    }
}
