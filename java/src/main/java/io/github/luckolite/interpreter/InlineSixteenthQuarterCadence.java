// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Restore a short quarter dyad between twelve beamed sixteenth dyads and a section divider. */
final class InlineSixteenthQuarterCadence {
    private InlineSixteenthQuarterCadence() {}

    static List<MeasureRegion> recover(
            List<MeasureRegion> measures,
            byte[] labels,
            byte[] gray,
            byte[] paper,
            int width,
            int height) {
        if (width <= 0
                || height <= 0
                || labels == null
                || gray == null
                || paper == null
                || gray.length != (long) width * height
                || labels.length != gray.length
                || paper.length != gray.length) return measures;
        List<MeasureRegion> repaired = null;
        for (int i = 0; i < measures.size(); i++) {
            var region = measures.get(i);
            var split =
                    i + 1 < measures.size()
                            ? split(region, measures.get(i + 1), labels, gray, paper, width, height)
                            : List.<MeasureRegion>of();
            if (!split.isEmpty()) {
                if (repaired == null) repaired = new ArrayList<>(measures.subList(0, i));
                repaired.addAll(split);
            } else if (repaired != null) {
                repaired.add(region);
            }
        }
        return repaired == null ? measures : List.copyOf(repaired);
    }

    /** Number reconciliation may flatten an already isolated printed quarter into the next bar. */
    static List<MeasureRegion> restoreIsolated(
            List<MeasureRegion> raw, List<MeasureRegion> fitted,
            byte[] labels, byte[] gray, byte[] original, int width, int height) {
        if (gray == null || original == null || labels == null || gray.length != (long) width * height
                || original.length != gray.length || labels.length != gray.length) return fitted;
        List<MeasureRegion> rebuilt = null;
        for (int i = 1; i + 1 < raw.size(); i++) {
            var before = raw.get(i - 1);
            var pocket = raw.get(i);
            var after = raw.get(i + 1);
            float gap = (pocket.bottom() - pocket.top()) * height / 8.2f;
            float l = pocket.left() * width, r = pocket.right() * width;
            if (gap < 11 || gap > 24 || (r - l) < gap * 2.75f || (r - l) > gap * 4.25f
                    || (before.right() - before.left()) * width < gap * 23
                    || (after.right() - after.left()) * width < gap * 23
                    || (pocket.left() - before.right()) * width < gap * .7f
                    || (pocket.left() - before.right()) * width > gap * 1.42f
                    || (after.left() - pocket.right()) * width < gap * .7f
                    || (after.left() - pocket.right()) * width > gap * 1.42f
                    || Math.abs(pocket.top() - before.top()) * height > gap * .25f
                    || Math.abs(pocket.top() - after.top()) * height > gap * .25f
                    || pocket.cadenceQuarterBeats() != 0) continue;
            var list = rebuilt == null ? fitted : rebuilt;
            int owner = list.indexOf(new MeasureRegion(
                    pocket.left(), after.right(), Math.min(pocket.top(), after.top()),
                    Math.max(pocket.bottom(), after.bottom())));
            if (owner < 0 || !list.contains(before)) continue;
            int doubleRule = isolatedDivider(gray, width, height, before, pocket, after, gap);
            if (doubleRule < 0 || doubleRule != isolatedDivider(original, width, height, before, pocket, after, gap))
                continue;
            var notes = OmrScoreInterpreter.analyze(labels, gray, width, height, List.of(pocket), original);
            if (notes.notes().size() != 2 || !notes.rests().isEmpty()) continue;
            var a = notes.notes().get(0);
            var b = notes.notes().get(notes.notes().size() - 1);
            if (!plain(a, true, 1) || !plain(b, true, 1)
                    || Math.abs(a.staffStep() - b.staffStep()) != 5) continue;
            float ax = l + a.positionInMeasure() * (r - l);
            float bx = l + b.positionInMeasure() * (r - l);
            if (Math.abs(ax - bx) > gap * .20f || ax - l < gap * .8f || doubleRule - ax < gap * 1.65f
                    || !sharedStem(gray, width, height, ax, a.pageY() * height, bx, b.pageY() * height, gap, 1)
                    || !sharedStem(original, width, height, ax, a.pageY() * height, bx, b.pageY() * height, gap, 1))
                continue;
            if (rebuilt == null) rebuilt = new ArrayList<>(fitted);
            rebuilt.set(owner, after);
            rebuilt.add(owner, new MeasureRegion(pocket.left(), pocket.right(), pocket.top(), pocket.bottom(), 1));
        }
        return rebuilt == null ? fitted : List.copyOf(rebuilt);
    }

