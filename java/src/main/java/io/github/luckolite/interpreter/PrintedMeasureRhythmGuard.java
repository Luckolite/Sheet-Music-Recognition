// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Rejects an inferred separator contradicted by two complete written voices. */
final class PrintedMeasureRhythmGuard {
    private PrintedMeasureRhythmGuard() {}

    static List<MeasureRegion> reconcile(
            List<MeasureRegion> raw,
            List<MeasureRegion> fitted,
            byte[] labels,
            byte[] gray,
            int width,
            int height) {
        raw = rejectOverlappingRows(raw);
        fitted = rejectOverlappingRows(fitted);
        fitted = preserveCountNeutralRows(raw, fitted, gray, width, height);
        fitted = preservePrintedBoundaries(raw, fitted, gray, width, height);
        fitted = preserveShiftedBoundary(raw, fitted, gray, width, height);
        fitted = rejectUnprintedRows(raw, fitted, labels, gray, width, height);
        fitted = preserveFinalRestRow(raw, fitted, labels, gray, width, height);
        if (gray != null && !fitted.isEmpty()) {
            int last = fitted.size() - 1;
            var cadence = terminalHalfNoteCadence(fitted.get(last), labels, gray, width, height);
            if (!cadence.isEmpty()) {
                var withCadence = new ArrayList<>(fitted.subList(0, last));
                withCadence.addAll(cadence);
                fitted = withCadence;
            }
        }
        final var guarded = fitted;
        boolean split = raw.stream().anyMatch(region -> fragments(region, guarded).size() == 2);
        if (!split && mergedOpening(raw, fitted).isEmpty()) return fitted;
        var analysis = OmrScoreInterpreter.analyze(labels, gray, width, height, raw);
        var notes = analysis.notes();
        fitted =
                preservePartialWrittenBars(
                        raw, fitted, notes, analysis.rests(), gray, width, height);
        var preserved = preserveWrittenOpening(raw, fitted, notes);
        if (!split) return preserved;
        var aligned = alignPrintedSeparators(raw, preserved, notes, gray, width, height);
        return preserveCompleteRuns(raw, aligned, notes, gray, width, height);
    }

