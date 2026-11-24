// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.IntStream;

/** Two closed sixteenth groups can precede a shared hollow chord with an underread lower head. */
final class ClosedDyadsBeforeHalf {
    private ClosedDyadsBeforeHalf() {}

    private record Head(int index, ScoreNoteEvent note, float x, float y) {}

    private record DottedPair(Head first, Head second, Head nextFirst, Head nextSecond) {}

    private record Change(Head head, float value) {}

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, ScoreMeterChange inheritedMeter, byte[] gray,
            byte[] original, int width, int height) {
        if (gray == null || original == null || gray.length != (long) width * height
                || original.length != gray.length) return score;
        var measures = score.measures();
        var notes = score.notes();
        var byBar = new HashMap<Integer, List<Integer>>();
        for (int i = 0; i < notes.size(); i++)
            byBar.computeIfAbsent(notes.get(i).measureIndex(), ignored -> new ArrayList<>()).add(i);
        boolean[] rests = new boolean[measures.size()];
        for (var rest : score.rests()) rests[rest.measureIndex()] = true;
        var meter = inheritedMeter;
        int change = 0;
        ArrayList<ScoreNoteEvent> repaired = null;
        for (int bar = 0; bar < measures.size(); bar++) {
            while (change < score.meterChanges().size()
                    && score.meterChanges().get(change).measureIndex() <= bar)
                meter = score.meterChanges().get(change++);
            var owners = byBar.get(bar);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4 || rests[bar]
                    || owners == null || owners.size() != 18) continue;
            var region = measures.get(bar);
            if (region.cadenceQuarterBeats() != 0) continue;
            var changes = repair(notes, owners, region, gray, original, width, height);
            if (changes.isEmpty()) continue;
            if (repaired == null) repaired = new ArrayList<>(notes);
            for (var item : changes) repaired.set(item.head.index, withValue(item.head.note, item.value));
        }
        return repaired == null ? score : score.withNotes(
                repaired, IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    private static List<Change> repair(
            List<ScoreNoteEvent> notes, List<Integer> owners, MeasureRegion region,
            byte[] gray, byte[] original, int width, int height) {
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        float left = region.left() * width, right = region.right() * width;
        float span = right - left;
        if (gap < 10 || gap > 27 || span < gap * 19 || span > gap * 37) return List.of();
        var heads = new ArrayList<Head>(18);
        for (int index : owners) {
            var n = notes.get(index);
            if (!ClosedSixteenthPairBeforeHalf.writtenHead(n, true)
                    || n.stemDirection() != -1
                    || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                    || (n.articulations() & NoteOrnament.GRACE) != 0) return List.of();
            heads.add(new Head(index, n, left + n.positionInMeasure() * span, n.pageY() * height));
        }
        heads.sort(Comparator.comparingDouble(Head::x));
        var group = new ClosedFourDyadBeam.Head[8];
        int interval = Math.abs(heads.get(0).note.staffStep() - heads.get(1).note.staffStep());
        if (interval != 5 && interval != 7) return List.of();
        float previous = Float.NaN;
        DottedPair dots = null;
        for (int i = 0; i < 18; i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            float x = (a.x + b.x) * .5f;
            if (b.x - a.x > gap * .32f
                    || Math.abs(a.note.staffStep() - b.note.staffStep()) != interval
                    || (i > 0 && (x - previous < gap * 1.35f || x - previous > gap * 4.7f))) return List.of();
            previous = x;
            if (a.note.augmentationDots() != b.note.augmentationDots()) return List.of();
            if (a.note.augmentationDots() != 0) {
                if (dots != null || a.note.augmentationDots() != 2 || i >= 14 || i / 2 % 4 == 3)
                    return List.of();
                dots = new DottedPair(a, b, heads.get(i+2), heads.get(i+3));
                if (dots.nextFirst.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP
                        || dots.nextSecond.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP)
                    return List.of();
            }
            if (i == 16) break;
            if (a.note.beamCount() != 2 || b.note.beamCount() != 2
                    || a.note.unbeamedDurationBeats() != 0 || b.note.unbeamedDurationBeats() != 0)
                return List.of();
            group[i / 2] = new ClosedFourDyadBeam.Head(x, Math.min(a.y,b.y), Math.max(a.y,b.y));
        }
        var a = heads.get(16);
        var b = heads.get(17);
        var upper = a.y < b.y ? a : b;
        var lower = a.y < b.y ? b : a;
        if (upper.note.beamCount() != 0 || lower.note.beamCount() != 0
                || upper.note.unbeamedDurationBeats() != 2
                || lower.note.unbeamedDurationBeats() != (dots == null ? 1 : 2)
                || right - previous < gap * 2.4f || right - previous > gap * 5.4f) return List.of();
        float floorHint = region.bottom() * height - 2.1f * gap;
        if (!proves(gray, width, height, group, upper, lower, right, floorHint, gap, dots)
                || original != gray && !proves(original, width, height, group, upper, lower, right, floorHint, gap, dots))
            return List.of();
        return dots == null ? List.of(new Change(lower, 2))
                : List.of(new Change(dots.first, 0), new Change(dots.second, 0));
    }

    private static boolean proves(
            byte[] gray, int width, int height, ClosedFourDyadBeam.Head[] heads,
            Head upper, Head lower, float right, float floorHint, float approximateGap, DottedPair dots) {
        var rules = PrintedDirectionStaff.printedRules(
                gray, width, height, right - approximateGap * .8f, floorHint, approximateGap);
        if (rules == null) return false;
        float gap = rules[1], floor = rules[0];
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        if (!ClosedSixteenthPairBeforeHalf.ownedSeparator(gray, width, height, staff, right)
                || !sharedHalf(gray, width, height, gap, upper, lower)) return false;
        var first = java.util.Arrays.copyOfRange(heads, 0, 4);
        var second = java.util.Arrays.copyOfRange(heads, 4, 8);
        if (dots == null)
            return ClosedFourDyadBeam.provesAcrossStaffRules(gray, width, height, first, gap, floor, 1)
                    && ClosedFourDyadBeam.provesAcrossStaffRules(gray, width, height, second, gap, floor, 1);
        var previousUpper = dots.first.y < dots.second.y ? dots.first : dots.second;
        var previousLower = dots.first.y < dots.second.y ? dots.second : dots.first;
        var nextUpper = dots.nextFirst.y < dots.nextSecond.y ? dots.nextFirst : dots.nextSecond;
        var nextLower = dots.nextFirst.y < dots.nextSecond.y ? dots.nextSecond : dots.nextFirst;
        return sharpOwnsDotGap(gray, width, height, gap, previousUpper, nextUpper)
                && sharpOwnsDotGap(gray, width, height, gap, previousLower, nextLower)
                && ClosedFourDyadBeam.provesAcrossDistinctStaffRules(gray, width, height, first, gap, floor, 1)
                && ClosedFourDyadBeam.provesAcrossDistinctStaffRules(gray, width, height, second, gap, floor, 1);
    }

    private static boolean sharedHalf(
            byte[] gray, int width, int height, float gap, Head upper, Head lower) {
        if (!hasOpening(gray, width, height, gap, upper)
                || !hasOpening(gray, width, height, gap, lower)) return false;
        var budget = new PrintedBeamShafts.Budget(60_000);
        var a = PrintedBeamShafts.at(gray, width, height, upper.x, upper.y, gap, budget);
        var b = PrintedBeamShafts.at(gray, width, height, lower.x, lower.y, gap, budget);
        if (budget.refused() || a.size() != 1 || b.size() != 1) return false;
        var first = a.get(0);
        var second = b.get(0);
        return first.direction() == 1 && second.direction() == 1
                && Math.max(first.left(), second.left()) <= Math.min(first.right(), second.right())
                && Math.abs(first.endY() - second.endY()) <= gap * .18f;
    }

    private static boolean hasOpening(byte[] gray, int width, int height, float gap, Head head) {
        float x = head.x, y = head.y;
        int left = Math.round(x - gap), right = Math.round(x + gap * .63f);
        int top = Math.round(y - gap * .73f), bottom = Math.round(y + gap * .73f);
        return left >= 0 && right < width && top >= 0 && bottom < height
                && WholeHeadPocketEvidence.hasEnclosedOpening(gray, width, height, left, top,
                        right, bottom, x - .12f * gap, y, gap, Math.max(12, Math.round(gap * gap * .044f)), 2);
    }

    private static boolean sharpOwnsDotGap(
            byte[] gray, int width, int height, float gap, Head previous, Head next) {
        return sharpOwnsDotGap(gray, width, height, gap, previous.x, previous.y, next.x, next.y);
    }

    static boolean sharpOwnsDotGap(
            byte[] gray, int width, int height, float gap,
            float previousX, float previousY, float nextX, float nextY) {
        int margin = Math.round(gap * .43f);
        int from = Math.round(previousX + gap * 1.14f);
        int to = Math.round(nextX - gap * 1.14f);
        int ymin = Math.round(nextY - gap * .43f), ymax = Math.round(nextY + gap * .21f);
        int dotLeft = Math.round(previousX + gap * .82f), dotTop = Math.round(previousY - gap * .69f);
        int dotBottom = Math.round(previousY + gap * .30f);
        if (from > to || dotLeft < 2 || to + margin + 2 >= width || dotTop < 2
                || ymin <= margin + 2 || ymax + margin + 2 >= height || dotBottom + 2 >= height)
            return false;
        for (int x = from; x <= to; x++) {
            for (int y = ymin; y <= ymax; y++) {
                if (DoubleSharpGlyph.matchesRaw(gray, width, height,
                        x - margin, y - margin, x + margin, y + margin, gap)
                        && noSeparateDot(gray, width, dotLeft, x - margin, dotTop, dotBottom))
                    return true;
            }
        }
        return false;
    }

    static boolean noSeparateDot(
            byte[] gray, int width, int left, int right, int top, int bottom) {
        for (int x = left; x + 2 < right; x++) {
            for (int y = top; y < bottom; y++) {
                int p = y * width + x;
                if ((gray[p] & 255) < 150 && (gray[p + 1] & 255) < 150
                        && (gray[p + width] & 255) < 150 && (gray[p + width + 1] & 255) < 150
                        && (gray[p - 2] & 255) >= 185 && (gray[p + 3] & 255) >= 185)
                    return false;
            }
        }
        return true;
    }

    private static ScoreNoteEvent withValue(ScoreNoteEvent n, float value) {
        return new ScoreNoteEvent(n.measureIndex(), n.positionInMeasure(), n.staffStep(),
                n.staffIndex(), n.staffCount(), n.pageY(), n.tiedFromPrevious(), 0,
                n.beamCount(), n.writtenAccidental(), value, n.tupletDivisor(), n.followingRestBeats(),
                n.articulations(), n.clefBottomDiatonic(), n.crossStaffBeam(), n.leadingRestBeats(),
                n.compactOpening(), n.octaveShift(), n.boundaryTies(), n.tupletNormalNotes(),
                n.stemDirection(), n.kind(), n.visualSize(), n.harmonicSemitones(), n.openStringTouch());
    }
}
