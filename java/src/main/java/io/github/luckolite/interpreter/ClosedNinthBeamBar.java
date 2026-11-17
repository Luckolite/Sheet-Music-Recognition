// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Restores a short first eighth after a new common-time signature in a closed run of ninths. */
final class ClosedNinthBeamBar {
    private ClosedNinthBeamBar() {}

    private static final List<List<Integer>> BRIDGES =
            List.of(List.of(1, 3), List.of(3, 3, 3), List.of(1, 3), List.of(3, 3, 3));
    private static final int[] FIRST = {0, 3, 7, 10};

    private record Owner(int index, ScoreNoteEvent note, float x, float y) {}

    private record Pair(Owner high, Owner low) {
        float x() {
            return (high.x() + low.x()) * .5f;
        }
    }

    static ScorePageInterpretation restore(
            ScorePageInterpretation score,
            byte[] normalized,
            byte[] raw,
            int width,
            int height,
            int page) {
        if (normalized == null
                || raw == null
                || normalized.length != (long) width * height
                || raw.length != normalized.length
                || width <= 0
                || height <= 0) return score;
        var regions = score.measures();
        var notes = new ArrayList<>(score.notes());
        var beams = score.beamSource();
        for (var meter : score.meterChanges()) {
            int index = meter.measureIndex();
            if (meter.numerator() != 4
                    || meter.denominator() != 4
                    || index < 1
                    || index + 1 >= regions.size()
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == index))
                continue;
            var bar = regions.get(index);
            float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
            float span = (bar.right() - bar.left()) * width;
            if (bar.cadenceQuarterBeats() != 0
                    || nominal < 12
                    || nominal > 24
                    || span < 44 * nominal
                    || span > 53 * nominal
                    || bar.top() <= regions.get(index - 1).bottom() + 3 * nominal / height
                    || Math.abs(bar.top() - regions.get(index + 1).top()) * height > nominal * .35f)
                continue;
            var pairs = candidates(score, index, width, height, nominal);
            if (pairs.isEmpty()
                    || !proves(normalized, width, height, bar, pairs, nominal)
                    || !proves(raw, width, height, bar, pairs, nominal)) continue;
            for (int j = 0; j < pairs.size(); j++) {
                int rails = j == 0 || j == 7 ? 1 : 2;
                var pair = pairs.get(j);
                for (var owner : List.of(pair.high(), pair.low()))
                    notes.set(
                            owner.index(),
                            ClosingTripletRhythm.beamedValue(owner.note(), rails)
                                    .withStemDirection(j < 3 ? 1 : -1));
            }
            if (page >= 0) beams = project(beams, pairs, page);
        }
        return notes.equals(score.notes())
                ? score
                : score.withBeamSource(beams)
                        .withNotes(notes, IntStream.range(0, notes.size()).boxed().toList());
    }

    private static List<Pair> candidates(
            ScorePageInterpretation score, int index, int w, int h, float gap) {
        var bar = score.measures().get(index);
        var heads = new ArrayList<Owner>(28);
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            if (note.measureIndex() != index) continue;
            float x = (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * w;
            heads.add(new Owner(i, note, x, note.pageY() * h));
        }
        if (heads.size() != 28) return List.of();
        heads.sort(Comparator.comparingDouble(Owner::x));
        var pairs = new ArrayList<Pair>(14);
        int clef = heads.get(0).note().clefBottomDiatonic();
        if (clef == ScoreNoteEvent.CLEF_UNKNOWN) return List.of();
        for (int i = 0; i < 14; i++) {
            var a = heads.get(2 * i);
            var b = heads.get(2 * i + 1);
            var pair = a.y() < b.y() ? new Pair(a, b) : new Pair(b, a);
            if (b.x() - a.x() > gap * .19f
                    || pair.high().note().staffStep() - pair.low().note().staffStep() != 9
                    || pair.low().y() - pair.high().y() < gap * 4.35f
                    || pair.low().y() - pair.high().y() > gap * 4.80f) return List.of();
            if (i > 0) {
                float distance = pair.x() - pairs.get(pairs.size() - 1).x();
                if (distance < gap * 2.40f || distance > gap * 3.75f) return List.of();
            }
            var up = pair.high().note();
            var down = pair.low().note();
            if (!ordinary(up, clef) || !ordinary(down, clef)) return List.of();
            if (i == 0) {
                if (up.beamCount() != 2
                        || up.stemDirection() != 0
                        || up.unbeamedDurationBeats() != 0
                        || down.beamCount() != 0
                        || down.stemDirection() != 1
                        || down.unbeamedDurationBeats() != 1) return List.of();
            } else {
                int direction = i < 3 ? 1 : -1;
                if (up.stemDirection() != direction
                        || down.stemDirection() != direction
                        || up.unbeamedDurationBeats() != 0
                        || down.unbeamedDurationBeats() != 0
                        || down.beamCount() != (i == 7 ? 1 : 2)
                        || (i == 7
                                ? up.beamCount() != 1
                                : up.beamCount() < 1 || up.beamCount() > 2)) return List.of();
            }
            pairs.add(pair);
        }
        return pairs;
    }

    private static boolean ordinary(ScoreNoteEvent n, int clef) {
        return ClosedSixteenthPairBeforeHalf.writtenHead(n.withStemDirection(1), true)
                && n.clefBottomDiatonic() == clef
                && n.augmentationDots() == 0
                && n.octaveShift() == 0
                && n.harmonicSemitones() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean proves(
            byte[] ink, int w, int h, MeasureRegion bar, List<Pair> pairs, float gap) {
        float x = pairs.get(0).x(), last = pairs.get(pairs.size() - 1).x(), left = bar.left() * w;
        var staff =
                PrintedDirectionStaff.printedRules(
                        ink, w, h, left + gap * .95f, bar.bottom() * h - gap * 2.1f, gap);
        if (staff == null
                || x - left < gap * 3.85f
                || x - left > gap * 5.0f
                || !WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                        ink, w, h, bar, last)) return false;
        int top = Math.round(staff[0] - 4 * staff[1]);
        int start = Math.round(left - gap * .72f), stop = Math.round(x - gap * .85f);
        int bottom = Math.round(staff[0] + gap * .2f);
        if (start < 0 || stop >= w || top < 3 || bottom >= h) return false;
        var meter =
                MeterFontMatcher.readCrop(
                        ink,
                        w,
                        h,
                        new MeterChangeDetector.Crop(start, top - 3, stop, bottom, top, staff[1]));
        if (!meter.text().equals("4/4") || meter.topScore() < .88f || meter.bottomScore() < .88f)
            return false;
        var shafts = new PrintedBeamShafts.Shaft[14];
        var budget = new PrintedBeamShafts.Budget(180_000);
        for (int group = 0; group < FIRST.length; group++) {
            int first = FIRST[group];
            int end = first + BRIDGES.get(group).size();
            int direction = group == 0 ? 1 : -1;
            // Two literal nearby shafts set the rail slope before the weak strokes are sampled.
            var a =
                    stem(
                            ink,
                            w,
                            h,
                            pairs.get(group < 2 ? first + 1 : first),
                            gap,
                            direction,
                            budget);
            var b = stem(ink, w, h, pairs.get(group == 1 ? end - 1 : end), gap, direction, budget);
            if (a == null || b == null || budget.refused() || a.left() >= b.left()) return false;
            float slope = (b.endY() - a.endY()) / (float) (b.left() - a.left());
            if (Math.abs(slope) > .27f) return false;
            for (int slot = first; slot <= end; slot++) {
                var pair = pairs.get(slot);
                float near = pair.x() + gap * .6f * direction;
                int expected = Math.round(a.endY() + slope * (near - a.left()));
                shafts[slot] =
                        OpeningOctaveTriplets.sharedShaft(
                                ink,
                                w,
                                h,
                                gap,
                                pair.x(),
                                direction > 0 ? pair.low().y() : pair.high().y(),
                                direction,
                                expected);
                if (shafts[slot] == null) return false;
                float next =
                        slot + 1 == pairs.size()
                                ? bar.right() * w + gap * .5f
                                : pairs.get(slot + 1).x();
                for (var owner : List.of(pair.high(), pair.low()))
                    if (WrappedTripletDotInk.uniqueCore(ink, w, h, owner.x(), owner.y(), next, gap)
                            != null) return false;
                if (slot == 0) continue;
                int rails =
                        slot == first ? 0 : BRIDGES.get(group).get(slot - first - 1) == 1 ? 1 : 2;
                if (!WrappedEighthTriplets.matchesBeamRails(
                        ink, w, h, shafts[slot - 1], shafts[slot], gap, rails)) return false;
            }
        }
        return true;
    }

    private static PrintedBeamShafts.Shaft stem(
            byte[] ink,
            int w,
            int h,
            Pair pair,
            float gap,
            int direction,
            PrintedBeamShafts.Budget budget) {
        for (var owner :
                direction > 0
                        ? List.of(pair.low(), pair.high())
                        : List.of(pair.high(), pair.low())) {
            var shafts = PrintedBeamShafts.at(ink, w, h, pair.x(), owner.y(), gap, budget);
            if (shafts.size() == 1 && shafts.get(0).direction() == -direction)
                return shafts.get(0);
        }
        return null;
    }

    /** Replace only subgroups already contained in the physically proved primary beam. */
    private static ScoreBeamSource project(ScoreBeamSource source, List<Pair> pairs, int page) {
        var groups = new ArrayList<>(source.groups());
        var origins = new ArrayList<>(source.noteOrigins());
        var detached = new ArrayList<>(source.detachedColumns());
        var used = new HashSet<ScoreBeamSource.Anchor>();
        for (int group = 0; group < FIRST.length; group++) {
            var columns = new ArrayList<ScoreBeamSource.Column>();
            var members = new HashSet<ScoreBeamSource.Anchor>();
            var bridges = BRIDGES.get(group);
            int first = FIRST[group], end = first + bridges.size();
            for (int i = first; i <= end; i++) {
                var pair = pairs.get(i);
                var column = new ArrayList<ScoreBeamSource.Anchor>(2);
                for (var owner : List.of(pair.high(), pair.low())) {
                    var retained = origins.get(owner.index());
                    var anchor =
                            retained.orElseGet(
                                    () -> new ScoreBeamSource.Anchor(page, owner.index()));
                    if (anchor.pageIndex() != page
                            || !used.add(anchor)
                            || java.util.Collections.frequency(origins, Optional.of(anchor))
                                    != (retained.isPresent() ? 1 : 0)) return source;
                    members.add(anchor);
                    column.add(anchor);
                    origins.set(owner.index(), Optional.of(anchor));
                }
                columns.add(new ScoreBeamSource.Column(column));
            }
            var replacements =
                    groups.stream()
                            .filter(
                                    g ->
                                            g.columns().stream()
                                                    .anyMatch(
                                                            c ->
                                                                    c.members().stream()
                                                                            .anyMatch(
                                                                                    members
                                                                                            ::contains)))
                            .toList();
            if (replacements.stream().anyMatch(g -> !matchesSubset(g, columns, bridges)))
                return source;
            var consumed =
                    detached.stream()
                            .filter(c -> c.members().stream().anyMatch(members::contains))
                            .toList();
            if (consumed.stream()
                    .anyMatch(
                            c ->
                                    columns.stream()
                                            .noneMatch(d -> d.members().containsAll(c.members()))))
                return source;
            var firstColumn = columns.get(0).members();
            String id =
                    "printed-beam:"
                            + page
                            + ":"
                            + Math.min(
                                    firstColumn.get(0).noteIndex(), firstColumn.get(1).noteIndex());
            if (groups.stream().anyMatch(g -> g.id().equals(id) && !replacements.contains(g)))
                return source;
            groups.removeAll(replacements);
            detached.removeAll(consumed);
            groups.add(new ScoreBeamSource.Group(id, columns, bridges, List.of()));
        }
        return new ScoreBeamSource(groups, detached, origins);
    }

    private static boolean matchesSubset(
            ScoreBeamSource.Group group,
            List<ScoreBeamSource.Column> columns,
            List<Integer> accents) {
        if (!group.hooks().isEmpty() || group.tupletMark() != null) return false;
        int previous = -1;
        for (var column : group.columns()) {
            int index = previous + 1;
            while (index < columns.size()
                    && !columns.get(index).members().containsAll(column.members())) index++;
            if (index == columns.size() || previous >= 0 && index != previous + 1) return false;
            previous = index;
        }
        int offset = previous - group.bridges().size();
        for (int i = 0; i < group.bridges().size(); i++)
            if ((group.bridges().get(i) & accents.get(offset + i)) != group.bridges().get(i))
                return false;
        return true;
    }
}
