// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0

package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A printed single rule can divide two complete sixteenth bars before an isolated half dyad. */
final class ClosedSixteenthPairBeforeHalf {
    private ClosedSixteenthPairBeforeHalf() {}

    static List<MeasureRegion> recover(
            List<MeasureRegion> regions, byte[] labels, byte[] gray, int width, int height) {
        if (gray == null
                || labels == null
                || gray.length != (long) width * height
                || labels.length != gray.length) return regions;
        var result = new ArrayList<MeasureRegion>();
        for (int i = 0; i < regions.size(); i++) {
            var span = regions.get(i);
            var replacement =
                    i + 2 < regions.size()
                            ? split(
                                    span,
                                    regions.get(i + 1),
                                    regions.get(i + 2),
                                    labels,
                                    gray,
                                    width,
                                    height)
                            : List.<MeasureRegion>of();
            if (replacement.isEmpty()) result.add(span);
            else result.addAll(replacement);
        }
        return result.size() == regions.size() ? regions : List.copyOf(result);
    }

    static List<MeasureRegion> split(
            MeasureRegion span,
            MeasureRegion half,
            MeasureRegion following,
            byte[] labels,
            byte[] gray,
            int width,
            int height) {
        float gap = (span.bottom() - span.top()) * height / 8.2f;
        float widthInGaps = (span.right() - span.left()) * width / gap;
        float halfWidth = (half.right() - half.left()) * width / gap;
        if (gap < 9
                || gap > 30
                || span.cadenceQuarterBeats() != 0
                || widthInGaps < 59
                || widthInGaps > 73
                || halfWidth < 4.5f
                || halfWidth > 7.5f
                || Math.abs(span.top() - half.top()) * height > gap * .4f
                || Math.abs(span.bottom() - half.bottom()) * height > gap * .4f
                || (following.top() - half.bottom()) * height < gap * 2
                || (half.left() - span.right()) * width < gap * .35f
                || (half.left() - span.right()) * width > gap * 1.8f) return List.of();

        float hint = half.bottom() * height - 2.1f * gap;
        var rules =
                PrintedDirectionStaff.printedRules(
                        gray, width, height, half.right() * width - gap * 2.5f, hint, gap);
        if (rules == null) return List.of();
        var staff =
                new PlayingTechniqueDetector.Staff(
                        rules[0] - rules[1] * 4, rules[0], rules[1], 0, 1);
        if (!ownedSeparator(gray, width, height, staff, (half.left() + span.right()) * width * .5f)
                || PrintedCadenceDurations.boundaryColumn(
                                gray, width, staff, half.right() * width, false, false)
                        < 0) return List.of();

        var split = StraightDyadCadenceBoundaries.propose(span, gray, width, height);
        if (split.size() != 2) return List.of();
        var trial =
                OmrScoreInterpreter.analyze(
                        labels, gray, width, height, List.of(split.get(0), split.get(1), half));
        if (!trial.rests().isEmpty() || !terminalHalf(local(trial.notes(), 2), half, width, gap))
            return List.of();
        return closedSixteenths(
                                local(trial.notes(), 0),
                                split.get(0),
                                gray,
                                width,
                                height,
                                gap,
                                rules[0])
                        && closedSixteenths(
                                local(trial.notes(), 1),
                                split.get(1),
                                gray,
                                width,
                                height,
                                gap,
                                rules[0])
                ? split
                : List.of();
    }