    private static int isolatedDivider(
            byte[] gray, int width, int height, MeasureRegion before, MeasureRegion pocket,
            MeasureRegion after, float gap) {
        float left = pocket.left() * width, right = pocket.right() * width;
        float hint = pocket.bottom() * height - 2.1f * gap;
        var lines = DottedDyadMeasureSeparator.localRails(gray, width, height,
                Math.round(right - .55f * gap), hint, gap);
        if (lines == null) return -1;
        int top = Math.round(lines[0] - 4 * lines[1]), bottom = Math.round(lines[0]);
        int columns = 0;
        for (int x = Math.round(before.right() * width); x <= Math.round(left); x++)
            if (isRule(gray, width, height, x, top, bottom, lines[1])) columns++;
        if (columns < 2 || columns > gap * .78f) return -1;
        var staff = new PlayingTechniqueDetector.Staff(top, bottom, lines[1], 0, 1);
        return PrintedCadenceDurations.boundaryColumn(
                gray, width, staff, (right + after.left() * width) * .5f, false, false);
    }

    private static List<MeasureRegion> split(
            MeasureRegion region,
            MeasureRegion next,
            byte[] labels,
            byte[] gray,
            byte[] paper,
            int width,
            int height) {
        float left = region.left() * width, right = region.right() * width;
        float top = region.top() * height, bottom = region.bottom() * height;
        float gap = (bottom - top) / 8.2f;
        float nextLeft = next.left() * width;
        if (gap < 9
                || gap > 25
                || region.cadenceQuarterBeats() != 0
                || right - left < gap * 30
                || right - left > gap * 43
                || nextLeft - right < .7f * gap
                || nextLeft - right > 1.45f * gap
                || Math.abs(next.top() * height - top) > gap * .45f
                || Math.abs(next.bottom() * height - bottom) > gap * .45f) return List.of();
        float hint = bottom - gap * 2.1f;
        int cut = separator(gray, width, height, right, nextLeft, hint, gap);
        if (cut < 0 || separator(paper, width, height, right, nextLeft, hint, gap) != cut)
            return List.of();

        var local = OmrScoreInterpreter.analyze(labels, gray, width, height, List.of(region));
        if (!local.rests().isEmpty() || local.notes().size() != 26) return List.of();
        var notes =
                local.notes().stream()
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        float previous = Float.NaN;
        int direction = notes.get(24).stemDirection();
        if (direction == 0) return List.of();
        for (int i = 0; i < notes.size(); i += 2) {
            var a = notes.get(i);
            var b = notes.get(i + 1);
            boolean cadence = i == 24;
            float x = left + a.positionInMeasure() * (right - left);
            float bx = left + b.positionInMeasure() * (right - left);
            if (!plain(a, cadence, direction)
                    || !plain(b, cadence, direction)
                    || Math.abs(a.staffStep() - b.staffStep()) < 2
                    || bx - x > gap * .23f
                    || (!Float.isNaN(previous)
                            && (x - previous < gap * 1.60f || x - previous > gap * 3.8f))
                    || (cadence
                            ? x - cut < gap * 1.1f || x - cut > gap * 2.0f
                            : cut - bx < gap * 1.1f)) return List.of();
            if (!sharedStem(
                            gray,
                            width,
                            height,
                            x,
                            a.pageY() * height,
                            bx,
                            b.pageY() * height,
                            gap,
                            direction)
                    || !sharedStem(
                            paper,
                            width,
                            height,
                            x,
                            a.pageY() * height,
                            bx,
                            b.pageY() * height,
                            gap,
                            direction)) return List.of();
            previous = x;
        }
        int margin = Math.round(gap * .50f);
        return List.of(
                new MeasureRegion(
                        region.left(),
                        (cut - margin) / (float) width,
                        region.top(),
                        region.bottom()),
                new MeasureRegion(
                        (cut + margin) / (float) width,
                        region.right(),
                        region.top(),
                        region.bottom(),
                        1));
    }

