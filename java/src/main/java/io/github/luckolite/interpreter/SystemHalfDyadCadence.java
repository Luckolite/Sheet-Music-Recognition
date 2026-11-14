// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Restores an isolated printed half-note dyad after a complete bar of sixteenth-note dyads. */
final class SystemHalfDyadCadence {
    private SystemHalfDyadCadence() {}

    static List<MeasureRegion> recover(List<MeasureRegion> regions, byte[] labels, byte[] gray,
            int width, int height) {
        if (gray == null || labels == null || gray.length != (long) width * height
                || labels.length != gray.length) return regions;
        var result = new ArrayList<MeasureRegion>();
        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            int cut = i + 1 < regions.size()
                    ? separator(region, regions.get(i + 1), gray, width, height) : -1;
            var recovered = cut < 0 ? List.<MeasureRegion>of() : splitIfComplete(
                    region, cut, OmrScoreInterpreter.analyze(labels, gray, width, height,
                            List.of(region)), width, height);
            if (!recovered.isEmpty() && nextOnSameSystem(region, regions.get(i + 1), height)
                    && !provedFollowingMeter(recovered, regions.get(i + 1), labels, gray, width, height))
                recovered = List.of();
            if (recovered.isEmpty()) result.add(region);
            else result.addAll(recovered);
        }
        return result.size() == regions.size() ? regions : List.copyOf(result);
    }

    static int separator(MeasureRegion region, MeasureRegion next, byte[] gray, int width, int height) {
        if (region.cadenceQuarterBeats() != 0) return -1;
        float left = region.left() * width, right = region.right() * width;
        float bottom = region.bottom() * height;
        float gap = (bottom - region.top() * height) / 8.2f;
        if (gap < 6 || gap > 32 || right-left < gap * 31 || right-left > gap * 46
                || right + gap * 2 >= width) return -1;
        if (next.top() <= region.bottom()
                && (!nextOnSameSystem(region, next, height)
                        || (next.left() - region.right()) * width < gap * .45f
                        || (next.left() - region.right()) * width > gap * 4.4f)) return -1;
        float hint = bottom - gap * 2.1f;
        var rails = PrintedDirectionStaff.printedRules(
                gray, width, height, right - gap * 2.5f, hint, gap);
        if (rails == null) return -1;
        gap = rails[1];
        float top = rails[0] - 4 * gap;
        if (top < 0 || rails[0] >= height) return -1;
        var staff = new PlayingTechniqueDetector.Staff(top, rails[0], gap, 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(gray, width, staff,
                right + gap * .35f, false, false) < 0) return -1;
        int cut = -1;
        int start = Math.round(right - gap * 7.4f), end = Math.round(right - gap * 3.8f);
        for (int x = start; x <= end; x++) {
            if (!crossesSpaces(gray, width, staff, x)) continue;
            int first = x;
            while (x < end && crossesSpaces(gray, width, staff, x + 1)) x++;
            int stroke = x - first + 1;
            // A two-pixel note shaft can cross four spaces. Require the printed
            // rule's wider solid axis and then independently prove the note gap.
            if (stroke < Math.max(3, Math.round(gap * .17f)) || stroke > gap * .42f) continue;
            int center = (first + x) / 2;
            if (ClosedHeadBarlineGuard.attached(gray, width, height, center,
                    Math.round(staff.top()), Math.round(staff.bottom()), gap)
                    || DottedDyadMeasureSeparator.continuesBeyondStaff(gray, width, height, center,
                            Math.round(staff.top()), Math.round(staff.bottom()), gap)) continue;
            if (cut >= 0) return -1;
            cut = center;
        }
        return cut;
    }

    private static boolean nextOnSameSystem(MeasureRegion region, MeasureRegion next, int height) {
        float tolerance = (region.bottom() - region.top()) * height * .11f;
        return Math.abs(next.top() - region.top()) * height <= tolerance
                && Math.abs(next.bottom() - region.bottom()) * height <= tolerance;
    }

    private static boolean provedFollowingMeter(
            List<MeasureRegion> parts, MeasureRegion next,
            byte[] labels, byte[] gray, int width, int height) {
        var window = List.of(parts.get(0), parts.get(1), next);
        var score = OmrScoreInterpreter.analyze(labels, gray, width, height, window);
        return MeterFontMatcher.standaloneReadings(labels, gray, width, height, window, score.notes())
                .stream().anyMatch(m -> m.measureIndex() == 2 && m.numerator() == 3 && m.denominator() == 4);
    }

    private static boolean crossesSpaces(byte[] gray, int width, PlayingTechniqueDetector.Staff staff,
            int x) {
        for (int space = 0; space < 4; space++) {
            int top = Math.round(staff.top() + (space + .24f) * staff.gap());
            int bottom = Math.round(staff.top() + (space + .76f) * staff.gap());
            int dark = 0;
            for (int y = top; y <= bottom; y++)
                if ((gray[y * width + x] & 255) < 120) dark++;
            if (dark < (bottom - top + 1) * .78f) return false;
        }
        return true;
    }

    static List<MeasureRegion> splitIfComplete(MeasureRegion region, int cut,
            OmrScoreInterpreter.Analysis analysis, int width, int height) {
        var notes = analysis.notes();
        if (!analysis.rests().isEmpty() || notes.size() != 34) return List.of();
        float left = region.left() * width, right = region.right() * width;
        float span = right - left, gap = (region.bottom() - region.top()) * height / 8.2f;
        var sorted = notes.stream().sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
        float previousX = left;
        for (int group = 0; group < 17; group++) {
            var a = sorted.get(group * 2);
            var b = sorted.get(group * 2 + 1);
            if (!eligible(a) || !eligible(b) || a.staffStep() == b.staffStep()
                    || a.stemDirection() != b.stemDirection()) return List.of();
            float ax = left + span * a.positionInMeasure(), bx = left + span * b.positionInMeasure();
            if (bx - ax > gap * .22f) return List.of();
            if (group == 16) {
                if (a.unbeamedDurationBeats() != 2 || b.unbeamedDurationBeats() != 2
                        || a.beamCount() != 0 || b.beamCount() != 0 || cut - previousX < gap * 1.25f
                        || ax - cut < gap * 1.45f || ax - cut > gap * 2.95f
                        || right - bx < gap * 2.15f) return List.of();
            } else if (a.beamCount() != 2 || b.beamCount() != 2
                    || a.unbeamedDurationBeats() != 0 || b.unbeamedDurationBeats() != 0
                    || bx >= cut || group > 0 && (ax - previousX < gap * 1.2f
                            || ax - previousX > gap * 3.6f)) return List.of();
            previousX = bx;
        }
        int padding = Math.max(2, Math.round(gap * .5f));
        return List.of(new MeasureRegion(region.left(), (float) (cut - padding) / width,
                        region.top(), region.bottom()),
                new MeasureRegion((float) (cut + padding) / width, region.right(),
                        region.top(), region.bottom(), 2));
    }

    private static boolean eligible(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.staffCount() == 1
                && note.staffIndex() == 0 && note.stemDirection() != 0
                && note.augmentationDots() == 0 && note.tupletDivisor() == 1
                && !note.crossStaffBeam() && !note.tiedFromPrevious() && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }
}
