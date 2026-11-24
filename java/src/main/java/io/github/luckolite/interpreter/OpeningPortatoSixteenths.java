// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/** Resolve a borrowed staccato and the written double beams of a closed portato bar. */
final class OpeningPortatoSixteenths {
    private OpeningPortatoSixteenths() {}

    static ScorePageInterpretation restore(ScorePageInterpretation score,
            byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height
                || raw.length != gray.length) return score;
        var bars = score.measures();
        var original = score.notes();
        var beams = score.beamSource();
        List<ScoreNoteEvent> edited = null;
        var changed = new HashSet<Integer>();
        for (var meter : score.meterChanges()) {
            int i = meter.measureIndex();
            if (meter.numerator() != 4 || meter.denominator() != 4 || i <= 0 || i + 1 >= bars.size()
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == i)) continue;
            var bar = bars.get(i);
            var after = bars.get(i + 1);
            if (bars.get(i - 1).bottom() >= bar.top() || Math.abs(after.top() - bar.top()) * height >= 5
                    || bar.cadenceQuarterBeats() != 0) continue;
            var owners = IntStream.range(0, original.size()).filter(n -> original.get(n).measureIndex() == i)
                    .boxed().sorted(Comparator.comparingDouble(n -> original.get(n).positionInMeasure())).toList();
            var row = owners.stream().map(original::get).toList();
            if (!openingShape(row) || !ownsPairedSlurs(score.slurSource(), owners)
                    || !provesOpticalGroups(gray, width, height, bar, after, row)
                    || !provesOpticalGroups(raw, width, height, bar, after, row)) continue;
            var repaired = replaceQuartets(beams, owners);
            if (repaired == null) continue;
            beams = repaired;
            if (edited == null) edited = new ArrayList<>(original);
            int borrowed = owners.get(0);
            edited.set(borrowed, ClosedMonophonicSixteenths.withoutAugmentationDot(original.get(borrowed)));
            changed.add(borrowed);
            for (int owner : owners) {
                if (original.get(owner).stemDirection() == 0) {
                    edited.set(owner, original.get(owner).withStemDirection(1));
                    changed.add(owner);
                }
            }
        }
        return edited == null ? score : score.withNotes(edited, IntStream.range(0, original.size()).boxed().toList())
                .withBeamSource(beams)
                .withClockContextSource(score.clockContextSource().withoutNoteObservations(changed));
    }

    private static boolean openingShape(List<ScoreNoteEvent> row) {
        if (row.size() != 16) return false;
        int fractured = 0;
        for (int i = 0; i < row.size(); i++) {
            var n = row.get(i);
            int stem = i < 12 ? 1 : -1;
            if (n.stemDirection() == 0 && i > 0 && i < 12 && i % 4 == 0) {
                fractured++;
                n = n.withStemDirection(stem);
            }
            if (!ClosedMonophonicSixteenths.regularQuartetHead(n) || n.stemDirection() != stem
                    || n.tupletDivisor() != 1 || n.tupletNormalNotes() != 1 || n.tiedFromPrevious()
                    || n.augmentationDots() != (i == 0 ? 1 : 0)
                    || n.articulations() != (i >= 1 && i <= 4 ? NoteArticulation.STACCATO : 0)) return false;
        }
        return fractured <= 1;
    }

    private static boolean ownsPairedSlurs(ScoreSlurSource source, List<Integer> owners) {
        for (int first : new int[]{1, 3}) {
            var start = source.noteOrigins().get(owners.get(first)).orElse(null);
            var end = source.noteOrigins().get(owners.get(first + 1)).orElse(null);
            if (start == null || end == null || source.spans().stream().noneMatch(s ->
                    s.start().equals(start) && s.end().equals(end)
                            && s.placement() == ScoreSlurSpan.Placement.BELOW)) return false;
        }
        return true;
    }

    static boolean provesOpticalGroups(byte[] ink, int width, int height, MeasureRegion bar,
            MeasureRegion after, List<ScoreNoteEvent> row) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        float cut = (bar.right() + after.left()) * width * .5f;
        float floor = bar.bottom() * height - 2.1f * gap;
        var rules = PrintedDirectionStaff.printedRules(ink, width, height, cut, floor, gap);
        if (rules == null)
            rules = DottedDyadMeasureSeparator.localRails(ink, width, height, Math.round(cut), floor, gap);
        if (rules == null) return false;
        var frame = new PlayingTechniqueDetector.Staff(rules[0] - 4 * rules[1], rules[0], rules[1], 0, 1);
        int edge = PrintedCadenceDurations.boundaryColumn(ink, width, frame, cut, true, false);
        if (edge < 0 || Math.abs(edge - cut) > .30f * gap) return false;
        var lines = new WholeStaffSlurRules.Page(ink, width, height);
        var track = lines.prove(ink, width, height, frame);
        if (track == null || !lines.finish()) return false;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float firstX = left + row.get(0).positionInMeasure() * span;
        float firstY = row.get(0).pageY() * height;
        float nextX = left + row.get(1).positionInMeasure() * span;
        if (WrappedTripletDotInk.uniqueCore(ink, width, height, firstX, firstY, nextX, gap) != null
                || LegacyTripletDotInk.uniqueDotCore(ink, width, height, firstX, firstY, nextX, gap) != null)
            return false;
        var budget = new PrintedBeamShafts.Budget(240_000);
        PrintedBeamShafts.Shaft previous = null;
        float previousX = 0;
        for (int i = 0; i < row.size(); i++) {
            var n = row.get(i);
            float x = left + n.positionInMeasure() * span, y = n.pageY() * height;
            if (i > 0 && (x - previousX < 1.6f * gap || x - previousX > 3.45f * gap)) return false;
            int direction = i < 12 ? -1 : 1;
            var axes = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
            var shaft = axes.size() == 1 && axes.get(0).direction() == direction ? axes.get(0) : null;
            if (shaft == null && axes.isEmpty() && n.stemDirection() == 0)
                shaft = interruptedOpeningShaft(ink, width, height, x, y, gap);
            if (shaft == null && axes.isEmpty())
                shaft = ClosedMonophonicSixteenths.openingStaccatoShaft(ink, width, height, x, y, gap, direction);
            if (shaft == null) return false;
            if (previous != null && (i % 4 != 0
                    ? !WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink, width, height, previous, shaft, gap, track)
                    : WrappedEighthTriplets.matchesBeamRails(ink, width, height, previous, shaft, gap, 1))) return false;
            if (i < 5 && PrintedStaccatoSlurs.looseLowerDot(ink, width, height,
                    new PrintedSlurOwners.GeometryHead(i, x, y, 0, 1), gap) != (i >= 1)) return false;
            previous = shaft;
            previousX = x;
        }
        return !budget.refused() && edge - previousX > gap * .9f;
    }

    private static ScoreBeamSource replaceQuartets(ScoreBeamSource source, List<Integer> owners) {
        var origins = owners.stream().map(source.noteOrigins()::get).toList();
        if (origins.stream().anyMatch(Optional::isEmpty)) return null;
        var anchors = origins.stream().map(Optional::orElseThrow).toList();
        int page = anchors.get(0).pageIndex();
        Set<ScoreBeamSource.Anchor> owned = new HashSet<>(anchors);
        if (owned.size() != 16 || anchors.stream().anyMatch(a -> a.pageIndex() != page)
                || source.detachedColumns().stream().anyMatch(c -> c.members().stream().anyMatch(owned::contains)))
            return null;
        var columns = anchors.stream().map(a -> new ScoreBeamSource.Column(List.of(a))).toList();
        var groups = new ArrayList<>(source.groups());
        var replacement = new ArrayList<ScoreBeamSource.Group>();
        for (int i = 0; i < columns.size(); i += 4) {
            var quartet = columns.subList(i, i + 4);
            var quartetAnchors = anchors.subList(i, i + 4);
            var same = groups.stream().filter(g -> g.columns().stream()
                    .anyMatch(c -> c.members().stream().anyMatch(quartetAnchors::contains))).toList();
            if (same.size() > 1 || same.stream().anyMatch(g -> !quartet.containsAll(g.columns())
                    || !g.hooks().isEmpty() || g.tupletMark() != null)) return null;
            String id = same.isEmpty() ? "portato-beam:" + page + ":" + quartet.get(0).members().get(0).noteIndex()
                    : same.get(0).id();
            groups.removeAll(same);
            replacement.add(new ScoreBeamSource.Group(id, quartet, List.of(3, 3, 3), List.of()));
        }
        // Retain the order of existing beam groups outside the four replaced quartets.
        int at = 0;
        while (at < groups.size() && groups.get(at).columns().get(0).members().get(0).noteIndex()
                < anchors.get(0).noteIndex()) at++;
        groups.addAll(at, replacement);
        return new ScoreBeamSource(groups, source.detachedColumns(), source.noteOrigins());
    }

    private static PrintedBeamShafts.Shaft interruptedOpeningShaft(
            byte[] ink, int width, int height, float x, float y, float gap) {
        int left = Math.round(x + gap * .40f), right = Math.round(x + gap * .68f);
        int start = Math.round(y - gap * .23f), limit = Math.round(gap * 5.6f);
        if (left < 0 || right >= width || start >= height || start - limit < 0) return null;
        var axes = new ArrayList<PrintedBeamShafts.Column>();
        int best = 0;
        for (int col = left; col <= right; col++) {
            int last = -1, hits = 0, longBreaks = 0;
            for (int step = 0; step <= limit; step++) {
                if ((ink[(start - step) * width + col] & 255) < 165) {
                    if (last < 0 && step > gap * .27f) break;
                    if (last >= 0 && step - last > gap * .28f) longBreaks++;
                    last = step;
                    hits++;
                }
                if (step - last > gap * .72f) break;
            }
            if (last < gap * 3.35f || last == limit || longBreaks != 1 || hits < (last + 1) * .78f) continue;
            axes.add(new PrintedBeamShafts.Column(col, start - last));
            best = Math.max(best, last);
        }
        int end = start - best;
        var cols = axes.stream().filter(c -> Math.abs(end - c.endY()) <= gap * .23f).toList();
        if (cols.size() < 2 || cols.get(cols.size() - 1).x() - cols.get(0).x() + 1 != cols.size()) return null;
        return new PrintedBeamShafts.Shaft(cols.get(0).x(), cols.get(cols.size() - 1).x(),
                start, end, -1, cols);
    }

}