    static boolean ownedSeparator(
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Staff staff,
            float expected) {
        float gap = staff.gap();
        int top = Math.round(staff.top()), bottom = Math.round(staff.bottom());
        int left = Math.round(expected - gap * .62f), right = Math.round(expected + gap * .62f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        int found = -1;
        for (int x = left; x <= right; x++) {
            if (!DottedDyadMeasureSeparator.fullInk(gray, width, x, top, gap)) continue;
            int start = x;
            while (x < right && DottedDyadMeasureSeparator.fullInk(gray, width, x + 1, top, gap))
                x++;
            int center = (start + x) / 2;
            if (x - start + 1 < Math.max(2, Math.round(gap * .13f))
                    || x - start + 1 > gap * .45f
                    || ClosedHeadBarlineGuard.attached(
                            gray, width, height, center, top, bottom, gap)
                    || DottedDyadMeasureSeparator.continuesBeyondStaff(
                            gray, width, height, center, top, bottom, gap)) continue;
            if (found >= 0) return false;
            found = center;
        }
        return found >= 0;
    }

    private static List<ScoreNoteEvent> local(List<ScoreNoteEvent> notes, int bar) {
        return notes.stream()
                .filter(n -> n.measureIndex() == bar)
                .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                .toList();
    }

    private static boolean terminalHalf(
            List<ScoreNoteEvent> notes, MeasureRegion region, int w, float g) {
        if (notes.size() != 2) return false;
        var first = notes.get(0);
        var last = notes.get(1);
        return first.staffStep() != last.staffStep()
                && first.stemDirection() == last.stemDirection()
                && (last.positionInMeasure() - first.positionInMeasure())
                                * (region.right() - region.left())
                                * w
                        <= g * .25f
                && notes.stream()
                        .allMatch(
                                n ->
                                        writtenHead(n)
                                                && n.unbeamedDurationBeats() == 2
                                                && n.beamCount() == 0
                                                && n.augmentationDots() <= 1);
    }

    static boolean closedSixteenths(
            List<ScoreNoteEvent> notes,
            MeasureRegion region,
            byte[] gray,
            int width,
            int height,
            float gap,
            float floor) {
        if (notes.size() < 30 || notes.size() > 32) return false;
        var columns = new ArrayList<List<ScoreNoteEvent>>();
        float span = (region.right() - region.left()) * width;
        for (var n : notes) {
            if (!writtenHead(n)
                    || n.unbeamedDurationBeats() != 0
                    || n.beamCount() != 2
                    || n.augmentationDots() != 0) return false;
            if (columns.isEmpty()
                    || (n.positionInMeasure()
                                            - columns.get(columns.size() - 1)
                                                    .get(0)
                                                    .positionInMeasure())
                                    * span
                            > gap * .37f) columns.add(new ArrayList<>(2));
            var owners = columns.get(columns.size() - 1);
            if (owners.size() > 1) return false;
            if (!owners.isEmpty()
                    && (n.stemDirection() != owners.get(0).stemDirection()
                            || n.staffStep() == owners.get(0).staffStep())) return false;
            owners.add(n);
        }
        if (columns.size() != 16) return false;
        int proved = 0;
        for (int i = 0; i < columns.size(); i += 4) {
            var heads = new ClosedFourDyadBeam.Head[4];
            int direction = columns.get(i).get(0).stemDirection();
            boolean complete = true;
            for (int j = 0; j < heads.length; j++) {
                var pair = columns.get(i + j);
                var a = pair.get(0);
                if (pair.size() < 2 || a.stemDirection() != direction) {
                    complete = false;
                    break;
                }
                var b = pair.get(1);
                float x =
                        region.left() * width
                                + (a.positionInMeasure() + b.positionInMeasure()) * .5f * span;
                heads[j] =
                        new ClosedFourDyadBeam.Head(
                                x,
                                height * Math.min(a.pageY(), b.pageY()),
                                height * Math.max(a.pageY(), b.pageY()));
            }
            if (complete
                    && ClosedFourDyadBeam.provesOctaves(
                            gray, width, height, heads, gap, floor, -direction, false)) proved++;
        }
        return proved >= 2;
    }

    private static boolean writtenHead(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffCount() == 1
                && n.staffIndex() == 0
                && !n.crossStaffBeam()
                && n.stemDirection() != 0
                && n.tupletDivisor() == 1
                && n.tupletNormalNotes() == 1
                && n.boundaryTies() == 0
                && !n.tiedFromPrevious()
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0;
    }

    static ScorePageInterpretation captureBareHalf(
            ScorePageInterpretation score, byte[] gray, byte[] annotation, int width, int height) {
        var regions = score.measures();
        var notes = score.notes();
        ArrayList<ScoreNoteEvent> result = null;
        for (int bar = 2; bar + 1 < regions.size(); bar++) {
            final int next = bar + 1;
            if (score.meterChanges().stream()
                    .noneMatch(
                            m ->
                                    m.measureIndex() == next
                                            && m.numerator() == 3
                                            && m.denominator() == 4)) continue;
            var bound = regions.get(bar);
            var left = regions.get(bar - 1);
            var runStart = regions.get(bar - 2);
            float gap = (bound.bottom() - bound.top()) * height / 8.2f;
            float span = (bound.right() - bound.left()) * width;
            if (gap < 10
                    || gap > 27
                    || span < gap * 4.5f
                    || span > gap * 7.5f
                    || Math.abs(runStart.top() - bound.top()) * height > gap * .4f
                    || Math.abs(left.top() - bound.top()) * height > gap * .4f
                    || (regions.get(next).top() - bound.bottom()) * height < gap * 2) continue;
            var held = local(notes, bar);
            if (!terminalHalf(held, bound, width, gap)) continue;
            var lower = held.get(0).pageY() > held.get(1).pageY() ? held.get(0) : held.get(1);
            var higher = lower == held.get(0) ? held.get(1) : held.get(0);
            if (lower.augmentationDots() != 1
                    || higher.augmentationDots() != 0
                    || higher.stemDirection() != 1) continue;
            float hiX = bound.left() * width + higher.positionInMeasure() * span;
            float loX = bound.left() * width + lower.positionInMeasure() * span;
            float hiY = higher.pageY() * height, loY = lower.pageY() * height;
            var rails =
                    PrintedDirectionStaff.printedRules(
                            gray,
                            width,
                            height,
                            bound.right() * width - gap * 2.5f,
                            bound.bottom() * height - gap * 2.1f,
                            gap);
            if (rails == null) continue;
            gap = rails[1];
            var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * gap, rails[0], gap, 0, 1);
            if (!ownedSeparator(
                            gray, width, height, staff, (bound.left() + left.right()) * width * .5f)
                    || PrintedCadenceDurations.boundaryColumn(
                                    gray, width, staff, bound.right() * width, false, false)
                            < 0
                    || !TerminalHalfSpeck.proves(gray, width, height, loX, loY, hiX, hiY, gap)
                    || !TerminalHalfSpeck.proves(annotation, width, height, loX, loY, hiX, hiY, gap)
                    || !closedSixteenths(
                            local(notes, bar - 2), runStart, gray, width, height, gap, rails[0])
                    || !closedSixteenths(
                            local(notes, bar - 1), left, gray, width, height, gap, rails[0]))
                continue;

            var budget = new PrintedBeamShafts.Budget(60_000);
            var a = PrintedBeamShafts.at(gray, width, height, hiX, hiY, gap, budget);
            var b = PrintedBeamShafts.at(gray, width, height, loX, loY, gap, budget);
            if (a.size() != 1
                    || b.size() != 1
                    || a.get(0).direction() != -1
                    || b.get(0).direction() != -1
                    || Math.max(a.get(0).left(), b.get(0).left())
                            > Math.min(a.get(0).right(), b.get(0).right())
                    || Math.abs(a.get(0).endY() - b.get(0).endY()) > gap * .18f) continue;

            if (result == null) result = new ArrayList<>(notes);
            result.set(notes.indexOf(lower), clearDot(lower));
        }
        return result == null
                ? score
                : score.withNotes(
                        result, java.util.stream.IntStream.range(0, notes.size()).boxed().toList());
    }

    private static ScoreNoteEvent clearDot(ScoreNoteEvent note) {
        return new ScoreNoteEvent(
                note.measureIndex(),
                note.positionInMeasure(),
                note.staffStep(),
                note.staffIndex(),
                note.staffCount(),
                note.pageY(),
                note.tiedFromPrevious(),
                0,
                note.beamCount(),
                note.writtenAccidental(),
                note.unbeamedDurationBeats(),
                note.tupletDivisor(),
                note.followingRestBeats(),
                note.articulations(),
                note.clefBottomDiatonic(),
                note.crossStaffBeam(),
                note.leadingRestBeats(),
                note.compactOpening(),
                note.octaveShift(),
                note.boundaryTies(),
                note.tupletNormalNotes(),
                note.stemDirection(),
                note.kind(),
                note.visualSize(),
                note.harmonicSemitones(),
                note.openStringTouch());
    }
}