    private static boolean plain(ScoreNoteEvent head, boolean cadence, int direction) {
        return head.kind() == ScoreNoteEvent.Kind.PITCHED
                && head.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && head.staffCount() == 1
                && head.staffIndex() == 0
                && head.stemDirection() == direction
                && head.beamCount() == (cadence ? 0 : 2)
                && head.unbeamedDurationBeats() == (cadence ? ScoreNoteEvent.DURATION_QUARTER : 0)
                && head.augmentationDots() == 0
                && head.tupletDivisor() == 1
                && !head.crossStaffBeam()
                && (!cadence || !head.tiedFromPrevious())
                && head.boundaryTies() == 0
                && head.leadingRestBeats() == 0
                && head.followingRestBeats() == 0
                && (head.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean sharedStem(
            byte[] gray,
            int width,
            int height,
            float x,
            float y,
            float bx,
            float by,
            float gap,
            int direction) {
        var budget = new PrintedBeamShafts.Budget(20_000);
        var a = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
        var b = PrintedBeamShafts.at(gray, width, height, bx, by, gap, budget);
        if (a.size() != 1 || b.size() != 1) return false;
        var first = a.get(0);
        var second = b.get(0);
        return first.direction() == -direction
                && second.direction() == -direction
                && Math.abs(first.endY() - second.endY()) <= gap * .15f
                && Math.max(first.left(), second.left()) <= Math.min(first.right(), second.right());
    }

    private static int separator(
            byte[] gray,
            int width,
            int height,
            float right,
            float nextLeft,
            float hint,
            float gap) {
        int from = Math.round(right - 4.15f * gap), to = Math.round(right - 2.6f * gap);
        var staff =
                DottedDyadMeasureSeparator.localRails(
                        gray, width, height, (from + to) / 2, hint, gap);
        if (staff == null) return -1;
        int top = Math.round(staff[0] - 4 * staff[1]), bottom = Math.round(staff[0]);
        int first = -1, last = -1;
        for (int x = from; x <= to; x++) {
            if (!isRule(gray, width, height, x, top, bottom, staff[1])) continue;
            if (last >= 0 && last + 1 != x) return -1;
            if (first < 0) first = x;
            last = x;
        }
        if (first < 0 || last - first + 1 < 2 || last - first + 1 > gap * .35f) return -1;
        int groups = 0, previous = -2;
        for (int x = Math.round(right + .17f * gap); x <= Math.round(nextLeft + .12f * gap); x++) {
            if (!isRule(gray, width, height, x, top, bottom, staff[1])) continue;
            if (x > previous + 1) groups++;
            previous = x;
        }
        return groups == 2 ? (first + last) / 2 : -1;
    }

    private static boolean isRule(
            byte[] gray, int width, int height, int x, int top, int bottom, float gap) {
        return DottedDyadMeasureSeparator.fullInk(gray, width, x, top, gap)
                && OmrMeasurePostProcessor.verticalRuleAt(gray, width, x, top, bottom, gap)
                && !DottedDyadMeasureSeparator.continuesBeyondStaff(
                        gray, width, height, x, top, bottom, gap)
                && !ClosedHeadBarlineGuard.attached(gray, width, height, x, top, bottom, gap);
    }
}
