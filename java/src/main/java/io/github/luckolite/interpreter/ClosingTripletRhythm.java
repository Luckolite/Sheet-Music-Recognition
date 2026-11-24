// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Recovers an unnumbered triplet cadence at the proved end of a single staff. */
final class ClosingTripletRhythm {
    private ClosingTripletRhythm() {}
    static ScorePageInterpretation apply(
            ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] labels, byte[] gray, int width, int height) {
        score = SystemClosingHarmonicRun.apply(score, inherited, gray, width, height);
        int barIndex = score.measures().size() - 1;
        if (barIndex < 1) return score;
        ScoreMeterChange meter = inherited;
        for (var change : score.meterChanges())
            if (change.measureIndex() <= barIndex) meter = change;
        if (meter != null && (meter.numerator() != 4 || meter.denominator() != 4)
                || score.rests().stream().anyMatch(rest -> rest.measureIndex() == barIndex)) return score;
        var notes = score.notes();
        var indices = IntStream.range(0, notes.size())
                .filter(i -> notes.get(i).measureIndex() == barIndex).boxed()
                .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
        var bar = score.measures().get(barIndex);
        if (indices.size() == 7) {
            var dotted = dottedClosingHalf(score, meter, gray, width, height, bar, indices);
            if (dotted != score) return dotted;
            return closingHalf(score, meter, labels, gray, width, height, barIndex, bar, indices, false);
        }
        if (indices.size() != 10) return score;
        float[] xs = new float[10], ys = new float[10];
        int direction = notes.get(indices.get(0)).stemDirection();
        if (Math.abs(direction) != 1) return score;
        int doubled = -1;
        for (int i = 0; i < 10; i++) {
            var n = notes.get(indices.get(i));
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1
                    || n.stemDirection() != direction
                    || n.tupletDivisor() != 1 || n.augmentationDots() != 0 || n.crossStaffBeam()
                    || n.leadingRestBeats() > 0 || n.followingRestBeats() > 0
                    || n.tiedFromPrevious() || n.boundaryTies() != 0 || (n.articulations() & NoteOrnament.GRACE) != 0) return score;
            if (i == 9) {
                if (n.beamCount() != 0 || n.unbeamedDurationBeats() != 1) return score;
            } else if (n.beamCount() == 2 && i % 3 == 0 && doubled < 0) doubled = i;
            else if (n.beamCount() != 1) return score;
            xs[i] = (bar.left() + n.positionInMeasure() * (bar.right() - bar.left())) * width;
            ys[i] = n.pageY() * height;
        }
        var staffs = OmrScoreInterpreter.techniqueStaffs(labels, gray, width, height, score.measures());
        var owner = staffs.stream()
                .filter(s -> s.top() >= bar.top() * height && s.bottom() <= bar.bottom() * height)
                .min(Comparator.comparingDouble(s -> Math.abs((s.top()+s.bottom())/2
                        -(bar.top()+bar.bottom())*height/2))).orElse(null);
        if (owner == null) return score;
        if (meter == null && !printedCommonTime(score, barIndex, labels, gray, width, height, owner))
            return score;
        // A group break must survive staff-line removal; shared line ink can bridge
        // otherwise independent triplets.
        var track = track(gray, width, height, bar, owner);
        if (track == null || !printedEnd(gray, width, height, bar, track, owner.gap())) return score;
        float gap = owner.gap();
        for (int i = 1; i < 10; i++) {
            float dx = xs[i] - xs[i-1];
            if (dx < gap * 1.4f || dx > gap * 7) return score;
            boolean linked = PrintedTupletBeamOwner.connectedAwayFromRules(
                    gray, width, height, xs[i-1], ys[i-1], xs[i], ys[i], gap, direction, track);
            if (linked != (i % 3 != 0)) return score;
        }
        if (doubled >= 0 && hasSecondary(gray, width, height, xs, ys, gap, direction, doubled))
            return score;
        var result = new ArrayList<>(notes);
        for (int i = 0; i < 9; i++) {
            int index = indices.get(i);
            var note = result.get(index);
            if (i == doubled) note = singleBeam(note);
            result.set(index, note.withTupletRatio(3, 2));
        }
        return score.withNotes(result, IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }


    /** Recover a 4/4 half-note cadence before a literal meter on the following staff. */
    static ScorePageInterpretation restoreBeforeSixFour(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] labels,
            byte[] gray, byte[] original, int width, int height, int page) {
        if (gray == null || labels == null || original == null || labels.length != gray.length
                || original.length != gray.length || gray.length != (long) width * height)
            return score;
        var regions = score.measures();
        var notes = score.notes();
        ScoreMeterChange prior = inherited;
        List<ScoreMeterChange> normalReadings = null;
        List<ScoreMeterChange> rawReadings = null;
        for (var change : score.meterChanges()) {
            var previous = prior;
            prior = change;
            int i = change.measureIndex() - 1;
            if (i < 0 || previous == null
                    || previous.numerator() != 4 || previous.denominator() != 4
                    || change.numerator() != 6 || change.denominator() != 4
                    || regions.get(i).bottom() >= regions.get(i + 1).top()
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == i))
                continue;
            var owners = IntStream.range(0, notes.size()).boxed()
                    .filter(index -> notes.get(index).measureIndex() == i)
                    .sorted(Comparator.comparingDouble(index -> notes.get(index).positionInMeasure()))
                    .toList();
            if (owners.size() != 7) continue;
            var bar = regions.get(i);
            var fixed = closingHalf(score, previous, labels, gray, width, height, i, bar, owners, true);
            var rawFixed = closingHalf(score, previous, labels, original, width, height, i, bar, owners, true);
            if (fixed == score || rawFixed == score || !fixed.notes().equals(rawFixed.notes()))
                continue;
            if (normalReadings == null) {
                normalReadings = MeterFontMatcher.standaloneReadings(labels, gray, width, height, regions, notes);
                rawReadings = MeterFontMatcher.standaloneReadings(labels, original, width, height, regions, notes);
            }
            if (normalReadings.contains(change) && rawReadings.contains(change))
                score = withCadenceBeams(fixed, owners, bar, gray, original, width, height, page);
        }
        return score;
    }


    private static ScorePageInterpretation withCadenceBeams(
            ScorePageInterpretation score, List<Integer> owners, MeasureRegion bar,
            byte[] gray, byte[] original, int width, int height, int page) {
        if (page < 0) return score;
        var source = score.beamSource();
        var groups = new ArrayList<>(source.groups());
        var origins = new ArrayList<>(source.noteOrigins());
        var rows = UnnumberedTripletInk.sourceRows(bar, score.measures(), height);
        for (int first = 0; first < 6; first += 3) {
            var indices = owners.subList(first, first + 3);
            var members = indices.stream().map(score.notes()::get).toList();
            if (!unnumberedCadenceTriplet(gray, width, height, bar, members, rows)
                    || !unnumberedCadenceTriplet(original, width, height, bar, members, rows))
                continue;
            var columns = new ArrayList<ScoreBeamSource.Column>(3);
            for (int owner : indices) {
                var old = origins.get(owner);
                var anchor = old.orElseGet(() -> new ScoreBeamSource.Anchor(page, owner));
                if (anchor.pageIndex() != page
                        || java.util.Collections.frequency(origins, java.util.Optional.of(anchor))
                                != (old.isPresent() ? 1 : 0)) break;
                columns.add(new ScoreBeamSource.Column(List.of(anchor)));
            }
            if (columns.size() != 3) continue;
            var anchors = columns.stream().map(c -> c.members().get(0)).toList();
            if (source.detachedColumns().stream().anyMatch(c -> c.members().stream().anyMatch(anchors::contains)))
                continue;
            var previous = groups.stream().filter(group -> group.columns().stream()
                    .anyMatch(c -> c.members().stream().anyMatch(anchors::contains))).toList();
            if (previous.size() > 1 || previous.stream().anyMatch(group ->
                    !columns.containsAll(group.columns()) || !group.hooks().isEmpty()
                            || group.bridges().stream().anyMatch(levels -> levels != 1)
                            || group.tupletMark() == ScoreBeamSource.TupletMark.NUMBERED)) continue;
            String id = previous.isEmpty() ? "closed-half-triplet:" + page + ":" + indices.get(0)
                    : previous.get(0).id();
            if (previous.isEmpty() && groups.stream().anyMatch(group -> group.id().equals(id))) continue;
            var group = new ScoreBeamSource.Group(id, columns, List.of(1, 1), List.of(),
                    ScoreBeamSource.TupletMark.UNNUMBERED);
            if (previous.isEmpty()) groups.add(group);
            else groups.set(groups.indexOf(previous.get(0)), group);
            for (int n = 0; n < 3; n++) origins.set(indices.get(n), java.util.Optional.of(anchors.get(n)));
        }
        return groups.equals(source.groups()) ? score
                : score.withBeamSource(new ScoreBeamSource(groups, source.detachedColumns(), origins));
    }

    static boolean unnumberedCadenceTriplet(
            byte[] ink, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes,
            UnnumberedTripletInk.Rows rows) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (notes.size() != 3 || gap < 10 || gap > 25) return false;
        float[] x = new float[3], y = new float[3];
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(3);
        var budget = new PrintedBeamShafts.Budget(100_000);
        int direction = -notes.get(0).stemDirection();
        if (Math.abs(direction) != 1) return false;
        for (int i = 0; i < 3; i++) {
            var note = notes.get(i);
            x[i] = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
            y[i] = note.pageY() * height;
            var found = PrintedBeamShafts.at(ink, width, height, x[i], y[i], gap, budget);
            if (found.size() != 1 || found.get(0).direction() != direction || budget.refused()) return false;
            shafts.add(found.get(0));
        }
        if (!WrappedEighthTriplets.matchesBeamRails(ink, width, height, shafts.get(0), shafts.get(1), gap, 1)
                || !WrappedEighthTriplets.matchesBeamRails(ink, width, height, shafts.get(1), shafts.get(2), gap, 1))
            return false;
        var track = StaffPitchTrack.forPrintedSlurs(
                ink, width, height, bar.top() * height + 2.1f * gap, bar.bottom() * height - 2.1f * gap, gap);
        float top = Math.min(y[0], Math.min(y[1], y[2]));
        float bottom = Math.max(y[0], Math.max(y[1], y[2]));
        int tip = shafts.get(0).endY();
        for (var shaft : shafts) tip = direction > 0 ? Math.max(tip, shaft.endY()) : Math.min(tip, shaft.endY());
        if (UnnumberedTripletInk.bracketBesideBeam(
                ink, width, height, gap, x[0], x[2], top, bottom, tip, rows, track, true)) return false;
        float inset = (x[2] - x[0]) * .12f;
        int left = Math.round(x[0] + inset), right = Math.round(x[2] - inset);
        int above = Math.max(rows.first(), Math.round(Math.min(top, tip) - 5 * gap));
        int below = Math.min(rows.last(), Math.round(Math.max(bottom, tip) + 5 * gap));
        if (left < 0 || right >= width || above < 0 || below >= height || left >= right || above >= below)
            return false;
        return RawNumeralComponents.captureLongWindow(ink, width, height, left, above, right, below)
                .components().stream().noneMatch(mark -> {
                    int h = mark.bottom - mark.top + 1;
                    int w = mark.right - mark.left + 1;
                    return h >= .55f * gap && h <= 1.7f * gap && w >= .26f * gap && w <= 1.24f * gap;
                });
    }

    private static ScorePageInterpretation dottedClosingHalf(
            ScorePageInterpretation score, ScoreMeterChange meter, byte[] gray, int width, int height,
            MeasureRegion bar, List<Integer> indices) {
        if (meter == null || gray == null || gray.length != (long) width * height) return score;
        var notes = score.notes();
        float[] x = new float[7], y = new float[7];
        for (int i = 0; i < indices.size(); i++) {
            var n = notes.get(indices.get(i));
            int beams = i == 6 ? 0 : i % 3 == 1 ? 2 : 1;
            boolean dotted = i < 6 && i % 3 == 0;
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1 || n.crossStaffBeam()
                    || n.beamCount() != beams || n.tupletDivisor() != 1
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || n.tiedFromPrevious() || n.boundaryTies() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0
                    || (dotted ? n.augmentationDots() < 1 : n.augmentationDots() != 0)
                    || i == 6 && n.unbeamedDurationBeats() != 2) return score;
            x[i] = (bar.left() + n.positionInMeasure() * (bar.right() - bar.left())) * width;
            y[i] = n.pageY() * height;
        }
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float edge = bar.right() * width;
        if (gap < 6 || gap > 32 || edge - x[6] < gap * 1.7f) return score;
        float floor = bar.bottom() * height - gap * 2.1f;
        var owner = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        if (!doubleRule(gray, width, height, owner, edge)) return score;
        int first = tripletDirection(gray, width, height, x, y, gap, 0);
        int second = tripletDirection(gray, width, height, x, y, gap, 3);
        if (first == 0 || second == 0 || first == second) return score;
        for (int i : new int[] {0, 3})
            if (!singleDotCore(gray, width, height, x[i], y[i], x[i + 1], gap)) return score;
        var result = new ArrayList<>(notes);
        for (int i = 0; i < 6; i++) {
            int index = indices.get(i);
            var n = notes.get(index);
            result.set(index, i % 3 == 0 ? dottedTriplet(n) : n.withTupletRatio(3, 2));
        }
        return score.withNotes(result, IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    /** Screen the inter-note engraving slot for one isolated round dark core. */
    private static boolean singleDotCore(byte[] gray, int width, int height,
            float x, float y, float nextX, float gap) {
        int left = Math.round(x + gap * .7f), right = Math.round(nextX - gap * .55f);
        int top = Math.round(y - gap * .85f), bottom = Math.round(y + gap * .65f);
        if (right <= left || bottom <= top || left < 0 || top < 0 || right >= width
                || bottom >= height || right - left > gap * 2) return false;
        int w = right - left + 1, h = bottom - top + 1;
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[seen.length];
        int round = 0;
        for (int start = 0; start < seen.length; start++) {
            if (seen[start] || (gray[(top + start / w) * width + left + start % w] & 255) >= 90)
                continue;
            seen[start] = true;
            queue[0] = start;
            int count = 1, a = w, b = -1, c = h, d = -1;
            for (int i = 0; i < count; i++) {
                int q = queue[i], xx = q % w, yy = q / w;
                a = Math.min(a, xx);
                b = Math.max(b, xx);
                c = Math.min(c, yy);
                d = Math.max(d, yy);
                for (int ny = Math.max(0, yy - 1); ny <= Math.min(h - 1, yy + 1); ny++)
                    for (int nx = Math.max(0, xx - 1); nx <= Math.min(w - 1, xx + 1); nx++) {
                        int p = ny * w + nx;
                        if (seen[p] || (gray[(top + ny) * width + left + nx] & 255) >= 90) continue;
                        seen[p] = true;
                        queue[count++] = p;
                    }
            }
            int dotW = b - a + 1, dotH = d - c + 1;
            if (a > 0 && b < w - 1 && c > 0 && d < h - 1
                    && dotW >= gap * .22f && dotW <= gap * .6f
                    && dotH >= gap * .22f && dotH <= gap * .6f
                    && Math.max(dotW, dotH) <= 1.5f * Math.min(dotW, dotH)
                    && count >= dotW * dotH * .55f) round++;
        }
        return round == 1;
    }

    private static ScoreNoteEvent dottedTriplet(ScoreNoteEvent n) {
        return new ScoreNoteEvent(
                n.measureIndex(), n.positionInMeasure(), n.staffStep(), n.staffIndex(), n.staffCount(),
                n.pageY(), n.tiedFromPrevious(), 1, n.beamCount(), n.writtenAccidental(),
                n.unbeamedDurationBeats(), 3, n.followingRestBeats(), n.articulations(),
                n.clefBottomDiatonic(), n.crossStaffBeam(), n.leadingRestBeats(), n.compactOpening(),
                n.octaveShift(), n.boundaryTies(), 2, n.stemDirection(), n.kind(), n.visualSize(),
                n.harmonicSemitones(), n.openStringTouch());
    }

    private static ScorePageInterpretation closingHalf(
            ScorePageInterpretation score, ScoreMeterChange meter, byte[] labels, byte[] gray,
            int width, int height, int barIndex, MeasureRegion bar, List<Integer> indices,
            boolean allowBrokenShafts) {
        if (gray == null || gray.length != (long) width * height) return score;
        var notes = score.notes();
        float[] x = new float[7], y = new float[7];
        boolean brokenShafts = false;
        for (int i = 0; i < indices.size(); i++) {
            var note = notes.get(indices.get(i));
            boolean suspect = allowBrokenShafts && i == 3
                    && note.beamCount() == 2 && note.stemDirection() == 0;
            brokenShafts |= suspect;
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1
                    || note.crossStaffBeam() || !suspect && note.beamCount() != (i < 6 ? 1 : 0)
                    || i == 6 && note.unbeamedDurationBeats() != 2
                    || note.augmentationDots() != 0 || note.tupletDivisor() != 1
                    || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                    || note.tiedFromPrevious() || note.boundaryTies() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0) return score;
            x[i] = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
            y[i] = note.pageY() * height;
        }
        float edge = bar.right() * width;
        for (var staff : RawStaffLineDetector.detect(gray, width, height)) {
            float gap = staff.gap();
            if (staff.top() < bar.top() * height - gap
                    || staff.bottom() > bar.bottom() * height + gap) continue;
            var owner = new PlayingTechniqueDetector.Staff(staff.top(), staff.bottom(), gap, 0, 1);
            if (meter == null && !printedCommonTime(score, barIndex, labels, gray, width, height, owner))
                continue;
            if (!doubleRule(gray, width, height, owner, edge)) continue;
            int first = tripletDirection(gray, width, height, x, y, gap, 0);
            int second = brokenShafts
                    ? brokenUpstemTriplet(gray, width, height, x, y, gap, 3)
                    : tripletDirection(gray, width, height, x, y, gap, 3);
            if (first == 0 || second == 0 || first == second && !brokenShafts
                    && !SeparateBeamGroups.between(gray, width, height, x[2], y[2], x[3], y[3], gap))
                continue;
            if (brokenShafts && (first != 1 || notes.get(indices.get(4)).stemDirection() != 1
                    || notes.get(indices.get(5)).stemDirection() != 1)) continue;
            var result = new ArrayList<>(notes);
            for (int i : indices.subList(0, 6)) result.set(i, notes.get(i).withTupletRatio(3, 2));
            if (brokenShafts) {
                int suspect = indices.get(3);
                result.set(suspect, singleBeam(result.get(suspect)).withStemDirection(1));
            }
            return score.withNotes(result, IntStream.range(0, notes.size()).boxed().toList())
                    .withClockContextSource(score.clockContextSource());
        }
        return score;
    }

    private record SplitShaft(PrintedBeamShafts.Shaft shaft, int gapStart) {}

    private static int brokenUpstemTriplet(
            byte[] gray, int width, int height, float[] x, float[] y, float gap, int first) {
        var left = splitUpstem(gray, width, height, x[first], y[first], gap);
        var middle = splitUpstem(gray, width, height, x[first + 1], y[first + 1], gap);
        if (left == null || middle == null
                || Math.abs(left.gapStart() - middle.gapStart()) > 1) return 0;
        var budget = new PrintedBeamShafts.Budget(30_000);
        var last = PrintedBeamShafts.at(gray, width, height, x[first + 2], y[first + 2], gap, budget);
        if (last.size() != 1 || last.get(0).direction() != -1 || budget.refused()) return 0;
        var preceding = PrintedBeamShafts.at(gray, width, height, x[first - 1], y[first - 1], gap, budget);
        if (preceding.size() != 1 || preceding.get(0).direction() != -1 || budget.refused()) return 0;
        var a = preceding.get(0);
        var b = left.shaft();
        if (!SeparateBeamGroups.clearBetweenTips(gray, width, height,
                new SeparateBeamGroups.Tip(a.right(), a.endY(), -1),
                new SeparateBeamGroups.Tip(b.left(), b.endY(), -1), gap)) return 0;
        return WrappedEighthTriplets.matchesBeamRails(gray, width, height, left.shaft(), middle.shaft(), gap, 1)
                && WrappedEighthTriplets.matchesBeamRails(gray, width, height, middle.shaft(), last.get(0), gap, 1)
                ? 1 : 0;
    }

    /** A light stripe may interrupt aligned shafts. Require real ink above and below it. */
    private static SplitShaft splitUpstem(
            byte[] gray, int width, int height, float x, float y, float gap) {
        int center = Math.round(x + .6f * gap), start = Math.round(y) - Math.round(.2f * gap);
        int reach = Math.round(.2f * gap), limit = Math.round(y - 4.5f * gap);
        if (center - reach < 0 || center + reach >= width || limit < 0 || start >= height) return null;
        var columns = new ArrayList<PrintedBeamShafts.Column>();
        int commonStart = -1, commonEnd = -1, tip = start;
        for (int col = center - reach; col <= center + reach; col++) {
            int row = start;
            while (row > limit && (gray[row * width + col] & 255) < 165) row--;
            int gapStart = row;
            if (start - row < .32f * gap) continue;
            while (row > limit && (gray[row * width + col] & 255) >= 165) row--;
            int gapEnd = row;
            int missing = gapStart - gapEnd;
            if (missing < 2 || missing > .49f * gap) continue;
            while (row > limit && (gray[row * width + col] & 255) < 165) row--;
            if (gapEnd - row < 1.15f * gap || row == limit) continue;
            if (!columns.isEmpty() && (col != columns.get(columns.size() - 1).x() + 1
                    || Math.abs(gapStart - commonStart) > 1 || Math.abs(gapEnd - commonEnd) > 2))
                return null;
            commonStart = gapStart;
            commonEnd = gapEnd;
            tip = Math.min(tip, row + 1);
            columns.add(new PrintedBeamShafts.Column(col, row + 1));
        }
        if (columns.isEmpty() || columns.size() > .3f * gap) return null;
        var shaft = new PrintedBeamShafts.Shaft(columns.get(0).x(),
                columns.get(columns.size() - 1).x(), start, tip, -1, columns);
        return new SplitShaft(shaft, commonStart);
    }

    private static boolean doubleRule(
            byte[] gray, int width, int height, PlayingTechniqueDetector.Staff staff, float edge) {
        float gap = staff.gap();
        for (float inset : new float[] {3, 2.5f, 4, 2}) {
            float[] rails = PrintedDirectionStaff.printedRules(
                    gray, width, height, edge - inset * gap, staff.bottom(), gap);
            if (rails == null) continue;
            var local = new PlayingTechniqueDetector.Staff(
                    rails[0] - 4 * rails[1], rails[0], rails[1], 0, 1);
            if (PrintedCadenceDurations.boundaryColumn(gray, width, local, edge, false, true) >= 0)
                return true;
        }
        return false;
    }

    private static int tripletDirection(
            byte[] gray, int width, int height, float[] x, float[] y, float gap, int start) {
        for (int direction : new int[] {-1, 1}) {
            if (PrintedTupletBeamOwner.connectedHeads(gray, width, height,
                    x[start], y[start], x[start + 1], y[start + 1], gap, direction)
                    && PrintedTupletBeamOwner.connectedHeads(gray, width, height,
                            x[start + 1], y[start + 1], x[start + 2], y[start + 2], gap, direction))
                return direction;
        }
        return 0;
    }

    private static boolean printedCommonTime(
            ScorePageInterpretation score, int barIndex, byte[] labels, byte[] gray,
            int width, int height, PlayingTechniqueDetector.Staff staff) {
        var first = score.measures().get(barIndex);
        for (int i = barIndex - 1; i >= 0; i--) {
            var earlier = score.measures().get(i);
            if (Math.abs(earlier.top() - first.top()) * height > staff.gap()) break;
            first = earlier;
        }
        float left = first.left() * width;
        return CommonTimeMeter.readHeader(
                labels, gray, width, height, Math.round(left), Math.round(staff.top()), staff.gap()) == 4;
    }

    /** All five rules must end together with no sustained continuation. */
    static boolean printedEnd(byte[] gray, int width, int height,
                              MeasureRegion bar, StaffPitchTrack track, float gap) {
        int edge = Math.round(bar.right() * width);
        int inset = Math.round(gap * 2.25f), outside = Math.round(gap * 2);
        int radius = Math.max(1, Math.round(gap * .15f));
        if (edge - inset < 0 || edge + outside >= width) return false;
        int firstEnd = width, lastEnd = -1;
        for (int line = 0; line < 5; line++) {
            int endpoint = -1, run = 0;
            for (int x = edge - inset; x <= edge + outside; x++) {
                float[] frame = track.at(Math.min(x, edge));
                int center = Math.round(frame[0] - line * frame[1]);
                if (center - radius < 0 || center + radius >= height) return false;
                boolean ink = false;
                for (int dy = -radius; dy <= radius; dy++)
                    if ((gray[(center + dy) * width + x] & 255) < 165) {
                        ink = true;
                        break;
                    }
                if (ink) {
                    run++;
                    if (run >= Math.round(gap * 1.4f)) endpoint = x;
                } else run = 0;
                // A continuing rule beyond the shared edge vetoes closure.
                if (x > edge + Math.round(gap * .55f) && run >= Math.max(2, Math.round(gap * .35f)))
                    return false;
            }
            if (Math.abs(endpoint - edge) > gap * .55f) return false;
            firstEnd = Math.min(firstEnd, endpoint);
            lastEnd = Math.max(lastEnd, endpoint);
        }
        return lastEnd - firstEnd <= Math.max(2, Math.round(gap * .28f));
    }
    private static StaffPitchTrack track(byte[] gray, int width, int height,
                                         MeasureRegion bar, PlayingTechniqueDetector.Staff owner) {
        float gap = owner.gap();
        if (gap < 6 || gap > 32) return null;
        int first = Math.round(bar.left() * width), last = Math.round(bar.right() * width);
        int top = Math.max(0, Math.round(owner.top() - gap * 1.5f));
        int bottom = Math.min(height - 1, Math.round(owner.bottom() + gap * 1.5f));
        int radius = Math.round(gap * 5.4f);
        var samples = new ArrayList<float[]>();
        for (int i = 0; i <= 12; i++) {
            int center = first + (last-first)*i/12;
            int left = Math.max(0, center-radius), right = Math.min(width-1,center+radius);
            int[] strength = new int[bottom-top+1];
            for (int y = top; y <= bottom; y++)
                for (int x = left; x <= right; x++)
                    if ((gray[y*width+x]&255) < 165) strength[y-top]++;
            var candidates = RawStaffLineDetector.detectFromStrength(
                    strength, Math.round((right-left+1)*.8f), strength.length);
            for (var candidate : candidates) {
                int base = candidate.bottom()+top;
                if (Math.abs(base-owner.bottom()) > gap*.32f
                        || Math.abs(candidate.gap()-gap) > gap*.06f) continue;
                samples.add(new float[]{center,base,candidate.gap()});
                break;
            }
        }
        return samples.size() < 5 ? null : StaffPitchTrack.fromVerifiedSamples(samples);
    }

    /** Preserve printed secondary rails and short, shaft-rooted beamlets. */
    private static boolean hasSecondary(byte[] gray, int width, int height, float[] xs, float[] ys,
                                        float gap, int direction, int first) {
        if (BoundedSecondaryBeam.proves(gray, width, height,
                Arrays.copyOfRange(xs, first, first+3),
                Arrays.copyOfRange(ys, first, first+3), gap)) return true;
        int center = Math.round(xs[first] + direction * gap * .47f);
        int y = Math.round(ys[first]);
        for (int x = center-Math.round(gap*.24f); x <= center+Math.round(gap*.24f); x++)
            for (int d = Math.round(gap*2); d <= gap*4; d++)
                if (StemOwnedBeamlet.proves(gray,width,height,new int[]{x,y-direction*d,-direction},gap))
                    return true;
        return false;
    }

    static ScoreNoteEvent singleBeam(ScoreNoteEvent n) {
        return beamedValue(n, 1);
    }

    static ScoreNoteEvent beamedValue(ScoreNoteEvent n, int rails) {
        return new ScoreNoteEvent(
                n.measureIndex(),
                n.positionInMeasure(),
                n.staffStep(),
                n.staffIndex(),
                n.staffCount(),
                n.pageY(),
                n.tiedFromPrevious(),
                n.augmentationDots(),
                rails,
                n.writtenAccidental(),
                0,
                n.tupletDivisor(),
                n.followingRestBeats(),
                n.articulations(),
                n.clefBottomDiatonic(),
                n.crossStaffBeam(),
                n.leadingRestBeats(),
                n.compactOpening(),
                n.octaveShift(),
                n.boundaryTies(),
                n.tupletNormalNotes(),
                n.stemDirection(),
                n.kind(),
                n.visualSize(),
                n.harmonicSemitones(),
                n.openStringTouch());
    }
}