    private static List<MeasureRegion> terminalHalfNoteCadence(
            MeasureRegion region, byte[] labels, byte[] gray, int width, int height) {
        int left = Math.round(region.left() * width), right = Math.round(region.right() * width);
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        if (gap < 6 || right - left < gap * 16 || right + 2 * gap >= width) return List.of();
        float bottom = region.bottom() * height - 2.1f * gap;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, right - 2 * gap, bottom, gap);
        if (rails == null)
            rails = PrintedDirectionStaff.printedRules(gray, width, height, right - 2.5f * gap, bottom, gap);
        if (rails == null) return List.of();
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * rails[1], rails[0], rails[1], 0, 1);
        int top = Math.round(staff.top()), base = Math.round(staff.bottom());
        if (top < 0 || base >= height) return List.of();
        gap = staff.gap();
        int closing = PrintedCadenceDurations.boundaryColumn(
                gray, width, staff, right + gap * .35f, false, true);
        if (closing < 0) return List.of();

        int firstRule = -1, lastRule = -1;
        for (int x = Math.round(right - gap * 8); x <= right - gap * 3; x++) {
            if (!OmrMeasurePostProcessor.verticalRuleAt(gray, width, x, top, base, gap)
                    || ClosedHeadBarlineGuard.attached(gray, width, height, x, top, base, gap)) continue;
            if (lastRule >= 0 && x - lastRule > 1) return List.of();
            if (firstRule < 0) firstRule = x;
            lastRule = x;
        }
        if (firstRule < 0 || lastRule - firstRule > gap) return List.of();
        int cut = (firstRule + lastRule) / 2;
        var analysis = OmrScoreInterpreter.analyze(labels, gray, width, height, List.of(region));
        float split = (cut - left) / (float) (right - left);
        // A rest in the preceding bar does not invalidate a proved cadence chord.
        if (analysis.rests().stream().anyMatch(rest -> rest.positionInMeasure() >= split))
            return List.of();
        var chord = analysis.notes().stream().filter(n -> n.positionInMeasure() > split).toList();
        if (chord.isEmpty() || chord.size() > 3 || analysis.notes().size() - chord.size() < 8
                || !chord.stream().allMatch(n -> n.kind() == ScoreNoteEvent.Kind.PITCHED
                        && n.staffCount() == 1 && n.beamCount() == 0 && n.tupletDivisor() == 1
                        && n.unbeamedDurationBeats() == 2
                        && n.augmentationDots() == chord.get(0).augmentationDots()
                        && n.augmentationDots() <= 1
                        && Math.abs(n.positionInMeasure() - chord.get(0).positionInMeasure()) <= .012f))
            return List.of();
        int padding = Math.max(2, Math.round(gap * .45f));
        return List.of(
                new MeasureRegion(region.left(), (cut - padding) / (float) width,
                        region.top(), region.bottom()),
                new MeasureRegion((cut + padding) / (float) width, Math.max(right, closing) / (float) width,
                        region.top(), region.bottom()));
    }

    /** A count inferred from other systems cannot insert a barline across clear paper. */
    static boolean paperContradictsSingleSplit(
            List<MeasureRegion> original, List<MeasureRegion> fitted,
            byte[] gray, int width, int height) {
        if (original.size() != 2 || fitted.size() != 3 || gray == null
                || gray.length != (long) width * height) return false;
        var first = original.get(0);
        var second = original.get(1);
        if (!sameRow(first, second) || second.left() <= first.right()) return false;
        boolean splitFirst = second.equals(fitted.get(2));
        if (!splitFirst && !first.equals(fitted.get(0))) return false;
        var originalBar = splitFirst ? first : second;
        var a = fitted.get(splitFirst ? 0 : 1);
        var b = fitted.get(splitFirst ? 1 : 2);
        if (a.left() != originalBar.left() || b.right() != originalBar.right()
                || !sameRow(a, originalBar) || !sameRow(b, originalBar)
                || b.left() <= a.right()) return false;
        int left = Math.max(0, Math.round(first.left() * width));
        int right = Math.min(width, Math.round(second.right() * width));
        int top = Math.max(0, Math.round(Math.min(first.top(), second.top()) * height));
        int bottom = Math.min(height, Math.round(Math.max(first.bottom(), second.bottom()) * height));
        int w = right - left, h = bottom - top;
        if (w < 24 || h < 20) return false;
        var raster = new byte[w * h];
        for (int y = 0; y < h; y++)
            System.arraycopy(gray, (top + y) * width + left, raster, y * w, w);
        var staffs = RawStaffLineDetector.detect(raster, w, h);
        if (staffs.size() != 1) return false;
        var staff = staffs.get(0);
        float floor = top + staff.bottom(), gap = staff.gap();
        float seam = (first.right() + second.left()) * width * .5f;
        float cut = (a.right() + b.left()) * width * .5f;
        if ((b.left() - a.right()) * width > gap
                || (second.left() - first.right()) * width > gap * 2) return false;
        var seamRails = PrintedDirectionStaff.printedRules(gray, width, height, seam, floor, gap);
        if (seamRails == null || !staffSpaceColumn(gray, width, height, seam, seamRails))
            return false;
        int from = Math.round(cut - gap * .5f), to = Math.round(cut + gap * .5f);
        if (from < left || to >= right) return false;
        int[] rules = new int[5];
        int radius = Math.max(2, Math.round(gap * .26f));
        for (int rule = 0; rule < rules.length; rule++) {
            int hint = Math.round(floor - (4 - rule) * gap);
            if (hint - radius < top || hint + radius >= bottom) return false;
            int darkest = -1;
            for (int y = hint - radius; y <= hint + radius; y++) {
                int ink = 0;
                for (int x = from; x <= to; x++)
                    ink += 255 - (gray[y * width + x] & 255);
                if (ink > darkest) {
                    darkest = ink;
                    rules[rule] = y;
                }
            }
            if (darkest < (to - from + 1) * 100
                    || rule > 0 && Math.abs(rules[rule] - rules[rule - 1] - gap) > gap * .26f)
                return false;
        }
        int clear = 0;
        for (int space = 0; space < 4; space++) {
            boolean paper = true;
            int step = rules[space + 1] - rules[space];
            for (int y = rules[space] + Math.round(step * .3f);
                    y <= rules[space] + Math.round(step * .7f); y++)
                for (int x = from; x <= to; x++)
                    if ((gray[y * width + x] & 255) < 225) paper = false;
            if (paper) clear++;
        }
        return clear >= 3;
    }

    private static boolean staffSpaceColumn(
            byte[] gray, int width, int height, float center, float[] rails) {
        float gap = rails[1], top = rails[0] - 4 * gap;
        if (top < 0 || rails[0] >= height) return false;
        for (int x = Math.max(0, Math.round(center - gap * .35f));
                x <= Math.min(width - 1, Math.round(center + gap * .35f)); x++) {
            boolean complete = true;
            for (int space = 0; space < 4; space++) {
                int first = Math.round(top + (space + .3f) * gap);
                int last = Math.round(top + (space + .7f) * gap);
                int ink = 0;
                for (int y = first; y <= last; y++)
                    if ((gray[y * width + x] & 255) < 160) ink++;
                if (ink < (last - first + 1) * .9f) complete = false;
            }
            if (complete) return true;
        }
        return false;
    }

    /** A partial split must not divide one independently complete written bar. */
    static List<MeasureRegion> preservePartialWrittenBars(
            List<MeasureRegion> raw,
            List<MeasureRegion> fitted,
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            byte[] gray,
            int width,
            int height) {
        if (gray == null || gray.length != (long) width * height) return fitted;
        var result = new ArrayList<>(fitted);
        List<ScoreNoteEvent> clockNotes = null;
        List<ScoreRestEvent> clockRests = null;
        for (var anchor : raw) {
            var original =
                    raw.stream()
                            .filter(r -> sameRow(anchor, r))
                            .sorted(Comparator.comparingDouble(MeasureRegion::left))
                            .toList();
            if (original.size() < 3 || original.get(0) != anchor) continue;
            var proposed =
                    fitted.stream()
                            .filter(r -> sameRow(anchor, r))
                            .sorted(Comparator.comparingDouble(MeasureRegion::left))
                            .toList();
            if (proposed.size() <= original.size()
                    || original.stream().allMatch(r -> fragments(r, proposed).size() >= 2)
                    || Math.abs(proposed.get(0).left() - anchor.left()) > .006f
                    || Math.abs(
                                    proposed.get(proposed.size() - 1).right()
                                            - original.get(original.size() - 1).right())
                            > .006f) continue;
            var row =
                    new MeasureRegion(
                            anchor.left(),
                            original.get(original.size() - 1).right(),
                            anchor.top(),
                            anchor.bottom());
            boolean proved = true;
            for (int i = 1; i < original.size(); i++) {
                var a = original.get(i - 1);
                var b = original.get(i);
                float cut = (a.right() + b.left()) * .5f;
                if (b.left() <= a.right()
                        || b.left() - a.right() > .015f
                        || !printedBarline(row, cut, gray, width, height, true)) {
                    proved = false;
                    break;
                }
                boolean retained = false;
                for (int j = 1; j < proposed.size(); j++)
                    if (Math.abs((proposed.get(j - 1).right() + proposed.get(j).left()) * .5f - cut)
                            < .006f) retained = true;
                if (!retained) {
                    proved = false;
                    break;
                }
            }
            if (!proved) continue;
            // Acquire actual ratios before trusting a literal capacity. This validation
            // snapshot never replaces the caller's note/rest streams.
            if (clockNotes == null) {
                var rhythm =
                        TripletRhythmDetector.withRests(notes, rests, raw, gray, width, height);
                clockNotes = rhythm.notes();
                clockRests = rhythm.rests();
            }
            double capacity = Double.NaN;
            int witnesses = 0;
            for (var bar : original) {
                var pieces = fragments(bar, proposed);
                if (pieces.size() != 1 || !pieces.get(0).equals(bar)) continue;
                int measure = raw.indexOf(bar);
                double written =
                        sameLiteralRests(rests, clockRests, measure)
                                ? literalWrittenCapacity(clockNotes, clockRests, measure)
                                : Double.NaN;
                if (!Double.isFinite(written)
                        || (Double.isFinite(capacity) && !same(capacity, written))) {
                    proved = false;
                    break;
                }
                capacity = written;
                witnesses++;
            }
            if (!proved || witnesses < 2) continue;
            for (var bar : original) {
                var pieces = fragments(bar, proposed);
                if (pieces.size() != 2
                        || Math.abs(pieces.get(0).left() - bar.left()) > .006f
                        || Math.abs(pieces.get(1).right() - bar.right()) > .006f
                        || pieces.get(1).left() <= pieces.get(0).right()
                        || pieces.get(1).left() - pieces.get(0).right() > .015f) continue;
                float cut = (pieces.get(0).right() + pieces.get(1).left()) * .5f;
                if (printedBarline(bar, cut, gray, width, height, true)
                        || printedBarline(bar, cut, gray, width, height)
                        || !sameLiteralRests(rests, clockRests, raw.indexOf(bar))
                        || !same(
                                capacity,
                                literalWrittenCapacity(clockNotes, clockRests, raw.indexOf(bar))))
                    continue;
                int at = result.indexOf(pieces.get(0));
                result.removeAll(pieces);
                result.add(at, bar);
            }
        }
        return List.copyOf(result);
    }

    private static boolean sameLiteralRests(
            List<ScoreRestEvent> source, List<ScoreRestEvent> clock, int measure) {
        return source.stream()
                .filter(r -> r.measureIndex() == measure)
                .toList()
                .equals(clock.stream().filter(r -> r.measureIndex() == measure).toList());
    }

    /** Literal evidence only: no meter fill, multi-rest placeholders or mixed clocks. */
    private static double literalWrittenCapacity(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, int measure) {
        var all = notes.stream().filter(n -> n.measureIndex() == measure).toList();
        var metrical =
                all.stream()
                        .filter(n -> (n.articulations() & NoteOrnament.GRACE) == 0)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        if (metrical.isEmpty()) return Double.NaN;
        int direction = metrical.get(0).stemDirection();
        if (direction == 0) return Double.NaN;
        for (var n : all) {
            if (n.staffCount() != 1
                    || n.staffIndex() != 0
                    || n.crossStaffBeam()
                    || n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.tupletDivisor() != 1
                    || n.tupletNormalNotes() != 1
                    || !Float.isFinite(n.positionInMeasure())) return Double.NaN;
            if ((n.articulations() & NoteOrnament.GRACE) != 0) {
                if (n.leadingRestBeats() != 0 || n.followingRestBeats() != 0) return Double.NaN;
            } else if (n.stemDirection() != direction
                    || n.beamCount() < 0
                    || (n.beamCount() == 0 && !(n.unbeamedDurationBeats() > 0))) return Double.NaN;
        }
        var spans = spans(metrical, measure);
        if (spans.size() != 1 || !Double.isFinite(spans.get(0).beats)) return Double.NaN;
        double pitched = 0;
        for (int start = 0; start < metrical.size(); ) {
            double duration = ScoreNoteTiming.writtenDurationBeats(metrical.get(start));
            if (!Double.isFinite(duration) || duration <= 0) return Double.NaN;
            int end = start + 1;
            while (end < metrical.size()
                    && metrical.get(end).positionInMeasure()
                                    - metrical.get(start).positionInMeasure()
                            <= .018f) {
                if (!same(duration, ScoreNoteTiming.writtenDurationBeats(metrical.get(end))))
                    return Double.NaN;
                end++;
            }
            pitched += duration;
            start = end;
        }
        double literal = 0;
        int restGroups = 0;
        for (var rest : rests)
            if (rest.measureIndex() == measure) {
                if (rest.isFullMeasure()
                        || rest.staffCount() != 1
                        || rest.staffIndex() != 0
                        || !Double.isFinite(rest.durationBeats())
                        || rest.durationBeats() <= 0
                        || !Float.isFinite(rest.positionInMeasure())) return Double.NaN;
                literal += rest.durationBeats();
                restGroups++;
            }
        var span = spans.get(0);
        return span.groups + restGroups >= 2 && same(span.beats, pitched + literal)
                ? span.beats
                : Double.NaN;
    }

    /** Count fitting cannot replace a fully proved printed row with unsupported interior cuts. */
    static List<MeasureRegion> preserveCountNeutralRows(
            List<MeasureRegion> raw,
            List<MeasureRegion> fitted,
            byte[] gray,
            int width,
            int height) {
        if (gray == null || gray.length != (long) width * height) return fitted;
        var result = new ArrayList<>(fitted);
        for (var anchor : raw) {
            var original =
                    raw.stream()
                            .filter(r -> sameRow(anchor, r))
                            .sorted(Comparator.comparingDouble(MeasureRegion::left))
                            .toList();
            if (original.size() < 2 || original.get(0) != anchor) continue;
            var proposed =
                    result.stream()
                            .filter(r -> sameRow(anchor, r))
                            .sorted(Comparator.comparingDouble(MeasureRegion::left))
                            .toList();
            if (proposed.size() < original.size()
                    || Math.abs(proposed.get(0).left() - anchor.left()) > .006f
                    || Math.abs(
                                    proposed.get(proposed.size() - 1).right()
                                            - original.get(original.size() - 1).right())
                            > .006f) continue;
            // A row-wide count inflation must subdivide every original bar. One extra cut
            // may repair a genuinely missed separator and requires stronger evidence.
            if (proposed.size() > original.size()
                    && original.stream().anyMatch(r -> fragments(r, proposed).size() < 2)) continue;
            var row =
                    new MeasureRegion(
                            anchor.left(),
                            original.get(original.size() - 1).right(),
                            anchor.top(),
                            anchor.bottom());
            var cuts = new ArrayList<Float>();
            boolean verified = true;
            for (int i = 1; i < original.size(); i++) {
                var a = original.get(i - 1);
                var b = original.get(i);
                float cut = (a.right() + b.left()) * .5f;
                if (b.left() <= a.right()
                        || b.left() - a.right() > .015f
                        || !printedBarline(row, cut, gray, width, height, true)) {
                    verified = false;
                    break;
                }
                cuts.add(cut);
            }
            if (!verified) continue;
            boolean inferred = false;
            for (int i = 1; i < proposed.size(); i++) {
                var a = proposed.get(i - 1);
                var b = proposed.get(i);
                float cut = (a.right() + b.left()) * .5f;
                if (b.left() <= a.right() || b.left() - a.right() > .015f) {
                    verified = false;
                    break;
                }
                if (cuts.stream().noneMatch(c -> Math.abs(c - cut) < .006f)) {
                    if (printedBarline(row, cut, gray, width, height, true)) {
                        verified = false;
                        break;
                    }
                    inferred = true;
                }
            }
            if (!verified || !inferred) continue;
            int at = result.indexOf(proposed.get(0));
            result.removeAll(proposed);
            result.addAll(at, original);
        }
        return List.copyOf(result);
    }

    /** Keep a proven printed separator when count fitting only shifted a two-bar division. */
    static List<MeasureRegion> preserveShiftedBoundary(
            List<MeasureRegion> raw,
            List<MeasureRegion> fitted,
            byte[] gray,
            int width,
            int height) {
        if (gray == null || gray.length != (long) width * height) return fitted;
        var result = new ArrayList<>(fitted);
        for (int i = 0; i + 1 < raw.size(); i++) {
            var a = raw.get(i);
            var b = raw.get(i + 1);
            if (!sameRow(a, b) || b.left() <= a.right() || b.left() - a.right() > .015f) continue;
            float cut = (a.right() + b.left()) * .5f;
            var joined = new MeasureRegion(a.left(), b.right(), a.top(), a.bottom());
            for (int j = 0; j + 1 < result.size(); j++) {
                var x = result.get(j);
                var y = result.get(j + 1);
                if (!sameRow(a, x)
                        || !sameRow(a, y)
                        || Math.abs(x.left() - a.left()) > .002f
                        || Math.abs(y.right() - b.right()) > .002f
                        || y.left() <= x.right()
                        || y.left() - x.right() > .015f) continue;
                float inferred = (x.right() + y.left()) * .5f;
                if (Math.abs(inferred - cut) < .012f
                        || !printedBarline(joined, cut, gray, width, height, true)
                        || printedBarline(joined, inferred, gray, width, height, true)) continue;
                result.set(j, a);
                result.set(j + 1, b);
            }
        }
        return List.copyOf(result);
    }

    /** A sole printed full-measure rest contradicts density-inferred final-row subdivisions. */
    static List<MeasureRegion> preserveFinalRestRow(
            List<MeasureRegion> raw,
            List<MeasureRegion> fitted,
            byte[] labels,
            byte[] gray,
            int width,
            int height) {
        if (raw.isEmpty()
                || gray == null
                || labels == null
                || gray.length != (long) width * height
                || labels.length != gray.length) return fitted;
        MeasureRegion last = raw.get(raw.size() - 1);
        if (raw.stream().filter(r -> sameRow(last, r)).count() != 1) return fitted;
        var pieces = fragments(last, fitted);
        if (pieces.size() < 2
                || Math.abs(pieces.get(0).left() - last.left()) > .006f
                || Math.abs(pieces.get(pieces.size() - 1).right() - last.right()) > .006f)
            return fitted;
        for (int i = 1; i < pieces.size(); i++) {
            var a = pieces.get(i - 1);
            var b = pieces.get(i);
            // Expanded multi-rests repeat geometry and must retain every logical bar.
            if (b.left() < a.right()
                    || b.left() - a.right() > .015f
                    || printedBarline(last, (a.right() + b.left()) * .5f, gray, width, height))
                return fitted;
        }
        var score = OmrScoreInterpreter.analyze(labels, gray, width, height, List.of(last));
        if (!score.notes().isEmpty() || score.rests().size() != 1) return fitted;
        var rest = score.rests().get(0);
        if (!rest.isFullMeasure()
                || rest.staffCount() != 1
                || rest.positionInMeasure() < .25f
                || rest.positionInMeasure() > .75f) return fitted;
        List<MeasureRegion> result = new ArrayList<>();
        boolean added = false;
        for (var region : fitted) {
            if (!pieces.contains(region)) result.add(region);
            else if (!added) {
                result.add(last);
                added = true;
            }
        }
        return List.copyOf(result);
    }

    /** Ledger lines below a staff can resemble a short, overlapping second system. */
    static List<MeasureRegion> rejectOverlappingRows(List<MeasureRegion> regions) {
        return regions.stream()
                .filter(
                        candidate -> {
                            float height = candidate.bottom() - candidate.top();
                            if (height <= 0) return true;
                            var upper =
                                    regions.stream()
                                            .filter(
                                                    other ->
                                                            other.top() < candidate.top() - .01f
                                                                    && Math.min(
                                                                                            other
                                                                                                    .bottom(),
                                                                                            candidate
                                                                                                    .bottom())
                                                                                    - candidate
                                                                                            .top()
                                                                            > .2f
                                                                                    * Math.min(
                                                                                            height,
                                                                                            other
                                                                                                            .bottom()
                                                                                                    - other
                                                                                                            .top()))
                                            .toList();
                            long crossedSeparators =
                                    upper.stream()
                                            .filter(
                                                    other ->
                                                            other.right() > candidate.left() + .01f
                                                                    && other.right()
                                                                            < candidate.right()
                                                                                    - .01f)
                                            .map(MeasureRegion::right)
                                            .distinct()
                                            .count();
                            return crossedSeparators < 2;
                        })
                .toList();
    }

    /** Number OCR may propose a missing row, but text in a footer is not a staff. */
    static List<MeasureRegion> rejectUnprintedRows(
            List<MeasureRegion> raw,
            List<MeasureRegion> fitted,
            byte[] labels,
            byte[] gray,
            int width,
            int height) {
        if (raw.isEmpty()
                || labels == null
                || gray == null
                || labels.length != width * height
                || gray.length != width * height) return fitted;
        List<MeasureRegion> result = new ArrayList<>();
        for (var region : fitted) {
            if (raw.stream()
                    .anyMatch(r -> r.top() <= region.bottom() && r.bottom() >= region.top())) {
                result.add(region);
                continue;
            }
            int left = Math.max(0, Math.round(region.left() * width));
            int right = Math.min(width, Math.round(region.right() * width));
            int top = Math.max(0, Math.round(region.top() * height));
            int bottom = Math.min(height, Math.round(region.bottom() * height));
            int w = right - left, h = bottom - top, staff = 0;
            if (w <= 0 || h <= 0) continue;
            byte[] crop = new byte[w * h];
            for (int y = top; y < bottom; y++)
                for (int x = left; x < right; x++) {
                    int at = y * width + x;
                    if (labels[at] == OmrMeasurePostProcessor.STAFF) staff++;
                    crop[(y - top) * w + x - left] = gray[at];
                }
            if (staff >= w * 2 || !RawStaffLineDetector.detect(crop, w, h).isEmpty())
                result.add(region);
        }
        return List.copyOf(result);
    }

    /** OCR counts cannot erase separators visibly spanning the original measure row. */
    static List<MeasureRegion> preservePrintedBoundaries(
            List<MeasureRegion> raw,
            List<MeasureRegion> fitted,
            byte[] gray,
            int width,
            int height) {
        if (gray == null || gray.length != width * height) return fitted;
        List<MeasureRegion> result = new ArrayList<>();
        for (var region : fitted) {
            var pieces = fragments(region, raw);
            boolean complete =
                    pieces.size() > 1
                            && Math.abs(pieces.get(0).left() - region.left()) < .006f
                            && Math.abs(pieces.get(pieces.size() - 1).right() - region.right())
                                    < .006f;
            for (int i = 1; complete && i < pieces.size(); i++) {
                float cut = (pieces.get(i - 1).right() + pieces.get(i).left()) * .5f;
                complete = printedBarline(region, cut, gray, width, height)
                        || printedDoubleBarline(region, cut, gray, width, height);
            }
            if (complete) result.addAll(pieces);
            else result.add(region);
        }
        return List.copyOf(result);
    }

    /** A detected two-rule separator can sit inside a crop with extra vertical padding. */
    private static boolean printedDoubleBarline(
            MeasureRegion region, float cut, byte[] gray, int width, int height) {
        int top = Math.max(0, Math.round(region.top() * height));
        int bottom = Math.min(height - 1, Math.round(region.bottom() * height));
        int center = Math.round(cut * width);
        int left = Math.max(0, center - Math.round(width * .065f));
        int right = Math.min(width - 1, center + Math.round(width * .065f));
        int cropWidth = right - left + 1, cropHeight = bottom - top + 1;
        if (cropWidth < 24 || cropHeight < 20) return false;
        byte[] crop = new byte[cropWidth * cropHeight];
        for (int y = top; y <= bottom; y++)
            System.arraycopy(gray, y * width + left, crop, (y - top) * cropWidth, cropWidth);
        var staffs = RawStaffLineDetector.detect(crop, cropWidth, cropHeight);
        if (staffs.size() != 1) return false;
        var staff = staffs.get(0);
        int a = top + staff.top(), b = top + staff.bottom();
        float gap = staff.gap();
        int reach = Math.max(3, Math.round(gap * .8f));
        int firstRail = -1, lastRail = -1;
        for (int x = Math.max(0, center - reach); x <= Math.min(width - 1, center + reach); x++) {
            int dark = 0;
            for (int y = a; y <= b; y++) if ((gray[y * width + x] & 255) < 150) dark++;
            if (dark < (b - a + 1) * .93f) continue;
            int outside = 0;
            for (int edge = 0; edge < 2; edge++)
                for (int distance = 3; distance <= Math.round(gap * .5f); distance++) {
                    int y = edge == 0 ? a - distance : b + distance;
                    if (y < 0 || y >= height || (gray[y * width + x] & 255) < 150) outside++;
                }
            if (outside > 2) continue;
            if (firstRail < 0) firstRail = x;
            lastRail = x;
        }
        if (firstRail < 0 || lastRail - firstRail < gap * .44f) return false;
        for (int x = firstRail + 2; x <= lastRail - 2; x++) {
            boolean clear = true;
            for (int space = 0; space < 4; space++) {
                int y = Math.round(a + (space + .5f) * (b - a) / 4);
                if ((gray[y * width + x] & 255) < 180) clear = false;
            }
            if (clear) return true;
        }
        return false;
    }

    /** A short, note-bearing opening bar must not be merged to match later row counts. */
    static List<MeasureRegion> preserveWrittenOpening(
            List<MeasureRegion> raw, List<MeasureRegion> fitted, List<ScoreNoteEvent> notes) {
        var pieces = mergedOpening(raw, fitted);
        if (pieces.isEmpty()
                || notes.stream().noneMatch(n -> n.measureIndex() == 0 && n.compactOpening()))
            return fitted;
        List<MeasureRegion> result = new ArrayList<>(pieces);
        result.addAll(fitted.subList(1, fitted.size()));
        return List.copyOf(result);
    }

    private static List<MeasureRegion> mergedOpening(
            List<MeasureRegion> raw, List<MeasureRegion> fitted) {
        if (raw.size() < 2 || fitted.isEmpty()) return List.of();
        var combined = fitted.get(0);
        if (!sameRow(raw.get(0), combined)
                || combined.left() > raw.get(0).left() + .006f
                || combined.left() < raw.get(0).left() - .02f) return List.of();
        List<MeasureRegion> pieces = new ArrayList<>();
        for (var region : raw) {
            if (!sameRow(region, combined) || region.right() > combined.right() + .006f) break;
            pieces.add(region);
        }
        if (pieces.size() < 2
                || Math.abs(pieces.get(pieces.size() - 1).right() - combined.right()) > .006f)
            return List.of();
        return pieces;
    }

    static List<MeasureRegion> alignPrintedSeparators(
            List<MeasureRegion> raw,
            List<MeasureRegion> fitted,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height) {
        if (gray == null || gray.length != width * height) return fitted;
        List<MeasureRegion> result = new ArrayList<>(fitted);
        for (int index = 0; index < raw.size(); index++) {
            final int measure = index;
            // On a grand staff, a barline crosses the empty space between staves.
            // A single-staff note stem can be just as tall as its measure region.
            boolean singleStaff =
                    notes.stream()
                                    .filter(n -> n.measureIndex() == measure)
                                    .map(ScoreNoteEvent::staffIndex)
                                    .distinct()
                                    .count()
                            < 2;
            var region = raw.get(index);
            var pieces = fragments(region, result);
            if (pieces.size() != 2
                    || Math.abs(pieces.get(0).left() - region.left()) > .006f
                    || Math.abs(pieces.get(1).right() - region.right()) > .006f) continue;
            if (singleStaff) {
                Float verified =
                        PrintedVoiceMeasureCut.resolve(region, notes, measure, gray, width, height);
                if (verified != null) {
                    float spacing = Math.min(.004f, (region.right() - region.left()) * .04f);
                    int at = result.indexOf(pieces.get(0));
                    result.set(
                            at,
                            new MeasureRegion(
                                    region.left(),
                                    verified - spacing * .5f,
                                    region.top(),
                                    region.bottom()));
                    result.set(
                            at + 1,
                            new MeasureRegion(
                                    verified + spacing * .5f,
                                    region.right(),
                                    region.top(),
                                    region.bottom()));
                }
                continue;
            }
            int top = Math.max(0, Math.round(region.top() * height));
            int bottom = Math.min(height - 1, Math.round(region.bottom() * height));
            float span = region.right() - region.left();
            int left = Math.max(0, Math.round((region.left() + span * .15f) * width));
            int right = Math.min(width - 1, Math.round((region.right() - span * .15f) * width));
            float inferred = (pieces.get(0).right() + pieces.get(1).left()) * .5f * width;
            int best = -1;
            double bestScore = 0;
            for (int x = left; x <= right; x++) {
                int ink = 0;
                for (int y = top; y <= bottom; y++) if ((gray[y * width + x] & 255) < 180) ink++;
                if (ink <= (bottom - top + 1) * .55f) continue;
                double score = ink - Math.abs(x - inferred) * .02;
                if (score > bestScore) {
                    best = x;
                    bestScore = score;
                }
            }
            if (best < 0) continue;
            float cut = best / (float) width, gap = Math.min(.004f, span * .04f);
            int at = result.indexOf(pieces.get(0));
            result.set(
                    at,
                    new MeasureRegion(region.left(), cut - gap / 2, region.top(), region.bottom()));
            result.set(
                    at + 1,
                    new MeasureRegion(
                            cut + gap / 2, region.right(), region.top(), region.bottom()));
        }
        return List.copyOf(result);
    }

    private record Span(double beats, int groups, boolean allBeamed) {}

    static List<MeasureRegion> preserveCompleteRuns(
            List<MeasureRegion> raw,
            List<MeasureRegion> fitted,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height) {
        List<MeasureRegion> result = new ArrayList<>(fitted);
        for (int index = 0; index < raw.size(); index++) {
            MeasureRegion region = raw.get(index);
            List<MeasureRegion> pieces = fragments(region, result);
            if (pieces.size() != 2
                    || Math.abs(pieces.get(0).left() - region.left()) > .006f
                    || Math.abs(pieces.get(1).right() - region.right()) > .006f) continue;
            float cut = (pieces.get(0).right() + pieces.get(1).left()) * .5f;
            if (printedBarline(region, cut, gray, width, height)) continue;
            List<Span> voices = spans(notes, index);
            boolean complete = false;
            for (Span voice : voices) {
                if (!voice.allBeamed || voice.groups < 8 || voice.beats < .5 || voice.beats > 16)
                    continue;
                if (voices.stream().filter(other -> same(other.beats, voice.beats)).count() < 2)
                    continue;
                int matchingBars = 0;
                for (int neighbor = 0; neighbor < raw.size(); neighbor++) {
                    if (neighbor == index
                            || !sameRow(raw.get(neighbor), region)
                            || fragments(raw.get(neighbor), fitted).size() != 1) continue;
                    if (spans(notes, neighbor).stream()
                            .anyMatch(other -> same(other.beats, voice.beats))) matchingBars++;
                }
                if (matchingBars >= 2) {
                    complete = true;
                    break;
                }
            }
            if (!complete) continue;
            int at = result.indexOf(pieces.get(0));
            result.removeAll(pieces);
            result.add(at, region);
        }
        return List.copyOf(result);
    }

    private static boolean same(double a, double b) {
        return Math.abs(a - b) < .01;
    }

    private static boolean sameRow(MeasureRegion a, MeasureRegion b) {
        return Math.abs(a.top() - b.top()) < .006f && Math.abs(a.bottom() - b.bottom()) < .006f;
    }

    private static List<MeasureRegion> fragments(MeasureRegion region, List<MeasureRegion> fitted) {
        return fitted.stream()
                .filter(
                        part ->
                                sameRow(region, part)
                                        && part.left() >= region.left() - .006f
                                        && part.right() <= region.right() + .006f)
                .sorted(Comparator.comparingDouble(MeasureRegion::left))
                .collect(java.util.stream.Collectors.toList());
    }

    private static List<Span> spans(List<ScoreNoteEvent> notes, int measure) {
        List<Span> result = new ArrayList<>();
        var lanes =
                notes.stream()
                        .filter(n -> n.measureIndex() == measure)
                        .map(n -> n.staffCount() * 16 + n.staffIndex())
                        .distinct()
                        .collect(java.util.stream.Collectors.toList());
        for (int lane : lanes) {
            var voice =
                    notes.stream()
                            .filter(
                                    n ->
                                            n.measureIndex() == measure
                                                    && n.staffCount() * 16 + n.staffIndex() == lane)
                            .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                            .collect(java.util.stream.Collectors.toList());
            double beats = 0;
            int groups = 0;
            boolean beamed = true, valid = true;
            for (int start = 0; start < voice.size(); ) {
                int end = start;
                double duration = 0, rest = 0, leading = 0;
                while (end < voice.size()
                        && voice.get(end).positionInMeasure() - voice.get(start).positionInMeasure()
                                <= .018f) {
                    var note = voice.get(end++);
                    double written = ScoreNoteTiming.writtenDurationBeats(note);
                    if (!Double.isFinite(written)
                            || (note.articulations() & NoteOrnament.GRACE) != 0) valid = false;
                    duration = Math.max(duration, written);
                    rest = Math.max(rest, note.followingRestBeats());
                    leading = Math.max(leading, note.leadingRestBeats());
                    beamed &= note.beamCount() > 0 && rest == 0 && leading == 0;
                }
                beats += duration + rest + (groups == 0 ? leading : 0);
                groups++;
                start = end;
            }
            if (valid) result.add(new Span(beats, groups, beamed));
        }
        return result;
    }

    private static boolean printedBarline(
            MeasureRegion region, float cut, byte[] gray, int width, int height) {
        return printedBarline(region, cut, gray, width, height, false);
    }

    private static boolean printedBarline(
            MeasureRegion region, float cut, byte[] gray, int width, int height, boolean strict) {
        if (gray == null || gray.length != width * height) return true;
        int top = Math.max(0, Math.round(region.top() * height));
        int bottom = Math.min(height - 1, Math.round(region.bottom() * height));
        int center = Math.round(cut * width), radius = Math.max(3, Math.round(width * .006f));
        for (int x = Math.max(0, center - radius); x <= Math.min(width - 1, center + radius); x++) {
            int ink = 0;
            for (int y = top; y <= bottom; y++) if ((gray[y * width + x] & 255) < 180) ink++;
            if (!strict && ink > (bottom - top + 1) * .55f) return true;
        }
        // Duet/orchestral separators may stop at each staff instead of crossing
        // the intervening whitespace. Require complete columns on two actual
        // five-line staffs; one note stem or a few isolated strokes cannot qualify.
        int left = Math.max(0, Math.round(region.left() * width));
        int right = Math.min(width - 1, Math.round(region.right() * width));
        int cropWidth = right - left + 1, cropHeight = bottom - top + 1;
        if (cropWidth < 24 || cropHeight < 20) return false;
        byte[] crop = new byte[cropWidth * cropHeight];
        for (int y = top; y <= bottom; y++)
            System.arraycopy(gray, y * width + left, crop, (y - top) * cropWidth, cropWidth);
        var staffs = RawStaffLineDetector.detect(crop, cropWidth, cropHeight);
        if (staffs.size() == 1) {
            var staff = staffs.get(0);
            for (int x = Math.max(left, center - radius);
                    x <= Math.min(right, center + radius);
                    x++) {
                int a = top + staff.top(), b = top + staff.bottom(), ink = 0;
                for (int y = a; y <= b; y++) if ((gray[y * width + x] & 255) < 180) ink++;
                if (ink < (b - a + 1) * .95f) continue;
                int clearance =
                        Math.max(2, Math.round(staff.gap() * .3f))
                                + Math.max(1, Math.round(staff.gap() * .22f));
                boolean clean = true;
                for (int edge : new int[] {a, b})
                    for (int dy = -clearance; dy <= clearance; dy++) {
                        int y = edge + dy;
                        if (y < 0 || y >= height) {
                            clean = false;
                            continue;
                        }
                        if (verifiedRuleBand(gray, width, height, x, edge, y, staff.gap()))
                            continue;
                        if ((edge == a && dy < -1) || (edge == b && dy > 1))
                            if ((gray[y * width + x] & 255) < 180
                                    && !verifiedRuleFringe(
                                            gray, width, height, x, x, edge, y, staff.gap()))
                                clean = false;
                        // Ignore the printed rule itself; a head spreads laterally off that rule.
                        if (Math.abs(dy) <= 1) continue;
                        int reach = Math.max(3, Math.round(staff.gap() * .45f));
                        for (int dx : new int[] {-reach, reach})
                            if (x + dx >= 0
                                    && x + dx < width
                                    && (gray[y * width + x + dx] & 255) < 180
                                    && !verifiedRuleFringe(
                                            gray, width, height, x, x + dx, edge, y, staff.gap())
                                    && !ThinBoundaryArc.proved(
                                            gray,
                                            width,
                                            height,
                                            x,
                                            edge,
                                            staff.gap(),
                                            edge == a ? 1 : -1)) clean = false;
                    }
                if (clean) return true;
            }
        }
        if (staffs.size() < 2) return false;
        for (int x = Math.max(left, center - radius); x <= Math.min(right, center + radius); x++) {
            int supported = 0;
            for (var staff : staffs) {
                int ink = 0;
                for (int y = staff.top() + top; y <= staff.bottom() + top; y++) {
                    boolean dark = false;
                    for (int dx = -1; dx <= 1; dx++)
                        if (x + dx >= 0 && x + dx < width && (gray[y * width + x + dx] & 255) < 180)
                            dark = true;
                    if (dark) ink++;
                }
                if (ink >= (staff.bottom() - staff.top() + 1) * .90f) supported++;
            }
            if (supported >= 2) return true;
        }
        return false;
    }

    /** Ignore only the connected rule band independently visible on both sides of the separator. */
    private static boolean verifiedRuleBand(
            byte[] gray, int width, int height, int x, int edge, int y, float gap) {
        if (Math.abs(y - edge) > Math.max(1, Math.round(gap * .22f))) return false;
        int near = Math.max(3, Math.round(gap * .8f));
        int far = Math.max(near + 3, Math.round(gap * 2.3f));
        if (x - far < 0 || x + far >= width) return false;
        for (int row = Math.min(edge, y); row <= Math.max(edge, y); row++) {
            if (row < 0 || row >= height) return false;
            for (int side : new int[] {-1, 1}) {
                int ink = 0;
                for (int dx = near; dx <= far; dx++)
                    if ((gray[row * width + x + side * dx] & 255) < 180) ink++;
                if (ink < (far - near + 1) * .85f) return false;
            }
        }
        return true;
    }

    /** One fading raster row may border a separately proved horizontal rule. */
    private static boolean verifiedRuleFringe(
            byte[] gray,
            int width,
            int height,
            int boundaryX,
            int sampleX,
            int edge,
            int y,
            float gap) {
        int distance = Math.abs(y - edge);
        if (distance < 1 || distance > Math.max(1, Math.round(gap * .22f)) + 1) return false;
        int toward = y + (edge > y ? 1 : -1), away = y + (edge > y ? -1 : 1);
        if (toward < 0
                || away < 0
                || toward >= height
                || away >= height
                || sampleX < 0
                || sampleX >= width) return false;
        int ink = gray[y * width + sampleX] & 255, core = gray[toward * width + sampleX] & 255;
        return core < 170
                && ink >= core + 24
                && (gray[away * width + sampleX] & 255) >= ink
                && verifiedRuleBand(gray, width, height, boundaryX, toward, toward, gap);
    }
}
