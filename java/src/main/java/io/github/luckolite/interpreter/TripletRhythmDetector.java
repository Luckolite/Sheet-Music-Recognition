// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
// Adapted from Music Sheets: standalone package and platform-independent diagnostics.
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Reads printed triplets and beamed five- or seven-in-four groups from numeral and attack geometry.
 */
final class TripletRhythmDetector {
    private TripletRhythmDetector() {}

    record Rhythm(List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests) {}

    private static final class PrintedStaffs {
        private List<RawStaffLineDetector.StaffLines> lines;

        List<RawStaffLineDetector.StaffLines> detect(byte[] gray, int width, int height) {
            if (lines == null) lines = RawStaffLineDetector.detect(gray, width, height);
            return lines;
        }
    }

    /**
     * A printed rest occupies one rhythmic slot just as a chord attack does. Virtual events are
     * used only for grouping and never returned as sounding notes.
     */
    static Rhythm withRests(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height) {
        return withRests(notes, rests, measures, gray, gray, width, height);
    }

    static Rhythm withRests(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, List<MeasureRegion> measures,
            byte[] gray, byte[] sourceGray, int width, int height) {
        if (rests == null || rests.isEmpty())
            return new Rhythm(apply(notes, measures, gray, width, height), rests);
        var printedStaffs = new PrintedStaffs();
        var aliases = new java.util.HashSet<>(septupletNumeralRests(notes, rests, measures, gray, width, height));
        List<ScoreNoteEvent> slots =
                new ArrayList<>(isolatedPrintedLanes(notes, measures, gray, width, height, printedStaffs));
        aliases.addAll(aboveStaffQuarterRestAliases(slots, rests, measures, sourceGray, width, height));
        List<Integer> restIndices = new ArrayList<>();
        for (int restIndex = 0; restIndex < rests.size(); restIndex++) {
            ScoreRestEvent rest = rests.get(restIndex);
            if (rest.isFullMeasure() || aliases.contains(rest)) continue;
            double value = rest.durationBeats();
            int beams = value == .25 ? 2 : value == .5 ? 1 : value == 1 ? 0 : -1;
            restIndices.add(restIndex);
            // Unsupported or already scaled rest values remain barriers between
            // attack columns; omitting them could join notes across real silence.
            slots.add(
                    new ScoreNoteEvent(
                            rest.measureIndex(),
                            rest.positionInMeasure(),
                            0,
                            rest.staffIndex(),
                            rest.staffCount(),
                            rest.pageY(),
                            false,
                            0,
                            Math.max(0, beams),
                            2,
                            beams <= 0 ? (float) value : 0,
                            beams < 0 ? 2 : 1));
        }
        List<ScoreNoteEvent> marked =
                apply(
                        slots,
                        measures,
                        gray,
                        width,
                        height,
                        notes.size(),
                        new PrintedTupletBeamOwner.LiteralWork(8_000_000),
                        aliases.isEmpty() ? rests : rests.stream().filter(r -> !aliases.contains(r)).toList(),
                        printedStaffs);
        List<ScoreRestEvent> scaled = new ArrayList<>(rests);
        for (int i = 0; i < restIndices.size(); i++) {
            int index = restIndices.get(i);
            ScoreRestEvent rest = rests.get(index);
            if (marked.get(notes.size() + i).tupletDivisor() <= 1) continue;
            scaled.set(
                    index,
                    new ScoreRestEvent(
                            rest.measureIndex(),
                            rest.positionInMeasure(),
                            rest.pageY(),
                            rest.pageHeight(),
                            rest.staffIndex(),
                            rest.staffCount(),
                            rest.durationBeats() * marked.get(notes.size() + i).durationScale()));
        }
        var adjustments = new HashMap<MeasureStaff, List<RestAdjustment>>();
        for (int i = 0; i < rests.size(); i++) {
            ScoreRestEvent rest = rests.get(i);
            double delta = rest.durationBeats()
                    - (aliases.contains(rest) ? 0 : scaled.get(i).durationBeats());
            if (delta == 0) continue;
            var key = new MeasureStaff(rest.measureIndex(), rest.staffIndex(), rest.staffCount());
            adjustments.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new RestAdjustment(rest, delta));
        }

        var originalBars = new HashMap<MeasureStaff, List<ScoreNoteEvent>>();
        if (!adjustments.isEmpty()) {
            for (var original : notes) {
                var key = MeasureStaff.of(original);
                if (adjustments.containsKey(key))
                    originalBars.computeIfAbsent(key, ignored -> new ArrayList<>()).add(original);
            }
        }

        List<ScoreNoteEvent> result = new ArrayList<>(notes.size());
        for (int i = 0; i < notes.size(); i++) {
            ScoreNoteEvent original = notes.get(i), note = marked.get(i);
            var localAdjustments = adjustments.get(MeasureStaff.of(note));
            if (localAdjustments == null) {
                result.add(withAdjustedRests(note, 0, 0));
                continue;
            }
            var localNotes = originalBars.get(MeasureStaff.of(original));
            float position = original.positionInMeasure();
            float next = 1.01f;
            boolean first = true;
            for (var other : localNotes) {
                float otherPosition = other.positionInMeasure();
                if (otherPosition > position + .018f) next = Math.min(next, otherPosition);
                if (otherPosition < position - .018f) first = false;
            }
            boolean independentSustain = ScoreNoteTiming.hasIndependentSustain(original);
            double before = 0, after = 0;
            for (var adjustment : localAdjustments) {
                ScoreRestEvent rest = adjustment.rest();
                float restPosition = rest.positionInMeasure();
                if (restPosition < note.positionInMeasure()
                        && original.leadingRestBeats() + .001 >= rest.durationBeats()
                        && (first || !independentSustain && sustainAt(localNotes, restPosition)))
                    before += adjustment.delta();
                if (restPosition > note.positionInMeasure()
                        && restPosition < next
                        && (!independentSustain || restPosition > note.positionInMeasure() + .018f)
                        && original.followingRestBeats() + .001 >= rest.durationBeats())
                    after += adjustment.delta();
            }
            result.add(withAdjustedRests(note, before, after));
        }
        return new Rhythm(List.copyOf(result), scaled.stream().filter(r -> !aliases.contains(r)).toList());
    }

    private static boolean sustainAt(List<ScoreNoteEvent> notes, float position) {
        for (var note : notes) {
            if (Math.abs(note.positionInMeasure() - position) <= .018f
                    && ScoreNoteTiming.hasIndependentSustain(note)) return true;
        }
        return false;
    }

    private record RestAdjustment(ScoreRestEvent rest, double delta) {}

    private static ScoreNoteEvent withAdjustedRests(ScoreNoteEvent note, double before, double after) {
        float following = (float) Math.max(0, note.followingRestBeats() - after);
        float leading = (float) Math.max(0, note.leadingRestBeats() - before);
        if (Float.compare(following, note.followingRestBeats()) == 0
                && Float.compare(leading, note.leadingRestBeats()) == 0) return note;
        return new ScoreNoteEvent(
                note.measureIndex(),
                note.positionInMeasure(),
                note.staffStep(),
                note.staffIndex(),
                note.staffCount(),
                note.pageY(),
                note.tiedFromPrevious(),
                note.augmentationDots(),
                note.beamCount(),
                note.writtenAccidental(),
                note.unbeamedDurationBeats(),
                note.tupletDivisor(),
                following,
                note.articulations(),
                note.clefBottomDiatonic(),
                note.crossStaffBeam(),
                leading,
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

    /**
     * The original beam and its printed numeral can disqualify a putative quarter
     * rest when its box is blank or surrounds that same numeral above the staff.
     */
    private static java.util.Set<ScoreRestEvent> aboveStaffQuarterRestAliases(
            List<ScoreNoteEvent> slots, List<ScoreRestEvent> rests, List<MeasureRegion> measures,
            byte[] gray, int width, int height) {
        var aliases = new java.util.HashSet<ScoreRestEvent>();
        if (slots.size() < 3 || measures == null || gray == null
                || width < 1 || height < 1 || gray.length != (long) width * height) return aliases;
        for (var rest : rests) {
            int at = rest.measureIndex();
            if (rest.kind() != ScoreRestEvent.Kind.LITERAL || rest.durationBeats() != 1
                    || rest.staffIndex() != 0 || rest.staffCount() != 1 || at < 0 || at >= measures.size())
                continue;
            var region = measures.get(at);
            float gap = (region.bottom() - region.top()) * height / 8;
            float y = rest.pageY() * height;
            float restHeight = rest.pageHeight() * height;
            if (gap < 6 || y > region.top() * height + gap * .4f
                    || restHeight > gap * 3.4f) continue;
            float x = (region.left() + rest.positionInMeasure()
                    * (region.right() - region.left())) * width;
            boolean blank = y <= region.top() * height - gap
                    && lacksDarkRestInk(gray, width, height, x, y, gap, restHeight);
            int beamCount = blank ? 1 : 2;
            var local = slots.stream().filter(n -> n.measureIndex() == at).toList();
            var attacks = onsets(local);
            for (int i = 0; i + 2 < attacks.size(); i++) {
                var group = attacks.subList(i, i + 3);
                var a = group.get(0);
                var b = group.get(1);
                var c = group.get(2);
                if (rest.positionInMeasure() <= a.position() || rest.positionInMeasure() >= c.position()
                        || !completePrintedThree(
                                group, local, local.size(), region, gray, width, height, gap, beamCount))
                    continue;
                var first = local.get(a.indices().get(0));
                var middle = local.get(b.indices().get(0));
                var last = local.get(c.indices().get(0));
                if (first.staffCount() != 1 || first.augmentationDots() != 0
                        || middle.augmentationDots() != 0 || last.augmentationDots() != 0
                        || first.tupletDivisor() != 1 || middle.tupletDivisor() != 1
                        || last.tupletDivisor() != 1) continue;
                float x1 = (region.left() + a.position() * (region.right() - region.left())) * width;
                float x3 = (region.left() + c.position() * (region.right() - region.left())) * width;
                float y1 = Math.min(a.top(), Math.min(b.top(), c.top())) * height;
                float y3 = Math.max(a.bottom(), Math.max(b.bottom(), c.bottom())) * height;
                var numeral = findOwnedNumeral(gray, width, height, x1, x3, y1, y3, gap,
                        true, Float.NaN, Float.NaN, 3, blank, false, first, local, region, measures);
                if (numeral == null || insideOtherSystem(numeral, region, measures, width, height)
                        || !beamOwnsNumeral(numeral, first, local, region, gray, width, height, x1, x3, gap)
                        || !blank && !quarterRestOverlapsTripletNumeral(
                                numeral, middle, x, y, restHeight, height, gap))
                    continue;
                slots.set(slots.indexOf(first), first.withTupletRatio(3, 2));
                slots.set(slots.indexOf(middle), middle.withTupletRatio(3, 2));
                slots.set(slots.indexOf(last), last.withTupletRatio(3, 2));
                aliases.add(rest);
                break;
            }
        }
        return aliases;
    }

    /** The rest box surrounds the beam's numeral and lies entirely above the staff. */
    private static boolean quarterRestOverlapsTripletNumeral(
            Glyph numeral, ScoreNoteEvent middle, float x, float y, float restHeight,
            int height, float gap) {
        float staffTop = middle.pageY() * height + (middle.staffStep() * .5f - 4) * gap;
        float top = y - restHeight * .5f;
        float bottom = y + restHeight * .5f;
        float tolerance = gap * .15f;
        return bottom < staffTop - tolerance
                && x >= numeral.left() - tolerance && x <= numeral.right() + tolerance
                && top < numeral.top() && bottom >= numeral.bottom() - tolerance
                && y <= numeral.top();
    }

    private static boolean lacksDarkRestInk(
            byte[] gray, int width, int height, float x, float y, float gap, float restHeight) {
        int left = Math.max(0, Math.round(x - gap * .75f));
        int right = Math.min(width - 1, Math.round(x + gap * .75f));
        int top = Math.max(0, Math.round(y - restHeight * .5f));
        int bottom = Math.min(height - 1, Math.round(y + restHeight * .5f));
        for (int row = top; row <= bottom; row++)
            for (int col = left; col <= right; col++)
                if ((gray[row * width + col] & 255) <= 125) return false;
        return left <= right && top <= bottom;
    }

    private record SevenOwner(int measure, int staff, int count, float first, float last, Glyph glyph) {}

    /** The same printed numeral cannot also be an eighth rest inside its seven-note beam. */
    private static java.util.Set<ScoreRestEvent> septupletNumeralRests(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, List<MeasureRegion> measures,
            byte[] gray, int width, int height) {
        if (notes.size() < 7 || measures == null || gray == null
                || width <= 0 || height <= 0 || gray.length != (long) width * height)
            return java.util.Set.of();
        var candidates = rests.stream().filter(r -> r.kind() == ScoreRestEvent.Kind.LITERAL
                && r.durationBeats() == .5 && r.measureIndex() >= 0
                && r.measureIndex() < measures.size()).toList();
        if (candidates.isEmpty()) return java.util.Set.of();
        var bars = new java.util.HashSet<Integer>();
        for (var rest : candidates) bars.add(rest.measureIndex());
        var local = notes.stream().filter(note -> bars.contains(note.measureIndex())).toList();
        if (local.size() < 7) return java.util.Set.of();
        var owners = new ArrayList<SevenOwner>();
        beamedTuplets(local, onsets(local), measures, gray, width, height, 7, local.size(), false, owners);
        var aliases = new java.util.HashSet<ScoreRestEvent>();
        for (var rest : candidates) {
            var region = measures.get(rest.measureIndex());
            float x = (region.left() + rest.positionInMeasure() * (region.right() - region.left())) * width;
            float y = rest.pageY() * height;
            for (var owner : owners) {
                if (rest.measureIndex() != owner.measure() || rest.staffIndex() != owner.staff()
                        || rest.staffCount() != owner.count()
                        || rest.positionInMeasure() <= owner.first()
                        || rest.positionInMeasure() >= owner.last()) continue;
                var glyph = owner.glyph();
                if (x >= glyph.left() && x <= glyph.right() && y >= glyph.top() && y <= glyph.bottom())
                    aliases.add(rest);
            }
        }
        return aliases;
    }

    /** A chord contributes one attack column, regardless of how many heads it contains. */
    private record Onset(List<Integer> indices, float position, float top, float bottom) {}

    private record OwnedPrintedThree(
            int measure,
            int staff,
            int count,
            int direction,
            int beams,
            float firstX,
            float firstTop,
            float firstBottom,
            float lastX,
            float lastTop,
            float lastBottom,
            Glyph glyph,
            float distance) {}

    private static List<Onset> onsets(List<ScoreNoteEvent> notes) {
        return onsets(notes, Integer.MAX_VALUE);
    }

    private static final Comparator<ScoreNoteEvent> ONSET_ORDER =
            Comparator.comparingInt(ScoreNoteEvent::measureIndex)
                    .thenComparingInt(ScoreNoteEvent::staffIndex)
                    .thenComparingInt(ScoreNoteEvent::staffCount)
                    .thenComparingDouble(ScoreNoteEvent::positionInMeasure);

    private static List<Integer> sortedOnsetIndices(List<ScoreNoteEvent> notes) {
        for (int i = 1; i < notes.size(); i++) {
            if (ONSET_ORDER.compare(notes.get(i - 1), notes.get(i)) <= 0) continue;
            List<Integer> order = new ArrayList<>(notes.size());
            for (int index = 0; index < notes.size(); index++) order.add(index);
            order.sort((a, b) -> ONSET_ORDER.compare(notes.get(a), notes.get(b)));
            return order;
        }
        return null;
    }

    private static List<Onset> onsets(List<ScoreNoteEvent> notes, int virtualStart) {
        return onsets(notes, virtualStart, sortedOnsetIndices(notes));
    }

    private static List<Onset> onsets(List<ScoreNoteEvent> notes, int virtualStart, List<Integer> order) {
        int count = order == null ? notes.size() : order.size();
        List<Onset> result = new ArrayList<>();
        for (int i = 0; i < count; ) {
            int firstIndex = order == null ? i : order.get(i);
            ScoreNoteEvent first = notes.get(firstIndex);
            List<Integer> chord = null;
            float top = first.pageY(), bottom = top;
            int j = i + 1;
            boolean includesRest = firstIndex >= virtualStart;
            while (j < count) {
                int index = order == null ? j : order.get(j);
                ScoreNoteEvent n = notes.get(index);
                boolean nextRest = index >= virtualStart;
                // A rest beside a displaced head shares its attack column. Sounding-only
                // groups retain their narrower tolerance and cannot skip a different attack.
                float tolerance = includesRest || nextRest ? .018f : .012f;
                if (!sameVoice(first, n)
                        || n.positionInMeasure() - first.positionInMeasure() > tolerance) break;
                includesRest |= nextRest;
                if (chord == null) {
                    chord = new ArrayList<>();
                    chord.add(firstIndex);
                }
                chord.add(index);
                top = Math.min(top, n.pageY());
                bottom = Math.max(bottom, n.pageY());
                j++;
            }
            List<Integer> members = chord == null ? List.of(firstIndex) : List.copyOf(chord);
            result.add(new Onset(members, first.positionInMeasure(), top, bottom));
            i = j;
        }
        return result;
    }

    /** A complete ordinary 4/4 bar can disambiguate a numeral over its final dyads. */
    static List<ScoreNoteEvent> ordinarySixteenthDyadBars(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, List<MeasureRegion> measures,
            List<ScoreMeterChange> meters, byte[] gray, int width, int height) {
        if (meters.isEmpty()) return notes;
        var groups = onsets(notes);
        ArrayList<ScoreNoteEvent> revised = null;
        for (int begin = 0; begin < groups.size();) {
            var first = notes.get(groups.get(begin).indices().get(0));
            int bar = first.measureIndex();
            int end = begin + 1;
            while (end < groups.size() && notes.get(groups.get(end).indices().get(0)).measureIndex() == bar)
                end++;
            if (end - begin == 16 && first.staffCount() == 1 && bar >= 0 && bar < measures.size()
                    && rests.stream().noneMatch(rest -> rest.measureIndex() == bar)
                    && meterAt(meters, bar).filter(m -> m.numerator() == 4 && m.denominator() == 4).isPresent()
                    && completeOrdinaryDyads(notes, groups.subList(begin, end), measures, measures.get(bar),
                            gray, width, height)) {
                if (revised == null) revised = new ArrayList<>(notes);
                for (int i = end - 3; i < end; i++)
                    for (int owner : groups.get(i).indices())
                        revised.set(owner, notes.get(owner).withTupletRatio(1, 1));
            }
            begin = end;
        }
        return revised == null ? notes : revised;
    }

    /** A missing endpoint rail and a borrowed tuplet must agree with the whole printed bar. */
    static List<ScoreNoteEvent> closeClippedDyadQuartets(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, List<MeasureRegion> measures,
            List<ScoreMeterChange> meters, ScoreMeterChange inherited,
            byte[] gray, byte[] raw, int width, int height) {
        return closeClippedDyadQuartets(notes, rests, measures, meters, inherited,
                gray, raw, width, height, null);
    }

    static List<ScoreNoteEvent> closeClippedDyadQuartets(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, List<MeasureRegion> measures,
            List<ScoreMeterChange> meters, ScoreMeterChange inherited,
            byte[] gray, byte[] raw, int width, int height, List<Integer> repairedBars) {
        if (gray == null || raw == null || gray.length != (long) width * height || raw.length != gray.length)
            return notes;
        var groups = onsets(notes);
        ArrayList<ScoreNoteEvent> revised = null;
        for (int begin = 0; begin < groups.size();) {
            int measure = notes.get(groups.get(begin).indices().get(0)).measureIndex();
            int end = begin + 1;
            while (end < groups.size() && notes.get(groups.get(end).indices().get(0)).measureIndex() == measure)
                end++;
            var meter = meterAt(meters, measure).orElse(inherited);
            if (end - begin == 16 && measure >= 0 && measure < measures.size() && meter != null
                    && meter.numerator() == 4 && meter.denominator() == 4
                    && rests.stream().noneMatch(rest -> rest.measureIndex() == measure)) {
                var row = groups.subList(begin, end);
                var bar = measures.get(measure);
                int[] repairs = clippedDyadSlots(notes, row, bar);
                if (repairs != null
                        && provesClippedDyads(notes, row, measures, bar, repairs, gray, width, height)
                        && provesClippedDyads(notes, row, measures, bar, repairs, raw, width, height)) {
                    if (revised == null) revised = new ArrayList<>(notes);
                    if (repairedBars != null) repairedBars.add(measure);
                    for (int slot = repairs[0]; slot < repairs[0] + 3; slot++)
                        for (int owner : row.get(slot).indices())
                            revised.set(owner, notes.get(owner).withTupletRatio(1, 1));
                    if (repairs[1] >= 0) for (int owner : row.get(repairs[1]).indices()) {
                        var n = revised.get(owner);
                        revised.set(owner, n.withBeamAndStem(2, n.stemDirection()));
                    }
                }
            }
            begin = end;
        }
        return revised == null ? notes : List.copyOf(revised);
    }

    static ScorePageInterpretation projectClippedDyadQuartets(
            ScorePageInterpretation score, List<ScoreNoteEvent> proof, List<Integer> repairedBars,
            int page, int width, int height) {
        if (page < 0 || repairedBars.isEmpty()) return score;
        var source = score.beamSource();
        var notes = score.notes();
        for (int measure : repairedBars) {
            var positions = proof.stream().filter(n -> n.measureIndex() == measure)
                    .map(n -> List.of(n.positionInMeasure(), n.pageY())).toList();
            var pitches = new ArrayList<ClosedSixteenthDyads.Head>(32);
            var bar = score.measures().get(measure);
            for (int i = 0; i < notes.size(); i++) {
                var note = notes.get(i);
                if (note.measureIndex() != measure) continue;
                float x = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
                pitches.add(new ClosedSixteenthDyads.Head(i, note, x, note.pageY() * height));
            }
            if (pitches.size() != 32 || !pitches.stream().map(ClosedSixteenthDyads.Head::note)
                    .map(n -> List.of(n.positionInMeasure(), n.pageY())).toList().equals(positions)
                    || pitches.stream().map(ClosedSixteenthDyads.Head::note)
                            .anyMatch(n -> n.beamCount() != 2 || n.tupletDivisor() != 1)) continue;
            var beams = ClosedSixteenthDyads.projectDoubleRails(source, page, pitches);
            if (beams == source)
                beams = ClosedSixteenthDyads.completeProvedDyadQuartets(source, page, pitches);
            source = beams;
        }
        return source == score.beamSource() ? score : score.withBeamSource(source);
    }

    private static int[] clippedDyadSlots(List<ScoreNoteEvent> notes, List<Onset> groups, MeasureRegion bar) {
        if (bar.cadenceQuarterBeats() != 0) return null;
        int triplet = -1, hits = 0, clipped = -1;
        for (int i = 0; i < groups.size(); i++) {
            var pair = groups.get(i).indices();
            if (pair.size() != 2) return null;
            var first = notes.get(pair.get(0));
            if (first.tupletDivisor() == 3) {
                if (triplet < 0) triplet = i;
                if (i != triplet + hits || ++hits > 3) return null;
            } else if (first.tupletDivisor() != 1) return null;
            if (first.beamCount() == 1) {
                if (clipped >= 0 || i % 4 != 3) return null;
                clipped = i;
            } else if (first.beamCount() != 2) return null;
            var stem = notes.get(groups.get(i / 4 * 4).indices().get(0)).stemDirection();
            for (int index : pair) {
                var n = notes.get(index);
                if (n.staffCount() != 1 || n.staffIndex() != 0 || n.tiedFromPrevious()
                        || n.boundaryTies() != 0 || n.kind() != ScoreNoteEvent.Kind.PITCHED
                        || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.crossStaffBeam()
                        || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                        || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                        || n.augmentationDots() != 0 || n.unbeamedDurationBeats() != 0
                        || (n.articulations() & NoteOrnament.GRACE) != 0 || stem == 0
                        || n.stemDirection() != stem || n.beamCount() != first.beamCount()
                        || n.tupletDivisor() != first.tupletDivisor()
                        || n.tupletNormalNotes() != (first.tupletDivisor() == 3 ? 2 : 1)) return null;
            }
        }
        return triplet >= 0 && hits == 3 && triplet % 4 <= 1 && (clipped >= 0 || triplet == 13)
                ? new int[] {triplet, clipped} : null;
    }

    private static boolean provesClippedDyads(List<ScoreNoteEvent> notes, List<Onset> groups,
            List<MeasureRegion> measures, MeasureRegion bar, int[] repair, byte[] ink, int width, int height) {
        int start = repair[0];
        float gap = (bar.bottom() - bar.top()) * height / 8;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float floor = bar.bottom() * height - 2 * gap;
        var first = notes.get(groups.get(start).indices().get(0));
        float x1 = left + groups.get(start).position() * span;
        float x2 = left + groups.get(start + 1).position() * span;
        float x3 = left + groups.get(start + 2).position() * span;
        float top = (float) groups.subList(start, start + 3).stream().mapToDouble(Onset::top).min().orElseThrow() * height;
        float bottom = (float) groups.subList(start, start + 3).stream().mapToDouble(Onset::bottom).max().orElseThrow() * height;
        var digit = findOwnedNumeral(ink, width, height, x1, x3, top, bottom, gap, true,
                Float.NaN, Float.NaN, 3, true, false, first, notes, bar, measures);
        float digitOffset = digit == null ? Float.POSITIVE_INFINITY
                : Math.abs((digit.left() + digit.right()) * .5f - x2);
        boolean requireRightRule = repair[1] < 0;
        if (requireRightRule ? digitOffset > .30f * gap : digitOffset < .63f * gap) return false;
        if (digit != null && bracketArm(ink, width, height, Math.round(x1 - .3f * gap), digit.left() - 2,
                        digit.top(), digit.bottom(), .25f * gap)
                   && bracketArm(ink, width, height, digit.right() + 2, Math.round(x3 + .3f * gap),
                        digit.top(), digit.bottom(), .25f * gap)) return false;
        int rightRule = -1;
        if (requireRightRule) {
            int measure = first.measureIndex();
            if (measure + 1 >= measures.size()) return false;
            var next = measures.get(measure + 1);
            float edge = (bar.right() + next.left()) * width * .5f;
            if (Math.abs(next.top() - bar.top()) * height > .25f * gap
                    || next.left() <= bar.right() || (next.left() - bar.right()) * width > 1.6f * gap)
                return false;
            var rails = DottedDyadMeasureSeparator.localRails(ink, width, height, Math.round(edge), floor, gap);
            if (rails == null) return false;
            var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * rails[1], rails[0], rails[1], 0, 1);
            rightRule = ClosedSixteenthPairBeforeHalf.ownedSeparatorCenter(ink, width, height, staff, edge);
            if (rightRule < 0) return false;
        }
        for (int q = 0; q < groups.size(); q += 4) {
            var heads = new ClosedFourDyadBeam.Head[4];
            for (int j = 0; j < 4; j++) {
                var onset = groups.get(q + j);
                heads[j] = new ClosedFourDyadBeam.Head(left + onset.position() * span,
                        onset.top() * height, onset.bottom() * height);
            }
            int stem = notes.get(groups.get(q).indices().get(0)).stemDirection();
            boolean proved = requireRightRule && q == 12
                    ? ClosedFourDyadBeam.provesBeforeStaffRule(ink, width, height, heads, gap, floor, -stem, rightRule)
                    : ClosedFourDyadBeam.provesAcrossDistinctStaffRules(ink, width, height, heads, gap, floor, -stem);
            if (!proved) return false;
        }
        return true;
    }

    private static boolean completeOrdinaryDyads(List<ScoreNoteEvent> notes, List<Onset> groups,
            List<MeasureRegion> measures, MeasureRegion bar, byte[] gray, int width, int height) {
        if (bar.cadenceQuarterBeats() != 0) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        for (int i = 0; i < groups.size(); i++) {
            var indices = groups.get(i).indices();
            if (indices.size() != 2) return false;
            int ratio = i < 13 ? 1 : 3;
            int stem = notes.get(groups.get(i / 4 * 4).indices().get(0)).stemDirection();
            if (stem == 0) return false;
            for (int index : indices) {
                var note = notes.get(index);
                if (note.staffIndex() != 0 || note.staffCount() != 1 || note.kind() != ScoreNoteEvent.Kind.PITCHED
                        || note.visualSize() != ScoreNoteEvent.VisualSize.FULL || note.beamCount() != 2
                        || note.tupletDivisor() != ratio || note.tupletNormalNotes() != (ratio == 1 ? 1 : 2)
                        || note.augmentationDots() != 0 || note.unbeamedDurationBeats() != 0
                        || note.stemDirection() != stem || note.tiedFromPrevious() || note.boundaryTies() != 0
                        || note.crossStaffBeam() || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                        || note.harmonicSemitones() != 0 || note.openStringTouch() != null
                        || (note.articulations() & NoteOrnament.GRACE) != 0) return false;
            }
        }
        float x1 = left + groups.get(13).position() * span;
        float x2 = left + groups.get(14).position() * span;
        float x3 = left + groups.get(15).position() * span;
        float y1 = (float) groups.subList(13, 16).stream().mapToDouble(Onset::top).min().orElseThrow() * height;
        float y2 = (float) groups.subList(13, 16).stream().mapToDouble(Onset::bottom).max().orElseThrow() * height;
        var first = notes.get(groups.get(13).indices().get(0));
        var digit = findOwnedNumeral(gray, width, height, x1, x3, y1, y2, gap, true,
                Float.NaN, Float.NaN, 3, true, false, first, notes, bar, measures);
        if (digit == null || Math.abs((digit.left() + digit.right()) * .5f - x2) > gap * .30f
                || bracketArm(gray, width, height, Math.round(x1 - gap * .3f), digit.left() - 2,
                            digit.top(), digit.bottom(), gap * .25f)
                        && bracketArm(gray, width, height, digit.right() + 2, Math.round(x3 + gap * .3f),
                            digit.top(), digit.bottom(), gap * .25f)) return false;
        for (int start = 0; start < groups.size(); start += 4) {
            var heads = new ClosedFourDyadBeam.Head[4];
            for (int j = 0; j < heads.length; j++) {
                var onset = groups.get(start + j);
                heads[j] = new ClosedFourDyadBeam.Head(left + onset.position() * span,
                        onset.top() * height, onset.bottom() * height);
            }
            int stem = notes.get(groups.get(start).indices().get(0)).stemDirection();
            if (!ClosedFourDyadBeam.proves(gray, width, height, heads, gap, -stem)) return false;
        }
        return true;
    }

    private static boolean sameVoice(ScoreNoteEvent a, ScoreNoteEvent b) {
        return a.measureIndex() == b.measureIndex()
                && a.staffIndex() == b.staffIndex()
                && a.staffCount() == b.staffCount();
    }

    /**
     * Keep independent held voices out of a moving chord's rhythmic group. The three attack columns
     * themselves remain consecutive: an intervening different-value attack must not be skipped to
     * manufacture a triplet.
     */
    private static Onset matching(Onset onset, List<ScoreNoteEvent> notes, ScoreNoteEvent first) {
        return matching(onset, notes, first, false);
    }

    private static Onset matching(
            Onset onset, List<ScoreNoteEvent> notes, ScoreNoteEvent first, boolean allowDotted) {
        double value = ScoreNoteTiming.writtenDurationBeats(first);
        if (!Double.isFinite(value) || value <= 0 || value > 1) return null;
        List<Integer> members = new ArrayList<>();
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (int index : onset.indices()) {
            ScoreNoteEvent n = notes.get(index);
            double written = ScoreNoteTiming.writtenDurationBeats(n);
            if (!Double.isFinite(written)
                    || !sameVoice(first, n)
                    || n.beamCount() != first.beamCount()
                    || n.augmentationDots() != (allowDotted ? first.augmentationDots() : 0)
                    || n.tupletDivisor() != 1
                    || (n.articulations() & NoteOrnament.GRACE) != 0
                    || Math.abs(written - value) > .001) continue;
            members.add(index);
            top = Math.min(top, n.pageY());
            bottom = Math.max(bottom, n.pageY());
        }
        return members.isEmpty()
                ? null
                : new Onset(List.copyOf(members), onset.position(), top, bottom);
    }

    private static List<List<Onset>> triples(
            Onset a, Onset b, Onset c, List<ScoreNoteEvent> notes, boolean beamed) {
        List<List<Onset>> result = new ArrayList<>();
        float ab = b.position() - a.position(), bc = c.position() - b.position();
        if (ab <= .012f || bc <= .012f || (beamed && Math.max(ab, bc) > Math.min(ab, bc) * 1.5f))
            return result;
        for (int index : a.indices()) {
            ScoreNoteEvent first = notes.get(index);
            if (beamed && first.beamCount() < 1) continue;
            boolean seen = false;
            for (List<Onset> group : result)
                if (group.get(0).indices().contains(index)) {
                    seen = true;
                    break;
                }
            if (seen) continue;
            Onset aa = matching(a, notes, first),
                    bb = matching(b, notes, first),
                    cc = matching(c, notes, first);
            if (aa != null && bb != null && cc != null) result.add(List.of(aa, bb, cc));
        }
        return result;
    }

    static List<ScoreNoteEvent> apply(
            List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height) {
        var printedStaffs = new PrintedStaffs();
        return apply(
                isolatedPrintedLanes(notes, measures, gray, width, height, printedStaffs),
                measures,
                gray,
                width,
                height,
                Integer.MAX_VALUE,
                new PrintedTupletBeamOwner.LiteralWork(8_000_000),
                List.of(),
                printedStaffs);
    }

    /**
     * Cheap candidate filter only: two horizontal arms separated by original paper. Full exact
     * printed-group/curve/glyph proof is still required before any ratio changes.
     */
    static boolean hasJoinedBracketCandidate(
            byte[] gray, int width, int height, float x1, float x3, float headTop, float gap) {
        if (gray == null
                || width < 1
                || height < 1
                || gray.length != (long) width * height
                || !Float.isFinite(gap)
                || gap < 4
                || !Float.isFinite(x1)
                || !Float.isFinite(x3)
                || !Float.isFinite(headTop)
                || x1 >= x3) return false;
        float middle = (x1 + x3) * .5f;
        int l = Math.max(0, Math.round(x1 - gap * .3f)),
                lr = Math.min(width - 1, Math.round(middle - gap * .65f));
        int rl = Math.max(0, Math.round(middle + gap * .65f)),
                r = Math.min(width - 1, Math.round(x3 + gap * .3f));
        int top = Math.max(0, Math.round(headTop - gap * 7)),
                bottom = Math.min(height - 1, Math.round(headTop - gap));
        if (lr - l < gap || r - rl < gap) return false;
        for (int y = top; y <= bottom; y++) {
            int left = 0, right = 0;
            boolean gapPaper = false;
            for (int x = l; x <= lr; x++) if ((gray[y * width + x] & 255) <= 165) left++;
            if (left < (lr - l + 1) * .60f) continue;
            for (int x = rl; x <= r; x++) if ((gray[y * width + x] & 255) <= 165) right++;
            if (right < (r - rl + 1) * .60f) continue;
            for (int x = lr + 1; x < rl; x++)
                if ((gray[y * width + x] & 255) == 255) {
                    gapPaper = true;
                    break;
                }
            if (gapPaper) return true;
        }
        return false;
    }

    private record MeasureStaff(int measure, int staff, int staffCount) {
        static MeasureStaff of(ScoreNoteEvent note) {
            return new MeasureStaff(note.measureIndex(), note.staffIndex(), note.staffCount());
        }
    }

    private record StemLane(int staff, int staffCount, int direction) {}

    private static int firstMeasureAfter(List<ScoreNoteEvent> lane, int measure) {
        int left = 0, right = lane.size();
        while (left < right) {
            int middle = (left + right) >>> 1;
            if (lane.get(middle).measureIndex() <= measure) left = middle + 1;
            else right = middle;
        }
        return left;
    }

    /** Resolve a connected printed voice before interleaved opposing attacks. */
    private static List<ScoreNoteEvent> isolatedPrintedLanes(
            List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height,
            PrintedStaffs printedStaffs) {
        if (notes == null || measures == null || gray == null || gray.length != width * height)
            return notes;
        var result = new ArrayList<>(notes);
        var bars = new LinkedHashMap<MeasureStaff, List<ScoreNoteEvent>>();
        for (var note : notes) {
            int measure = note.measureIndex();
            if (measure < 0 || measure >= measures.size()) continue;
            var key = MeasureStaff.of(note);
            bars.computeIfAbsent(key, ignored -> new ArrayList<>()).add(note);
        }
        var lanes = new HashMap<StemLane, List<ScoreNoteEvent>>();
        IdentityHashMap<ScoreNoteEvent, List<Integer>> noteIndices = null;
        for (var bar : bars.values()) {
            int up = 0, down = 0;
            boolean unknown = false;
            for (var note : bar) {
                switch (note.stemDirection()) {
                    case 1 -> up++;
                    case -1 -> down++;
                    case 0 -> unknown = true;
                }
                if (unknown) break;
            }
            if (unknown || up < 2 || down < 2) continue;

            var first = bar.get(0);
            var region = measures.get(first.measureIndex());
            float fallbackGap =
                    Math.max(
                            4,
                            (region.bottom() - region.top()) * height / (8 * first.staffCount()));
            float gap = printedRestTupletGap(
                    printedStaffs.detect(gray, width, height), region, first, height, fallbackGap);
            int measure = first.measureIndex();
            for (int direction = -1; direction <= 1; direction += 2) {
                var key = new StemLane(first.staffIndex(), first.staffCount(), direction);
                var fullLane = lanes.computeIfAbsent(key, laneKey ->
                        notes.stream()
                                .filter(n -> n.staffIndex() == laneKey.staff()
                                        && n.staffCount() == laneKey.staffCount()
                                        && n.stemDirection() == laneKey.direction())
                                .sorted(Comparator.comparingInt(ScoreNoteEvent::measureIndex)
                                        .thenComparingDouble(ScoreNoteEvent::positionInMeasure))
                                .toList());
                int end = firstMeasureAfter(fullLane, measure);
                if (end < 3) continue;
                // Earlier measures provide context for the selected measure.
                var lane = fullLane.subList(0, end);
                var marked = apply(lane, measures, gray, width, height, Integer.MAX_VALUE, null, List.of());
                var owned = new ArrayList<Integer>();
                int start = firstMeasureAfter(lane, measure - 1);
                for (int i = start; i < lane.size(); i++)
                    if (lane.get(i).tupletDivisor() == 1
                            && marked.get(i).tupletDivisor() == 3) owned.add(i);
                var attackGroups = new ArrayList<List<Integer>>();
                for (int index : owned) {
                    if (attackGroups.isEmpty()
                            || lane.get(index).positionInMeasure()
                                            - lane.get(
                                                            attackGroups
                                                                    .get(attackGroups.size() - 1)
                                                                    .get(0))
                                                    .positionInMeasure()
                                    > .018f) attackGroups.add(new ArrayList<>());
                    attackGroups.get(attackGroups.size() - 1).add(index);
                }
                if (attackGroups.size() != 3) continue;
                boolean connected = true;
                for (int i = 1; i < attackGroups.size() && connected; i++) {
                    boolean pairConnected = false;
                    pairs:
                    for (int left : attackGroups.get(i - 1))
                        for (int right : attackGroups.get(i)) {
                            var a = lane.get(left);
                            var b = lane.get(right);
                            if (a.beamCount() > 0
                                    && a.beamCount() == b.beamCount()
                                    && PrintedTupletBeamOwner.connectedHeads(
                                            gray,
                                            width,
                                            height,
                                            (region.left()
                                                            + a.positionInMeasure()
                                                                    * (region.right()
                                                                            - region.left()))
                                                    * width,
                                            a.pageY() * height,
                                            (region.left()
                                                            + b.positionInMeasure()
                                                                    * (region.right()
                                                                            - region.left()))
                                                    * width,
                                            b.pageY() * height,
                                            gap,
                                            direction)) {
                                pairConnected = true;
                                break pairs;
                            }
                        }
                    connected = pairConnected;
                }
                if (!connected) continue;
                if (noteIndices == null) {
                    noteIndices = new IdentityHashMap<>();
                    for (int i = 0; i < notes.size(); i++)
                        noteIndices.computeIfAbsent(notes.get(i), ignored -> new ArrayList<>()).add(i);
                }
                for (int index : owned)
                    for (int originalIndex : noteIndices.get(lane.get(index)))
                        result.set(originalIndex, marked.get(index));
            }
        }
        return result;
    }

    private static List<ScoreNoteEvent> apply(
            List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height,
            int virtualStart,
            PrintedTupletBeamOwner.LiteralWork literalWork,
            List<ScoreRestEvent> literalRests) {
        return apply(notes, measures, gray, width, height, virtualStart, literalWork, literalRests,
                new PrintedStaffs());
    }

    private static List<ScoreNoteEvent> apply(
            List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height,
            int virtualStart,
            PrintedTupletBeamOwner.LiteralWork literalWork,
            List<ScoreRestEvent> literalRests,
            PrintedStaffs printedStaffs) {
        if (notes == null
                || notes.size() < 2
                || measures == null
                || gray == null
                || width < 1
                || height < 1
                || gray.length != width * height) return notes;
        // Ratio updates preserve the indices and geometry used by the attack groups.
        var onsetOrder = sortedOnsetIndices(notes);
        List<Onset> soundingGroups = onsets(notes, Integer.MAX_VALUE, onsetOrder);
        // A complete printed seven-note group owns its attacks before a smaller candidate.
        List<ScoreNoteEvent> result =
                new ArrayList<>(beamedTuplets(
                        notes, soundingGroups, measures, gray, width, height, 7, virtualStart, false));
        List<Glyph> clearThrees = new ArrayList<>();
        List<OwnedPrintedThree> ownedPrintedThrees = new ArrayList<>();
        List<Onset> groups = virtualStart >= notes.size()
                ? soundingGroups : onsets(notes, virtualStart, onsetOrder);
        for (int i = 0; i + 2 < groups.size(); i++) {
            boolean marked = false;
            for (List<Onset> group :
                    triples(groups.get(i), groups.get(i + 1), groups.get(i + 2), result, false)) {
                Onset a = group.get(0), b = group.get(1), c = group.get(2);
                ScoreNoteEvent first = result.get(a.indices().get(0));
                if (first.measureIndex() < 0 || first.measureIndex() >= measures.size()) continue;
                MeasureRegion region = measures.get(first.measureIndex());
                float gap =
                        Math.max(
                                4,
                                (region.bottom() - region.top())
                                        * height
                                        / (8 * first.staffCount()));
                float x1 =
                        (region.left() + a.position() * (region.right() - region.left())) * width;
                float x3 =
                        (region.left() + c.position() * (region.right() - region.left())) * width;
                if (x3 - x1 < gap * 2 || x3 - x1 > gap * 18) continue;
                float y1 = Math.min(a.top(), Math.min(b.top(), c.top())) * height;
                float y2 = Math.max(a.bottom(), Math.max(b.bottom(), c.bottom())) * height;
                final List<ScoreNoteEvent> numeralGroupNotes = result;
                var literal =
                        a.indices().size() == 1
                                        && b.indices().size() == 1
                                        && c.indices().size() == 1
                                ? PrintedTupletBeamOwner.LiteralProof.create(
                                        gray,
                                        width,
                                        height,
                                        result,
                                        Math.min(virtualStart, result.size()),
                                        measures,
                                        a.indices().get(0),
                                        b.indices().get(0),
                                        c.indices().get(0),
                                        gap,
                                        literalWork,
                                        literalRests)
                                : null;
                Glyph numeral =
                        findOwnedNumeral(
                                gray,
                                width,
                                height,
                                x1,
                                x3,
                                y1,
                                y2,
                                gap,
                                first.beamCount() > 0,
                                Float.NaN,
                                Float.NaN,
                                3,
                                true,
                                false,
                                first,
                                result.subList(0, Math.min(virtualStart, result.size())),
                                region,
                                measures,
                                glyph ->
                                        completePrintedThree(
                                                group,
                                                numeralGroupNotes,
                                                virtualStart,
                                                region,
                                                gray,
                                                width,
                                                height,
                                                gap),
                                literal);
                if (numeral == null && virtualStart < result.size()) {
                    final List<ScoreNoteEvent> restGroupNotes = result;
                    final float restGap = printedRestTupletGap(
                            printedStaffs.detect(gray, width, height), region, first, height, gap);
                    numeral =
                            findContrastedNumeral(
                                    gray,
                                    width,
                                    height,
                                    x1,
                                    x3,
                                    y1,
                                    y2,
                                    restGap,
                                    first.beamCount() > 0,
                                    Float.NaN,
                                    Float.NaN,
                                    3,
                                    false,
                                    glyph ->
                                            bracketedRestBeamOwns(
                                                    glyph,
                                                    List.of(a, b, c),
                                                    restGroupNotes,
                                                    virtualStart,
                                                    region,
                                                    measures,
                                                    gray,
                                                    width,
                                                    height,
                                                    x1,
                                                    x3,
                                                    restGap));
                }
                if (numeral == null
                        && a.indices().size() == 1
                        && b.indices().size() == 1
                        && c.indices().size() == 1
                        && (first.beamCount() == 2 || first.beamCount() == 3)
                        && result.get(b.indices().get(0)).beamCount() == first.beamCount()
                        && result.get(c.indices().get(0)).beamCount() == first.beamCount()) {
                    float x2 =
                            (region.left() + b.position() * (region.right() - region.left()))
                                    * width;
                    if (BoundedSecondaryBeam.proves(
                            gray,
                            width,
                            height,
                            new float[] {x1, x2, x3},
                            new float[] {a.top() * height, b.top() * height, c.top() * height},
                            gap))
                        numeral =
                                findOwnedNumeral(
                                        gray,
                                        width,
                                        height,
                                        x1,
                                        x3,
                                        y1,
                                        y2,
                                        gap,
                                        true,
                                        Float.NaN,
                                        Float.NaN,
                                        3,
                                        true,
                                        true,
                                        first,
                                        result.subList(0, Math.min(virtualStart, result.size())),
                                        region,
                                        measures);
                }
                final List<ScoreNoteEvent> ownershipNotes =
                        result.subList(0, Math.min(virtualStart, result.size()));
                boolean recoveredJoined = false;
                boolean recoveredCurveJoined = false;
                boolean sharedJoinedDirection = true;
                for (Onset onset : group)
                    for (int index : onset.indices())
                        if (index >= virtualStart
                                || result.get(index).stemDirection() != first.stemDirection())
                            sharedJoinedDirection = false;
                if (numeral == null
                        && first.beamCount() > 0
                        && first.stemDirection() != 0
                        && sharedJoinedDirection) {
                    for (Glyph reference : clearThrees) {
                        int[] found =
                                RepeatedTupletNumeral.findJoined(
                                        gray,
                                        width,
                                        height,
                                        new int[] {
                                            reference.left(),
                                            reference.top(),
                                            reference.right(),
                                            reference.bottom()
                                        },
                                        x1,
                                        x3,
                                        y1,
                                        y2,
                                        gap,
                                        box -> {
                                            Glyph glyph = new Glyph(box[0], box[1], box[2], box[3]);
                                            return !insideOtherSystem(
                                                            glyph, region, measures, width, height)
                                                    && joinedBeamOwnsNumeral(
                                                            glyph,
                                                            group,
                                                            ownershipNotes,
                                                            first,
                                                            ownershipNotes,
                                                            region,
                                                            gray,
                                                            width,
                                                            height,
                                                            x1,
                                                            x3,
                                                            gap,
                                                            ownedPrintedThrees);
                                        });
                        if (found != null) {
                            numeral = new Glyph(found[0], found[1], found[2], found[3]);
                            recoveredJoined = true;
                            break;
                        }
                    }
                }
                if (numeral == null
                        && first.beamCount() > 0
                        && first.stemDirection() != 0
                        && sharedJoinedDirection
                        && a.indices().size() == 1
                        && b.indices().size() == 1
                        && c.indices().size() == 1
                        && hasJoinedBracketCandidate(gray, width, height, x1, x3, y1, gap)) {
                    int ia = a.indices().get(0), ib = b.indices().get(0), ic = c.indices().get(0);
                    var joined =
                            JoinedTupletNumeral.inspect(
                                    gray,
                                    width,
                                    height,
                                    ownershipNotes,
                                    measures,
                                    ia,
                                    ib,
                                    ic,
                                    8_000_000L);
                    if (joined.status() == JoinedTupletNumeral.Status.SUPPORTED) {
                        var printed = joined.glyph();
                        numeral =
                                new Glyph(
                                        printed.left(),
                                        printed.top(),
                                        printed.right(),
                                        printed.bottom());
                        // This is a joined glyph, not a detached template for unrelated groups.
                        // Existing system/fingering/beam-break and rhythmic-group guards below
                        // remain authoritative; the helper changes no note or raw pixel.
                        recoveredJoined = true;
                        recoveredCurveJoined = true;
                    }
                }
                if (numeral == null || insideOtherSystem(numeral, region, measures, width, height))
                    continue;
                if (!recoveredJoined) {
                    boolean represented = false;
                    for (Glyph known : clearThrees)
                        if (Math.abs(
                                                known.right()
                                                        - known.left()
                                                        - numeral.right()
                                                        + numeral.left())
                                        <= gap * .1f
                                && Math.abs(
                                                known.bottom()
                                                        - known.top()
                                                        - numeral.bottom()
                                                        + numeral.top())
                                        <= gap * .1f) represented = true;
                    if (!represented && clearThrees.size() < 4) clearThrees.add(numeral);
                }
                // A finger number must not regroup attacks across two separate beams.
                // A real tuplet bracket remains authoritative across beam breaks.
                float x2 =
                        (region.left() + b.position() * (region.right() - region.left())) * width;
                float ab = b.position() - a.position(), bc = c.position() - b.position();
                if (Math.max(ab, bc) > Math.min(ab, bc) * 1.5f) {
                    var middle = result.get(b.indices().get(0));
                    var last = result.get(c.indices().get(0));
                    boolean sharedDirection = true;
                    for (var onset : List.of(a, b, c))
                        for (int index : onset.indices())
                            if (result.get(index).stemDirection() != first.stemDirection())
                                sharedDirection = false;
                    if (!sharedDirection
                            || first.stemDirection() == 0
                            || middle.stemDirection() != first.stemDirection()
                            || last.stemDirection() != first.stemDirection()
                            || !beamOwnsNumeral(
                                    numeral,
                                    first,
                                    result.subList(0, Math.min(virtualStart, result.size())),
                                    region,
                                    gray,
                                    width,
                                    height,
                                    x1,
                                    x3,
                                    gap)
                            || !PrintedTupletBeamOwner.connectedHeads(
                                    gray,
                                    width,
                                    height,
                                    x1,
                                    first.pageY() * height,
                                    x2,
                                    middle.pageY() * height,
                                    gap,
                                    first.stemDirection())
                            || !PrintedTupletBeamOwner.connectedHeads(
                                    gray,
                                    width,
                                    height,
                                    x2,
                                    middle.pageY() * height,
                                    x3,
                                    last.pageY() * height,
                                    gap,
                                    first.stemDirection())) continue;
                }
                boolean bracket =
                        bracketArm(
                                        gray,
                                        width,
                                        height,
                                        Math.round(x1 - gap * .3f),
                                        numeral.left() - 2,
                                        numeral.top(),
                                        numeral.bottom(),
                                        gap * .25f)
                                && bracketArm(
                                        gray,
                                        width,
                                        height,
                                        numeral.right() + 2,
                                        Math.round(x3 + gap * .3f),
                                        numeral.top(),
                                        numeral.bottom(),
                                        gap * .25f);
                if (bracket
                        && first.staffCount() == 1 && first.beamCount() == 2 && first.stemDirection() == -1
                        && a.indices().size() == 1 && b.indices().size() == 1 && c.indices().size() == 1
                        && i + 3 < groups.size()
                        && ruledFingeringBracket(gray, width, height, gap, region, numeral,
                                x1, x2, x3, a, b, c, groups.get(i + 3), result, virtualStart, printedStaffs))
                    continue;
                if (!bracket && first.staffCount() == 1 && first.beamCount() == 2
                        && first.stemDirection() == 1
                        && Math.abs((numeral.left() + numeral.right()) * .5f - x2) <= gap * .23f
                        && (numeral.top() + numeral.bottom()) * .5f - b.top() * height >= gap * 2.25f
                        && (numeral.top() + numeral.bottom()) * .5f - b.top() * height <= gap * 3f
                        && fingeringAtOpposedBeamBoundary(gray, width, height, gap, region,
                                numeral, result, groups, i, virtualStart, first)) continue;
                // A shared numeral can fit two consecutive windows under one primary beam.
                // Prefer the window independently bounded by the secondary beam, while
                // preserving explicit brackets and groups without a proved alternative.
                if (!bracket
                        && first.beamCount() == 2
                        && a.indices().size() == 1
                        && b.indices().size() == 1
                        && c.indices().size() == 1
                        && a.indices().get(0) < virtualStart
                        && b.indices().get(0) < virtualStart
                        && c.indices().get(0) < virtualStart
                        && i + 3 < groups.size()
                        && result.get(b.indices().get(0)).beamCount() == 2
                        && result.get(c.indices().get(0)).beamCount() == 2) {
                    Onset fourth = matching(groups.get(i + 3), result, first);
                    if (fourth != null
                            && fourth.indices().size() == 1
                            && fourth.indices().get(0) < virtualStart) {
                        ScoreNoteEvent extra = result.get(fourth.indices().get(0));
                        if (extra.measureIndex() == first.measureIndex()
                                && extra.beamCount() == 2
                                && extra.tupletDivisor() == 1
                                && extra.augmentationDots() == 0
                                && (extra.articulations() & NoteOrnament.GRACE) == 0
                                && extra.stemDirection() == first.stemDirection()) {
                            float x4 =
                                    (region.left()
                                                    + fourth.position()
                                                            * (region.right() - region.left()))
                                            * width;
                            if (!BoundedSecondaryBeam.proves(
                                            gray,
                                            width,
                                            height,
                                            new float[] {x1, x2, x3},
                                            new float[] {
                                                a.top() * height, b.top() * height, c.top() * height
                                            },
                                            gap)
                                    && BoundedSecondaryBeam.proves(
                                            gray,
                                            width,
                                            height,
                                            new float[] {x2, x3, x4},
                                            new float[] {
                                                b.top() * height,
                                                c.top() * height,
                                                fourth.top() * height
                                            },
                                            gap)) {
                                Glyph alternative =
                                        findOwnedNumeral(
                                                gray,
                                                width,
                                                height,
                                                x2,
                                                x4,
                                                Math.min(b.top(), Math.min(c.top(), fourth.top()))
                                                        * height,
                                                Math.max(
                                                                b.bottom(),
                                                                Math.max(
                                                                        c.bottom(),
                                                                        fourth.bottom()))
                                                        * height,
                                                gap,
                                                true,
                                                Float.NaN,
                                                Float.NaN,
                                                3,
                                                true,
                                                false,
                                                result.get(b.indices().get(0)),
                                                ownershipNotes,
                                                region,
                                                measures);
                                if (numeral.equals(alternative)) continue;
                            }
                        }
                    }
                }
                if (first.beamCount() > 0
                        && (!bracket
                                || first.staffCount() == 1
                                        && first.beamCount() == 2
                                        && first.stemDirection() == -1
                                        && a.indices().size() == 1
                                        && b.indices().size() == 1
                                        && c.indices().size() == 1
                                        && staffRuleBracket(
                                                gray,
                                                width,
                                                height,
                                                region,
                                                numeral,
                                                x1,
                                                x2,
                                                x3,
                                                gap,
                                                printedStaffs))
                        && (SeparateBeamGroups.between(
                                        gray,
                                        width,
                                        height,
                                        x1,
                                        a.top() * height,
                                        x2,
                                        b.top() * height,
                                        gap)
                                || SeparateBeamGroups.between(
                                        gray,
                                        width,
                                        height,
                                        x2,
                                        b.top() * height,
                                        x3,
                                        c.top() * height,
                                        gap))) continue;
                // Finger numbers can sit under a four-note beam, centered on its last
                // three notes. Require another finger numeral and the larger beam before
                // rejecting the apparent triplet; an explicit bracket always wins.
                if (!bracket
                        && first.beamCount() > 0
                        && a.indices().size() == 1
                        && b.indices().size() == 1
                        && c.indices().size() == 1) {
                    var middleHead = result.get(b.indices().get(0));
                    var lastHead = result.get(c.indices().get(0));
                    float numeralX = (numeral.left() + numeral.right()) * .5f;
                    float numeralY = (numeral.top() + numeral.bottom()) * .5f;
                    int stem = first.stemDirection();
                    boolean centeredOnHead = first.beamCount() == 2 && stem != 0
                            && middleHead.stemDirection() == stem && lastHead.stemDirection() == stem
                            && Math.abs(numeralX - x2) <= gap * .22f
                            && stem * (middleHead.pageY() * height - numeralY) < -gap;
                    boolean alignedOnBeamSide =
                            first.beamCount() == 2
                                    && middleHead.beamCount() == 2
                                    && lastHead.beamCount() == 2
                                    && stem != 0
                                    && middleHead.stemDirection() == stem
                                    && lastHead.stemDirection() == stem
                                    && Math.abs(numeralX - x2) <= gap * .46f
                                    && stem * (middleHead.pageY() * height - numeralY) > gap * 2.4f;
                    boolean pairedClosingFinger = first.beamCount() == 2 && stem == -1
                            && middleHead.stemDirection() == stem && middleHead.beamCount() == 2
                            && lastHead.stemDirection() == stem && lastHead.beamCount() == 2
                            && Math.abs(numeralX - x2) <= gap * .80f && numeralY > y2 + gap * 2.4f
                            && x1 - region.left() * width < gap * 1.60f
                            && pairedStaffJoinedFingering(gray, width, height, gap,
                                    numeral, measures, first.measureIndex());
                    boolean fingering = false;
                    for (int adjacent : new int[] {i - 1, i + 3}) {
                        if (adjacent < 0 || adjacent >= groups.size()) continue;
                        Onset fourth = matching(groups.get(adjacent), result, first);
                        if (fourth == null || fourth.indices().size() != 1) continue;
                        var extra = result.get(fourth.indices().get(0));
                        if (extra.measureIndex() != first.measureIndex()) continue;
                        float xx =
                                (region.left()
                                                + fourth.position()
                                                        * (region.right() - region.left()))
                                        * width;
                        float spacing = adjacent < i ? x1 - xx : xx - x3;
                        if (spacing < (x3 - x1) * .30f || spacing > (x3 - x1) * .75f) continue;
                        float leftX = adjacent < i ? xx : x1,
                                leftY = (adjacent < i ? fourth.top() : a.top()) * height;
                        float rightX = adjacent < i ? x3 : xx,
                                rightY = (adjacent < i ? c.top() : fourth.top()) * height;
                        int side = numeral.top() > y2 ? -1 : 1;
                        if (SeparateBeamGroups.connected(
                                                gray, width, height, leftX, leftY, rightX, rightY,
                                                gap, side)
                                        && hasNearbyFour(gray, width, height, numeral, gap)
                                || centeredOnHead
                                        && extra.beamCount() == 2
                                        && extra.stemDirection() == stem
                                        && extra.tupletDivisor() == 1
                                        && (extra.articulations() & NoteOrnament.GRACE) == 0
                                        && (SeparateBeamGroups.connected(
                                                        gray, width, height, leftX, leftY, rightX,
                                                        rightY, gap, -stem)
                                                || fingerSequence(
                                                        gray,
                                                        width,
                                                        height,
                                                        numeral,
                                                        gap,
                                                        stem,
                                                        stem,
                                                        x1,
                                                        a.top() * height,
                                                        x2,
                                                        b.top() * height,
                                                        x3,
                                                        c.top() * height,
                                                        xx,
                                                        fourth.top() * height))
                                || pairedClosingFinger && adjacent == i + 3
                                        && extra.beamCount() == 2 && extra.stemDirection() == stem
                                        && extra.tupletDivisor() == 1 && extra.augmentationDots() == 0
                                        && pairedFingeringRails(gray, width, height, gap,
                                                new float[] {x1, x2, x3, xx},
                                                new float[] {a.top() * height, b.top() * height,
                                                        c.top() * height, fourth.top() * height})
                                || alignedOnBeamSide && stem == 1 && adjacent == i + 3
                                        && extra.beamCount() == 2 && extra.stemDirection() == 1
                                        && extra.tupletDivisor() == 1 && extra.augmentationDots() == 0
                                        && (extra.articulations() & NoteOrnament.GRACE) == 0
                                        && pairedFingersAroundOpeningBeam(gray, width, height, numeral, gap,
                                                new float[] {x1, x2, x3, xx},
                                                new float[] {a.top() * height, b.top() * height,
                                                        c.top() * height, fourth.top() * height})
                                || alignedOnBeamSide && stem == -1
                                        && extra.beamCount() == 2 && extra.stemDirection() == -1
                                        && extra.tupletDivisor() == 1 && extra.augmentationDots() == 0
                                        && (extra.articulations() & NoteOrnament.GRACE) == 0
                                        && lowerFingerRowOnFourBeams(gray, width, height, numeral, gap,
                                                x1, a.top() * height, x2, b.top() * height,
                                                x3, c.top() * height, xx, fourth.top() * height)
                                || alignedOnBeamSide
                                        && extra.beamCount() == 2
                                        && extra.augmentationDots() == 0
                                        && extra.stemDirection() == stem
                                        && extra.tupletDivisor() == 1
                                        && (extra.articulations() & NoteOrnament.GRACE) == 0
                                        && (fingerSequence(
                                                        gray, width, height, numeral, gap, stem, -stem,
                                                        x1, a.top() * height, x2, b.top() * height,
                                                        x3, c.top() * height, xx, fourth.top() * height)
                                                || openingFingerParenthesis(gray, width, height,
                                                        numeral.left(), numeral.top(), numeral.bottom(), gap)
                                                        && SeparateBeamGroups.connected(gray, width, height,
                                                                leftX, leftY, rightX, rightY, gap, -stem))) {
                            fingering = true;
                            break;
                        }
                    }
                    if (fingering) continue;
                }
                if (!bracket && !recoveredJoined && first.staffCount() == 1
                        && first.beamCount() == 2 && first.stemDirection() == -1
                        && Math.abs((numeral.left() + numeral.right()) * .5f - x2) < .34f * gap
                        && fingersBesideClosedDoubleBeam(gray, width, height, region, gap,
                                numeral, groups, result, i, virtualStart)) continue;
                // Vertically stacked small numbers assign fingers to chord tones.
                // They do not turn the surrounding three chord attacks into a tuplet.
                if (a.indices().size() > 1
                        && b.indices().size() > 1
                        && c.indices().size() > 1
                        && hasStackedFingering(gray, width, height, numeral, gap)) continue;
                for (Onset onset : List.of(a, b, c))
                    for (int index : onset.indices()) {
                        ScoreNoteEvent n = result.get(index);
                        result.set(index, n.withTupletRatio(3, 2));
                    }
                if (recoveredJoined && !recoveredCurveJoined) {
                    var last = result.get(c.indices().get(0));
                    float ownedDistance =
                            PrintedTupletBeamOwner.joinedDistance(
                                    gray,
                                    width,
                                    height,
                                    x1,
                                    first.pageY() * height,
                                    x3,
                                    last.pageY() * height,
                                    gap,
                                    first.stemDirection(),
                                    numeral.left(),
                                    numeral.top(),
                                    numeral.right(),
                                    numeral.bottom());
                    if (Float.isFinite(ownedDistance))
                        ownedPrintedThrees.add(
                                new OwnedPrintedThree(
                                        first.measureIndex(),
                                        first.staffIndex(),
                                        first.staffCount(),
                                        first.stemDirection(),
                                        first.beamCount(),
                                        x1,
                                        a.top() * height,
                                        a.bottom() * height,
                                        x3,
                                        c.top() * height,
                                        c.bottom() * height,
                                        numeral,
                                        ownedDistance));
                }
                marked = true;
            }
            if (marked) i += 2;
        }
        result = mixedPrintedThrees(result, measures, gray, width, height, virtualStart);
        result = mixedBracketPairs(result, soundingGroups, measures, gray, width, height);
        result = mixedBeamedTuplets(result, soundingGroups, measures, gray, width, height, 5, virtualStart);
        result = mixedBeamedTuplets(result, soundingGroups, measures, gray, width, height, 7, virtualStart);
        result = boundedQuintuplets(result, soundingGroups, measures, gray, width, height, virtualStart);
        for (int divisor : new int[] {7, 5, 6})
            result = beamedTuplets(
                    result, soundingGroups, measures, gray, width, height, divisor, virtualStart, false);
        return beamedTuplets(result, soundingGroups, measures, gray, width, height, 4, virtualStart, true);
    }

    /** A short secondary beamlet changes the member value, not the primary beam's ownership. */
    private static List<ScoreNoteEvent> mixedPrintedThrees(
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures, byte[] gray,
            int width, int height, int virtualStart) {
        var result = new ArrayList<>(notes);
        var groups = onsets(notes, virtualStart);
        var physical = result.subList(0, Math.min(virtualStart, result.size()));
        for (int i = 0; i + 2 < groups.size(); i++) {
            var a = groups.get(i);
            var b = groups.get(i + 1);
            var c = groups.get(i + 2);
            if (a.indices().size() != 1 || b.indices().size() != 1 || c.indices().size() != 1)
                continue;
            int ai = a.indices().get(0), bi = b.indices().get(0), ci = c.indices().get(0);
            if (ai >= virtualStart || bi >= virtualStart || ci >= virtualStart) continue;
            var first = result.get(ai);
            var middle = result.get(bi);
            var last = result.get(ci);
            if (!sameVoice(first, middle) || !sameVoice(first, last)
                    || first.measureIndex() < 0 || first.measureIndex() >= measures.size()
                    || first.beamCount() != 1 || middle.beamCount() != 2 || last.beamCount() != 1
                    || first.stemDirection() == 0 || middle.stemDirection() != first.stemDirection()
                    || last.stemDirection() != first.stemDirection()) continue;
            if (List.of(first, middle, last).stream().anyMatch(note ->
                    note.kind() != ScoreNoteEvent.Kind.PITCHED || note.tupletDivisor() != 1
                            || note.augmentationDots() != 0
                            || note.crossStaffBeam() || (note.articulations() & NoteOrnament.GRACE) != 0))
                continue;
            var bar = measures.get(first.measureIndex());
            float gap = Math.max(4, (bar.bottom() - bar.top()) * height / (8 * first.staffCount()));
            float x1 = (bar.left() + a.position() * (bar.right() - bar.left())) * width;
            float x2 = (bar.left() + b.position() * (bar.right() - bar.left())) * width;
            float x3 = (bar.left() + c.position() * (bar.right() - bar.left())) * width;
            if (x3 - x1 < 2 * gap || x3 - x1 > 18 * gap || x2 - x1 < gap || x3 - x2 < gap)
                continue;
            if (!PrintedTupletBeamOwner.connectedHeads(gray, width, height, x1, a.top() * height,
                            x2, b.top() * height, gap, first.stemDirection())
                    || !PrintedTupletBeamOwner.connectedHeads(gray, width, height, x2, b.top() * height,
                            x3, c.top() * height, gap, first.stemDirection())) continue;
            float y1 = Math.min(a.top(), Math.min(b.top(), c.top())) * height;
            float y2 = Math.max(a.bottom(), Math.max(b.bottom(), c.bottom())) * height;
            var numeral = findOwnedNumeral(gray, width, height, x1, x3, y1, y2, gap,
                    true, Float.NaN, Float.NaN, 3, true, false, first, physical, bar,
                    measures, glyph -> true);
            if (numeral == null || !beamOwnsNumeral(
                    numeral, first, physical, bar, gray, width, height, x1, x3, gap)) continue;
            result.set(ai, first.withTupletRatio(3, 2));
            result.set(bi, middle.withTupletRatio(3, 2));
            result.set(ci, last.withTupletRatio(3, 2));
        }
        return result;
    }

    /** Recheck printed ratios after faint-page OCR and final note ownership have settled. */
    static ScorePageInterpretation recoverSourceTriplets(
            ScorePageInterpretation score, byte[] gray, int width, int height) {
        return recoverSourceTriplets(score, gray, gray, width, height);
    }

    static ScorePageInterpretation recoverSourceTriplets(
            ScorePageInterpretation score, byte[] decoderGray, byte[] gray, int width, int height) {
        var notes = score.notes();
        var measures = score.measures();
        var result = new ArrayList<>(notes);
        // The engraved beam can pass under a grace pair without taking its rhythm.
        var order = sortedOnsetIndices(notes);
        if (notes.stream().anyMatch(note -> (note.articulations() & NoteOrnament.GRACE) != 0)) {
            var metricalOrder = new ArrayList<Integer>();
            for (int i = 0; i < notes.size(); i++) {
                int index = order == null ? i : order.get(i);
                if ((notes.get(index).articulations() & NoteOrnament.GRACE) == 0)
                    metricalOrder.add(index);
            }
            order = metricalOrder;
        }
        var groups = onsets(notes, Integer.MAX_VALUE, order);
        boolean completedPartialBeams = recoverPartialSourceBeams(
                score, result, groups, decoderGray, gray, width, height);
        var withRest = new java.util.BitSet();
        for (var rest : score.rests()) withRest.set(rest.measureIndex());
        var withPrintedThree = new java.util.BitSet();
        for (var note : notes)
            if (note.tupletDivisor() == 3 && note.tupletNormalNotes() == 2)
                withPrintedThree.set(note.measureIndex());
        boolean changed = completedPartialBeams;
        for (int i = 0; i + 1 < groups.size(); i++) {
            var a = groups.get(i);
            var b = groups.get(i + 1);
            if (a.indices().size() != 1 || b.indices().size() != 1) continue;
            int ia = a.indices().get(0), ib = b.indices().get(0);
            var first = result.get(ia);
            var second = result.get(ib);
            if (!eligibleSourceTriplet(first) || !eligibleSourceTriplet(second)
                    || first.staffCount() != 1 || !sameVoice(first, second)
                    || first.stemDirection() == 0 || first.stemDirection() != second.stemDirection()) continue;
            int m = first.measureIndex();
            var bar = measures.get(m);
            float gap = Math.max(4, (bar.bottom() - bar.top()) * height / 8);
            float x1 = sourceX(bar, width, a);
            float x2 = sourceX(bar, width, b);
            double d1 = ScoreNoteTiming.writtenDurationBeats(first);
            double d2 = ScoreNoteTiming.writtenDurationBeats(second);
            if (!withRest.get(m) && (d1 == 1 && d2 == .5 || d1 == .5 && d2 == 1)
                    && x2 - x1 >= gap * 2 && x2 - x1 <= gap * 7) {
                var glyph = hookedMixedPair(gray, width, height, x1, x2,
                        Math.min(a.top(), b.top()) * height, Math.max(a.bottom(), b.bottom()) * height, gap);
                if (glyph != null && !insideOtherSystem(glyph, bar, measures, width, height)) {
                    result.set(ia, first.withTupletRatio(3, 2));
                    result.set(ib, second.withTupletRatio(3, 2));
                    changed = true;
                    continue;
                }
            }
            if (i + 2 >= groups.size()) continue;
            var c = groups.get(i + 2);
            if (c.indices().size() != 1) continue;
            int ic = c.indices().get(0);
            var third = result.get(ic);
            if (!eligibleSourceTriplet(third) || !sameVoice(first, third)
                    || first.stemDirection() != third.stemDirection()) continue;
            float x3 = sourceX(bar, width, c);
            if (x3 - x1 < gap * 2 || x3 - x1 > gap * 10) continue;
            float y1 = Math.min(a.top(), Math.min(b.top(), c.top())) * height;
            float y3 = Math.max(a.bottom(), Math.max(b.bottom(), c.bottom())) * height;
            Glyph numeral = null;
            double d3 = ScoreNoteTiming.writtenDurationBeats(third);
            if (!withRest.get(m) && d1 == 1 && d2 == 1 && d3 == 1) {
                numeral = findPrintedThree(gray, width, height, x1, x3, y1, y3, gap,
                        false, Float.NaN, Float.NaN);
            } else if (withPrintedThree.get(m) && d1 == .5 && d2 == .5 && d3 == .5
                    && completePrintedThree(groups.subList(i, i + 3), result, result.size(),
                            bar, gray, width, height, gap)
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == m
                            && r.positionInMeasure() > a.position() && r.positionInMeasure() < c.position())) {
                numeral = findOwnedNumeral(gray, width, height, x1, x3, y1, y3, gap,
                        true, Float.NaN, Float.NaN, 3, false, false, first, notes, bar, measures);
                if (numeral != null && !beamOwnsNumeral(
                        numeral, first, notes, bar, gray, width, height, x1, x3, gap)) numeral = null;

                if (numeral == null)
                    numeral = repeatNeighborThree(decoderGray, gray, width, height,
                            groups, i, notes, bar, gap);
            }
            if (numeral == null || insideOtherSystem(numeral, bar, measures, width, height)) continue;
            result.set(ia, first.withTupletRatio(3, 2));
            result.set(ib, second.withTupletRatio(3, 2));
            result.set(ic, third.withTupletRatio(3, 2));
            changed = true;
        }
        return changed ? score.withNotes(result, java.util.stream.IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource()) : score;
    }

    /**
     * A neighboring read numeral can resolve a staff-rule overlap for an independently closed beam.
     */
    private static Glyph repeatNeighborThree(
            byte[] decoderGray, byte[] sourceGray, int width, int height, List<Onset> groups,
            int start, List<ScoreNoteEvent> notes, MeasureRegion bar,
            float gap) {
        if (decoderGray == sourceGray) return null;
        var a = groups.get(start);
        var b = groups.get(start + 1);
        var c = groups.get(start + 2);
        var first = notes.get(a.indices().get(0));
        float x1 = sourceX(bar, width, a), x3 = sourceX(bar, width, c);
        float top = Math.min(a.top(), Math.min(b.top(), c.top())) * height;
        float bottom = Math.max(a.bottom(), Math.max(b.bottom(), c.bottom())) * height;
        for (int next = start + 3; next <= start + 4 && next + 2 < groups.size(); next++) {
            var observed = groups.subList(next, next + 3);
            boolean matches = true;
            for (var attack : observed) {
                var n = notes.get(attack.indices().get(0));
                if (!sameVoice(n, first) || n.stemDirection() != first.stemDirection()
                        || n.tupletDivisor() != 3 || n.tupletNormalNotes() != 2
                        || n.augmentationDots() != 0) matches = false;
            }
            if (!matches || !completePrintedThree(
                    observed, notes, notes.size(), bar, sourceGray, width, height, gap)) continue;
            var refFirst = observed.get(0);
            var refMiddle = observed.get(1);
            var refLast = observed.get(2);
            float refX1 = sourceX(bar, width, refFirst), refX3 = sourceX(bar, width, refLast);
            float refTop = Math.min(refFirst.top(), Math.min(refMiddle.top(), refLast.top())) * height;
            float refBottom = Math.max(refFirst.bottom(), Math.max(refMiddle.bottom(), refLast.bottom())) * height;
            if (Math.abs(top - refTop) > gap || Math.abs((refX3 - refX1) - (x3 - x1)) > gap) continue;
            var refNote = notes.get(refFirst.indices().get(0));
            var reference = findPrintedThree(decoderGray, width, height, refX1, refX3, refTop, refBottom, gap, true, Float.NaN, Float.NaN);
            if (reference == null || !beamOwnsNumeral(reference, refNote, notes, bar, decoderGray,
                    width, height, refX1, refX3, gap)) continue;
            int[] ref = {reference.left(), reference.top(), reference.right(), reference.bottom()};
            float dx = (x1 + x3 - refX1 - refX3) * .5f;
            var match = RepeatedTupletNumeral.findJoined(decoderGray, width, height, ref, x1, x3,
                    top, bottom, gap, box -> {
                        if (Math.abs(box[0] - ref[0] - dx) > gap * .55f
                                || Math.abs(box[1] - ref[1] - (top - refTop)) > gap * .55f) return false;
                        var glyph = new Glyph(box[0], box[1], box[2], box[3]);
                        return beamOwnsNumeral(glyph, first, notes, bar, decoderGray,
                                width, height, x1, x3, gap);
                    });
            if (match != null) return new Glyph(match[0], match[1], match[2], match[3]);
        }
        return null;
    }

    private static boolean eligibleSourceTriplet(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.tupletDivisor() == 1
                && note.augmentationDots() == 0 && !note.crossStaffBeam()
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static float sourceX(MeasureRegion bar, int width, Onset onset) {
        return (bar.left() + onset.position() * (bar.right() - bar.left())) * width;
    }

    /**
     * Preserve one rhythm within a finite printed three-head eighth beam when
     * the numeral was assigned to only part of that group on a multistaff system.
     */
    private static boolean recoverPartialSourceBeams(
            ScorePageInterpretation score, List<ScoreNoteEvent> notes, List<Onset> groups,
            byte[] decoderGray, byte[] gray, int width, int height) {
        List<RawStaffLineDetector.StaffLines> sourceStaffs = null;
        boolean changed = false;
        // Work from right to left so a proved following figure can supply the
        // printed shape when a staff rule touches the preceding numeral.
        for (int i = groups.size() - 3; i >= 0; i--) {
            var a = groups.get(i);
            var b = groups.get(i + 1);
            var c = groups.get(i + 2);
            if (a.indices().size() != 1 || b.indices().size() != 1 || c.indices().size() != 1) continue;
            int ai = a.indices().get(0), bi = b.indices().get(0), ci = c.indices().get(0);
            var first = notes.get(ai);
            var middle = notes.get(bi);
            var last = notes.get(ci);
            int measure = first.measureIndex();
            if (first.staffCount() < 2 || measure < 0 || measure >= score.measures().size()
                    || !sameVoice(first, middle) || !sameVoice(first, last)
                    || !eligiblePartialBeamHead(first) || !eligiblePartialBeamHead(middle)
                    || !eligiblePartialBeamHead(last)) continue;
            int marked = (first.tupletDivisor() == 3 ? 1 : 0)
                    + (middle.tupletDivisor() == 3 ? 1 : 0) + (last.tupletDivisor() == 3 ? 1 : 0);
            if (marked != 1 && marked != 2) continue;
            if (score.rests().stream().anyMatch(rest -> rest.measureIndex() == measure
                    && rest.staffIndex() == first.staffIndex() && rest.staffCount() == first.staffCount()
                    && rest.positionInMeasure() > a.position() && rest.positionInMeasure() < c.position()))
                continue;

            var bar = score.measures().get(measure);
            if (sourceStaffs == null) sourceStaffs = RawStaffLineDetector.detect(gray, width, height);
            var localStaffs = sourceStaffs.stream()
                    .filter(staff -> staff.center() >= bar.top() * height
                            && staff.center() <= bar.bottom() * height).toList();
            if (localStaffs.size() != first.staffCount()
                    || first.staffIndex() < 0 || first.staffIndex() >= localStaffs.size()) continue;
            float gap = Math.round(localStaffs.get(first.staffIndex()).gap());
            float x1 = sourceX(bar, width, a), x3 = sourceX(bar, width, c);
            if (x3 - x1 < gap * 2 || x3 - x1 > gap * 9) continue;
            var group = groups.subList(i, i + 3);
            if (!completePrintedThree(group, notes, notes.size(), bar, gray, width, height, gap)
                    || connectsOutsidePartialBeam(groups, notes, i - 1, first,
                            bar, gray, width, height, gap)
                    || connectsOutsidePartialBeam(groups, notes, i + 3, last,
                            bar, gray, width, height, gap)) continue;

            float top = Math.min(a.top(), Math.min(b.top(), c.top())) * height;
            float bottom = Math.max(a.bottom(), Math.max(b.bottom(), c.bottom())) * height;
            Glyph mark = findOwnedNumeral(gray, width, height, x1, x3, top, bottom, gap,
                    true, Float.NaN, Float.NaN, 3, true, false, first, notes, bar, score.measures());
            if (mark != null && !beamOwnsNumeral(mark, first, notes, bar, gray, width, height, x1, x3, gap))
                mark = null;
            if (mark == null) {
                mark = repeatNeighborThree(decoderGray, gray, width, height, groups, i, notes, bar, gap);
                if (mark == null || repeatNeighborThree(gray, decoderGray, width, height,
                        groups, i, notes, bar, gap) == null) continue;
            }
            if (insideOtherSystem(mark, bar, score.measures(), width, height)) continue;
            notes.set(ai, first.withTupletRatio(3, 2));
            notes.set(bi, middle.withTupletRatio(3, 2));
            notes.set(ci, last.withTupletRatio(3, 2));
            changed = true;
        }
        return changed;
    }

    private static boolean eligiblePartialBeamHead(ScoreNoteEvent note) {
        return (eligibleSourceTriplet(note) || note.tupletDivisor() == 3 && note.tupletNormalNotes() == 2)
                && note.kind() == ScoreNoteEvent.Kind.PITCHED && note.beamCount() == 1
                && note.augmentationDots() == 0 && !note.crossStaffBeam()
                && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean connectsOutsidePartialBeam(
            List<Onset> groups, List<ScoreNoteEvent> notes, int outside, ScoreNoteEvent edge,
            MeasureRegion bar, byte[] gray, int width, int height, float gap) {
        if (outside < 0 || outside >= groups.size()) return false;
        for (int index : groups.get(outside).indices()) {
            var n = notes.get(index);
            if (!sameVoice(n, edge) || n.stemDirection() != edge.stemDirection()) continue;
            float x = (bar.left() + n.positionInMeasure() * (bar.right() - bar.left())) * width;
            float edgeX = (bar.left() + edge.positionInMeasure() * (bar.right() - bar.left())) * width;
            if (x < edgeX ? PrintedTupletBeamOwner.connectedHeads(
                            gray, width, height, x, n.pageY() * height, edgeX, edge.pageY() * height,
                            gap, edge.stemDirection())
                    : PrintedTupletBeamOwner.connectedHeads(
                            gray, width, height, edgeX, edge.pageY() * height, x, n.pageY() * height,
                            gap, edge.stemDirection())) return true;
        }
        return false;
    }

    private static Glyph hookedMixedPair(
            byte[] gray, int width, int height, float x1, float x2, float y1, float y2, float gap) {
        var crop = TupletNumeralInk.crop(gray, width, height, x1, x2, y1, y2, gap);
        if (crop == null) return null;
        Glyph previous = null;
        int previousThreshold = Integer.MIN_VALUE;
        for (int level : new int[] {165, 150, 140, 135, 130, 120}) {
            int threshold = crop.effectiveThreshold(level);
            if (threshold == previousThreshold) continue;
            previousThreshold = threshold;
            var window = crop.window(level, true);
            if (window == null) continue;
            int left = window.left(), top = window.top();
            Glyph candidate = findPrintedNumeral(window.pixels(), window.width(), window.height(),
                    x1 - left, x2 - left, y1 - top, y2 - top, gap, true, Float.NaN, Float.NaN,
                    3, true, found -> {
                        int l = found.left() + left, r = found.right() + left;
                        int t = found.top() + top, b = found.bottom() + top;
                        return connectedBracketHook(gray, width, height, x1 - gap * .3f, l - 2, t, b, gap)
                                && connectedBracketHook(gray, width, height, x2 + gap * .3f, r + 2, t, b, gap);
                    });
            if (candidate == null) continue;
            var found = new Glyph(candidate.left() + left, candidate.top() + top,
                    candidate.right() + left, candidate.bottom() + top);
            if (!TupletNumeralInk.hasGlyphContrast(gray, width, height,
                    found.left(), found.top(), found.right(), found.bottom(), gap)) continue;
            if (previous != null
                    && Math.abs(previous.left() - found.left()) <= gap * .25f
                    && Math.abs(previous.right() - found.right()) <= gap * .25f
                    && Math.abs(previous.top() - found.top()) <= gap * .25f
                    && Math.abs(previous.bottom() - found.bottom()) <= gap * .25f) return found;
            previous = found;
        }
        return null;
    }

    private static List<ScoreNoteEvent> mixedBracketPairs(
            List<ScoreNoteEvent> notes,
            List<Onset> groups,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height) {
        List<ScoreNoteEvent> result = new ArrayList<>(notes);
        for (int i = 0; i + 1 < groups.size(); i++) {
            Onset a = groups.get(i), b = groups.get(i + 1);
            if (a.indices().size() != 1 || b.indices().size() != 1) continue;
            ScoreNoteEvent first = result.get(a.indices().get(0)),
                    last = result.get(b.indices().get(0));
            if (!sameVoice(first, last)
                    || first.measureIndex() < 0
                    || first.measureIndex() >= measures.size()
                    || first.augmentationDots() != 0
                    || last.augmentationDots() != 0
                    || first.tupletDivisor() != 1
                    || last.tupletDivisor() != 1
                    || ((first.articulations() | last.articulations()) & NoteOrnament.GRACE) != 0)
                continue;
            double d1 = ScoreNoteTiming.writtenDurationBeats(first),
                    d2 = ScoreNoteTiming.writtenDurationBeats(last);
            if (d1 <= 0
                    || d2 <= 0
                    || Math.max(d1, d2) > 2
                    || Math.abs(Math.max(d1, d2) / Math.min(d1, d2) - 2) > .001) continue;
            MeasureRegion region = measures.get(first.measureIndex());
            float gap =
                    Math.max(
                            4,
                            (region.bottom() - region.top()) * height / (8 * first.staffCount()));
            float x1 = (region.left() + a.position() * (region.right() - region.left())) * width;
            float x2 = (region.left() + b.position() * (region.right() - region.left())) * width;
            if (x2 - x1 < gap * 2 || x2 - x1 > gap * 18) continue;
            Glyph numeral =
                    findPrintedThree(
                            gray,
                            width,
                            height,
                            x1,
                            x2,
                            Math.min(a.top(), b.top()) * height,
                            Math.max(a.bottom(), b.bottom()) * height,
                            gap,
                            false,
                            Float.NaN,
                            Float.NaN);
            if (numeral == null || insideOtherSystem(numeral, region, measures, width, height))
                continue;
            for (int index : new int[] {a.indices().get(0), b.indices().get(0)}) {
                ScoreNoteEvent n = result.get(index);
                result.set(index, n.withTupletRatio(3, 2));
            }
            i++;
        }
        return result;
    }

    /**
     * Close attacks may belong to separate printed beam groups; an ink gap alone is insufficient.
     */
    private static boolean oppositeSingleStem(Onset left, Onset right, List<ScoreNoteEvent> notes) {
        if (left.indices().size() != 1 || right.indices().size() != 1) return false;
        var a = notes.get(left.indices().get(0));
        var b = notes.get(right.indices().get(0));
        return sameVoice(a, b) && a.stemDirection() != 0 && b.stemDirection() == -a.stemDirection()
                && !a.crossStaffBeam() && !b.crossStaffBeam();
    }

    private static boolean separateTupletBoundary(
            Onset left,
            Onset right,
            MeasureRegion bar,
            byte[] gray,
            int width,
            int height,
            float gap) {
        return SeparateBeamGroups.between(
                gray,
                width,
                height,
                (bar.left() + left.position() * (bar.right() - bar.left())) * width,
                left.top() * height,
                (bar.left() + right.position() * (bar.right() - bar.left())) * width,
                right.top() * height,
                gap);
    }

    private static Optional<ScoreMeterChange> meterAt(
            List<ScoreMeterChange> meters, int measure) {
        ScoreMeterChange selected = null;
        boolean conflict = false;
        for (var meter : meters) {
            if (meter.measureIndex() < 0 || meter.measureIndex() > measure) continue;
            if (selected == null || meter.measureIndex() > selected.measureIndex()) {
                selected = meter;
                conflict = false;
            } else if (meter.measureIndex() == selected.measureIndex() && !meter.equals(selected)) {
                conflict = true;
            }
        }
        return conflict ? Optional.empty() : Optional.ofNullable(selected);
    }

    /** Capture owned eighth-note pairs and resolve them when the local compound meter closes the bar. */
    static List<ScoreNoteEvent> neutralPrintedPairs(
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures,
            List<ScoreMeterChange> meters, byte[] gray, int width, int height, boolean openingPickup) {
        if (gray == null || gray.length != (long) width * height || notes.isEmpty()) return notes;
        var result = new ArrayList<>(notes);
        var groups = onsets(notes);
        for (int i = 0; i + 1 < groups.size(); i++) {
            var a = groups.get(i);
            var b = groups.get(i + 1);
            if (a.indices().size() != 1 || b.indices().size() != 1) continue;
            int ai = a.indices().get(0), bi = b.indices().get(0);
            var first = result.get(ai);
            var last = result.get(bi);
            if (!sameVoice(first, last) || first.measureIndex() < 0
                    || first.measureIndex() >= measures.size()
                    || !ordinaryBeamedEighth(first) || !ordinaryBeamedEighth(last)
                    || first.stemDirection() != last.stemDirection()
                    || first.followingRestBeats() != 0 || last.leadingRestBeats() != 0
                    || !canOwnPrintedPair(meters, first.measureIndex(),
                            neutralStaffClock(groups, notes, first))) continue;
            var bar = measures.get(first.measureIndex());
            float gap = Math.max(4, (bar.bottom() - bar.top()) * height / (8 * first.staffCount()));
            float x1 = (bar.left() + a.position() * (bar.right() - bar.left())) * width;
            float x2 = (bar.left() + b.position() * (bar.right() - bar.left())) * width;
            if (x2 - x1 < gap * 1.15f || x2 - x1 > gap * 12) continue;
            float y1 = first.pageY() * height, y2 = last.pageY() * height;
            if (!PrintedTupletBeamOwner.connectedHeads(
                    gray, width, height, x1, y1, x2, y2, gap, first.stemDirection())) continue;
            var glyph = findContrastedNumeral(
                    gray, width, height, x1, x2, Math.min(y1, y2), Math.max(y1, y2), gap, true,
                    Float.NaN, Float.NaN, 2, false,
                    numeral -> {
                        float distance = PrintedTupletBeamOwner.compactPairDistance(
                                gray, width, height, x1, y1, x2, y2, gap, first.stemDirection(),
                                numeral.left(), numeral.top(), numeral.right(), numeral.bottom());
                        return Float.isFinite(distance)
                                && !insideOtherSystem(numeral, bar, measures, width, height)
                                && !numeralCrossedByShaft(numeral, gray, width, height, gap)
                                && !hasCompetingBeamOwner(
                                        numeral, first, notes, bar, gray, width, height, gap, distance);
                    },
                    numeral -> false);
            if (glyph == null) continue;
            result.set(ai, first.withTupletRatio(2, 2));
            result.set(bi, last.withTupletRatio(2, 2));
        }
        return BracketedDuplets.resolve(List.copyOf(result), meters, openingPickup);
    }

    private static boolean ordinaryBeamedEighth(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.beamCount() == 1
                && note.augmentationDots() == 0 && note.tupletDivisor() == 1
                && note.stemDirection() != 0 && !note.crossStaffBeam()
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean canOwnPrintedPair(
            List<ScoreMeterChange> meters, int measure, double clock) {
        if (!Double.isFinite(clock)) return false;
        if (Math.abs(clock - 4) <= .001 && fourQuarterContext(meters, measure)) return true;
        ScoreMeterChange active = null;
        for (var meter : meters) {
            if (meter.measureIndex() < 0 || meter.measureIndex() > measure) continue;
            if (active == null || meter.measureIndex() > active.measureIndex()) active = meter;
        }
        if (active != null) {
            for (var meter : meters)
                if (meter.measureIndex() == active.measureIndex() && !meter.equals(active))
                    return false;
            return active.denominator() == 8 && active.numerator() >= 6
                    && active.numerator() % 3 == 0
                    && possibleDupletGain(active.quarterBeats(), clock);
        }
        for (double length : new double[] {3, 4.5, 6})
            if (possibleDupletGain(length, clock)) return true;
        return false;
    }

    private static boolean possibleDupletGain(double target, double clock) {
        double pairs = (target - clock) * 2;
        return pairs >= 1 && pairs <= Math.floor(target / 1.5)
                && Math.abs(pairs - Math.rint(pairs)) <= .001;
    }

    private static boolean fourQuarterContext(List<ScoreMeterChange> meters, int measure) {
        ScoreMeterChange active = null;
        for (var meter : meters) {
            if (meter.measureIndex() < 0 || meter.measureIndex() > measure) continue;
            if (active == null || meter.measureIndex() > active.measureIndex()) active = meter;
        }
        if (active == null) return true;
        for (var meter : meters)
            if (meter.measureIndex() == active.measureIndex() && !meter.equals(active)) return false;
        return active.numerator() == active.denominator()
                && (active.denominator() == 2 || active.denominator() == 4);
    }

    private static double neutralStaffClock(
            List<Onset> groups, List<ScoreNoteEvent> notes, ScoreNoteEvent first) {
        double total = 0;
        boolean seen = false;
        for (var group : groups) {
            var note = notes.get(group.indices().get(0));
            if (!sameVoice(first, note)) continue;
            double value = ScoreNoteTiming.writtenDurationBeats(note);
            if (!Double.isFinite(value) || value <= 0) return Double.POSITIVE_INFINITY;
            for (int index : group.indices()) {
                var member = notes.get(index);
                if (member.tupletDivisor() != 1 || member.crossStaffBeam()
                        || (member.articulations() & NoteOrnament.GRACE) != 0
                        || Math.abs(ScoreNoteTiming.writtenDurationBeats(member) - value) > .001
                        || member.leadingRestBeats() != note.leadingRestBeats()
                        || member.followingRestBeats() != note.followingRestBeats())
                    return Double.POSITIVE_INFINITY;
            }
            if (seen && note.leadingRestBeats() != 0) return Double.POSITIVE_INFINITY;
            total += value + note.leadingRestBeats() + note.followingRestBeats();
            seen = true;
        }
        return total;
    }

    /** Printed duplets require compound meter and a complete unambiguous staff clock. */
    static List<ScoreNoteEvent> compoundDuplets(
            List<ScoreNoteEvent> sourceNotes,
            List<MeasureRegion> measures,
            List<ScoreMeterChange> meters,
            byte[] gray,
            int width,
            int height) {
        var notes = BracketedDuplets.capture(sourceNotes, measures, gray, width, height);
        if (meters == null || meters.isEmpty() || notes.isEmpty()) return notes;
        var groups = onsets(notes);
        var duplets = beamedTuplets(notes, groups, measures, gray, width, height, 2, notes.size(), false);
        var candidate = beamedTuplets(duplets, groups, measures, gray, width, height, 4, notes.size(), false);
        var result = new ArrayList<>(notes);
        var meterByMeasure = new HashMap<Integer, Optional<ScoreMeterChange>>();
        var approvedStaffs = new HashMap<MeasureStaff, Boolean>();
        for (int i = 0; i < notes.size(); i++) {
            var n = candidate.get(i);
            if ((n.tupletDivisor() != 2 && n.tupletDivisor() != 4)
                    || notes.get(i).tupletDivisor() != 1) continue;
            var selected = meterByMeasure.computeIfAbsent(n.measureIndex(), m -> meterAt(meters, m))
                    .orElse(null);
            if (selected == null
                    || selected.denominator() != 8
                    || selected.numerator() < 6
                    || selected.numerator() % 3 != 0) continue;
            boolean approved = approvedStaffs.computeIfAbsent(MeasureStaff.of(n), key -> {
                double expected = selected.numerator() * .5;
                double old = staffClock(groups, notes, n, key.staff(), notes.size());
                double fixed = staffClock(groups, candidate, n, key.staff(), notes.size());
                return Double.isFinite(old)
                        && Double.isFinite(fixed)
                        && old < expected - .001
                        && Math.abs(fixed - expected) < .001;
            });
            if (approved) result.set(i, n);
        }
        return BracketedDuplets.resolve(List.copyOf(result), meters, false);
    }

    static List<ScoreNoteEvent> contextualTuplets(
            List<ScoreNoteEvent> notes, List<ScoreMeterChange> meters, boolean openingPickup) {
        return BracketedDuplets.resolve(contextualSevens(notes, meters, openingPickup), meters, openingPickup);
    }

    /** Resolve a complete printed seven against an established, closed staff clock. */
    static List<ScoreNoteEvent> contextualSevens(
            List<ScoreNoteEvent> notes, List<ScoreMeterChange> meters, boolean openingPickup) {
        if (notes == null || notes.isEmpty() || meters == null || meters.isEmpty()) return notes;
        var result = new ArrayList<>(notes);
        var meterByMeasure = new HashMap<Integer, Optional<ScoreMeterChange>>();
        var sevensByStaff = new HashMap<MeasureStaff, List<Integer>>();
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            if (note.tupletDivisor() == 7)
                sevensByStaff.computeIfAbsent(MeasureStaff.of(note), ignored -> new ArrayList<>()).add(i);
        }
        List<Onset> originalGroups = null;
        List<ScoreNoteEvent> candidate = null;
        for (var members : sevensByStaff.values()) {
            if (members.size() != 7) continue;
            var first = notes.get(members.get(0));
            if (first.tupletNormalNotes() != 4
                    || (first.beamCount() != 2 && first.beamCount() != 3)
                    || openingPickup && first.measureIndex() == 0) continue;
            var selected = meterByMeasure.computeIfAbsent(first.measureIndex(), m -> meterAt(meters, m))
                    .orElse(null);
            if (selected == null) continue;
            float left = first.positionInMeasure(),
                    right = notes.get(members.get(6)).positionInMeasure();
            boolean interrupted = false;
            for (var n : notes) {
                if (sameVoice(first, n)
                        && n.positionInMeasure() > left
                        && n.positionInMeasure() < right
                        && n.tupletDivisor() != 7) {
                    interrupted = true;
                    break;
                }
            }
            if (interrupted) continue;
            // Two-beam sevens may fill six sixteenths. Three-beam sevens may
            // fill eight thirty-seconds when the other written beats need a full beat.
            int normal = first.beamCount() == 2 ? 6 : 8;
            boolean valid = true;
            float previous = -1;
            for (int i : members) {
                var n = notes.get(i);
                if (n.tupletNormalNotes() != 4
                        || n.beamCount() != first.beamCount()
                        || n.augmentationDots() != 0
                        || n.leadingRestBeats() != 0
                        || n.followingRestBeats() != 0
                        || previous >= 0 && n.positionInMeasure() - previous < .018f) {
                    valid = false;
                    break;
                }
                previous = n.positionInMeasure();
            }
            if (!valid) continue;
            if (originalGroups == null) {
                originalGroups = onsets(notes);
                candidate = new ArrayList<>(notes);
            }
            for (int i : members) candidate.set(i, notes.get(i).withTupletRatio(7, normal));
            double before =
                    staffClock(originalGroups, notes, first, first.staffIndex(), notes.size());
            double after =
                    staffClock(originalGroups, candidate, first, first.staffIndex(), candidate.size());
            double expected = selected.quarterBeats();
            boolean approved = Double.isFinite(before)
                    && Double.isFinite(after)
                    && before < expected - .001
                    && Math.abs(after - expected) < .001;
            for (int i : members) {
                if (approved) result.set(i, candidate.get(i));
                candidate.set(i, notes.get(i));
            }
        }
        return List.copyOf(result);
    }

    private static List<ScoreNoteEvent> beamedTuplets(
            List<ScoreNoteEvent> notes,
            List<Onset> groups,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height,
            int divisor,
            int virtualStart,
            boolean rapidQuadruplets) {
        return beamedTuplets(notes, groups, measures, gray, width, height, divisor, virtualStart,
                rapidQuadruplets, null);
    }

    private static List<ScoreNoteEvent> beamedTuplets(
            List<ScoreNoteEvent> notes,
            List<Onset> groups,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height,
            int divisor,
            int virtualStart,
            boolean rapidQuadruplets,
            List<SevenOwner> owners) {
        List<ScoreNoteEvent> result = new ArrayList<>(notes);
        for (int i = 0; i + divisor - 1 < groups.size(); i++) {
            Onset firstGroup = groups.get(i);
            for (int index : firstGroup.indices()) {
                ScoreNoteEvent first = result.get(index);
                if (first.tupletDivisor() != 1
                        || first.beamCount() < 1
                        || (first.augmentationDots() != 0 && !(divisor == 7 && first.augmentationDots() == 1))
                        || first.measureIndex() < 0
                        || first.measureIndex() >= measures.size()) continue;
                if (divisor == 4 && (first.beamCount() > 2) != rapidQuadruplets) continue;
                List<Onset> run = new ArrayList<>();
                float minimum = Float.MAX_VALUE, maximum = 0;
                boolean valid = true;
                for (int j = 0; j < divisor; j++) {
                    Onset onset = matching(groups.get(i + j), result, first, divisor == 7);
                    if (onset == null) {
                        valid = false;
                        break;
                    }
                    if (j > 0) {
                        float delta = onset.position() - run.get(j - 1).position();
                        minimum = Math.min(minimum, delta);
                        maximum = Math.max(maximum, delta);
                    }
                    run.add(onset);
                }
                if (!valid || minimum < .012f || maximum > minimum * 1.6f) continue;
                // A longer uninterrupted run does not become a shorter tuplet merely
                // because a numeral happens to be nearby.
                MeasureRegion bar = measures.get(first.measureIndex());
                float gap =
                        Math.max(4, (bar.bottom() - bar.top()) * height / (8 * first.staffCount()));
                if (i > 0
                        && matching(groups.get(i - 1), result, first, divisor == 7) != null
                        && !(divisor == 7 && oppositeSingleStem(groups.get(i - 1), firstGroup, result))
                        && firstGroup.position() - groups.get(i - 1).position() < minimum * 1.5f
                        && !separateTupletBoundary(
                                groups.get(i - 1), firstGroup, bar, gray, width, height, gap)
                        && !((divisor >= 5 && divisor <= 7)
                                && adjacentTuplet(
                                        groups, result, i - divisor, first, bar, gray, width, height,
                                        gap, divisor))) continue;
                if (i + divisor < groups.size()
                        && matching(groups.get(i + divisor), result, first, divisor == 7) != null
                        && !(divisor == 7 && oppositeSingleStem(
                                run.get(divisor - 1), groups.get(i + divisor), result))
                        && groups.get(i + divisor).position() - run.get(divisor - 1).position()
                                < minimum * 1.5f
                        && !separateTupletBoundary(
                                run.get(divisor - 1),
                                groups.get(i + divisor),
                                bar,
                                gray,
                                width,
                                height,
                                gap)
                        && !((divisor >= 5 && divisor <= 7)
                                && adjacentTuplet(
                                        groups, result, i + divisor, first, bar, gray, width, height,
                                        gap, divisor))) continue;
                float x1 =
                        (bar.left() + run.get(0).position() * (bar.right() - bar.left())) * width;
                float lastX =
                        (bar.left() + run.get(divisor - 1).position() * (bar.right() - bar.left()))
                                * width;
                if (lastX - x1 < gap * 3 || lastX - x1 > gap * 26) continue;
                float y1 = Float.MAX_VALUE, y2 = -Float.MAX_VALUE;
                for (Onset onset : run) {
                    y1 = Math.min(y1, onset.top() * height);
                    y2 = Math.max(y2, onset.bottom() * height);
                }
                if (divisor >= 4) {
                    boolean separated = false;
                    for (int j = 1; j < run.size(); j++) {
                        Onset previous = run.get(j - 1), next = run.get(j);
                        float previousX =
                                (bar.left() + previous.position() * (bar.right() - bar.left()))
                                        * width;
                        float nextX =
                                (bar.left() + next.position() * (bar.right() - bar.left())) * width;
                        if (SeparateBeamGroups.between(
                                gray,
                                width,
                                height,
                                previousX,
                                previous.top() * height,
                                nextX,
                                next.top() * height,
                                gap)) {
                            separated = true;
                            break;
                        }
                    }
                    if (separated) continue;
                }
                Glyph numeral =
                        findOwnedNumeral(
                                gray,
                                width,
                                height,
                                x1,
                                lastX,
                                y1,
                                y2,
                                gap,
                                true,
                                Float.NaN,
                                Float.NaN,
                                divisor,
                                divisor == 6 || divisor == 2 || divisor == 4,
                                false,
                                first,
                                result.subList(0, Math.min(virtualStart, result.size())),
                                bar,
                                measures);
                if (numeral == null || insideOtherSystem(numeral, bar, measures, width, height))
                    continue;
                if (rapidQuadruplets
                        && (Math.abs((numeral.left() + numeral.right()) / 2f
                                        - (x1 + lastX) / 2f) > gap
                                || !rapidQuadrupletCloses(groups, result, i, first, virtualStart)))
                    continue;
                if ((divisor == 2 || divisor == 4)
                        && !beamOwnsNumeral(
                                numeral, first, notes, bar, gray, width, height, x1, lastX, gap))
                    continue;
                if (owners != null && divisor == 7)
                    owners.add(new SevenOwner(first.measureIndex(), first.staffIndex(), first.staffCount(),
                            run.get(0).position(), run.get(6).position(), numeral));
                for (Onset onset : run)
                    for (int at : onset.indices()) {
                        ScoreNoteEvent n = result.get(at);
                        int normal = switch (divisor) {
                            case 2 -> 3;
                            case 4 -> n.beamCount() >= 2 ? 6 : 3;
                            default -> ScoreNoteEvent.defaultTupletNormalNotes(divisor);
                        };
                        result.set(at, n.withTupletRatio(divisor, normal));
                    }
            }
        }
        return List.copyOf(result);
    }

    /** Six nominal slots are supported only by a complete staff clock and a separate final attack. */
    private static boolean rapidQuadrupletCloses(
            List<Onset> groups, List<ScoreNoteEvent> notes, int start, ScoreNoteEvent first,
            int virtualStart) {
        int last = start + 4;
        if (last >= groups.size() || groups.get(last).indices().size() != 1) return false;
        var terminal = notes.get(groups.get(last).indices().get(0));
        if (!sameVoice(first, terminal) || terminal.tupletDivisor() != 1
                || terminal.beamCount() != first.beamCount() - 1
                || terminal.augmentationDots() != 0) return false;
        if (last + 1 < groups.size() && notes.get(groups.get(last + 1).indices().get(0))
                .measureIndex() == first.measureIndex()) return false;
        double duration = staffClock(groups, notes, first, first.staffIndex(), virtualStart);
        double adjusted = duration + 2 * ScoreNoteTiming.writtenDurationBeats(first);
        return Double.isFinite(adjusted) && adjusted >= 1
                && Math.abs(adjusted - Math.rint(adjusted)) < .001;
    }

    /** A non-binary ratio needs both printed membership and an independent complete staff clock. */
    private static List<ScoreNoteEvent> boundedQuintuplets(
            List<ScoreNoteEvent> notes,
            List<Onset> groups,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height,
            int virtualStart) {
        List<ScoreNoteEvent> result = new ArrayList<>(notes);
        for (int i = 0; i + 5 < groups.size(); i++) {
            Onset opening = groups.get(i);
            for (int index : opening.indices()) {
                ScoreNoteEvent first = result.get(index);
                if (first.staffCount() < 2
                        || first.crossStaffBeam()
                        || first.beamCount() != 2
                        || first.tupletDivisor() != 1
                        || first.augmentationDots() != 0
                        || first.measureIndex() < 0
                        || first.measureIndex() >= measures.size()) continue;
                List<Onset> run = new ArrayList<>();
                for (int j = 0; j < 5; j++) {
                    Onset onset = matching(groups.get(i + j), result, first);
                    if (onset == null
                            || !run.isEmpty()
                                    && onset.position() - run.get(run.size() - 1).position()
                                            < .012f) break;
                    run.add(onset);
                }
                if (run.size() != 5 || matching(groups.get(i + 5), result, first) == null) continue;
                double own = staffClock(groups, result, first, first.staffIndex(), virtualStart);
                double other = Double.NaN;
                for (int staff = 0; staff < first.staffCount(); staff++) {
                    if (staff == first.staffIndex()) continue;
                    double clock = staffClock(groups, result, first, staff, virtualStart);
                    if (Double.isFinite(clock) && Math.abs(clock - Math.rint(clock)) < .001) {
                        if (Double.isFinite(other) && Math.abs(other - clock) > .001) {
                            other = Double.NaN;
                            break;
                        }
                        other = clock;
                    }
                }
                double unit = ScoreNoteTiming.writtenDurationBeats(first);
                // Five-in-three is distinguishable from five-in-four only with a full other voice.
                if (!Double.isFinite(own)
                        || !Double.isFinite(other)
                        || other < 1
                        || other > 16
                        || Math.abs(own - other - unit * 2) > .001) continue;
                MeasureRegion bar = measures.get(first.measureIndex());
                float gap =
                        Math.max(4, (bar.bottom() - bar.top()) * height / (8 * first.staffCount()));
                float x1 = (bar.left() + opening.position() * (bar.right() - bar.left())) * width;
                float x2 =
                        (bar.left() + run.get(4).position() * (bar.right() - bar.left())) * width;
                float nextX =
                        (bar.left() + groups.get(i + 5).position() * (bar.right() - bar.left()))
                                * width;
                float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
                for (Onset onset : run) {
                    top = Math.min(top, onset.top() * height);
                    bottom = Math.max(bottom, onset.bottom() * height);
                }
                Glyph glyph =
                        findOwnedNumeral(
                                gray,
                                width,
                                height,
                                x1,
                                x2,
                                top,
                                bottom,
                                gap,
                                true,
                                Float.NaN,
                                Float.NaN,
                                5,
                                false,
                                false,
                                first,
                                result.subList(0, Math.min(virtualStart, result.size())),
                                bar,
                                measures);
                if (glyph == null
                        || insideOtherSystem(glyph, bar, measures, width, height)
                        || !BoundedTupletBeam.five(
                                gray,
                                width,
                                height,
                                x1,
                                x2,
                                nextX,
                                glyph.top(),
                                glyph.bottom(),
                                gap,
                                glyph.bottom() < top)) continue;
                for (Onset onset : run)
                    for (int member : onset.indices())
                        result.set(member, result.get(member).withTupletRatio(5, 3));
                break;
            }
        }
        return result;
    }

    private static int firstStaffOnset(
            List<Onset> groups, List<ScoreNoteEvent> notes, int measure, int staff, int staffCount) {
        int left = 0, right = groups.size();
        while (left < right) {
            int middle = (left + right) >>> 1;
            var sample = notes.get(groups.get(middle).indices().get(0));
            int order = Integer.compare(sample.measureIndex(), measure);
            if (order == 0) order = Integer.compare(sample.staffIndex(), staff);
            if (order == 0) order = Integer.compare(sample.staffCount(), staffCount);
            if (order < 0) left = middle + 1;
            else right = middle;
        }
        return left;
    }

    /** Chords count once. Parallel durations and sustained voices cannot prove the other clock. */
    private static double staffClock(
            List<Onset> groups,
            List<ScoreNoteEvent> notes,
            ScoreNoteEvent first,
            int staff,
            int virtualStart) {
        double total = 0;
        boolean seen = false;
        boolean explicitRests = false;
        for (int index = virtualStart; index < notes.size(); index++) {
            ScoreNoteEvent rest = notes.get(index);
            if (rest.measureIndex() == first.measureIndex()
                    && rest.staffIndex() == staff
                    && rest.staffCount() == first.staffCount()) {
                explicitRests = true;
                break;
            }
        }
        int start = firstStaffOnset(groups, notes, first.measureIndex(), staff, first.staffCount());
        for (int i = start; i < groups.size(); i++) {
            Onset onset = groups.get(i);
            ScoreNoteEvent sample = notes.get(onset.indices().get(0));
            if (sample.measureIndex() != first.measureIndex()
                    || sample.staffIndex() != staff
                    || sample.staffCount() != first.staffCount()) break;
            double duration = Double.NaN, before = 0, after = 0;
            for (int index : onset.indices()) {
                ScoreNoteEvent note = notes.get(index);
                if (note.crossStaffBeam()
                        || ScoreNoteTiming.hasIndependentSustain(note)
                        || (note.articulations() & NoteOrnament.GRACE) != 0) return Double.NaN;
                double value = ScoreNoteTiming.writtenDurationBeats(note);
                if (Double.isFinite(duration) && Math.abs(duration - value) > .001)
                    return Double.NaN;
                duration = value;
                before = Math.max(before, note.leadingRestBeats());
                after = Math.max(after, note.followingRestBeats());
            }
            // The virtual slots already include attached silence. Direct note-only calls
            // instead use the preceding/following rest metadata once per attack column.
            if (!explicitRests) total += (seen ? 0 : before) + after;
            total += duration;
            seen = true;
        }
        return seen ? total : Double.NaN;
    }

    /** Subdividing a tuplet slot changes its attack count, not its printed ratio. */
    private static List<ScoreNoteEvent> mixedBeamedTuplets(
            List<ScoreNoteEvent> notes,
            List<Onset> groups,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height,
            int divisor,
            int virtualStart) {
        List<ScoreNoteEvent> result = new ArrayList<>(notes);
        for (int i = 0; i < groups.size(); i++) {
            ScoreNoteEvent first = result.get(groups.get(i).indices().get(0));
            if (first.measureIndex() < 0
                    || first.measureIndex() >= measures.size()
                    || first.beamCount() < 1
                    || first.beamCount() > 3
                    || first.tupletDivisor() != 1
                    || first.augmentationDots() != 0) continue;
            double unit = ScoreNoteTiming.writtenDurationBeats(first), total = 0;
            if (i + 1 < groups.size())
                for (int next : groups.get(i + 1).indices()) {
                    ScoreNoteEvent n = result.get(next);
                    double value = ScoreNoteTiming.writtenDurationBeats(n);
                    if (sameVoice(first, n)
                            && n.beamCount() > 0
                            && n.tupletDivisor() == 1
                            && n.augmentationDots() == 0
                            && Math.abs(value - unit * 2) < .001) {
                        unit = value;
                        break;
                    }
                }
            if (unit <= 0 || unit > .5) continue;
            MeasureRegion bar = measures.get(first.measureIndex());
            float gap = Math.max(4, (bar.bottom() - bar.top()) * height / (8 * first.staffCount()));
            List<Onset> run = new ArrayList<>();
            boolean subdivided = false, separated = false;
            for (int j = i; j < groups.size() && j < i + divisor * 2; j++) {
                Onset raw = groups.get(j);
                List<Integer> members = new ArrayList<>();
                float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
                double value = -1;
                for (int index : raw.indices()) {
                    ScoreNoteEvent n = result.get(index);
                    double duration = ScoreNoteTiming.writtenDurationBeats(n);
                    if (!sameVoice(first, n)
                            || n.tupletDivisor() != 1
                            || n.augmentationDots() != 0
                            || n.beamCount() < 1
                            || (n.articulations() & NoteOrnament.GRACE) != 0
                            || Math.abs(duration - unit) > .001
                                    && Math.abs(duration - unit * .5) > .001) continue;
                    if (value >= 0 && Math.abs(value - duration) > .001) continue;
                    value = duration;
                    members.add(index);
                    top = Math.min(top, n.pageY());
                    bottom = Math.max(bottom, n.pageY());
                }
                if (members.isEmpty()) break;
                Onset onset = new Onset(List.copyOf(members), raw.position(), top, bottom);
                if (!run.isEmpty()) {
                    Onset previous = run.get(run.size() - 1);
                    // Compression alone is not a beam break. Two independently outward
                    // beams with a clear interval still delimit different printed groups.
                    float previousX =
                            (bar.left() + previous.position() * (bar.right() - bar.left())) * width;
                    float nextX =
                            (bar.left() + onset.position() * (bar.right() - bar.left())) * width;
                    separated |=
                            SeparateBeamGroups.between(
                                    gray,
                                    width,
                                    height,
                                    previousX,
                                    previous.top() * height,
                                    nextX,
                                    onset.top() * height,
                                    gap);
                    if (onset.position() - previous.position() < .012f) break;
                }
                run.add(onset);
                total += value;
                subdivided |= value < unit - .001;
                if (total > unit * divisor + .001) break;
                if (!subdivided || run.size() <= divisor || Math.abs(total - unit * divisor) > .001)
                    continue;
                float x1 =
                        (bar.left() + run.get(0).position() * (bar.right() - bar.left())) * width;
                float x2 = (bar.left() + onset.position() * (bar.right() - bar.left())) * width;
                if (x2 - x1 < gap * 3 || x2 - x1 > gap * 26) break;
                float y1 = Float.POSITIVE_INFINITY, y2 = Float.NEGATIVE_INFINITY;
                for (Onset slot : run) {
                    y1 = Math.min(y1, slot.top() * height);
                    y2 = Math.max(y2, slot.bottom() * height);
                }
                Glyph numeral =
                        findOwnedNumeral(
                                gray,
                                width,
                                height,
                                x1,
                                x2,
                                y1,
                                y2,
                                gap,
                                true,
                                Float.NaN,
                                Float.NaN,
                                divisor,
                                true,
                                false,
                                first,
                                result.subList(0, Math.min(virtualStart, result.size())),
                                bar,
                                measures);
                if (numeral == null || insideOtherSystem(numeral, bar, measures, width, height))
                    break;
                // An explicit bracket can span separately beamed subdivisions. Otherwise,
                // two outward beam ends cannot be joined merely by a nearby finger numeral.
                if (separated
                        && !(bracketArm(
                                        gray,
                                        width,
                                        height,
                                        Math.round(x1 - gap * .3f),
                                        numeral.left() - 2,
                                        numeral.top(),
                                        numeral.bottom(),
                                        gap)
                                && bracketArm(
                                        gray,
                                        width,
                                        height,
                                        numeral.right() + 2,
                                        Math.round(x2 + gap * .3f),
                                        numeral.top(),
                                        numeral.bottom(),
                                        gap))) break;
                for (Onset slot : run)
                    for (int index : slot.indices()) {
                        ScoreNoteEvent n = result.get(index);
                        result.set(
                                index,
                                n.withTupletRatio(divisor, ScoreNoteEvent.defaultTupletNormalNotes(divisor)));
                    }
                break;
            }
        }
        return result;
    }

    /**
     * Between piano staves a single numeral belongs to the nearer staff. Explicit cross-staff beams
     * keep their existing grouping authority.
     */
    /** An actual rest slot has no shaft; the two sounding notes must own its complete bracket. */
    private static boolean bracketedRestBeamOwns(
            Glyph glyph,
            List<Onset> group,
            List<ScoreNoteEvent> notes,
            int virtualStart,
            MeasureRegion bar,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float gap) {
        int restCount = 0;
        List<ScoreNoteEvent> sounding = new ArrayList<>();
        for (Onset onset : group) {
            if (onset.indices().size() != 1) return false;
            int index = onset.indices().get(0);
            if (index >= virtualStart) restCount++;
            else sounding.add(notes.get(index));
        }
        if (restCount != 1 || sounding.size() != 2) return false;
        ScoreNoteEvent a = sounding.get(0), b = sounding.get(1);
        if (a.beamCount() < 1
                || a.beamCount() != b.beamCount()
                || a.stemDirection() == 0
                || a.stemDirection() != b.stemDirection()
                || a.crossStaffBeam()
                || b.crossStaffBeam()
                || insideOtherSystem(glyph, bar, measures, width, height)) return false;
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (Onset onset : group) {
            top = Math.min(top, onset.top() * height);
            bottom = Math.max(bottom, onset.bottom() * height);
        }
        // The existing bounded retry requires real deficit from local paper. Its
        // rule cleanup only removes ink; it cannot create a shaft or bracket.
        var ink =
                TupletNumeralInk.window(gray, width, height, firstX, lastX, top, bottom, gap, 165);
        if (ink == null) return false;
        int l = ink.left(), t = ink.top();
        if (!bracketArm(
                        ink.pixels(),
                        ink.width(),
                        ink.height(),
                        Math.round(firstX - gap * .3f) - l,
                        glyph.left() - 2 - l,
                        glyph.top() - t,
                        glyph.bottom() - t,
                        gap * .25f)
                || !bracketArm(
                        ink.pixels(),
                        ink.width(),
                        ink.height(),
                        glyph.right() + 2 - l,
                        Math.round(lastX + gap * .3f) - l,
                        glyph.top() - t,
                        glyph.bottom() - t,
                        gap * .25f)
                || !bracketHook(
                        ink.pixels(),
                        ink.width(),
                        ink.height(),
                        firstX - gap * .3f - l,
                        glyph.top() - t,
                        glyph.bottom() - t,
                        gap)
                || !bracketHook(
                        ink.pixels(),
                        ink.width(),
                        ink.height(),
                        lastX + gap * .3f - l,
                        glyph.top() - t,
                        glyph.bottom() - t,
                        gap)) return false;
        List<ScoreNoteEvent> heads = notes.subList(0, Math.min(virtualStart, notes.size()));
        float ax = (bar.left() + a.positionInMeasure() * (bar.right() - bar.left())) * width;
        float bx = (bar.left() + b.positionInMeasure() * (bar.right() - bar.left())) * width;
        float distance =
                PrintedTupletBeamOwner.bracketedDistance(
                        ink.pixels(),
                        ink.width(),
                        ink.height(),
                        ax - l,
                        a.pageY() * height - t,
                        bx - l,
                        b.pageY() * height - t,
                        gap,
                        a.stemDirection(),
                        glyph.left() - l,
                        glyph.top() - t,
                        glyph.right() - l,
                        glyph.bottom() - t);
        return Float.isFinite(distance)
                && !hasCompetingBeamOwner(glyph, a, heads, bar, gray, width, height, gap, distance)
                && !otherSystemBeamOwns(
                        glyph, a, heads, bar, measures, gray, width, height, gap, distance);
    }

    /** A complete printed bracket can own a numeral before its intervening rest is read. */
    private static boolean printedBracketOwnsPair(
            Glyph glyph,
            ScoreNoteEvent a,
            ScoreNoteEvent b,
            List<ScoreNoteEvent> notes,
            MeasureRegion bar,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float gap) {
        if (a.beamCount() < 1
                || a.beamCount() != b.beamCount()
                || a.stemDirection() == 0
                || a.stemDirection() != b.stemDirection()
                || a.crossStaffBeam()
                || b.crossStaffBeam()
                || insideOtherSystem(glyph, bar, measures, width, height)) return false;
        float top = Math.min(a.pageY(), b.pageY()) * height;
        float bottom = Math.max(a.pageY(), b.pageY()) * height;
        // The existing bounded retry requires real deficit from local paper. Its
        // rule cleanup only removes ink; it cannot create a shaft or bracket.
        var ink =
                TupletNumeralInk.window(gray, width, height, firstX, lastX, top, bottom, gap, 165);
        if (ink == null) return false;
        int l = ink.left(), t = ink.top();
        if (!bracketArm(
                        ink.pixels(),
                        ink.width(),
                        ink.height(),
                        Math.round(firstX - gap * .3f) - l,
                        glyph.left() - 2 - l,
                        glyph.top() - t,
                        glyph.bottom() - t,
                        gap * .25f)
                || !bracketArm(
                        ink.pixels(),
                        ink.width(),
                        ink.height(),
                        glyph.right() + 2 - l,
                        Math.round(lastX + gap * .3f) - l,
                        glyph.top() - t,
                        glyph.bottom() - t,
                        gap * .25f)
                || !connectedBracketHook(
                        ink.pixels(),
                        ink.width(),
                        ink.height(),
                        firstX - gap * .3f - l,
                        glyph.left() - 2 - l,
                        glyph.top() - t,
                        glyph.bottom() - t,
                        gap)
                || !connectedBracketHook(
                        ink.pixels(),
                        ink.width(),
                        ink.height(),
                        lastX + gap * .3f - l,
                        glyph.right() + 2 - l,
                        glyph.top() - t,
                        glyph.bottom() - t,
                        gap)) return false;
        List<ScoreNoteEvent> heads = notes;
        float ax = (bar.left() + a.positionInMeasure() * (bar.right() - bar.left())) * width;
        float bx = (bar.left() + b.positionInMeasure() * (bar.right() - bar.left())) * width;
        float distance =
                PrintedTupletBeamOwner.bracketedDistance(
                        ink.pixels(),
                        ink.width(),
                        ink.height(),
                        ax - l,
                        a.pageY() * height - t,
                        bx - l,
                        b.pageY() * height - t,
                        gap,
                        a.stemDirection(),
                        glyph.left() - l,
                        glyph.top() - t,
                        glyph.right() - l,
                        glyph.bottom() - t);
        if (!Float.isFinite(distance) && a.beamCount() == 1 && b.beamCount() == 1) {
            float first =
                    printedFlaggedShaftDistance(
                            ink.pixels(),
                            ink.width(),
                            ink.height(),
                            ax - l,
                            a.pageY() * height - t,
                            gap,
                            a.stemDirection(),
                            glyph.top() - t,
                            glyph.bottom() - t);
            float last =
                    printedFlaggedShaftDistance(
                            ink.pixels(),
                            ink.width(),
                            ink.height(),
                            bx - l,
                            b.pageY() * height - t,
                            gap,
                            b.stemDirection(),
                            glyph.top() - t,
                            glyph.bottom() - t);
            if (Float.isFinite(first) && Float.isFinite(last)) distance = Math.max(first, last);
        }
        return Float.isFinite(distance)
                && !hasCompetingBeamOwner(glyph, a, heads, bar, gray, width, height, gap, distance)
                && !otherSystemBeamOwns(
                        glyph, a, heads, bar, measures, gray, width, height, gap, distance);
    }

    /** Offset hooks must still join the numeral's own printed arm. */
    private static boolean connectedBracketHook(
            byte[] gray,
            int width,
            int height,
            float endpoint,
            int nearGlyph,
            int top,
            int bottom,
            float gap) {
        for (int x = Math.max(0, Math.round(endpoint - gap * .7f));
                x <= Math.min(width - 1, Math.round(endpoint + gap * .7f));
                x++) {
            int run = 0;
            for (int y = Math.max(0, Math.round(top - gap * .5f));
                    y <= Math.min(height - 1, Math.round(bottom + gap * .5f));
                    y++) {
                run = dark(gray, width, x, y) ? run + 1 : 0;
                if (run >= Math.max(3, Math.round(gap * .65f))
                        && bracketArm(
                                gray,
                                width,
                                height,
                                Math.min(x, nearGlyph),
                                Math.max(x, nearGlyph),
                                top,
                                bottom,
                                gap * .25f)) return true;
            }
        }
        return false;
    }

    /** Separate flagged shafts may share a printed bracket without a connecting beam. */
    private static float printedFlaggedShaftDistance(
            byte[] gray,
            int width,
            int height,
            float headX,
            float headY,
            float gap,
            int direction,
            int numeralTop,
            int numeralBottom) {
        float nearest = Float.POSITIVE_INFINITY;
        int center = Math.round(headX + direction * gap * .3f),
                radius = Math.max(1, Math.round(gap * .5f));
        for (int x = Math.max(0, center - radius); x <= Math.min(width - 1, center + radius); x++) {
            int total = 0, hits = 0, blank = 0, lastInk = -1;
            for (int d = Math.round(gap * .6f); d <= gap * 6; d++) {
                int y = Math.round(headY) - direction * d;
                if (y < 0 || y >= height) break;
                total++;
                if ((gray[y * width + x] & 255) < 165) {
                    hits++;
                    blank = 0;
                    lastInk = y;
                } else if (++blank > Math.max(1, Math.round(gap * .16f))) break;
            }
            if (lastInk < 0 || hits < gap * 2.2f || hits < (total - blank) * .92f) continue;
            float distance = direction > 0 ? lastInk - numeralBottom : numeralTop - lastInk;
            if (distance >= gap * .2f && distance <= gap * 2.6f)
                nearest = Math.min(nearest, distance);
        }
        return nearest;
    }

    /** A cropped staff/shaft crossing cannot supply a separately printed numeral. */

    /** Runs only after the entire original recognizer, including deferred consensus, failed. */
    private static Glyph literalFallback(
            PrintedTupletBeamOwner.LiteralProof literal,
            Glyph[] pending,
            int[] levels,
            int count,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number,
            boolean boundedSecondary) {
        if (!literal.reserve(16L + count + 16L * count * count)) return null;
        boolean[] qualified = new boolean[count];
        Glyph independentConsensus = null;
        for (int i = 0; i < count; i++) {
            Glyph found = pending[i];
            if (!Float.isFinite(
                    literal.distance(found.left(), found.top(), found.right(), found.bottom())))
                continue;
            qualified[i] = true;
            for (int earlier = 0; earlier < i; earlier++)
                if (qualified[earlier]
                        && levels[earlier] != levels[i]
                        && Math.abs(pending[earlier].left() - found.left()) <= gap * .25f
                        && Math.abs(pending[earlier].right() - found.right()) <= gap * .25f
                        && Math.abs(pending[earlier].top() - found.top()) <= gap * .25f
                        && Math.abs(pending[earlier].bottom() - found.bottom()) <= gap * .25f)
                    return found;
            if (independentConsensus != null) continue;
            var window =
                    literal.numeralWindow(found.left(), found.top(), found.right(), found.bottom());
            if (window == null) continue;
            Glyph independent =
                    findPrintedNumeral(
                            window.pixels(),
                            window.width(),
                            window.height(),
                            firstX - window.left(),
                            lastX - window.left(),
                            firstY - window.top(),
                            lastY - window.top(),
                            gap,
                            shortNotes,
                            headX - window.left(),
                            headY - window.top(),
                            number,
                            boundedSecondary,
                            glyph -> true);
            int area = 0;
            for (byte pixel : window.pixels()) if (pixel == 0) area++;
            boolean four =
                    independent != null
                            && completeFourCrossbar(
                                    window.pixels(),
                                    window.width(),
                                    area,
                                    independent.left(),
                                    independent.top(),
                                    independent.right() - independent.left() + 1,
                                    independent.bottom() - independent.top() + 1);
            if (independent != null
                    && !four
                    && Math.abs(independent.left() + window.left() - found.left()) <= gap * .25f
                    && Math.abs(independent.right() + window.left() - found.right()) <= gap * .25f
                    && Math.abs(independent.top() + window.top() - found.top()) <= gap * .25f
                    && Math.abs(independent.bottom() + window.top() - found.bottom()) <= gap * .25f)
                independentConsensus = found;
        }
        return literal.work.exhausted ? null : independentConsensus;
    }

    private static boolean numeralCrossedByShaft(
            Glyph glyph, byte[] gray, int width, int height, float gap) {
        int extension = Math.max(3, Math.round(gap * .7f)),
                flank = Math.max(2, Math.round(gap * .35f));
        int top = glyph.top() - extension, bottom = glyph.bottom() + extension;
        if (top < 0 || bottom >= height) return false;
        int local =
                BeamInkThreshold.at(
                        gray, width, height, (glyph.left() + glyph.right()) / 2, top, bottom, gap);
        int limit = local < 153 ? local + 15 : 205;
        int length = bottom - top + 1, maximumBlank = Math.max(1, Math.round(gap * .16f));
        for (int x = Math.max(flank, glyph.left());
                x <= Math.min(width - flank - 1, glyph.right());
                x++) {
            int dark = 0, narrow = 0, run = 0, longest = 0, blank = 0;
            for (int y = top; y <= bottom; y++) {
                int ink = gray[y * width + x] & 255;
                if (ink < limit) {
                    dark++;
                    run += blank + 1;
                    blank = 0;
                    longest = Math.max(longest, run);
                    if ((gray[y * width + x - flank] & 255) >= ink + 18
                            && (gray[y * width + x + flank] & 255) >= ink + 18) narrow++;
                } else if (++blank > maximumBlank) {
                    run = 0;
                    blank = 0;
                }
            }
            if (dark >= length * .85f && longest >= length * .9f && narrow >= length * .45f)
                return true;
        }
        return false;
    }

    private static Glyph findOwnedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number,
            boolean contrasted,
            boolean boundedSecondary,
            ScoreNoteEvent first,
            List<ScoreNoteEvent> soundingHeads,
            MeasureRegion bar,
            List<MeasureRegion> measures) {
        return findOwnedNumeral(
                gray,
                width,
                height,
                firstX,
                lastX,
                firstY,
                lastY,
                gap,
                shortNotes,
                headX,
                headY,
                number,
                contrasted,
                boundedSecondary,
                first,
                soundingHeads,
                bar,
                measures,
                null);
    }

    private static Glyph findOwnedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number,
            boolean contrasted,
            boolean boundedSecondary,
            ScoreNoteEvent first,
            List<ScoreNoteEvent> soundingHeads,
            MeasureRegion bar,
            List<MeasureRegion> measures,
            java.util.function.Predicate<Glyph> completeBeamGroup) {
        return findOwnedNumeral(
                gray,
                width,
                height,
                firstX,
                lastX,
                firstY,
                lastY,
                gap,
                shortNotes,
                headX,
                headY,
                number,
                contrasted,
                boundedSecondary,
                first,
                soundingHeads,
                bar,
                measures,
                completeBeamGroup,
                null);
    }

    private static Glyph findOwnedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number,
            boolean contrasted,
            boolean boundedSecondary,
            ScoreNoteEvent first,
            List<ScoreNoteEvent> soundingHeads,
            MeasureRegion bar,
            List<MeasureRegion> measures,
            java.util.function.Predicate<Glyph> completeBeamGroup,
            PrintedTupletBeamOwner.LiteralProof literal) {
        java.util.Set<Glyph> physicalNumerals =
                completeBeamGroup == null ? null : new java.util.HashSet<>();
        java.util.function.Predicate<Glyph> ownership =
                glyph -> {
                    if (insideOtherSystem(glyph, bar, measures, width, height)) return false;
                    if (numeralCrossedByShaft(glyph, gray, width, height, gap)) return false;
                    boolean physical =
                            beamOwnsNumeral(
                                    glyph,
                                    first,
                                    soundingHeads,
                                    bar,
                                    gray,
                                    width,
                                    height,
                                    firstX,
                                    lastX,
                                    gap);
                    if (!physical
                            && !ownsNumeral(glyph, first, soundingHeads, bar, gap, width, height))
                        return false;
                    float ownDistance =
                            physical
                                    ? attachedBeamDistance(
                                            glyph,
                                            first,
                                            soundingHeads,
                                            bar,
                                            gray,
                                            width,
                                            height,
                                            firstX,
                                            lastX,
                                            gap)
                                    : Float.POSITIVE_INFINITY;
                    boolean uncontested =
                            !otherSystemBeamOwns(
                                    glyph,
                                    first,
                                    soundingHeads,
                                    bar,
                                    measures,
                                    gray,
                                    width,
                                    height,
                                    gap,
                                    ownDistance);
                    if (physical && uncontested && physicalNumerals != null)
                        physicalNumerals.add(glyph);
                    return uncontested;
                };
        java.util.function.Predicate<Glyph> physicalGroupContext =
                glyph ->
                        physicalNumerals != null
                                && physicalNumerals.contains(glyph)
                                && completeBeamGroup.test(glyph);
        Glyph normal =
                contrasted
                        ? findContrastedNumeral(
                                gray,
                                width,
                                height,
                                firstX,
                                lastX,
                                firstY,
                                lastY,
                                gap,
                                shortNotes,
                                headX,
                                headY,
                                number,
                                boundedSecondary,
                                ownership,
                                physicalGroupContext,
                                literal)
                        : findPrintedNumeral(
                                gray,
                                width,
                                height,
                                firstX,
                                lastX,
                                firstY,
                                lastY,
                                gap,
                                shortNotes,
                                headX,
                                headY,
                                number,
                                boundedSecondary,
                                ownership);
        if (normal != null
                || number != 3
                || !shortNotes
                || !(boundedSecondary
                        || (first.staffStep() <= -3 && first.stemDirection() == 1)
                        || (first.staffStep() >= 11 && first.stemDirection() == -1))) return normal;
        Glyph extended =
                findPrintedNumeral(
                        gray,
                        width,
                        height,
                        firstX,
                        lastX,
                        firstY,
                        lastY,
                        gap,
                        shortNotes,
                        headX,
                        headY,
                        number,
                        boundedSecondary,
                        ownership,
                        10,
                        glyph ->
                                boundedSecondary
                                        || ledgerBeamOwns(
                                                glyph,
                                                first,
                                                soundingHeads,
                                                bar,
                                                gray,
                                                width,
                                                height,
                                                firstX,
                                                lastX,
                                                gap,
                                                measures));
        return extended != null
                        && TupletNumeralInk.hasGlyphContrast(
                                gray,
                                width,
                                height,
                                extended.left(),
                                extended.top(),
                                extended.right(),
                                extended.bottom(),
                                gap)
                        && validNumeralContext(
                                gray, width, height, extended, firstX, lastX, gap, number)
                ? extended
                : null;
    }

    /**
     * Staff-rule context can be overridden only by actual ownership of all three real heads.
     * Same-operation glyph/beam ownership is separately required by the caller.
     */
    private static boolean completePrintedThree(
            List<Onset> group,
            List<ScoreNoteEvent> notes,
            int virtualStart,
            MeasureRegion bar,
            byte[] gray,
            int width,
            int height,
            float gap) {
        return completePrintedThree(group, notes, virtualStart, bar, gray, width, height, gap, 1);
    }

    private static boolean completePrintedThree(
            List<Onset> group, List<ScoreNoteEvent> notes, int virtualStart, MeasureRegion bar,
            byte[] gray, int width, int height, float gap, int beamCount) {
        if (group.size() != 3) return false;
        ScoreNoteEvent[] heads = new ScoreNoteEvent[3];
        for (int i = 0; i < 3; i++) {
            var members = group.get(i).indices();
            if (members.size() != 1) return false;
            int index = members.get(0);
            if (index < 0 || index >= virtualStart || index >= notes.size()) return false;
            heads[i] = notes.get(index);
            if (heads[i].kind() != ScoreNoteEvent.Kind.PITCHED
                    || heads[i].beamCount() != beamCount
                    || heads[i].stemDirection() == 0
                    || heads[i].crossStaffBeam()
                    || (heads[i].articulations() & NoteOrnament.GRACE) != 0
                    || !sameVoice(heads[0], heads[i])
                    || heads[i].stemDirection() != heads[0].stemDirection()) return false;
        }
        for (int i = 0; i < 2; i++) {
            float x1 =
                    (bar.left() + heads[i].positionInMeasure() * (bar.right() - bar.left()))
                            * width;
            float x2 =
                    (bar.left() + heads[i + 1].positionInMeasure() * (bar.right() - bar.left()))
                            * width;
            if (!PrintedTupletBeamOwner.connectedHeads(
                    gray,
                    width,
                    height,
                    x1,
                    heads[i].pageY() * height,
                    x2,
                    heads[i + 1].pageY() * height,
                    gap,
                    heads[0].stemDirection())) return false;
        }
        return true;
    }

    private static boolean beamOwnsNumeral(
            Glyph glyph,
            ScoreNoteEvent first,
            List<ScoreNoteEvent> heads,
            MeasureRegion bar,
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float gap) {
        if (first.beamCount() < 1 || first.stemDirection() == 0 || !heads.contains(first))
            return false;
        float firstHeadX =
                (bar.left() + first.positionInMeasure() * (bar.right() - bar.left())) * width;
        if (Math.abs(firstHeadX - firstX) > gap * .4f) return false;
        for (var last : heads) {
            if (last.measureIndex() != first.measureIndex()
                    || last.staffCount() != first.staffCount()
                    || last.staffIndex() != first.staffIndex()
                    || last.beamCount() < 1
                    || last.stemDirection() != first.stemDirection()) continue;
            float lastHeadX =
                    (bar.left() + last.positionInMeasure() * (bar.right() - bar.left())) * width;
            if (Math.abs(lastHeadX - lastX) > gap * .4f) continue;
            float ownDistance =
                    PrintedTupletBeamOwner.distance(
                            gray,
                            width,
                            height,
                            firstHeadX,
                            first.pageY() * height,
                            lastHeadX,
                            last.pageY() * height,
                            gap,
                            first.stemDirection(),
                            glyph.left(),
                            glyph.top(),
                            glyph.right(),
                            glyph.bottom());
            if (!Float.isFinite(ownDistance)) continue;
            if (!hasCompetingBeamOwner(
                    glyph, first, heads, bar, gray, width, height, gap, ownDistance)) return true;
        }
        return false;
    }

    /** A partial numeral between neighboring glyphs on the same text baseline is caption ink. */
    static boolean joinedNumeralSharesCaption(
            byte[] gray, int width, int height, int left, int top, int right, int bottom, float gap) {
        int firstRow = Math.max(0, top - Math.round(gap * .45f));
        int lastRow = Math.min(height - 1, bottom + Math.round(gap * .2f));
        int firstColumn = Math.max(0, left - Math.round(gap * 2.5f));
        int lastColumn = Math.min(width - 1, right + Math.round(gap * 3.5f));
        int runStart = -1, runTop = height, runBottom = -1;
        int before = 0, after = 0;
        for (int x = firstColumn; x <= lastColumn + 1; x++) {
            int inkTop = height, inkBottom = -1;
            if (x <= lastColumn)
                for (int y = firstRow; y <= lastRow; y++) {
                    if ((gray[y * width + x] & 255) >= 150) continue;
                    inkTop = Math.min(inkTop, y);
                    inkBottom = y;
                }
            if (inkBottom >= 0) {
                if (runStart < 0) runStart = x;
                runTop = Math.min(runTop, inkTop);
                runBottom = Math.max(runBottom, inkBottom);
            } else if (runStart >= 0) {
                int span = x - runStart, glyphHeight = runBottom - runTop + 1;
                if (span >= gap * .33f && span <= gap * 1.6f
                        && glyphHeight >= (bottom - top + 1) * .72f
                        && Math.abs(runBottom - bottom) <= gap * .45f) {
                    if (x <= left) before++;
                    if (runStart > right) after++;
                }
                runStart = -1;
                runTop = height;
                runBottom = -1;
            }
        }
        return before > 0 && after > 0 && before + after >= 3;
    }

    /** A repeated numeral must remain attached to all three sounding attack columns. */
    private static boolean joinedBeamOwnsNumeral(
            Glyph glyph,
            List<Onset> group,
            List<ScoreNoteEvent> groupNotes,
            ScoreNoteEvent first,
            List<ScoreNoteEvent> heads,
            MeasureRegion bar,
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float gap,
            List<OwnedPrintedThree> proven) {
        if (group.size() != 3 || first.beamCount() < 1 || first.stemDirection() == 0) return false;
        if (joinedNumeralSharesCaption(gray, width, height,
                glyph.left(), glyph.top(), glyph.right(), glyph.bottom(), gap)) return false;
        List<ScoreNoteEvent> attacks = new ArrayList<>();
        for (Onset onset : group) {
            if (onset.indices().isEmpty()) return false;
            for (int index : onset.indices()) {
                if (index >= groupNotes.size()) return false;
                var n = groupNotes.get(index);
                if (!sameVoice(first, n)
                        || n.beamCount() != first.beamCount()
                        || n.stemDirection() != first.stemDirection()
                        || n.crossStaffBeam()
                        || (n.articulations() & NoteOrnament.GRACE) != 0) return false;
            }
            attacks.add(groupNotes.get(onset.indices().get(0)));
        }
        var middle = attacks.get(1);
        var last = attacks.get(2);
        float middleX =
                (bar.left() + middle.positionInMeasure() * (bar.right() - bar.left())) * width;
        if (!PrintedTupletBeamOwner.connectedHeads(
                        gray,
                        width,
                        height,
                        firstX,
                        first.pageY() * height,
                        middleX,
                        middle.pageY() * height,
                        gap,
                        first.stemDirection())
                || !PrintedTupletBeamOwner.connectedHeads(
                        gray,
                        width,
                        height,
                        middleX,
                        middle.pageY() * height,
                        lastX,
                        last.pageY() * height,
                        gap,
                        first.stemDirection())) return false;
        float distance =
                PrintedTupletBeamOwner.joinedDistance(
                        gray,
                        width,
                        height,
                        firstX,
                        first.pageY() * height,
                        lastX,
                        last.pageY() * height,
                        gap,
                        first.stemDirection(),
                        glyph.left(),
                        glyph.top(),
                        glyph.right(),
                        glyph.bottom());
        return Float.isFinite(distance)
                && !hasCompetingJoinedBeamOwner(
                        glyph, first, heads, bar, gray, width, height, gap, distance, proven);
    }

    private static boolean hasCompetingJoinedBeamOwner(
            Glyph glyph,
            ScoreNoteEvent first,
            List<ScoreNoteEvent> heads,
            MeasureRegion bar,
            byte[] gray,
            int width,
            int height,
            float gap,
            float ownDistance,
            List<OwnedPrintedThree> proven) {
        float cx = (glyph.left() + glyph.right()) * .5f;
        for (var a : heads) {
            if (a.measureIndex() != first.measureIndex()
                    || a.staffCount() != first.staffCount()
                    || a.staffIndex() == first.staffIndex()
                    || a.beamCount() < 1
                    || a.stemDirection() == 0) continue;
            float ax = (bar.left() + a.positionInMeasure() * (bar.right() - bar.left())) * width;
            if (ax >= cx || cx - ax > gap * 26) continue;
            for (var b : heads) {
                if (b.measureIndex() != a.measureIndex()
                        || b.staffCount() != a.staffCount()
                        || b.staffIndex() != a.staffIndex()
                        || b.beamCount() != a.beamCount()
                        || b.stemDirection() != a.stemDirection()) continue;
                float bx =
                        (bar.left() + b.positionInMeasure() * (bar.right() - bar.left())) * width;
                if (bx <= cx || bx - ax > gap * 26) continue;
                float other =
                        PrintedTupletBeamOwner.joinedDistance(
                                gray,
                                width,
                                height,
                                ax,
                                a.pageY() * height,
                                bx,
                                b.pageY() * height,
                                gap,
                                a.stemDirection(),
                                glyph.left(),
                                glyph.top(),
                                glyph.right(),
                                glyph.bottom());
                if (Float.isFinite(other) && other <= ownDistance + gap * .1f) {
                    boolean alreadyOwned = false;
                    for (var proof : proven) {
                        if (proof.measure() != a.measureIndex()
                                || proof.staff() != a.staffIndex()
                                || proof.count() != a.staffCount()
                                || proof.direction() != a.stemDirection()
                                || proof.beams() != a.beamCount()
                                || Math.abs(proof.firstX() - ax) > gap * .4f
                                || Math.abs(proof.lastX() - bx) > gap * .4f
                                || a.pageY() * height < proof.firstTop() - gap * .4f
                                || a.pageY() * height > proof.firstBottom() + gap * .4f
                                || b.pageY() * height < proof.lastTop() - gap * .4f
                                || b.pageY() * height > proof.lastBottom() + gap * .4f) continue;
                        var known = proof.glyph();
                        boolean separate =
                                Math.abs(
                                                        (known.left()
                                                                        + known.right()
                                                                        - glyph.left()
                                                                        - glyph.right())
                                                                * .5f)
                                                > gap * .35f
                                        || Math.abs(
                                                        (known.top()
                                                                        + known.bottom()
                                                                        - glyph.top()
                                                                        - glyph.bottom())
                                                                * .5f)
                                                > gap * .35f;
                        if (separate && proof.distance() + gap * .1f < other) {
                            alreadyOwned = true;
                            break;
                        }
                    }
                    if (!alreadyOwned) return true;
                }
            }
        }
        return false;
    }

    /** A nearer foreign beam prevents borrowing its numeral across piano staves. */
    private static boolean hasCompetingBeamOwner(
            Glyph glyph,
            ScoreNoteEvent first,
            List<ScoreNoteEvent> heads,
            MeasureRegion bar,
            byte[] gray,
            int width,
            int height,
            float gap,
            float ownDistance) {
        float numeralX = (glyph.left() + glyph.right()) * .5f;
        for (var a : heads) {
            if (a.measureIndex() != first.measureIndex()
                    || a.staffCount() != first.staffCount()
                    || a.staffIndex() == first.staffIndex()
                    || a.beamCount() < 1
                    || a.stemDirection() == 0) continue;
            float ax = (bar.left() + a.positionInMeasure() * (bar.right() - bar.left())) * width;
            if (ax >= numeralX || numeralX - ax > gap * 26) continue;
            for (var b : heads) {
                if (b.measureIndex() != a.measureIndex()
                        || b.staffIndex() != a.staffIndex()
                        || b.staffCount() != a.staffCount()
                        || b.beamCount() != a.beamCount()
                        || b.stemDirection() != a.stemDirection()) continue;
                float bx =
                        (bar.left() + b.positionInMeasure() * (bar.right() - bar.left())) * width;
                if (bx <= numeralX || bx - ax > gap * 26) continue;
                float other =
                        PrintedTupletBeamOwner.distance(
                                gray,
                                width,
                                height,
                                ax,
                                a.pageY() * height,
                                bx,
                                b.pageY() * height,
                                gap,
                                a.stemDirection(),
                                glyph.left(),
                                glyph.top(),
                                glyph.right(),
                                glyph.bottom());
                if (Float.isFinite(other) && other <= ownDistance + gap * .1f) return true;
            }
        }
        return false;
    }

    private static float attachedBeamDistance(
            Glyph glyph,
            ScoreNoteEvent first,
            List<ScoreNoteEvent> heads,
            MeasureRegion bar,
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float gap) {
        float x = (bar.left() + first.positionInMeasure() * (bar.right() - bar.left())) * width;
        if (first.beamCount() < 1 || first.stemDirection() == 0 || Math.abs(x - firstX) > gap * .4f)
            return Float.POSITIVE_INFINITY;
        float nearest = Float.POSITIVE_INFINITY;
        for (var last : heads) {
            if (last.measureIndex() != first.measureIndex()
                    || last.staffIndex() != first.staffIndex()
                    || last.staffCount() != first.staffCount()
                    || last.beamCount() < 1
                    || last.stemDirection() != first.stemDirection()) continue;
            float lx = (bar.left() + last.positionInMeasure() * (bar.right() - bar.left())) * width;
            if (Math.abs(lx - lastX) > gap * .4f) continue;
            nearest =
                    Math.min(
                            nearest,
                            PrintedTupletBeamOwner.distance(
                                    gray,
                                    width,
                                    height,
                                    x,
                                    first.pageY() * height,
                                    lx,
                                    last.pageY() * height,
                                    gap,
                                    first.stemDirection(),
                                    glyph.left(),
                                    glyph.top(),
                                    glyph.right(),
                                    glyph.bottom()));
        }
        return nearest;
    }

    /** A next-system numeral may sit outside its staff box but still own a physical beam. */
    private static boolean otherSystemBeamOwns(
            Glyph glyph,
            ScoreNoteEvent first,
            List<ScoreNoteEvent> heads,
            MeasureRegion current,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height,
            float gap,
            float ownDistance) {
        float numeralX = (glyph.left() + glyph.right()) * .5f;
        for (var a : heads) {
            if (a.measureIndex() == first.measureIndex()
                    || a.measureIndex() < 0
                    || a.measureIndex() >= measures.size()
                    || a.beamCount() < 1
                    || a.stemDirection() == 0
                    || a.crossStaffBeam()) continue;
            var region = measures.get(a.measureIndex());
            if (!(region.top() > current.bottom() || region.bottom() < current.top())) continue;
            float ax =
                    (region.left() + a.positionInMeasure() * (region.right() - region.left()))
                            * width;
            if (first.staffCount() == 1 && a.staffCount() == 1
                    && first.beamCount() == 2
                    && Math.abs((glyph.top() + glyph.bottom()) * .5f - first.pageY() * height)
                            > gap * 3.8f
                    && foreignNumeralEndpoint(gray, width, height, ax, a.pageY() * height,
                            a.stemDirection(), gap, glyph.left(), glyph.top(), glyph.right(),
                            glyph.bottom(), ownDistance)) return true;
            if (ax >= numeralX || numeralX - ax > gap * 26) continue;
            for (var b : heads) {
                if (b.measureIndex() != a.measureIndex()
                        || b.staffIndex() != a.staffIndex()
                        || b.staffCount() != a.staffCount()
                        || b.beamCount() != a.beamCount()
                        || b.stemDirection() != a.stemDirection()
                        || b.crossStaffBeam()) continue;
                float bx =
                        (region.left() + b.positionInMeasure() * (region.right() - region.left()))
                                * width;
                if (bx <= numeralX || bx - ax > gap * 26) continue;
                float other =
                        PrintedTupletBeamOwner.distance(
                                gray,
                                width,
                                height,
                                ax,
                                a.pageY() * height,
                                bx,
                                b.pageY() * height,
                                gap,
                                a.stemDirection(),
                                glyph.left(),
                                glyph.top(),
                                glyph.right(),
                                glyph.bottom());
                if (Float.isFinite(other) && other + gap * .1f < ownDistance) return true;
            }
        }
        return false;
    }

    /** A detached digit can be close to the preceding beam tip or the following notehead. */
    static boolean foreignNumeralEndpoint(byte[] gray, int width, int height, float x, float y,
            int stem, float gap, int left, int top, int right, int bottom, float ownDistance) {
        float numeralX = (left + right) * .5f;
        float numeralY = (top + bottom) * .5f;
        if (Math.abs(numeralX - x) > gap * .8f
                || Math.abs(numeralY - y) > gap * 5.4f) return false;
        var shafts = PrintedBeamShafts.at(
                gray, width, height, x, y, gap, new PrintedBeamShafts.Budget(30_000));
        if (shafts.size() != 1 || shafts.get(0).direction() != -stem) return false;
        var shaft = shafts.get(0);
        boolean beyondBeam = (numeralY - y) * shaft.direction() > 0;
        float ownerX = beyondBeam ? (shaft.left() + shaft.right()) * .5f : x;
        float distance = shaft.direction() > 0
                ? (beyondBeam ? top - shaft.endY() : y - bottom)
                : (beyondBeam ? shaft.endY() - bottom : top - y);
        return Math.abs(numeralX - ownerX) <= gap * .38f
                && distance >= gap * .16f
                && distance <= gap * (beyondBeam ? .95f : 1.25f)
                && distance + gap * .25f < ownDistance;
    }

    private static boolean ownsNumeral(
            Glyph glyph,
            ScoreNoteEvent first,
            List<ScoreNoteEvent> notes,
            MeasureRegion bar,
            float gap,
            int width,
            int height) {
        if (first.staffCount() < 2 || first.crossStaffBeam()) return true;
        float center = (glyph.top() + glyph.bottom()) * .5f;
        float x = (glyph.left() + glyph.right()) * .5f / width;
        float position = (x - bar.left()) / (bar.right() - bar.left());
        double own = Double.POSITIVE_INFINITY, other = Double.POSITIVE_INFINITY;
        for (ScoreNoteEvent note : notes) {
            if (note.measureIndex() != first.measureIndex()
                    || note.staffCount() != first.staffCount()
                    || Math.abs(note.positionInMeasure() - position) > .3f) continue;
            if (note.crossStaffBeam() && note.staffIndex() == first.staffIndex()) return true;
            float bottom = note.pageY() * height + note.staffStep() * gap * .5f;
            double distance = Math.max(0, Math.max(bottom - 4 * gap - center, center - bottom));
            if (note.staffIndex() == first.staffIndex()) own = Math.min(own, distance);
            else other = Math.min(other, distance);
        }
        return own <= other + gap * .35;
    }

    /** Two explicitly labelled six-note groups may be adjacent without a spacing gap. */
    private static boolean adjacentTuplet(
            List<Onset> groups,
            List<ScoreNoteEvent> notes,
            int start,
            ScoreNoteEvent first,
            MeasureRegion bar,
            byte[] gray,
            int width,
            int height,
            float gap, int divisor) {
        if (start < 0 || start + divisor > groups.size()) return false;
        float top = Float.MAX_VALUE, bottom = 0, min = Float.MAX_VALUE, max = 0;
        for (int j = 0; j < divisor; j++) {
            Onset onset = matching(groups.get(start + j), notes, first, divisor == 7);
            if (onset == null) return false;
            top = Math.min(top, onset.top() * height);
            bottom = Math.max(bottom, onset.bottom() * height);
            if (j > 0) {
                float d = onset.position() - groups.get(start + j - 1).position();
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
        }
        if (min < .012f || max > min * 1.6f) return false;
        float x1 = (bar.left() + groups.get(start).position() * (bar.right() - bar.left())) * width;
        float x2 =
                (bar.left() + groups.get(start + divisor - 1).position() * (bar.right() - bar.left()))
                        * width;
        if (x2 - x1 < gap * 3 || x2 - x1 > gap * 26) return false;
        Glyph glyph =
                findContrastedNumeral(
                        gray, width, height, x1, x2, top, bottom, gap, true, Float.NaN, Float.NaN,
                        divisor);
        return glyph != null;
    }

    /** Complete printed numeral evidence overrides spurious head/beam predictions on that glyph. */
    static List<ScoreNoteEvent> withoutNumeralHeads(
            List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height) {
        if (notes == null
                || notes.size() < 3
                || measures == null
                || gray == null
                || width < 1
                || height < 1
                || gray.length != width * height) return notes;
        var activeByStaff = new HashMap<MeasureStaff, List<ScoreNoteEvent>>();
        for (var note : notes)
            activeByStaff.computeIfAbsent(MeasureStaff.of(note), ignored -> new ArrayList<>()).add(note);
        List<ScoreNoteEvent> result = new ArrayList<>(notes);
        for (ScoreNoteEvent candidate : notes) {
            if (candidate.tiedFromPrevious()
                    || candidate.measureIndex() < 0
                    || candidate.measureIndex() >= measures.size()) continue;
            MeasureRegion region = measures.get(candidate.measureIndex());
            float gap =
                    Math.max(
                            4,
                            (region.bottom() - region.top())
                                    * height
                                    / (8 * candidate.staffCount()));
            var active = activeByStaff.get(MeasureStaff.of(candidate));
            List<ScoreNoteEvent> voice = new ArrayList<>();
            for (ScoreNoteEvent n : active)
                if (n != candidate
                        && !(Math.abs(n.positionInMeasure() - candidate.positionInMeasure())
                                        <= .018f
                                && Math.abs(n.pageY() - candidate.pageY()) * height <= gap * 2.3f))
                    voice.add(n);
            if (voice.size() < 2 || voice.size() == 2 && candidate.stemDirection() != 0) continue;
            List<Onset> groups = onsets(voice);
            float candidateX =
                    (region.left()
                                    + candidate.positionInMeasure()
                                            * (region.right() - region.left()))
                            * width;
            float candidateY = candidate.pageY() * height;
            if (candidate.stemDirection() == 0) {
                boolean printedNumeral = false;
                for (int i = 0; i + 1 < groups.size(); i++) {
                    Onset left = groups.get(i), right = groups.get(i + 1);
                    if (left.indices().size() != 1 || right.indices().size() != 1) continue;
                    ScoreNoteEvent a = voice.get(left.indices().get(0));
                    ScoreNoteEvent b = voice.get(right.indices().get(0));
                    float x1 =
                            (region.left()
                                            + a.positionInMeasure()
                                                    * (region.right() - region.left()))
                                    * width;
                    float x2 =
                            (region.left()
                                            + b.positionInMeasure()
                                                    * (region.right() - region.left()))
                                    * width;
                    float y1 = Math.min(a.pageY(), b.pageY()) * height;
                    float y2 = Math.max(a.pageY(), b.pageY()) * height;
                    if (x2 - x1 < gap * 2
                            || x2 - x1 > gap * 18
                            || candidateX <= x1
                            || candidateX >= x2
                            || candidateY >= y1 - gap && candidateY <= y2 + gap) continue;
                    // Pitched stem-direction fields are populated after this cleanup.
                    // The bracket side supplies a hypothesis; raw shafts must prove it.
                    int direction = candidateY < y1 ? 1 : -1;
                    if (a.stemDirection() != 0 && a.stemDirection() != direction
                            || b.stemDirection() != 0 && b.stemDirection() != direction) continue;
                    final ScoreNoteEvent ownedA = a.withStemDirection(direction);
                    final ScoreNoteEvent ownedB = b.withStemDirection(direction);
                    Glyph glyph =
                            findContrastedNumeral(
                                    gray,
                                    width,
                                    height,
                                    x1,
                                    x2,
                                    y1,
                                    y2,
                                    gap,
                                    true,
                                    candidateX,
                                    candidateY,
                                    3,
                                    false,
                                    found ->
                                            printedBracketOwnsPair(
                                                    found, ownedA, ownedB, voice, region, measures,
                                                    gray, width, height, x1, x2, gap));
                    if (glyph != null) {
                        printedNumeral = true;
                        break;
                    }
                }
                if (printedNumeral) {
                    active.remove(candidate);
                    result.remove(candidate);
                    continue;
                }
            }

            for (int i = 0; i + 2 < groups.size(); i++) {
                for (List<Onset> group :
                        triples(groups.get(i), groups.get(i + 1), groups.get(i + 2), voice, true)) {
                    Onset a = group.get(0), b = group.get(1), c = group.get(2);
                    float x1 =
                            (region.left() + a.position() * (region.right() - region.left()))
                                    * width;
                    float x3 =
                            (region.left() + c.position() * (region.right() - region.left()))
                                    * width;
                    float y1 = Math.min(a.top(), Math.min(b.top(), c.top())) * height;
                    float y2 = Math.max(a.bottom(), Math.max(b.bottom(), c.bottom())) * height;
                    if (x3 - x1 < gap * 2
                            || x3 - x1 > gap * 18
                            || candidateX < x1 - gap
                            || candidateX > x3 + gap
                            || candidateY >= y1 - gap && candidateY <= y2 + gap) continue;
                    if (findPrintedThree(
                                    gray,
                                    width,
                                    height,
                                    x1,
                                    x3,
                                    y1,
                                    y2,
                                    gap,
                                    true,
                                    candidateX,
                                    candidateY)
                            != null) {
                        active.remove(candidate);
                        result.remove(candidate);
                        break;
                    }
                }
                if (!active.contains(candidate)) break;
            }
        }
        return List.copyOf(result);
    }

    /** A numeral printed inside another system cannot change this row's rhythm. */
    private static boolean insideOtherSystem(
            Glyph numeral,
            MeasureRegion current,
            List<MeasureRegion> measures,
            int width,
            int height) {
        float x = (numeral.left() + numeral.right()) * .5f / width;
        float y = (numeral.top() + numeral.bottom()) * .5f / height;
        if (y >= current.top() && y <= current.bottom()) return false;
        for (var other : measures)
            if ((other.top() > current.bottom() || other.bottom() < current.top())
                    && x >= other.left()
                    && x <= other.right()
                    && y >= other.top()
                    && y <= other.bottom()) return true;
        return false;
    }

    /** The literal that owns the three attacks cannot also name a finger. */
    static boolean ownsTripletFingeringBox(
            byte[] gray, int width, int height, ScorePageInterpretation score, int middle,
            double left, double top, double right, double bottom) {
        var notes = score.notes();
        if (middle < 1 || middle + 1 >= notes.size()) return false;
        var a = notes.get(middle - 1);
        var b = notes.get(middle);
        var c = notes.get(middle + 1);
        int stem = b.stemDirection(), beams = b.beamCount();
        if (!sameVoice(a,b) || !sameVoice(b,c) || stem == 0 || beams < 1
                || a.positionInMeasure() >= b.positionInMeasure()
                || b.positionInMeasure() >= c.positionInMeasure()
                || !sameBeamedTriplet(a, stem, beams) || !sameBeamedTriplet(b, stem, beams)
                || !sameBeamedTriplet(c, stem, beams)) return false;
        var bar = score.measures().get(b.measureIndex());
        float gap = (bar.bottom() - bar.top()) * height / (8 * b.staffCount());
        float x1 = (bar.left() + a.positionInMeasure() * (bar.right() - bar.left())) * width;
        float x3 = (bar.left() + c.positionInMeasure() * (bar.right() - bar.left())) * width;
        float y1 = a.pageY() * height, y2 = b.pageY() * height, y3 = c.pageY() * height;
        if (gap < 5 || gap > 32 || x3 - x1 < gap * 2 || x3 - x1 > gap * 16
                || Math.abs((left + right) / 2 - (x1 + x3) / 2) > gap * 1.25f
                || stem * ((top + bottom) / 2 - y2) >= 0) return false;
        var glyph = findOwnedNumeral(gray, width, height, x1, x3,
                Math.min(y1, Math.min(y2,y3)), Math.max(y1, Math.max(y2,y3)), gap, true,
                Float.NaN, Float.NaN, 3, true, beams >= 2, a, notes, bar, score.measures());
        return glyph != null && glyph.left() >= left - 1 && glyph.right() <= right + 1
                && glyph.top() >= top - 1 && glyph.bottom() <= bottom + 1
                && beamOwnsNumeral(glyph, a, notes, bar, gray, width, height, x1, x3, gap);
    }

    private static boolean sameBeamedTriplet(ScoreNoteEvent n, int stem, int beams) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.stemDirection() == stem
                && n.beamCount() == beams && n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2
                && !n.crossStaffBeam() && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private record Glyph(int left, int top, int right, int bottom) {}

    private static Glyph findPrintedThree(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY) {
        return findContrastedNumeral(
                gray,
                width,
                height,
                firstX,
                lastX,
                firstY,
                lastY,
                gap,
                shortNotes,
                headX,
                headY,
                3);
    }

    private static Glyph findContrastedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number) {
        return findContrastedNumeral(
                gray,
                width,
                height,
                firstX,
                lastX,
                firstY,
                lastY,
                gap,
                shortNotes,
                headX,
                headY,
                number,
                false);
    }

    private static Glyph findContrastedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number,
            boolean boundedSecondary) {
        return findContrastedNumeral(
                gray,
                width,
                height,
                firstX,
                lastX,
                firstY,
                lastY,
                gap,
                shortNotes,
                headX,
                headY,
                number,
                boundedSecondary,
                glyph -> true);
    }

    private static Glyph findContrastedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number,
            boolean boundedSecondary,
            java.util.function.Predicate<Glyph> ownership) {
        return findContrastedNumeral(
                gray,
                width,
                height,
                firstX,
                lastX,
                firstY,
                lastY,
                gap,
                shortNotes,
                headX,
                headY,
                number,
                boundedSecondary,
                ownership,
                glyph -> false);
    }

    private static Glyph findContrastedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number,
            boolean boundedSecondary,
            java.util.function.Predicate<Glyph> ownership,
            java.util.function.Predicate<Glyph> physicalGroupContext) {
        return findContrastedNumeral(
                gray,
                width,
                height,
                firstX,
                lastX,
                firstY,
                lastY,
                gap,
                shortNotes,
                headX,
                headY,
                number,
                boundedSecondary,
                ownership,
                physicalGroupContext,
                null);
    }

    private static Glyph findContrastedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number,
            boolean boundedSecondary,
            java.util.function.Predicate<Glyph> ownership,
            java.util.function.Predicate<Glyph> physicalGroupContext,
            PrintedTupletBeamOwner.LiteralProof literal) {
        boolean[] fourCandidate = number == 3 ? new boolean[1] : null;
        Glyph normal =
                findPrintedNumeral(
                        gray,
                        width,
                        height,
                        firstX,
                        lastX,
                        firstY,
                        lastY,
                        gap,
                        shortNotes,
                        headX,
                        headY,
                        number,
                        boundedSecondary,
                        ownership, 8, glyph -> false, fourCandidate);
        if (normal != null
                && TupletNumeralInk.hasGlyphContrast(
                        gray,
                        width,
                        height,
                        normal.left(),
                        normal.top(),
                        normal.right(),
                        normal.bottom(),
                        gap)
                && (boundedSecondary
                        || validNumeralContext(
                                gray, width, height, normal, firstX, lastX, gap, number)))
            return normal;
        // Photometric retries may remove a four's pale crossbar. Keep the complete
        // original component and its unchanged physical ownership authoritative.
        Glyph originalFour =
                number == 3 && (normal != null || fourCandidate[0])
                        ? findPrintedNumeral(
                                gray,
                                width,
                                height,
                                firstX,
                                lastX,
                                firstY,
                                lastY,
                                gap,
                                shortNotes,
                                headX,
                                headY,
                                4,
                                boundedSecondary,
                                ownership)
                        : null;
        Glyph candidate = null;
        int candidateLevel = -1;
        // Keep legacy successful returns first. A noisy first accepted outline must
        // not hide agreement between two later, independently thresholded outlines.
        int[] levels = {165, 140, 120, 190, 210, 150};
        Glyph[] admitted = new Glyph[2 * levels.length];
        int[] admittedLevels = new int[admitted.length];
        int admittedCount = 0;
        Glyph deferredConsensus = null;
        boolean pendingEnabled = literal != null && literal.reserve(64);
        Glyph[] pending = pendingEnabled ? new Glyph[admitted.length] : null;
        int[] pendingLevels = pendingEnabled ? new int[admitted.length] : null;
        int pendingCount = 0;
        var crop = TupletNumeralInk.crop(gray, width, height, firstX, lastX, firstY, lastY, gap);
        var retryMemo = crop == null ? null : TupletNumeralInk.activeRetryMemo(crop.gray());
        var search = retryMemo == null ? null : new TupletNumeralInk.RetryGeometry(width,
                crop.left(), crop.top(), crop.width(), crop.height(), firstX, lastX, firstY,
                lastY, gap, headX, headY, shortNotes, number, boundedSecondary);
        // Equal effective thresholds have the same binary ink. Keep the requested
        // levels and ownership call order for consensus and budget accounting.
        int[] thresholds = new int[levels.length];
        if (crop != null)
            for (int i = 0; i < levels.length; i++) thresholds[i] = crop.effectiveThreshold(levels[i]);
        // Keep cross-pass masks only for small crops to bound retained pixel storage.
        var unchanged = crop != null && (long) crop.width() * crop.height() <= 16_384
                ? new TupletNumeralInk.Window[levels.length] : null;
        boolean[] consultedOwner = {false};
        int crossPassMisses = 0;
        int paperIndex = -1;
        if (crop != null)
            for (int i = 0; i < levels.length; i++)
                if (levels[i] >= crop.paperLimit()) {
                    paperIndex = i;
                    break;
                }
        for (int pass = 0; crop != null && crop.paperLimit() >= 100 && pass < 2; pass++) {
            TupletNumeralInk.Window paperLimited = null;
            int misses = 0;
            for (int retryIndex = 0; retryIndex < levels.length; retryIndex++) {
                int level = levels[retryIndex], threshold = thresholds[retryIndex];
                if (threshold < 0) continue;
                int key = 0;
                while (thresholds[key] != threshold) key++;
                int retryBit = 1 << key;
                if ((misses & retryBit) != 0 || (pass != 0 && (crossPassMisses & retryBit) != 0))
                    continue;
                var retryKey = search == null ? null : new TupletNumeralInk.RetryKey(
                        search, threshold, pass != 0);
                if (retryMemo != null) {
                    var known = retryMemo.find(retryKey);
                    if (known != null) {
                        misses |= retryBit;
                        if (pass == 0 && known == TupletNumeralInk.RetryMiss.UNCHANGED_EDGES)
                            crossPassMisses |= retryBit;
                        continue;
                    }
                }
                var saved = unchanged == null ? null : unchanged[key];
                TupletNumeralInk.Window local;
                if (paperIndex >= 0 && threshold == thresholds[paperIndex]) {
                    if (paperLimited == null)
                        paperLimited = saved != null ? saved : crop.window(level, pass != 0);
                    local = paperLimited;
                } else {
                    local = saved != null ? saved : crop.window(level, pass != 0);
                }
                if (local == null) continue;
                if (pass == 0 && unchanged != null && !local.hasRuleEdges()) unchanged[key] = local;
                consultedOwner[0] = false;
                Glyph retry =
                        findPrintedNumeral(
                                local.pixels(),
                                local.width(),
                                local.height(),
                                firstX - local.left(),
                                lastX - local.left(),
                                firstY - local.top(),
                                lastY - local.top(),
                                gap,
                                shortNotes,
                                headX - local.left(),
                                headY - local.top(),
                                number,
                                boundedSecondary,
                                glyph -> {
                                    consultedOwner[0] = true;
                                    return ownership.test(
                                                new Glyph(
                                                        glyph.left() + local.left(),
                                                                glyph.top() + local.top(),
                                                        glyph.right() + local.left(),
                                                                glyph.bottom() + local.top()));
                                });
                if (retry == null && !consultedOwner[0]) {
                    if (retryMemo != null) retryMemo.record(retryKey, local.hasRuleEdges());
                    misses |= retryBit;
                    if (pass == 0 && !local.hasRuleEdges()) crossPassMisses |= retryBit;
                    continue;
                }
                if (retry != null
                        && TupletNumeralInk.hasGlyphContrast(
                                gray,
                                width,
                                height,
                                retry.left() + local.left(),
                                retry.top() + local.top(),
                                retry.right() + local.left(),
                                retry.bottom() + local.top(),
                                gap)) {
                    Glyph found =
                            new Glyph(
                                    retry.left() + local.left(),
                                    retry.top() + local.top(),
                                    retry.right() + local.left(),
                                    retry.bottom() + local.top());
                    if (originalFour != null
                            && originalFourOwnsRetry(gray, width, originalFour, found)) continue;
                    if (pendingEnabled) {
                        pending[pendingCount] = found;
                        pendingLevels[pendingCount++] = level;
                    }
                    boolean legacyContext =
                            boundedSecondary
                                    || validNumeralContext(
                                            gray, width, height, found, firstX, lastX, gap, number);
                    if (!legacyContext && !physicalGroupContext.test(found)) continue;
                    // A single threshold can turn antialiased rest ink into two apparent
                    // bowls. A recovered numeral needs the same outline at another level;
                    // two masking passes at one threshold are not independent evidence.
                    if (legacyContext
                            && candidate != null
                            && candidateLevel != level
                            && Math.abs(candidate.left() - found.left()) <= gap * .25f
                            && Math.abs(candidate.right() - found.right()) <= gap * .25f
                            && Math.abs(candidate.top() - found.top()) <= gap * .25f
                            && Math.abs(candidate.bottom() - found.bottom()) <= gap * .25f)
                        return found;
                    if (deferredConsensus == null)
                        for (int earlier = 0; earlier < admittedCount; earlier++) {
                            Glyph previous = admitted[earlier];
                            if (admittedLevels[earlier] != level
                                    && Math.abs(previous.left() - found.left()) <= gap * .25f
                                    && Math.abs(previous.right() - found.right()) <= gap * .25f
                                    && Math.abs(previous.top() - found.top()) <= gap * .25f
                                    && Math.abs(previous.bottom() - found.bottom()) <= gap * .25f) {
                                deferredConsensus = found;
                                break;
                            }
                        }
                    admitted[admittedCount] = found;
                    admittedLevels[admittedCount++] = level;
                    if (legacyContext && candidate == null) {
                        candidate = found;
                        candidateLevel = level;
                    }
                }
            }
        }
        if (deferredConsensus != null) return deferredConsensus;
        return pendingEnabled
                ? literalFallback(
                        literal,
                        pending,
                        pendingLevels,
                        pendingCount,
                        firstX,
                        lastX,
                        firstY,
                        lastY,
                        gap,
                        shortNotes,
                        headX,
                        headY,
                        number,
                        boundedSecondary)
                : null;
    }

    /** The retry must be ink from this same complete raw four, not a neighboring glyph. */
    private static boolean originalFourOwnsRetry(byte[] gray, int width, Glyph four, Glyph retry) {
        if (retry.left() < four.left()
                || retry.right() > four.right()
                || retry.top() < four.top()
                || retry.bottom() > four.bottom()) return false;
        int w = four.right() - four.left() + 1, h = four.bottom() - four.top() + 1;
        int seed = -1;
        for (int y = retry.top(); y <= retry.bottom() && seed < 0; y++)
            for (int x = retry.left(); x <= retry.right(); x++)
                if (dark(gray, width, x, y)) {
                    seed = (y - four.top()) * w + x - four.left();
                    break;
                }
        if (seed < 0) return false;
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[seen.length];
        byte[] component = new byte[seen.length];
        java.util.Arrays.fill(component, (byte) 255);
        int begin = 0, end = 1, minX = w, maxX = -1, minY = h, maxY = -1;
        queue[0] = seed;
        seen[seed] = true;
        while (begin < end) {
            int p = queue[begin++], x = p % w, y = p / w;
            component[p] = 0;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                    int next = ny * w + nx;
                    if (!seen[next] && dark(gray, width, four.left() + nx, four.top() + ny)) {
                        seen[next] = true;
                        queue[end++] = next;
                    }
                }
        }
        if (minX != 0 || maxX != w - 1 || minY != 0 || maxY != h - 1) return false;
        for (int y = retry.top(); y <= retry.bottom(); y++)
            for (int x = retry.left(); x <= retry.right(); x++)
                if (dark(gray, width, x, y) && !seen[(y - four.top()) * w + x - four.left()])
                    return false;
        return completeFourCrossbar(component, w, end, 0, 0, w, h);
    }

    private static Glyph findPrintedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number) {
        return findPrintedNumeral(
                gray,
                width,
                height,
                firstX,
                lastX,
                firstY,
                lastY,
                gap,
                shortNotes,
                headX,
                headY,
                number,
                false);
    }

    private static Glyph findPrintedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number,
            boolean boundedSecondary) {
        return findPrintedNumeral(
                gray,
                width,
                height,
                firstX,
                lastX,
                firstY,
                lastY,
                gap,
                shortNotes,
                headX,
                headY,
                number,
                boundedSecondary,
                glyph -> true);
    }

    private static Glyph findPrintedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number,
            boolean boundedSecondary,
            java.util.function.Predicate<Glyph> ownership) {
        return findPrintedNumeral(
                gray,
                width,
                height,
                firstX,
                lastX,
                firstY,
                lastY,
                gap,
                shortNotes,
                headX,
                headY,
                number,
                boundedSecondary,
                ownership,
                8,
                glyph -> false);
    }

    private static Glyph findPrintedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number,
            boolean boundedSecondary,
            java.util.function.Predicate<Glyph> ownership,
            float searchGaps,
            java.util.function.Predicate<Glyph> extendedOwnership) {
        return findPrintedNumeral(gray, width, height, firstX, lastX, firstY, lastY, gap,
                shortNotes, headX, headY, number, boundedSecondary, ownership, searchGaps, extendedOwnership, null);
    }

    private static Glyph findPrintedNumeral(
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float firstY,
            float lastY,
            float gap,
            boolean shortNotes,
            float headX,
            float headY,
            int number,
            boolean boundedSecondary,
            java.util.function.Predicate<Glyph> ownership,
            float searchGaps,
            java.util.function.Predicate<Glyph> extendedOwnership, boolean[] fourCandidate) {
        float centerX = (firstX + lastX) * .5f;
        // Numerals align with the beam/stems, which can sit to one side of the
        // oval centres. Include that offset without clipping an italic 3.
        float searchWidth = gap * (number == 2 ? 3.35f : 2.1f);
        int left = Math.max(0, Math.round(centerX - searchWidth));
        int right = Math.min(width - 1, Math.round(centerX + searchWidth));
        int normalTop = Math.max(0, Math.round(firstY - gap * 7));
        int top = Math.max(0, Math.round(firstY - gap * searchGaps));
        int normalBottom = Math.min(height - 1, Math.round(lastY + gap * 7));
        int bottom = Math.min(height - 1, Math.round(lastY + gap * searchGaps));
        int localWidth = right - left + 1, localHeight = bottom - top + 1;
        if (localWidth < 3 || localHeight < 3) return null;
        int firstOrigin = firstNumeralInk(gray, width, left, top, bottom, localWidth);
        if (firstOrigin < 0) return null;
        int cells = localWidth * localHeight;
        int rasterStart = top * width + left;

        boolean usesComponentShape = number != 2 && number != 4 && number != 5
                && number != 6 && number != 7;
        float maximumHeight = gap * 2.3f, maximumWidth = maximumHeight * .95f;
        // A component admitted by the size checks must fit inside this many pixels.
        // Use one count bound instead of testing its changing box at every pixel.
        int shapeLimit = maximumHeight >= (1 << 24) ? cells
                : (int) Math.min(cells,
                (long) Math.min(localHeight, (int) maximumHeight)
                        * Math.min(localWidth, (int) maximumWidth));
        boolean rescueBracket = number == 3 && shortNotes;
        float upperNumeralLimit = firstY - gap, lowerNumeralLimit = lastY + gap;
        var flood = new NumeralRuns(gray, rasterStart, width, localWidth, localHeight, firstOrigin);
        int firstSeedRow = firstOrigin / localWidth;
        int firstSeedColumn = firstOrigin - firstSeedRow * localWidth;
        int lowerGuard = Math.max(top, Math.min(bottom, (int) Math.floor(lowerNumeralLimit))) - top;
        for (int seedY = firstSeedRow; seedY < localHeight; seedY++) {
            if (seedY < lowerGuard && top + seedY >= upperNumeralLimit) {
                // This middle band cannot own a numeral. Flood its last row so an
                // attached stroke cannot be rediscovered as a detached lower glyph.
                seedY = lowerGuard;
            }
            int row = seedY * localWidth, raster = rasterStart + seedY * width;
            for (int seedX = seedY == firstSeedRow ? firstSeedColumn : 0;
                    seedX < localWidth; seedX++) {
                int origin = row + seedX;
                if (flood.visited[origin]) continue;
                if (origin != firstOrigin && (gray[raster + seedX] & 255) > NUMERAL_INK_LIMIT) {
                    // Only ink can seed a flood. Bright neighbors are checked when reached.
                    int next = nextNumeralInk(gray, raster + seedX + 1, raster + localWidth);
                    if (next < 0) break;
                    seedX = next - raster - 1;
                    continue;
                }
                flood.collect(seedX, seedY, usesComponentShape ? shapeLimit : 0);
                int count = flood.count, minX = flood.minX, maxX = flood.maxX, maxY = flood.maxY;
                int[] component = flood.component;
                minX += left;
                maxX += left;
                int minY = top + seedY;
                maxY += top;
                if (rescueBracket && minX <= left && maxX >= right && minY < upperNumeralLimit) {
                    Glyph joined =
                            bracketedCurveThree(
                                    gray, width, height, left, right, minY, firstX, lastX, firstY, gap,
                                    ownership);
                    if (joined != null
                            && (!Float.isFinite(headX)
                                    || headX >= joined.left() - gap * .1f
                                            && headX <= joined.right() + gap * .1f
                                            && headY >= joined.top() - gap * .1f
                                            && headY <= joined.bottom() + gap * .1f)) return joined;
                }
                // A complete bracket can place a numeral closer to the highest head.
                boolean bracketNearHead = number == 3 && maxY >= upperNumeralLimit
                        && maxY < firstY - gap * .7f;
                int gw = maxX - minX + 1, gh = maxY - minY + 1;
                if (minX <= left
                        || maxX >= right
                        || minY <= top
                        || maxY >= bottom
                        || gh < gap * .7f
                        || gh > maximumHeight
                        || gw < gh * .30f
                        || gw > gh * .95f
                        || count < gw * gh * .15f
                        || count > gw * gh * .70f
                        || !(maxY < upperNumeralLimit || minY > lowerNumeralLimit || bracketNearHead)) continue;
                if (Float.isFinite(headX)
                        && (headX < minX - gap * .1f
                                || headX > maxX + gap * .1f
                                || headY < minY - gap * .1f
                                || headY > maxY + gap * .1f)) continue;
                // Three and four use identical windows, connectivity, size and head bounds.
                // A complete three scan with no such component also rules out a raw four.
                if (fourCandidate != null) fourCandidate[0] = true;
                if (!(number == 2
                        ? looksLikeTwo(gray, width, count, minX, maxX, minY, maxY)
                        : number == 4
                                ? looksLikeFingerFour(gray, width, count, minX, maxX, minY, maxY)
                                : number == 7
                                        ? looksLikeSeven(gray, width, minX, minY, gw, gh)
                                        : number == 5
                                                ? looksLikeFive(gray, width, minX, minY, gw, gh)
                                                : number == 6
                                                        ? TupletSixGlyph.matches(
                                                                gray, width, minX, minY, gw, gh)
                                                        : looksLikeComponentThree(
                                                                component,
                                                                count,
                                                                localWidth,
                                                                left,
                                                                top,
                                                                minX,
                                                                minY,
                                                                gw,
                                                                gh))) continue;
                if (TupletNumeralNeighbors.joinedText(gray, width, height, minX, minY, maxX, maxY))
                    continue;
                // Quarter-note tuplets need the two bracket arms. For beamed/flagged short notes,
                // publishers routinely print only the numeral, so its shape/group alignment suffices.
                boolean bracket =
                        bracketArm(
                                        gray,
                                        width,
                                        height,
                                        Math.round(firstX - gap * .3f),
                                        minX - 2,
                                        minY,
                                        maxY,
                                        gap)
                                && bracketArm(
                                        gray,
                                        width,
                                        height,
                                        maxX + 2,
                                        Math.round(lastX + gap * .3f),
                                        minY,
                                        maxY,
                                        gap);
                // An unbracketed two-bowl shape inside five actual staff rules can be
                // a rest, not a numeral below an elevated beamed melody. Explicit
                // brackets still prove in-staff tuplets; isolated beam rules do not.
                if (number == 3
                        && !boundedSecondary
                        && !bracket
                        && insideFiveLineStaff(gray, width, height, minX, minY, maxX, maxY, gap))
                    continue;
                if ((minY <= normalTop || maxY >= normalBottom)
                        && !bracket
                        && !extendedOwnership.test(new Glyph(minX, minY, maxX, maxY))) continue;
                if (bracketNearHead && !bracket) continue;
                if (shortNotes || bracket) {
                    Glyph glyph = new Glyph(minX, minY, maxX, maxY);
                    if (ownership.test(glyph)) return glyph;
                }
            }
        }
        return null;
    }

    private static final class NumeralRuns {
        private final byte[] gray;
        private final int rasterStart, stride, width, height;
        private final boolean[] visited;
        private int[] starts, ends, component;
        private int pending, count, minX, maxX, maxY;

        NumeralRuns(byte[] gray, int rasterStart, int stride, int width, int height, int first) {
            this.gray = gray;
            this.rasterStart = rasterStart;
            this.stride = stride;
            this.width = width;
            this.height = height;
            visited = new boolean[width * height];
            java.util.Arrays.fill(visited, Math.max(0, first - width - 1), first, true);
            starts = new int[Math.min(64, visited.length)];
            ends = new int[starts.length];
        }

        void collect(int x, int y, int shapeLimit) {
            count = 0;
            minX = maxX = x;
            maxY = y;
            boolean keepShape = shapeLimit > 0 && y > 0;
            push(x, y * width, rasterStart + y * stride);
            while (pending > 0) {
                int start = starts[--pending], end = ends[pending];
                int row = start / width, base = row * width;
                int left = start - base, right = end - base;
                minX = Math.min(minX, left);
                maxX = Math.max(maxX, right);
                maxY = Math.max(maxY, row);
                int size = end - start + 1;
                keepShape &= left > 0 && right < width - 1 && row < height - 1;
                if (keepShape && count + size <= shapeLimit) {
                    if (component == null) component = new int[Math.min(128, shapeLimit)];
                    while (count + size > component.length)
                        component = growNumeralScratch(component, shapeLimit);
                    for (int i = 0; i < size; i++) component[count + i] = start + i;
                }
                count += size;
                int from = Math.max(0, left - 1), to = Math.min(width - 1, right + 1);
                if (row > 0) visit(from, to, base - width, rasterStart + (row - 1) * stride);
                if (row + 1 < height) visit(from, to, base + width, rasterStart + (row + 1) * stride);
            }
        }

        private void visit(int from, int to, int row, int raster) {
            for (int x = from; x <= to; x++) {
                if (visited[row + x]) continue;
                if ((gray[raster + x] & 255) <= NUMERAL_INK_LIMIT) x = push(x, row, raster);
                else visited[row + x] = true;
            }
        }

        private int push(int x, int row, int raster) {
            int left = x, right = x;
            while (left > 0 && !visited[row + left - 1]
                    && (gray[raster + left - 1] & 255) <= NUMERAL_INK_LIMIT) left--;
            while (right + 1 < width && !visited[row + right + 1]
                    && (gray[raster + right + 1] & 255) <= NUMERAL_INK_LIMIT) right++;
            java.util.Arrays.fill(visited, row + left, row + right + 1, true);
            if (pending == starts.length) {
                starts = growNumeralScratch(starts, visited.length);
                ends = growNumeralScratch(ends, visited.length);
            }
            starts[pending] = row + left;
            ends[pending++] = row + right;
            return right;
        }
    }

    private static final int NUMERAL_INK_LIMIT = 165;
    private static final java.lang.invoke.VarHandle NUMERAL_WORDS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(
                    long[].class, java.nio.ByteOrder.nativeOrder());
    private static final long NUMERAL_BYTE_HIGH_BITS = 0x8080808080808080L;
    private static final long NUMERAL_BRIGHT_LOW_BITS =
            0x0101010101010101L * (NUMERAL_INK_LIMIT + 1 - 128);

    private static int firstNumeralInk(byte[] gray, int width, int left,
            int top, int bottom, int localWidth) {
        for (int y = top, origin = 0, start = top * width + left; y <= bottom;
                y++, origin += localWidth, start += width) {
            int ink = nextNumeralInk(gray, start, start + localWidth);
            if (ink >= 0) return origin + ink - start;
        }
        return -1;
    }

    private static int nextNumeralInk(byte[] gray, int at, int end) {
        int wordEnd = end - Long.BYTES;
        for (; at <= wordEnd; at += Long.BYTES) {
            long word = (long) NUMERAL_WORDS.get(gray, at);
            // The high bits prevent a borrow from crossing byte boundaries.
            long bright = ((word | NUMERAL_BYTE_HIGH_BITS) - NUMERAL_BRIGHT_LOW_BITS)
                    & word & NUMERAL_BYTE_HIGH_BITS;
            if (bright == NUMERAL_BYTE_HIGH_BITS) continue;
            for (int i = at; i < at + Long.BYTES; i++)
                if ((gray[i] & 255) <= NUMERAL_INK_LIMIT) return i;
        }
        for (; at < end; at++)
            if ((gray[at] & 255) <= NUMERAL_INK_LIMIT) return at;
        return -1;
    }

    private static int[] growNumeralScratch(int[] buffer, int limit) {
        return java.util.Arrays.copyOf(buffer, buffer.length + Math.min(buffer.length, limit - buffer.length));
    }

    /** Validate against the untouched raster, not a retry with its staff rules erased. */
    private static boolean validNumeralContext(
            byte[] gray,
            int width,
            int height,
            Glyph glyph,
            float firstX,
            float lastX,
            float gap,
            int number) {
        if (number != 3
                || !insideFiveLineStaff(
                        gray,
                        width,
                        height,
                        glyph.left(),
                        glyph.top(),
                        glyph.right(),
                        glyph.bottom(),
                        gap)) return true;
        return bracketHook(gray, width, height, firstX, glyph.top(), glyph.bottom(), gap)
                && bracketHook(gray, width, height, lastX, glyph.top(), glyph.bottom(), gap);
    }

    private static boolean bracketHook(
            byte[] gray, int width, int height, float x, int top, int bottom, float gap) {
        for (int xx = Math.max(0, Math.round(x - gap * .4f));
                xx <= Math.min(width - 1, Math.round(x + gap * .4f));
                xx++) {
            int run = 0;
            for (int y = Math.max(0, Math.round(top - gap * .5f));
                    y <= Math.min(height - 1, Math.round(bottom + gap * .5f));
                    y++) {
                run = dark(gray, width, xx, y) ? run + 1 : 0;
                if (run >= Math.max(3, Math.round(gap * .65f))) return true;
            }
        }
        return false;
    }

    private static boolean insideFiveLineStaff(
            byte[] gray,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            float gap) {
        float center = (top + bottom) * .5f;
        int x0 = Math.max(0, Math.round(left - gap * 5)),
                x1 = Math.min(width - 1, Math.round(right + gap * 5));
        if (x1 - x0 < gap * 6) return false;
        var rules = new ArrayList<Integer>();
        boolean previous = false;
        for (int y = Math.max(0, Math.round(center - gap * 6));
                y <= Math.min(height - 1, Math.round(center + gap * 6));
                y++) {
            int count = 0, total = 0;
            for (int x = x0; x <= x1; x++)
                if (x < left - 1 || x > right + 1) {
                    total++;
                    if (dark(gray, width, x, y)) count++;
                }
            boolean rule = total > 0 && count >= total * .8f;
            if (rule && !previous) rules.add(y);
            previous = rule;
        }
        for (int i = 0; i + 4 < rules.size(); i++) {
            float spacing = (rules.get(i + 4) - rules.get(i)) / 4f;
            if (spacing < 3
                    || spacing < gap * .4f
                    || spacing > gap * 2
                    || center < rules.get(i)
                    || center > rules.get(i + 4)) continue;
            boolean regular = true;
            for (int j = 1; j <= 4; j++)
                if (Math.abs(rules.get(i + j) - rules.get(i) - j * spacing) > spacing * .2f)
                    regular = false;
            if (regular) return true;
        }
        return false;
    }

    /**
     * Look for a separate, similarly sized upright glyph stacked over or under this 3. The caller
     * requires chord tones at all three candidate attacks.
     */
    private static boolean hasStackedFingering(
            byte[] gray, int width, int height, Glyph three, float gap) {
        float cx = (three.left() + three.right()) * .5f;
        int gh = three.bottom() - three.top() + 1;
        int left = Math.max(0, Math.round(cx - gap)),
                right = Math.min(width - 1, Math.round(cx + gap));
        int top = Math.max(0, Math.round(three.top() - gap * 2.4f));
        int bottom = Math.min(height - 1, Math.round(three.bottom() + gap * 2.4f));
        int w = right - left + 1, h = bottom - top + 1;
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        for (int seed = 0; seed < w * h; seed++) {
            if (seen[seed] || !dark(gray, width, left + seed % w, top + seed / w)) continue;
            int take = 0, size = 1, minX = width, maxX = -1, minY = height, maxY = -1;
            queue[0] = seed;
            seen[seed] = true;
            while (take < size) {
                int at = queue[take++], x = at % w, y = at / w;
                minX = Math.min(minX, left + x);
                maxX = Math.max(maxX, left + x);
                minY = Math.min(minY, top + y);
                maxY = Math.max(maxY, top + y);
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        int next = ny * w + nx;
                        if (!seen[next] && dark(gray, width, left + nx, top + ny)) {
                            seen[next] = true;
                            queue[size++] = next;
                        }
                    }
            }
            if (minX <= left || maxX >= right || minY <= top || maxY >= bottom) continue;
            int cw = maxX - minX + 1, ch = maxY - minY + 1;
            if (ch < gap * .7f
                    || ch > gap * 2.3f
                    || ch < gh * .65f
                    || ch > gh * 1.4f
                    || cw < ch * .2f
                    || cw > ch * .95f
                    || size < cw * ch * .15f
                    || size > cw * ch * .7f
                    || Math.abs((minX + maxX) * .5f - cx) > gap * .35f) continue;
            int separation =
                    maxY < three.top()
                            ? three.top() - maxY
                            : minY > three.bottom() ? minY - three.bottom() : -1;
            if (separation >= Math.max(2, gap * .2f) && separation <= gap) return true;
        }
        return false;
    }

    /** Opposite-side digits follow individual heads even when a curved staff crosses the beam. */
    static boolean openingFingerParenthesis(byte[] gray, int width, int height,
            int digitLeft, int digitTop, int digitBottom, float gap) {
        int left = digitLeft - Math.round(gap * .68f);
        int top = digitTop - Math.round(gap * .45f);
        int bottom = digitBottom + Math.round(gap * .45f);
        if (left < 0 || top < 0 || bottom >= height || digitLeft >= width) return false;
        var ink = RawNumeralComponents.capture(
                gray, width, height, left, top, digitLeft - 1, bottom, 165, () -> {}, () -> {});
        for (var curve : ink.components()) {
            int w = curve.right - curve.left + 1, h = curve.bottom - curve.top + 1;
            if (curve.left <= left || curve.right >= digitLeft - 1
                    || w < gap * .20f || w > gap * .55f
                    || h < gap * 1.2f || h > gap * 1.9f
                    || digitLeft - curve.right > gap * .23f
                    || curve.top >= digitTop - 1 || curve.bottom <= digitBottom + 1
                    || curve.size() > h * gap * .22f) continue;
            float[] centers = new float[3];
            int[] counts = new int[3];
            for (int i = 0; i < curve.size(); i++) {
                int pixel = curve.pixel(i);
                int band = Math.min(2, (pixel / width - curve.top) * 3 / h);
                centers[band] += pixel % width - curve.left;
                counts[band]++;
            }
            if (counts[0] == 0 || counts[1] == 0 || counts[2] == 0) continue;
            float first = centers[0] / counts[0], middle = centers[1] / counts[1];
            float last = centers[2] / counts[2];
            if ((first + last) * .5f - middle >= gap * .09f
                    && Math.abs(first - last) < gap * .32f) return true;
        }
        return false;
    }

    /** A head-aligned fingering must not shorten a proved four-note double beam. */
    private static boolean fingersBesideClosedDoubleBeam(byte[] gray, int width, int height,
            MeasureRegion region, float gap, Glyph digit, List<Onset> groups,
            List<ScoreNoteEvent> notes, int start, int virtualStart) {
        float headY = groups.get(start + 1).top() * height;
        boolean above = headY - digit.bottom() >= 1.2f * gap
                && headY - digit.top() <= 3.1f * gap;
        boolean below = digit.top() - headY >= 3.2f * gap
                && digit.bottom() - headY <= 7.6f * gap
                && clippedOpeningFingerParenthesis(gray, width, height, gap, digit);
        if (!above && !below) return false;

        int begin = above ? start + 1 : start;
        if (begin == 0 || begin + 3 >= groups.size()) return false;
        float floor = region.bottom() * height - 2.1f * gap;
        var staff = StaffPitchTrack.forPrintedSlurs(gray, width, height, floor - 4 * gap, floor, gap);
        if (staff == null) return false;
        var budget = new PrintedBeamShafts.Budget(45_000);
        PrintedBeamShafts.Shaft previous = null;
        int measure = notes.get(groups.get(start).indices().get(0)).measureIndex();
        for (int i = begin - 1; i < begin + 4; i++) {
            var onset = groups.get(i);
            if (onset.indices().size() != 1 || onset.indices().get(0) >= virtualStart) return false;
            var note = notes.get(onset.indices().get(0));
            if (note.measureIndex() != measure || note.stemDirection() != -1
                    || !ClosedMonophonicSixteenths.regularQuartetHead(note)
                    || note.augmentationDots() != 0 || note.tupletDivisor() != 1) return false;
            float x = (region.left() + (region.right() - region.left()) * onset.position()) * width;
            var shafts = PrintedBeamShafts.at(gray, width, height, x, note.pageY() * height, gap, budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != 1) return false;
            var shaft = shafts.get(0);
            if (previous != null) {
                boolean agrees = i == begin
                        ? WrappedEighthTriplets.cutBetweenRecoveredGroups(
                                gray, width, height, region, previous, shaft, gap)
                        : WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                                gray, width, height, previous, shaft, gap, staff);
                if (!agrees) return false;
            }
            previous = shaft;
        }
        return !budget.refused();
    }

    /** A lower opening parenthesis can touch the staff rule above the numeral. */
    private static boolean clippedOpeningFingerParenthesis(
            byte[] gray, int width, int height, float gap, Glyph digit) {
        int left = digit.left() - Math.round(gap * 1.06f), right = digit.left() - 2;
        int first = digit.top() - Math.round(gap * .72f);
        int bottom = digit.bottom() + Math.round(gap * .5f);
        if (left < 0 || first < 0 || bottom >= height || right >= width) return false;
        int rule = -1;
        for (int y = first; y <= digit.top() - Math.round(gap * .18f); y++) {
            int dark = 0;
            for (int x = left; x <= right; x++) if ((gray[y * width + x] & 255) < 139) dark++;
            if (dark >= .85f * (right - left + 1)) rule = y;
        }
        if (rule < 0) return false;
        var ink = RawNumeralComponents.capture(
                gray, width, height, left, rule + 1, right, bottom, 155, () -> {}, () -> {});
        for (var part : ink.components()) {
            float h = part.bottom - part.top + 1, w = part.right - part.left + 1;
            if (part.top > rule + 2 || part.left <= left || part.right >= right
                    || part.bottom < digit.bottom() - .34f * gap || part.bottom >= bottom
                    || h < .97f * gap || h > 1.95f * gap || w < .23f * gap || w > .70f * gap
                    || part.size() > .24f * h * gap) continue;
            float[] centers = new float[3];
            int[] count = new int[3];
            for (int i = 0; i < part.size(); i++) {
                int pixel = part.pixel(i);
                int band = Math.min(2, (int) ((pixel / width - part.top) * 3 / h));
                centers[band] += pixel % width;
                count[band]++;
            }
            if (count[0] == 0 || count[1] == 0 || count[2] == 0) continue;
            float upper = centers[0] / count[0], center = centers[1] / count[1], lower = centers[2] / count[2];
            if (upper - center >= .12f * gap && lower >= center - .04f * gap) return true;
        }
        return false;
    }

    private static boolean pairedStaffJoinedFingering(
            byte[] gray, int width, int height, float gap, Glyph digit, List<MeasureRegion> measures, int measure) {
        if (gap < 8 || gap > 24 || measure <= 0
                || !staffJoinedFingeringParenthesis(gray, width, height, gap, digit, true)) return false;
        var now = measures.get(measure);
        var previous = measures.get(measure - 1);
        float boundary = previous.right() * width;
        if (Math.abs((previous.bottom() - now.bottom()) * height) > gap * .35f
                || Math.abs(now.left() * width - boundary) > gap * 1.55f) return false;
        int left = Math.max((int) Math.ceil(previous.left() * width), Math.round(boundary - gap * 11));
        int right = Math.round(boundary - gap * .75f);
        int top = digit.top() - Math.round(gap * .2f);
        int bottom = digit.bottom() + Math.round(gap * .2f);
        if (left < 0 || right >= width || top < 0 || bottom >= height
                || left >= right || (long) (right-left+1)*(bottom-top+1) > 16_384) return false;
        var window = RawNumeralComponents.capture(
                gray, width, height, left, top, right, bottom, 139, () -> {}, () -> {});
        int matched = 0;
        for (var glyph : window.components()) {
            int glyphHeight = glyph.bottom - glyph.top + 1;
            if (glyph.left <= left || glyph.right >= right || glyph.top <= top || glyph.bottom >= bottom
                    || glyphHeight < gap * .85f || glyphHeight > gap * 1.42f
                    || Math.abs(glyph.top - digit.top()) > gap * .20f
                    || !repeatFingeringDigit(gray, width, gap, digit, glyph)) continue;
            if (staffJoinedFingeringParenthesis(gray, width, height, gap,
                    new Glyph(glyph.left, glyph.top, glyph.right, glyph.bottom), false)) matched++;
        }
        return matched == 1;
    }

    private static boolean repeatFingeringDigit(byte[] gray, int width, float gap,
            Glyph close, RawNumeralComponents.Component open) {
        if (Math.abs((close.right() - close.left()) - (open.right - open.left)) > gap * .20f
                || Math.abs((close.bottom() - close.top()) - (open.bottom - open.top)) > gap * .16f) return false;
        int shift = Math.round((close.left() + close.right() - open.left - open.right) * .5f);
        int yShift = close.top() - open.top;
        int left = Math.min(open.left, close.left() - shift);
        int right = Math.max(open.right, close.right() - shift);
        int bottom = Math.max(open.bottom, close.bottom() - yShift);
        for (int offset = -1; offset <= 1; offset++) {
            int same = 0, union = 0;
            for (int y = open.top; y <= bottom; y++) for (int x = left; x <= right; x++) {
                boolean a = x >= open.left && x <= open.right && y <= open.bottom
                        && (gray[y * width + x] & 255) < 139;
                int xx = x + shift + offset, yy = y + yShift;
                boolean b = xx >= close.left() && xx <= close.right() && yy <= close.bottom()
                        && (gray[yy * width + xx] & 255) < 139;
                if (a || b) union++;
                if (a && b) same++;
            }
            if (union > 0 && same >= union * .80f) return true;
        }
        return false;
    }

    /** Follow the visible remainder of a parenthesis below the independently visible staff rule. */
    private static boolean staffJoinedFingeringParenthesis(
            byte[] gray, int width, int height, float gap, Glyph digit, boolean closing) {
        return staffJoinedFingeringParenthesis(gray, width, height, gap, digit, closing, 139);
    }

    private static boolean staffJoinedFingeringParenthesis(
            byte[] gray, int width, int height, float gap, Glyph digit, boolean closing, int threshold) {
        int edge = closing ? digit.right() : digit.left();
        int reach = Math.round(gap * 1.02f);
        int left = closing ? edge + 1 : edge - reach;
        int right = closing ? edge + reach : edge - 1;
        int ruleStart = digit.top() - Math.round(gap * .72f),
                ruleEnd = digit.top() - Math.round(gap * .17f);
        int bottom = digit.bottom() + Math.round(gap * .72f);
        int shaft = -1;
        if (ruleStart < 0 || bottom >= height || left - reach < 0 || right + reach >= width)
            return false;
        for (int y = ruleStart; y <= ruleEnd; y++) {
            int ink = 0;
            for (int x = left - reach; x <= right + reach; x++)
                if ((gray[y * width + x] & 255) < 139) ink++;
            if (ink >= (right - left + 2 * reach + 1) * .84f) shaft = y;
        }
        if (shaft < 0) return false;
        var window = RawNumeralComponents.capture(
                gray, width, height, left, shaft + 1, right, bottom, threshold, () -> {}, () -> {});
        for (var curve : window.components()) {
            int h = curve.bottom - curve.top + 1, w = curve.right - curve.left + 1;
            if (curve.top > shaft + 2 || curve.bottom <= digit.bottom() + 2
                    || curve.left <= left || curve.right >= right || curve.bottom >= bottom
                    || h < gap * 1.32f || h > gap * 2.25f || w < gap * .23f || w > gap * .65f
                    || curve.size() > h * gap * .27f) continue;
            float[] centers = new float[3];
            int[] counts = new int[3];
            for (int i = 0; i < curve.size(); i++) {
                int pixel = curve.pixel(i);
                int band = Math.min(2, (pixel / width - curve.top) * 3 / h);
                centers[band] += pixel % width - curve.left;
                counts[band]++;
            }
            if (counts[0] == 0 || counts[1] == 0 || counts[2] == 0) continue;
            float first = centers[0] / counts[0], middle = centers[1] / counts[1], last = centers[2] / counts[2];
            float bend = middle - (first + last) * .5f;
            if ((closing ? bend : -bend) >= gap * .09f && Math.abs(first - last) < gap * .30f) return true;
        }
        return false;
    }

    static boolean pairedFingeringsWithinBar(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> heads,
            int first, int second) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        float staffBottom = bar.bottom() * height - gap * 2.1f;
        var opening = parenthesizedThree(gray, width, height, bar, heads, first, staffBottom, gap, false);
        if (opening == null) return false;
        var closing = parenthesizedThree(gray, width, height, bar, heads, second, staffBottom, gap, true);
        if (closing == null || Math.abs(opening.top - closing.top) > gap * .25f
                || closing.left - opening.right < gap * 7 || closing.left - opening.right > gap * 16)
            return false;
        return repeatFingeringDigit(gray, width, gap,
                new Glyph(closing.left, closing.top, closing.right, closing.bottom), opening);
    }

    private static RawNumeralComponents.Component parenthesizedThree(
            byte[] gray, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> heads, int first,
            float staffBottom, float gap, boolean closing) {
        float barLeft = bar.left() * width, length = (bar.right() - bar.left()) * width;
        float x = barLeft + length * heads.get(first + 1).positionInMeasure();
        int left = Math.round(x - gap * .90f), right = Math.round(x + gap * .90f);
        int top = Math.round(staffBottom + gap * .16f), bottom = Math.round(staffBottom + gap * 1.90f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var scan = RawNumeralComponents.capture(gray, width, height, left, top, right, bottom, 139, () -> {}, () -> {});
        RawNumeralComponents.Component result = null;
        for (var component : scan.components()) {
            int h = component.bottom - component.top + 1;
            if (component.left <= left || component.right >= right || component.top <= top
                    || component.bottom >= bottom || h < gap * .86f || h > gap * 1.42f
                    || Math.abs((component.left + component.right) * .5f - x) > gap * .54f
                    || !isIsolatedThree(component, width)) continue;
            var glyph = new Glyph(component.left, component.top, component.right, component.bottom);
            // A weak original-pixel join at the staff can split the darkest curve core.
            if (!staffJoinedFingeringParenthesis(gray, width, height, gap, glyph, closing, 165)) continue;
            int before = Math.round(barLeft + length * heads.get(first).positionInMeasure() - gap * .3f);
            int after = Math.round(barLeft + length * heads.get(first + 2).positionInMeasure() + gap * .3f);
            if (bracketArm(gray, width, height, before, glyph.left() - 2,
                        glyph.top(), glyph.bottom(), gap * .25f)
                    && bracketArm(gray, width, height, glyph.right() + 2, after,
                        glyph.top(), glyph.bottom(), gap * .25f))
                return null;
            if (result != null) return null;
            result = component;
        }
        return result;
    }

    private static boolean pairedFingeringRails(
            byte[] gray, int width, int height, float gap, float[] xs, float[] ys) {
        return pairedFingeringRails(gray, width, height, gap, xs, ys, 1);
    }

    private static boolean pairedFingeringRails(
            byte[] gray, int width, int height, float gap, float[] xs, float[] ys, int shaftDirection) {
        var budget = new PrintedBeamShafts.Budget(60_000);
        PrintedBeamShafts.Shaft last = null;
        for (int i = 0; i < xs.length; i++) {
            var choices = PrintedBeamShafts.at(gray, width, height, xs[i], ys[i], gap, budget);
            if (choices.size() != 1 || choices.get(0).direction() != shaftDirection) return false;
            var next = choices.get(0);
            if (last != null && !WrappedEighthTriplets.matchesBeamRails(gray, width, height, last, next, gap, 2))
                return false;
            last = next;
        }
        return true;
    }

    private static boolean fingeringAtOpposedBeamBoundary(byte[] gray, int width, int height, float gap,
            MeasureRegion region, Glyph numeral, List<ScoreNoteEvent> notes, List<Onset> groups,
            int position, int virtualStart, ScoreNoteEvent first) {
        if (position < 2 || position + 3 >= groups.size()) return false;
        var budget = new PrintedBeamShafts.Budget(45_000);
        PrintedBeamShafts.Shaft previous = null;
        float precedingX = 0;
        for (int slot = 0; slot < 6; slot++) {
            Onset onset = matching(groups.get(position - 2 + slot), notes, first);
            if (onset == null || onset.indices().size() != 1 || onset.indices().get(0) >= virtualStart)
                return false;
            var note = notes.get(onset.indices().get(0));
            int direction = slot < 4 ? 1 : -1;
            if (note.measureIndex() != first.measureIndex() || note.stemDirection() != direction
                    || note.beamCount() != 2 || note.augmentationDots() != 0
                    || note.tupletDivisor() != 1 || note.unbeamedDurationBeats() != 0
                    || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0) return false;
            float x = (region.left() + onset.position() * (region.right() - region.left())) * width;
            float y = note.pageY() * height;
            var axes = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
            if (axes.size() != 1 || axes.get(0).direction() != -direction) return false;
            var current = axes.get(0);
            if (previous != null && slot != 4 && !FingeringAnnotationFilter.pairedBeamRails(
                    gray, width, height, previous, current, gap)) return false;
            previous = current;
            if (slot == 2) precedingX = x;
        }
        int left = Math.max(0, Math.round(precedingX - gap * .80f));
        int right = Math.min(width - 1, Math.round(precedingX + gap * .80f));
        int top = Math.max(0, Math.round(numeral.top() + gap * .37f));
        int bottom = Math.min(height - 1, Math.round(numeral.bottom() + gap * 1.16f));
        var digits = RawNumeralComponents.capture(gray, width, height, left, top, right, bottom,
                () -> {}, () -> {});
        return digits.components().stream().anyMatch(digit -> digit.left > left && digit.right < right
                && digit.top > top && digit.bottom < bottom && fingerOne(gray, width, digit));
    }

    /** The middle 3 belongs to a fingering row when it repeats below an intact quartet. */
    private static boolean pairedFingersAroundOpeningBeam(byte[] gray, int width, int height,
            Glyph three, float gap, float[] xs, float[] ys) {
        int glyphHeight = three.bottom() - three.top() + 1;
        int upperTop = Math.round(three.top() - .66f * gap);
        int upperBottom = Math.round(three.bottom() + .59f * gap);
        int lowerTop = Math.round(ys[1] + 2.30f * gap);
        int lowerBottom = Math.round(ys[1] + 4.25f * gap);
        var left = detachedFingerNear(gray, width, height, xs[0], upperTop, upperBottom, glyphHeight, gap);
        var right = detachedFingerNear(gray, width, height, xs[2], upperTop, upperBottom, glyphHeight, gap);
        var repeat = detachedFingerNear(gray, width, height, xs[1], lowerTop, lowerBottom, glyphHeight, gap);
        return left != null && (fingerTwo(gray, width, left) || fingerOne(gray, width, left))
                && right != null && looksLikeFingerFour(gray, width, right.size(),
                        right.left, right.right, right.top, right.bottom)
                && repeat != null && isIsolatedThree(repeat, width)
                && pairedFingeringRails(gray, width, height, gap, xs, ys, -1);
    }

    private static RawNumeralComponents.Component detachedFingerNear(byte[] gray, int width, int height,
            float x, int top, int bottom, int glyphHeight, float gap) {
        int left = Math.round(x - 1.08f * gap), right = Math.round(x + 1.08f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var ink = RawNumeralComponents.capture(gray, width, height, left, top, right, bottom, () -> {}, () -> {});
        RawNumeralComponents.Component found = null;
        for (var part : ink.components()) {
            int h = part.bottom - part.top + 1, w = part.right - part.left + 1;
            if (part.left <= left || part.right >= right || part.top <= top || part.bottom >= bottom
                    || Math.abs((part.left + part.right) * .5f - x) > .53f * gap
                    || h < glyphHeight * .74f || h > glyphHeight * 1.28f
                    || w < h * .25f || w > h * 1.05f) continue;
            if (found != null) return null;
            found = part;
        }
        return found;
    }

    /** A row of individually aligned fingers below a double beam can include repeated threes. */
    private static boolean lowerFingerRowOnFourBeams(
            byte[] gray, int width, int height, Glyph numeral, float gap,
            float x1, float y1, float x2, float y2, float x3, float y3, float extra, float extraY) {
        if (numeral.top() - Math.max(Math.max(y1, y2), Math.max(y3, extraY)) < 4.5f * gap
                || Math.abs((numeral.left() + numeral.right()) * .5f - x2) > .32f * gap) return false;
        float[] xs = extra < x1 ? new float[] {extra, x1, x2, x3} : new float[] {x1, x2, x3, extra};
        float[] ys = extra < x1 ? new float[] {extraY, y1, y2, y3} : new float[] {y1, y2, y3, extraY};
        int left = Math.round(xs[0] - .85f * gap), right = Math.round(xs[3] + .85f * gap);
        int top = Math.round(numeral.top() - .56f * gap), bottom = Math.round(numeral.bottom() + .56f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        int glyphHeight = numeral.bottom() - numeral.top() + 1;
        var ink = RawNumeralComponents.capture(gray, width, height, left, top, right, bottom, () -> {}, () -> {});
        boolean hasDistinctFinger = false;
        for (float owner : xs) {
            if (owner == x2) continue;
            boolean found = false;
            for (var digit : ink.components()) {
                int h = digit.bottom - digit.top + 1, w = digit.right - digit.left + 1;
                if (digit.left <= left || digit.right >= right || digit.top <= top || digit.bottom >= bottom
                        || Math.abs((digit.left + digit.right) * .5f - owner) > .49f * gap
                        || Math.abs(digit.bottom - numeral.bottom()) > .39f * gap
                        || h < glyphHeight * .73f || h > glyphHeight * 1.27f
                        || w < h * .28f || w > h * .98f) continue;
                boolean otherFinger = looksLikeTwo(gray, width, digit.size(), digit.left, digit.right,
                        digit.top, digit.bottom)
                        || looksLikeFingerFour(gray, width, digit.size(), digit.left, digit.right,
                                digit.top, digit.bottom)
                        || fingerOne(gray, width, digit);
                if (!otherFinger && !isIsolatedThree(digit, width)) continue;
                if (found) return false;
                found = true;
                hasDistinctFinger |= otherFinger;
            }
            if (!found) return false;
        }
        return hasDistinctFinger && pairedFingeringRails(gray, width, height, gap, xs, ys);
    }

    private static boolean fingerSequence(
            byte[] gray,
            int width,
            int height,
            Glyph numeral,
            float gap,
            int stem,
            int digitSide,
            float a,
            float ay,
            float b,
            float by,
            float c,
            float cy,
            float extra,
            float extraY) {
        float[] xs = extra < a ? new float[] {extra, a, b, c} : new float[] {a, b, c, extra};
        float[] ys = extra < a ? new float[] {extraY, ay, by, cy} : new float[] {ay, by, cy, extraY};
        if (digitSide == -1 && stem == 1 && extra < a
                && beamSideFingerSequence(gray, width, height, numeral, gap, xs, ys))
            return true;
        int glyphHeight = numeral.bottom() - numeral.top() + 1;
        int left = Math.round(xs[0] - gap * .85f), right = Math.round(xs[3] + gap * .85f);
        int top = Math.round(numeral.top() - gap * 1.1f);
        int bottom = Math.round(numeral.bottom() + gap * 1.1f);
        if (left < 0 || right >= width || top < 0 || bottom >= height
                || (long) (right - left + 1) * (bottom - top + 1) > 16384) return false;
        var ink = RawNumeralComponents.capture(gray, width, height, left, top, right, bottom,
                () -> {}, () -> {});
        int otherDigits = 0;
        for (int i = 0; i < xs.length; i++) {
            if (i > 0 && !PrintedTupletBeamOwner.connectedHeads(gray, width, height,
                    xs[i - 1], ys[i - 1], xs[i], ys[i], gap, stem)) return false;
            if (xs[i] == b) continue;
            for (var component : ink.components()) {
                int cw = component.right - component.left + 1, ch = component.bottom - component.top + 1;
                float x = (component.left + component.right) * .5f;
                float y = (component.top + component.bottom) * .5f;
                if (component.left <= left
                        || component.right >= right
                        || component.top <= top
                        || component.bottom >= bottom
                        || Math.abs(x - xs[i]) > gap * .40f
                        || Math.abs(component.bottom - numeral.bottom()) > gap * .85f
                        || (ys[i] - y) * digitSide >= -gap
                        || ch < glyphHeight * .75f
                        || ch > glyphHeight * 1.25f
                        || cw < ch * .28f
                        || cw > ch * .9f) continue;
                if (looksLikeTwo(gray, width, component.size(), component.left, component.right,
                                component.top, component.bottom)
                        || fingerOne(gray, width, component)) {
                    otherDigits++;
                    break;
                }
            }
        }
        return otherDigits >= 2;
    }

    /** A run of upper fingering digits may follow the shafts of an intact double beam. */
    private static boolean beamSideFingerSequence(
            byte[] gray, int width, int height, Glyph numeral, float gap, float[] xs, float[] ys) {
        int glyphHeight = numeral.bottom() - numeral.top() + 1;
        if (Math.abs((numeral.left() + numeral.right()) * .5f - xs[2]) > gap * .36f) return false;
        int left = Math.round(xs[0] - gap * .86f), right = Math.round(xs[3] + gap * 1.2f);
        int top = Math.round(numeral.top() - gap), bottom = Math.round(numeral.bottom() + gap * 1.15f);
        if (left < 0 || right >= width || top < 0 || bottom >= height
                || (long) (right - left + 1) * (bottom - top + 1) > 16384)
            return false;
        var ink = RawNumeralComponents.capture(gray, width, height, left, top, right, bottom,
                () -> {}, () -> {});
        var budget = new PrintedBeamShafts.Budget(80_000);
        PrintedBeamShafts.Shaft previous = null;
        boolean earlierDigit = false, terminalFour = false;
        for (int i = 0; i < xs.length; i++) {
            var shafts = PrintedBeamShafts.at(gray, width, height, xs[i], ys[i], gap, budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != -1) return false;
            var shaft = shafts.get(0);
            if (previous != null
                    && !WrappedEighthTriplets.matchesBeamRails(gray, width, height, previous, shaft, gap, 2))
                return false;
            previous = shaft;
            if (i == 2) continue;
            for (var mark : ink.components()) {
                if (mark.left <= left || mark.right >= right || mark.top <= top || mark.bottom >= bottom)
                    continue;
                float x = (mark.left + mark.right) * .5f;
                int h = mark.bottom - mark.top + 1, w = mark.right - mark.left + 1;
                if (x < xs[i] - gap * .42f || x > shaft.right() + gap * .26f
                        || Math.abs(mark.bottom - numeral.bottom()) > gap
                        || shaft.endY() - mark.bottom < Math.round(gap * .12f)
                        || h < glyphHeight * .75f || h > glyphHeight * 1.25f
                        || w < h * .28f || w > h * .95f) continue;
                if (i < 2 && (fingerOne(gray, width, mark) || fingerTwo(gray, width, mark)))
                    earlierDigit = true;
                if (i == 3 && looksLikeFingerFour(
                        gray, width, mark.size(), mark.left, mark.right, mark.top, mark.bottom))
                    terminalFour = true;
            }
        }
        return earlierDigit && terminalFour;
    }

    static boolean fingerOne(byte[] gray, int width, RawNumeralComponents.Component glyph) {
        int h = glyph.bottom - glyph.top + 1, w = glyph.right - glyph.left + 1;
        if (w > h * .65f) return false;
        int spine = 0;
        for (int x = glyph.left + 1; x < glyph.right; x++) {
            int ink = 0;
            for (int y = glyph.top + 1; y < glyph.bottom; y++)
                if ((gray[y * width + x] & 255) <= 165) ink++;
            spine = Math.max(spine, ink);
        }
        return spine >= h * .80f;
    }

    private static boolean hasNearbyFour(
            byte[] gray, int width, int height, Glyph three, float gap) {
        float cx = (three.left() + three.right()) * .5f;
        int gh = three.bottom() - three.top() + 1;
        int left = Math.max(0, Math.round(cx - gap * 11)),
                right = Math.min(width - 1, Math.round(cx + gap * 11));
        int top = Math.max(0, Math.round(three.top() - gap * .4f));
        int bottom = Math.min(height - 1, Math.round(three.bottom() + gap * .4f));
        int w = right - left + 1, h = bottom - top + 1;
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[w * h];
        for (int seed = 0; seed < w * h; seed++) {
            if (seen[seed] || !dark(gray, width, left + seed % w, top + seed / w)) continue;
            int take = 0, size = 1, minX = width, maxX = -1, minY = height, maxY = -1;
            queue[0] = seed;
            seen[seed] = true;
            while (take < size) {
                int at = queue[take++], x = at % w, y = at / w;
                minX = Math.min(minX, left + x);
                maxX = Math.max(maxX, left + x);
                minY = Math.min(minY, top + y);
                maxY = Math.max(maxY, top + y);
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        int next = ny * w + nx;
                        if (!seen[next] && dark(gray, width, left + nx, top + ny)) {
                            seen[next] = true;
                            queue[size++] = next;
                        }
                    }
            }
            if (minX <= left || maxX >= right || minY <= top || maxY >= bottom) continue;
            int cw = maxX - minX + 1, ch = maxY - minY + 1;
            if (ch < gap * .7f
                    || ch > gap * 2.3f
                    || ch < gh * .65f
                    || ch > gh * 1.4f
                    || cw < ch * .2f
                    || cw > ch * .95f
                    || size < cw * ch * .15f
                    || size > cw * ch * .7f
                    || Math.abs((minY + maxY - three.top() - three.bottom()) * .5f) > gap * .3f)
                continue;
            if (Math.abs((minX + maxX) * .5f - cx) > gap * 1.5f
                    && looksLikeFingerFour(gray, width, size, minX, maxX, minY, maxY)) return true;
        }
        return false;
    }

    private static boolean looksLikeFingerFour(
            byte[] gray, int width, int area, int minX, int maxX, int minY, int maxY) {
        int glyphWidth = maxX - minX + 1, glyphHeight = maxY - minY + 1;
        float fill = area / (float) (glyphWidth * glyphHeight);
        if (fill < .14f || fill > .66f) return false;
        int[] rows = new int[glyphHeight];
        int[] columns = new int[glyphWidth];
        int upperLeft = 0, lowerLeft = 0;
        for (int y = minY; y <= maxY; y++)
            for (int x = minX; x <= maxX; x++) {
                if ((gray[y * width + x] & 0xff) > 165) continue;
                int localX = x - minX, localY = y - minY;
                rows[localY]++;
                columns[localX]++;
                if (localX < glyphWidth * .55f && localY < glyphHeight * .58f) upperLeft++;
                if (localX < glyphWidth * .45f && localY > glyphHeight * .72f) lowerLeft++;
            }
        int rightSpine = 0;
        for (int x = Math.max(0, Math.round(glyphWidth * .52f)); x < glyphWidth; x++)
            rightSpine = Math.max(rightSpine, columns[x]);
        int middleCrossbar = 0;
        for (int y = Math.max(0, Math.round(glyphHeight * .36f));
                y <= Math.min(glyphHeight - 1, Math.round(glyphHeight * .74f));
                y++) middleCrossbar = Math.max(middleCrossbar, rows[y]);
        int topBar = 0;
        for (int y = 0; y < Math.max(1, Math.round(glyphHeight * .28f)); y++)
            topBar = Math.max(topBar, rows[y]);
        return rightSpine >= glyphHeight * .58f
                && middleCrossbar >= glyphWidth * .50f
                && topBar < glyphWidth * .68f
                && upperLeft >= Math.max(2, Math.round(area * .10f))
                && lowerLeft <= Math.max(2, Math.round(area * .16f));
    }

    static boolean fingerTwo(byte[] gray, int width, RawNumeralComponents.Component glyph) {
        return looksLikeTwo(gray, width, glyph.size(), glyph.left, glyph.right, glyph.top, glyph.bottom);
    }

    private static boolean looksLikeTwo(
            byte[] gray, int width, int area, int minX, int maxX, int minY, int maxY) {
        int glyphWidth = maxX - minX + 1, glyphHeight = maxY - minY + 1;
        float fill = area / (float) (glyphWidth * glyphHeight);
        if (fill < .13f || fill > .66f) return false;
        int[] rows = new int[glyphHeight];
        int upperRight = 0, lowerLeft = 0, lowerDiagonal = 0;
        for (int y = minY; y <= maxY; y++)
            for (int x = minX; x <= maxX; x++) {
                if ((gray[y * width + x] & 0xff) > 165) continue;
                int localX = x - minX, localY = y - minY;
                rows[localY]++;
                if (localX >= glyphWidth * .52f && localY <= glyphHeight * .48f) upperRight++;
                if (localX <= glyphWidth * .48f && localY >= glyphHeight * .52f) lowerLeft++;
                if (localX <= glyphWidth * .42f
                        && localY >= glyphHeight * .52f
                        && localY <= glyphHeight * .82f) lowerDiagonal++;
            }
        int topStroke = 0, bottomStroke = 0, rightSpine = 0, leftSpine = 0;
        for (int y = 0; y <= Math.min(glyphHeight - 1, Math.round(glyphHeight * .30f)); y++)
            topStroke = Math.max(topStroke, rows[y]);
        for (int y = Math.max(0, Math.round(glyphHeight * .70f)); y < glyphHeight; y++)
            bottomStroke = Math.max(bottomStroke, rows[y]);
        for (int x = Math.max(0, Math.round(glyphWidth * .58f)); x < glyphWidth; x++) {
            int run = 0;
            for (int y = minY; y <= maxY; y++) {
                run = (gray[y * width + minX + x] & 255) <= 165 ? run + 1 : 0;
                rightSpine = Math.max(rightSpine, run);
            }
        }
        for (int x = 0; x < Math.round(glyphWidth * .42f); x++) {
            int run = 0;
            for (int y = minY; y <= maxY; y++) {
                run = (gray[y * width + minX + x] & 255) <= 165 ? run + 1 : 0;
                leftSpine = Math.max(leftSpine, run);
            }
        }
        // A two has separated left terminals, not the long left spine of a
        // mirrored digit. Serifs must not manufacture diagonal evidence.
        return topStroke >= glyphWidth * .46f
                && bottomStroke >= glyphWidth * .52f
                && (rightSpine < glyphHeight * .90f
                        || descendingTwoStroke(gray, width, minX, minY, glyphWidth, glyphHeight))
                && leftSpine < glyphHeight * .50f
                && upperRight >= Math.max(2, Math.round(area * .10f))
                && lowerLeft >= Math.max(2, Math.round(area * .10f))
                && lowerDiagonal >= Math.max(2, (int) (area * .06f));
    }

    /** Bold twos may share one right-hand column while their middle stroke still descends left. */
    private static boolean descendingTwoStroke(
            byte[] gray, int width, int left, int top, int w, int h) {
        double upper = 0, lower = 0;
        int upperCount = 0, lowerCount = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if ((gray[(top + y) * width + left + x] & 255) <= 165) {
                    if (y >= h * .25 && y < h * .45) {
                        upper += x;
                        upperCount++;
                    }
                    if (y >= h * .55 && y < h * .75) {
                        lower += x;
                        lowerCount++;
                    }
                }
        return upperCount >= h * .2
                && lowerCount >= h * .2
                && upper / upperCount - lower / lowerCount >= w * .20;
    }

    private static boolean looksLikeSeven(byte[] gray, int width, int left, int top, int w, int h) {
        // Broad top bar followed by one descending diagonal, without the lower
        // bowl/base of 2, closed counters of 8, or two lobes of 3.
        int broad = 0, upper = 0, lower = 0, upperRows = 0, lowerRows = 0;
        int previous = Integer.MAX_VALUE, reverse = 0;
        for (int y = 0; y < h; y++) {
            int min = w, max = -1, count = 0;
            for (int x = 0; x < w; x++)
                if (dark(gray, width, left + x, top + y)) {
                    min = Math.min(min, x);
                    max = x;
                    count++;
                }
            if (max < 0) return false;
            if (y < h * .3f && max - min >= w * .65f) broad++;
            if (y >= h * .35f) {
                if (max - min > w * .55f
                        || y < h * .8f && count < Math.max(1, Math.round(w * .12f))) return false;
                int center = min + max;
                if (previous != Integer.MAX_VALUE && center > previous + 2) reverse++;
                previous = center;
                if (y < h * .55f) {
                    upper += center;
                    upperRows++;
                }
                if (y >= h * .8f) {
                    lower += center;
                    lowerRows++;
                }
            }
        }
        return broad >= Math.max(1, h / 12)
                && upperRows > 0
                && lowerRows > 0
                && reverse <= h / 10
                && upper / (float) upperRows - lower / (float) lowerRows >= w * .45f;
    }

    private static boolean looksLikeFive(byte[] gray, int width, int left, int top, int w, int h) {
        int bars = 0, leftStem = 0, lowerOpen = 0, lowerPocket = 0, lowerEdge = -1, foot = -1;
        for (int y = 0; y < h; y++) {
            int min = w, max = -1;
            for (int x = 0; x < w; x++)
                if (dark(gray, width, left + x, top + y)) {
                    min = Math.min(min, x);
                    max = Math.max(max, x);
                }
            // Italic fives can have a narrower cap than their lower bowl.
            if (y < h * .28f && max >= min && max - min + 1 >= w * .45f) bars++;
            if (y >= h * .18f && y < h * .42f && min <= w * .4f && max <= w * .6f && max >= min)
                leftStem++;
            if (y >= h * .48f && y < h * .82f) {
                lowerEdge = Math.max(lowerEdge, max);
                if (min >= w * .45f && max >= w * .7f) lowerOpen++;
                if (hasLobePocket(gray, width, left, top + y, w)) lowerPocket++;
            }
            // Include the final pixel band even when the glyph is shorter than 17 rows.
            if (y >= Math.min(h - 1, (int) Math.ceil(h * .94f))) foot = Math.max(foot, max);
        }
        return bars >= Math.max(2, h / 12)
                && leftStem >= Math.max(1, h / 12)
                && lowerOpen >= 1
                && lowerOpen + lowerPocket >= Math.max(2, h / 8)
                // A descending accent stroke may satisfy the cap and open-right rows,
                // but a five must curve back into a lower bowl at its foot.
                && foot >= 0
                && lowerEdge - foot >= Math.max(2, Math.round(w * .20f));
    }

    /** Neighboring disconnected ink cannot supply the lobes of this component. */
    private static boolean looksLikeComponentThree(
            int[] component,
            int count,
            int sourceWidth,
            int sourceLeft,
            int sourceTop,
            int left,
            int top,
            int w,
            int h) {
        byte[] pixels = new byte[w * h];
        java.util.Arrays.fill(pixels, (byte) 255);
        int[] min = new int[h], max = new int[h];
        java.util.Arrays.fill(min, w);
        java.util.Arrays.fill(max, -1);
        for (int i = 0; i < count; i++) {
            int index = component[i], row = index / sourceWidth;
            int x = sourceLeft + index - row * sourceWidth - left;
            int y = sourceTop + row - top;
            pixels[y * w + x] = 0;
            min[y] = Math.min(min[y], x);
            max[y] = Math.max(max[y], x);
        }
        return looksLikeThree(pixels, w, 0, 0, w, h, min, max, count);
    }

    /** Shared optical identity only. No tuplet or fingering ownership follows from shape. */
    static boolean isIsolatedThree(RawNumeralComponents.Component glyph, int sourceWidth) {
        int w = glyph.right - glyph.left + 1, h = glyph.bottom - glyph.top + 1;
        byte[] pixels = new byte[w * h];
        java.util.Arrays.fill(pixels, (byte) 255);
        for (int i = 0; i < glyph.size(); i++) {
            int pixel = glyph.pixel(i);
            pixels[(pixel / sourceWidth - glyph.top) * w + pixel % sourceWidth - glyph.left] = 0;
        }
        return looksLikeThree(pixels, w, 0, 0, w, h);
    }

    private static boolean looksLikeThree(byte[] gray, int width, int left, int top, int w, int h) {
        int[] min = new int[h], max = new int[h];
        int area = 0;
        for (int y = 0; y < h; y++) {
            min[y] = w;
            max[y] = -1;
            for (int x = 0; x < w; x++)
                if (dark(gray, width, left + x, top + y)) {
                    area++;
                    min[y] = Math.min(min[y], x);
                    max[y] = x;
                }
        }
        return looksLikeThree(gray, width, left, top, w, h, min, max, area);
    }

    private static boolean looksLikeThree(byte[] gray, int width, int left, int top, int w, int h,
            int[] min, int[] max, int area) {
        int upperOpen = 0, lowerOpen = 0, upperPocket = 0, lowerPocket = 0;
        int upperRasterPocket = 0, lowerRasterPocket = 0;
        int upperLobe = -1, lowerLobe = -1, waist = w, waistLeft = w, lowerOpenLeft = w;
        float upperEnd = .36f + 1f / h;
        int pocketStart = Math.round(w * .25f), pocketWidth = Math.max(2, Math.round(w * .22f));
        float pocketEnd = w * .55f;
        for (int y = 0; y < h; y++) {
            // Staff-rule removal can leave a blank scanline through a rest hook.
            // No ink is not an opening or an indented waist of a printed 3.
            if (max[y] < 0) continue;
            float fraction = y / (float) h;
            // A row occupies a whole pixel band; include a short opening that
            // crosses a lobe boundary instead of discarding it at small sizes.
            float nextFraction = (y + 1) / (float) h;
            if (nextFraction > .15f && fraction <= upperEnd) {
                upperLobe = Math.max(upperLobe, max[y]);
                if (min[y] >= w * .40f) upperOpen++;
                int pocket = lobePocket(gray, (top + y) * width + left,
                        pocketStart, max[y], pocketWidth, pocketEnd);
                if (pocket == 2) upperPocket++;
                if (pocket != 0) upperRasterPocket++;
            }
            if (nextFraction > .60f && fraction <= .82f) {
                // The lower curve must bulge before the baseline; a 2 only widens at its foot.
                if (fraction <= .75f) lowerLobe = Math.max(lowerLobe, max[y]);
                // An italic lower bowl sits left of the upper bowl. Its opening
                // is relative to its own right edge, not the whole glyph width.
                if (max[y] >= w * .5f && min[y] >= Math.min(w * .40f, max[y] * .50f)) {
                    lowerOpen++;
                    lowerOpenLeft = Math.min(lowerOpenLeft, min[y]);
                }
                int pocket = lobePocket(gray, (top + y) * width + left,
                        pocketStart, max[y], pocketWidth, pocketEnd);
                if (pocket == 2) lowerPocket++;
                if (pocket != 0) lowerRasterPocket++;
            }
            if (fraction >= .37f && fraction <= .55f) {
                waist = Math.min(waist, max[y]);
                waistLeft = Math.min(waistLeft, min[y]);
            }
        }
        int required = Math.max(2, h / 12);
        // Curled terminals put ink on the left of an otherwise open lobe. Allow
        // that ink only with a wide interior pocket and a truly open row in each
        // lobe: a closed 8 and the solid upper-left stem of a 5 still fail.
        boolean upper = upperOpen >= required || upperOpen >= 1 && upperPocket >= required;
        boolean lower = lowerOpen >= required || lowerOpen >= 1 && lowerPocket >= required;
        int indentation = Math.max(1, (int) Math.floor(w * .08f));
        int foot = -1;
        for (int y = (int) Math.ceil(h * .92); y < h; y++) foot = Math.max(foot, max[y]);
        if (lowerLobe - foot < indentation || upperLobe - waist < indentation) return false;
        int capLeft = w;
        for (int y = 0, capEnd = Math.max(2, (int) Math.ceil(h * .10)); y < capEnd; y++)
            capLeft = Math.min(capLeft, min[y]);
        boolean capIndented = upperLobe - capLeft >= Math.max(2, (int) Math.ceil(w * .25f));
        int diagonalReach = Math.max(2, Math.round(h * .15f));
        double diagonalGrowth = Math.max(2, Math.ceil(w * .2f));
        int extendedSolidFoot = 0;
        for (int y = (int) Math.ceil(h * .55f); y < (int) Math.ceil(h * .92f); y++) {
            if (min[y] > w * .4f || max[y] - min[y] + 1 < w * .6f) continue;
            boolean growsFromDiagonal = false;
            for (int prior = Math.max(0, y - diagonalReach); prior < y; prior++)
                if (max[prior] >= 0
                        && max[y] - max[prior] >= diagonalGrowth
                        && min[y] <= min[prior] + indentation) {
                    growsFromDiagonal = true;
                    break;
                }
            if (!growsFromDiagonal) continue;
            int occupied = 0;
            for (int x = min[y]; x <= max[y]; x++)
                if (dark(gray, width, left + x, top + y)) occupied++;
            if (occupied >= (max[y] - min[y] + 1) * .9f && ++extendedSolidFoot == required)
                return false;
        }
        // A tilted 2's full terminal can end above the last antialiased rows.
        // Its solid foot extends beyond the preceding diagonal, not around a lower bowl.
        boolean strictLower =
                lowerLobe - waist >= indentation
                        || lowerLobe >= w * .5f
                                && lowerOpen >= required
                                && lowerOpenLeft - waistLeft >= indentation
                                && capIndented;
        // Reject a complete four before accepting either three recognition path.
        if (upper && lower && strictLower)
            return !completeFourCrossbar(gray, width, area, left, top, w, h);
        boolean rasterUpper =
                upperOpen >= required || upperOpen >= 1 && upperRasterPocket >= required;
        boolean rasterLower =
                lowerOpen >= required || lowerOpen >= 1 && lowerRasterPocket >= required;
        boolean curledLower =
                lowerLobe - waist >= indentation
                        || lowerLobe >= w * .5f
                                && rasterLower
                                && lowerOpenLeft - waistLeft >= indentation
                                && capIndented;
        if (!(rasterUpper && rasterLower && curledLower)) return false;
        // A four's complete spine and crossbar contradict the added two-bowl proposal.
        // Do not reinterpret a four's narrow diagonal paper pocket as a curled terminal.
        return !completeFourCrossbar(gray, width, area, left, top, w, h);
    }

    /** Return 2 for a pocket meeting both cutoffs, 1 for the pixel-center cutoff alone. */
    private static int lobePocket(byte[] gray, int row, int first, int last,
            int minimumWidth, float minimumEnd) {
        int run = 0, result = 0;
        for (int x = first; x <= last; x++) {
            if ((gray[row + x] & 255) > NUMERAL_INK_LIMIT) {
                run++;
            } else {
                if (run >= minimumWidth) {
                    if (x >= minimumEnd) return 2;
                    if (x + .5f >= minimumEnd) result = 1;
                }
                run = 0;
            }
        }
        return result;
    }

    /** A four needs a separately inked crossbar, not a terminal and bowl separated by paper. */
    private static boolean completeFourCrossbar(
            byte[] gray, int width, int area, int left, int top, int w, int h) {
        if (!looksLikeFingerFour(gray, width, area, left, left + w - 1, top, top + h - 1))
            return false;
        // A waist joining two bowls is not a four's bar above a single lower stem.
        int lastLowerBowl = -1;
        for (int y = 0; y < h; y++) {
            if ((y + 1) / (float) h <= .60f || y / (float) h > .82f) continue;
            boolean leftInk = false;
            int paper = 0;
            for (int x = 0; x < w; x++) {
                if (dark(gray, width, left + x, top + y)) {
                    if (leftInk && paper >= Math.max(2, Math.round(w * .22f))) lastLowerBowl = y;
                    leftInk = true;
                    paper = 0;
                } else if (leftInk) paper++;
            }
        }
        for (int y = Math.max(0, Math.round(h * .36f));
                y <= Math.min(h - 1, Math.round(h * .74f));
                y++) {
            if (y < lastLowerBowl) continue;
            int run = 0;
            for (int x = 0; x < w; x++) {
                run = dark(gray, width, left + x, top + y) ? run + 1 : 0;
                if (run >= w * .50f) return true;
            }
        }
        return false;
    }

    private static boolean hasLobePocket(byte[] gray, int width, int left, int y, int w) {
        return lobePocket(gray, y * width + left, Math.round(w * .25f), w - 1,
                Math.max(2, Math.round(w * .22f)), w * .55f) == 2;
    }

    private static boolean staffRuleBracket(
            byte[] gray,
            int width,
            int height,
            MeasureRegion region,
            Glyph numeral,
            float first,
            float middle,
            float last,
            float gap,
            PrintedStaffs printedStaffs) {
        // A digit centered on a head can borrow two segments of the same printed staff as
        // bracket arms. Keep actual arms that remain after accounting for the five staff rules.
        float center = (numeral.left() + numeral.right()) * .5f;
        if (gap < 10 || gap > 26 || Math.abs(center - middle) > .35f * gap) return false;
        float floor = region.bottom() * height - 2.1f * gap;
        if (numeral.bottom() < floor - .45f * gap || numeral.top() > floor + .25f * gap)
            return false;
        int left = Math.round(first - 1.35f * gap), right = Math.round(last + 1.35f * gap);
        if (left < region.left() * width || right > region.right() * width) return false;
        int[] rules = null;
        for (var staff : printedStaffs.detect(gray, width, height)) {
            if (Math.abs(staff.bottom() - floor) > .45f * gap
                    || Math.abs(staff.gap() - gap) > .22f * gap) continue;
            if (rules != null) return false;
            rules = staff.rows();
        }
        if (rules == null) return false;
        int top = Math.max(0, numeral.top() - Math.round(gap * .25f));
        int bottom = Math.min(height - 1, numeral.bottom() + Math.round(gap * .25f));
        return staffRuleArm(gray, width, rules, left, numeral.left() - 2, top, bottom, gap)
                && staffRuleArm(gray, width, rules, numeral.right() + 2, right, top, bottom, gap);
    }

    /** A large lower annotation can borrow both arms from one measured staff rule. */
    private static boolean ruledFingeringBracket(
            byte[] gray, int width, int height, float gap, MeasureRegion region, Glyph numeral,
            float x1, float x2, float x3, Onset a, Onset b, Onset c, Onset following,
            List<ScoreNoteEvent> notes, int virtualStart, PrintedStaffs printedStaffs) {
        if (gap < 10 || gap > 26 || Math.abs((numeral.left() + numeral.right()) * .5f - x2) > gap * .28f
                || following.indices().isEmpty())
            return false;
        var first = notes.get(a.indices().get(0));
        ScoreNoteEvent fourth = null;
        for (int index : following.indices()) {
            if (index >= virtualStart) continue;
            if (fourth != null) return false;
            fourth = notes.get(index);
        }
        if (fourth == null || !sameVoice(first, fourth) || fourth.beamCount() != 2 || fourth.tupletDivisor() != 1
                || fourth.stemDirection() != first.stemDirection() || fourth.augmentationDots() != 0
                || (fourth.articulations() & NoteOrnament.GRACE) != 0)
            return false;
        float x4 = (region.left() + fourth.positionInMeasure() * (region.right() - region.left())) * width;
        if (x4 - x3 < gap || x4 - x3 > gap * 2.9f) return false;
        int floor = -1;
        float estimatedFloor = region.bottom() * height - 2.1f * gap;
        for (var staff : printedStaffs.detect(gray, width, height)) {
            if (Math.abs(staff.bottom() - estimatedFloor) > gap * .45f
                    || Math.abs(staff.gap() - gap) > gap * .22f)
                continue;
            if (floor >= 0) return false;
            floor = Math.round(staff.bottom());
        }
        if (floor < 0 || numeral.top() > floor - gap * .24f
                || numeral.bottom() < floor + gap || numeral.bottom() - numeral.top() < gap * 1.8f)
            return false;
        int left = Math.max(0, Math.round(x1 - gap * .3f));
        int right = Math.min(width - 1, Math.round(x3 + gap * .3f));
        int pad = Math.round(gap * .125f);
        int top = Math.max(0, numeral.top() - pad);
        int bottom = Math.min(height - 1, numeral.bottom() + pad);
        int before = ruledBracketArm(gray, width, left, numeral.left() - 2, top, bottom, floor, gap);
        int after = ruledBracketArm(gray, width, numeral.right() + 2, right, top, bottom, floor, gap);
        if (before < 0 || after < 0 || before + after == 0)
            return false;
        float[] xs = {x1, x2, x3, x4};
        float[] ys = {a.top() * height, b.top() * height, c.top() * height, fourth.pageY() * height};
        var budget = new PrintedBeamShafts.Budget(70_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int i = 0; i < xs.length; i++) {
            var shafts = PrintedBeamShafts.at(gray, width, height, xs[i], ys[i], gap, budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != 1) return false;
            var shaft = shafts.get(0);
            if (previous != null
                    && !WrappedEighthTriplets.matchesBeamRails(gray, width, height, previous, shaft, gap, 2))
                return i < 3 && WrappedEighthTriplets.matchesRailBridge(
                        gray, width, height, previous, shaft, gap, false);
            previous = shaft;
        }
        return true;
    }

    // -1: no measured rule, 0: a rule plus independent support, 1: only the rule provides an arm.
    private static int ruledBracketArm(
            byte[] gray, int width, int left, int right, int top, int bottom, int rule, float gap) {
        int columns = right - left + 1;
        if (columns < gap * 1.15f) return -1;
        int ruleColumns = 0, otherColumns = 0;
        for (int x = left; x <= right; x++) {
            boolean onRule = false, other = false;
            for (int y = top; y <= bottom; y++) {
                if (!dark(gray, width, x, y)) continue;
                if (Math.abs(y - rule) <= gap * .19f) onRule = true;
                else other = true;
            }
            if (onRule) ruleColumns++;
            if (other) otherColumns++;
        }
        if (ruleColumns < columns * .93f) return -1;
        return otherColumns <= columns * .52f ? 1 : 0;
    }

    private static boolean staffRuleArm(
            byte[] gray,
            int width,
            int[] rules,
            int left,
            int right,
            int top,
            int bottom,
            float gap) {
        int columns = 1 + right - left;
        if (columns < 2 * gap) return false;
        int staffColumns = 0, independentColumns = 0;
        for (int x = left; x <= right; x++) {
            boolean ruled = false, independent = false;
            for (int y = top; y <= bottom; y++) {
                if (!dark(gray, width, x, y)) continue;
                boolean onRule = false;
                for (int row : rules) {
                    if (Math.abs(row - y) <= .18f * gap) onRule = true;
                }
                if (onRule) ruled = true;
                else independent = true;
            }
            if (ruled) staffColumns++;
            if (independent) independentColumns++;
        }
        return staffColumns >= .90f * columns && independentColumns <= .22f * columns;
    }

    private static boolean bracketArm(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int top,
            int bottom,
            float gap) {
        left = Math.max(0, left);
        right = Math.min(width - 1, right);
        if (right - left < gap) return false;
        int occupied = 0;
        for (int x = left; x <= right; x++) {
            // Sloped brackets can rise above the numeral at their outer ends.
            // Allow half a staff space while still requiring both long arms.
            for (int y = Math.max(0, top - Math.round(gap * .5f));
                    y <= Math.min(height - 1, bottom + Math.round(gap * .5f));
                    y++)
                if (dark(gray, width, x, y)) {
                    occupied++;
                    break;
                }
        }
        return occupied >= (right - left + 1) * .76f;
    }


    private static boolean dark(byte[] gray, int width, int x, int y) {
        return (gray[y * width + x] & 0xff) <= NUMERAL_INK_LIMIT;
    }

    private static float printedRestTupletGap(
            List<RawStaffLineDetector.StaffLines> printed,
            MeasureRegion bar,
            ScoreNoteEvent note,
            int height,
            float fallback) {
        List<RawStaffLineDetector.StaffLines> inside = new ArrayList<>();
        for (var staff : printed)
            if (staff.center() >= bar.top() * height && staff.center() <= bar.bottom() * height)
                inside.add(staff);
        if (inside.size() != note.staffCount()
                || note.staffIndex() < 0
                || note.staffIndex() >= inside.size()) return fallback;
        float gap = inside.get(note.staffIndex()).gap();
        return gap >= fallback * .65f && gap <= fallback * 1.35f ? gap : fallback;
    }

    /**
     * Separate a numeral above a proved thin crossing rule; brackets and original ownership remain
     * mandatory.
     */
    private static Glyph bracketedCurveThree(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int top,
            float firstX,
            float lastX,
            float firstY,
            float gap,
            java.util.function.Predicate<Glyph> ownership) {
        // Both passes probe the same columns at the same rounded rows.
        int[] probes = new int[512];
        int margin = Math.max(right - (left + right) / 2, (left + right) / 2 - left) / 2 + 2;
        int denseTop = Math.max(2, Math.round(top + gap * .7f) - margin);
        int denseBottom = Math.min(height - 3,
                (int) Math.floor(Math.min(firstY - 1, top + gap * 2.3f)) + margin);
        long cells = (long) (right - left + 1) * (denseBottom - denseTop + 1);
        byte[] dense = cells > 0 && cells <= 65_536 ? new byte[(int) cells] : null;
        Glyph plain =
                bracketedCurveThreePass(
                        gray, width, height, left, right, top, firstX, lastX, firstY, gap,
                        ownership, false, probes, dense, denseTop);
        return plain != null
                ? plain
                : bracketedCurveThreePass(
                        gray, width, height, left, right, top, firstX, lastX, firstY, gap,
                        ownership, true, probes, dense, denseTop);
    }

    private static int curveProbe(byte[] gray, int width, int height, int x, int y,
            int radius, float maximumInk) {
        int flags = 0;
        if (dark(gray, width, x, y - 1) || dark(gray, width, x, y)
                || dark(gray, width, x, y + 1)) flags = 1;
        int ink = 0;
        int last = Math.min(height - 1, y + radius);
        for (int yy = Math.max(0, y - radius); yy <= last; yy++)
            if (dark(gray, width, x, yy) && ++ink > maximumInk) break;
        return ink <= maximumInk ? flags | 2 : flags;
    }

    private static Glyph bracketedCurveThreePass(
            byte[] gray,
            int width,
            int height,
            int left,
            int right,
            int top,
            float firstX,
            float lastX,
            float firstY,
            float gap,
            java.util.function.Predicate<Glyph> ownership,
            boolean occluded,
            int[] probes, byte[] dense, int denseTop) {
        int center = (left + right) / 2, span = right - left + 1;
        int radius = Math.round(gap * .4f);
        float maximumInk = Math.max(3, gap * .35f);
        float requiredHits = span * .94f, requiredThin = span * .90f;
        BracketPrefix prefix = null;
        for (int cy = Math.round(top + gap * .7f);
                cy <= Math.min(firstY - 1, top + gap * 2.3f);
                cy++) {
            slopeCandidate:
            for (int angle = -10; angle <= 10; angle++) {
                float slope = angle * .05f;
                int hits = 0, thin = 0;
                for (int x = left; x <= right; x++) {
                    int y = Math.round(cy + slope * (x - center));
                    if (y >= 2 && y < height - 2) {
                        int cell = (y - denseTop) * span + x - left;
                        int probe;
                        if (dense != null && cell >= 0 && cell < dense.length) {
                            probe = dense[cell];
                            if (probe == 0) {
                                probe = 4 | curveProbe(gray, width, height, x, y, radius, maximumInk);
                                dense[cell] = (byte) probe;
                            }
                        } else {
                            int pixel = y * width + x;
                            int key = (pixel + 1) << 2;
                            int slot = (pixel ^ (pixel >>> 9)) & (probes.length - 1);
                            probe = probes[slot];
                            if ((probe & ~3) != key) {
                                probe = key | curveProbe(gray, width, height, x, y, radius, maximumInk);
                                probes[slot] = probe;
                            }
                        }
                        hits += probe & 1;
                        thin += (probe >>> 1) & 1;
                    }
                    // A later column can contribute at most one vote to either threshold.
                    int remaining = right - x;
                    if (hits + remaining < requiredHits || thin + remaining < requiredThin)
                        continue slopeCandidate;
                }
                int cut =
                        (int)
                                        Math.floor(
                                                cy
                                                        - Math.abs(slope)
                                                                * (occluded
                                                                        ? (span - 1) * .5f
                                                                        : gap * .7f))
                                - 2;
                int rows = cut - top + 1;
                if (rows < gap * .7f || rows > gap * 2.3f) continue;
                if (prefix == null)
                    prefix = new BracketPrefix(gray, width, left, right, top, span, gap, firstY);
                for (var box : prefix.at(rows)) {
                    int l = box.left(), t = box.top(), r = box.right(), b = box.bottom();
                    int gw = r - l + 1, gh = b - t + 1;
                    // A slur can obscure the lower terminal of an italic numeral. The
                    // isolated upper component bounds the crop; all pixels remain original,
                    // and both bracket arms, the complete three shape and ownership still apply.
                    if (occluded) {
                        l = Math.max(left + 1, l - Math.round(gap * .15f));
                        b = Math.round(cy + slope * ((l + r) * .5f - center)) + 1;
                        gw = r - l + 1;
                        gh = b - t + 1;
                        if (gh > gap * 2.3f || b >= firstY - gap || gw > gh * .95f) continue;
                    }
                    // A two's detached baseline serif is not the continuous lower bowl of three.
                    if (occluded) {
                        int previousRight = -1;
                        boolean detachedFoot = false;
                        for (int yy = (int) Math.ceil(gh * .55f);
                                yy <= (int) Math.floor(gh * .82f);
                                yy++) {
                            int edge = -1;
                            for (int xx = 0; xx < gw; xx++)
                                if (dark(gray, width, l + xx, t + yy)) edge = xx;
                            if (edge >= 0
                                    && previousRight >= 0
                                    && edge - previousRight > Math.max(2, gw * .25f))
                                detachedFoot = true;
                            previousRight = edge;
                        }
                        if (detachedFoot) continue;
                    }
                    if (!looksLikeThree(gray, width, l, t, gw, gh)) continue;
                    if (!bracketArm(
                                    gray,
                                    width,
                                    height,
                                    Math.round(firstX - gap * .3f),
                                    l - 2,
                                    t,
                                    t,
                                    gap)
                            || !bracketArm(
                                    gray,
                                    width,
                                    height,
                                    r + 2,
                                    Math.round(lastX + gap * .3f),
                                    t,
                                    t,
                                    gap)) continue;
                    if (TupletNumeralNeighbors.joinedText(gray, width, height, l, t, r, b))
                        continue;
                    Glyph glyph = new Glyph(l, t, r, b);
                    if (ownership.test(glyph)) return glyph;
                }
            }
        }
        return null;
    }

    /** Builds eight-connected runs once and retains ordered glyph bounds at each cut. */
    private static final class BracketPrefix {
        private static final int NO_TOP = Integer.MAX_VALUE;
        private final byte[] gray;
        private final int width, left, right, top, span;
        private final float gap, firstY;
        private final List<List<Glyph>> snapshots = new ArrayList<>();
        private int[] previous, current;
        private int previousCount, runCount;
        private boolean exhausted;
        private int[] parent = new int[32], area = new int[32], runStart = new int[32],
                runEnd = new int[32], minX = new int[32], maxX = new int[32],
                firstTop = new int[32], seenRow = new int[32];

        BracketPrefix(byte[] gray, int width, int left, int right, int top, int span, float gap, float firstY) {
            this.gray = gray;
            this.width = width;
            this.left = left;
            this.right = right;
            this.top = top;
            this.span = span;
            this.gap = gap;
            this.firstY = firstY;
            previous = new int[(span + 1) / 2];
            current = new int[previous.length];
        }

        List<Glyph> at(int rows) {
            while (!exhausted && snapshots.size() < rows) advance(snapshots.size());
            return snapshots.size() < rows ? List.of() : snapshots.get(rows - 1);
        }

        private void advance(int y) {
            int scan = (top + y) * width + left;
            int x = 0, skip = 0, count = 0;
            while (x < span) {
                if ((gray[scan + x] & 255) > NUMERAL_INK_LIMIT) {
                    x++;
                    continue;
                }
                int start = x++;
                while (x < span && (gray[scan + x] & 255) <= NUMERAL_INK_LIMIT) x++;
                int stop = x - 1;
                int run = add(start, stop, y);
                current[count++] = run;
                while (skip < previousCount && runEnd[previous[skip]] < start - 1) skip++;
                for (int at = skip; at < previousCount && runStart[previous[at]] <= stop + 1; at++)
                    join(run, previous[at]);
            }
            snapshots.add(snapshot(y, count));
            int[] swap = previous;
            previous = current;
            current = swap;
            previousCount = count;
        }

        private int add(int start, int stop, int y) {
            int id = runCount++;
            if (id == parent.length) {
                int capacity = id * 2;
                parent = java.util.Arrays.copyOf(parent, capacity);
                area = java.util.Arrays.copyOf(area, capacity);
                runStart = java.util.Arrays.copyOf(runStart, capacity);
                runEnd = java.util.Arrays.copyOf(runEnd, capacity);
                minX = java.util.Arrays.copyOf(minX, capacity);
                maxX = java.util.Arrays.copyOf(maxX, capacity);
                firstTop = java.util.Arrays.copyOf(firstTop, capacity);
                seenRow = java.util.Arrays.copyOf(seenRow, capacity);
            }
            parent[id] = id;
            area[id] = stop - start + 1;
            runStart[id] = minX[id] = start;
            runEnd[id] = maxX[id] = stop;
            firstTop[id] = y == 0 ? start : NO_TOP;
            return id;
        }

        private int root(int id) {
            while (parent[id] != id) {
                parent[id] = parent[parent[id]];
                id = parent[id];
            }
            return id;
        }

        private void join(int a, int b) {
            a = root(a);
            b = root(b);
            if (a == b) return;
            if (area[a] < area[b]) {
                int swap = a;
                a = b;
                b = swap;
            }
            parent[b] = a;
            area[a] += area[b];
            minX[a] = Math.min(minX[a], minX[b]);
            maxX[a] = Math.max(maxX[a], maxX[b]);
            firstTop[a] = Math.min(firstTop[a], firstTop[b]);
        }

        private List<Glyph> snapshot(int y, int count) {
            int rows = y + 1, cut = top + y;
            boolean fitsHeight = !(rows < gap * .7f || rows > gap * 2.3f || cut >= firstY - gap);
            boolean reachesCut = false;
            ArrayList<Integer> found = null;
            for (int i = 0; i < count; i++) {
                int id = root(current[i]);
                if (firstTop[id] == NO_TOP || seenRow[id] == rows) continue;
                seenRow[id] = rows;
                if (minX[id] == 0 || left + maxX[id] >= right) continue;
                reachesCut = true;
                if (!fitsHeight) continue;
                int w = maxX[id] - minX[id] + 1;
                if (w < rows * .3f || w > rows * .95f
                        || area[id] < w * rows * .15f || area[id] > w * rows * .7f) continue;
                if (found == null) found = new ArrayList<>();
                found.add(id);
            }
            // Adding rows cannot reconnect a top seed past the current bottom edge
            // or detach it from either side of the crop.
            exhausted = !reachesCut;
            if (found == null) return List.of();
            found.sort(Comparator.comparingInt(id -> firstTop[id]));
            List<Glyph> boxes = new ArrayList<>(found.size());
            for (int id : found)
                boxes.add(new Glyph(left + minX[id], top, left + maxX[id], cut));
            return boxes;
        }
    }

    /**
     * Extra search height requires a connected ledger-note beam and independently printed staff.
     */
    private static boolean ledgerBeamOwns(
            Glyph glyph,
            ScoreNoteEvent first,
            List<ScoreNoteEvent> heads,
            MeasureRegion bar,
            byte[] gray,
            int width,
            int height,
            float firstX,
            float lastX,
            float gap,
            List<MeasureRegion> measures) {
        boolean upper = first.stemDirection() == -1;
        ScoreNoteEvent last = null;
        for (var n : heads) {
            float x = (bar.left() + n.positionInMeasure() * (bar.right() - bar.left())) * width;
            if (n.measureIndex() == first.measureIndex()
                    && n.staffIndex() == first.staffIndex()
                    && n.staffCount() == first.staffCount()
                    && (upper ? n.staffStep() >= 11 : n.staffStep() <= -3)
                    && n.stemDirection() == first.stemDirection()
                    && n.beamCount() > 0
                    && Math.abs(x - lastX) < gap * .4f) last = n;
        }
        if (last == null
                || !PrintedTupletBeamOwner.connectedHeads(
                        gray,
                        width,
                        height,
                        firstX,
                        first.pageY() * height,
                        lastX,
                        last.pageY() * height,
                        gap,
                        first.stemDirection())) return false;
        float expectedBottom = first.pageY() * height + first.staffStep() * gap * .5f;
        float expectedTop = expectedBottom - 4 * gap;
        int left = Math.max(0, Math.round(upper ? bar.left() * width : firstX - gap)),
                right = Math.min(width - 1, Math.round(upper ? bar.right() * width : lastX + gap));
        int top = Math.max(0, Math.round(expectedTop - gap)),
                bottom = Math.min(height - 1, Math.round(expectedBottom + gap));
        int w = right - left + 1, h = bottom - top + 1;
        if (w < 24 || h < 1) return false;
        byte[] crop = new byte[w * h];
        for (int y = 0; y < h; y++)
            System.arraycopy(gray, (top + y) * width + left, crop, y * w, w);
        int matches = 0;
        for (var staff : RawStaffLineDetector.detect(crop, w, h)) {
            float staffTop = top + staff.top(), staffBottom = top + staff.bottom();
            if (staff.gap() < gap * .8f
                    || staff.gap() > gap * 1.2f
                    || Math.abs(staffTop - expectedTop) > gap * .7f) continue;
            if (upper) {
                if (glyph.top() <= staffBottom + gap * .15f
                        || glyph.bottom() > staffBottom + gap * 2.5f
                        || first.pageY() * height > staffTop - gap
                        || last.pageY() * height > staffTop - gap) continue;
            } else if (glyph.bottom() >= staffTop - gap * .15f
                    || glyph.top() < staffTop - gap * 2.5f
                    || first.pageY() * height < staffBottom + gap
                    || last.pageY() * height < staffBottom + gap) continue;
            matches++;
        }
        if (matches != 0) return matches == 1;
        return ledgerRowStaffOwns(
                glyph, first, last, heads, bar, measures, gray, width, height, gap);
    }

    /** A dense beam can merge rules in a group projection; separate head columns prove the row. */
    private static boolean ledgerRowStaffOwns(
            Glyph glyph,
            ScoreNoteEvent first,
            ScoreNoteEvent last,
            List<ScoreNoteEvent> heads,
            MeasureRegion bar,
            List<MeasureRegion> measures,
            byte[] gray,
            int width,
            int height,
            float gap) {
        if (first.staffCount() != 1 || first.crossStaffBeam() || last.crossStaffBeam())
            return false;
        float rowLeft = bar.left() * width, rowRight = bar.right() * width;
        for (int pass = 0; pass < measures.size(); pass++) {
            float beforeLeft = rowLeft, beforeRight = rowRight;
            for (var next : measures) {
                if (Math.abs(next.top() - bar.top()) * height > gap * .7f
                        || Math.abs(next.bottom() - bar.bottom()) * height > gap * .7f) continue;
                float left = next.left() * width, right = next.right() * width;
                if (left > rowRight + gap * 2 || right < rowLeft - gap * 2) continue;
                rowLeft = Math.min(rowLeft, left);
                rowRight = Math.max(rowRight, right);
            }
            if (rowLeft == beforeLeft && rowRight == beforeRight) break;
        }
        float expectedBottom = first.pageY() * height + first.staffStep() * gap * .5f;
        float proofX = Float.NaN, proofBottom = 0, proofGap = 0;
        boolean separated = false;
        for (var head : heads) {
            if (head.measureIndex() == first.measureIndex()
                    || head.measureIndex() < 0
                    || head.measureIndex() >= measures.size()) continue;
            var region = measures.get(head.measureIndex());
            if (Math.abs(region.top() - bar.top()) * height > gap * .7f
                    || Math.abs(region.bottom() - bar.bottom()) * height > gap * .7f) continue;
            float x =
                    (region.left() + head.positionInMeasure() * (region.right() - region.left()))
                            * width;
            if (x < rowLeft || x > rowRight) continue;
            if (head.staffIndex() != first.staffIndex()
                    || head.staffCount() != first.staffCount()
                    || head.crossStaffBeam()) return false;
            if (head.kind() == ScoreNoteEvent.Kind.UNPITCHED
                    || (head.articulations() & NoteOrnament.GRACE) != 0) continue;
            float bottom = head.pageY() * height + head.staffStep() * gap * .5f;
            if (Math.abs(bottom - expectedBottom) > gap * .7f) continue;
            float[] rules = PrintedDirectionStaff.printedRules(gray, width, height, x, bottom, gap);
            if (rules == null) continue;
            if (Math.abs(rules[0] - expectedBottom) > gap * .7f
                    || rules[1] < gap * .8f
                    || rules[1] > gap * 1.2f) return false;
            if (!Float.isFinite(proofX)) {
                proofX = x;
                proofBottom = rules[0];
                proofGap = rules[1];
            } else {
                if (Math.abs(proofBottom - rules[0]) > gap * .2f
                        || Math.abs(proofGap - rules[1]) > gap * .08f) return false;
                separated |= Math.abs(proofX - x) >= gap * 3;
            }
        }
        if (!separated) return false;
        float staffTop = proofBottom - proofGap * 4;
        if (first.stemDirection() == -1)
            return glyph.top() > proofBottom + gap * .15f
                    && glyph.bottom() <= proofBottom + gap * 2.5f
                    && first.pageY() * height <= staffTop - gap
                    && last.pageY() * height <= staffTop - gap;
        return glyph.bottom() < staffTop - gap * .15f
                && glyph.top() >= staffTop - gap * 2.5f
                && first.pageY() * height >= proofBottom + gap
                && last.pageY() * height >= proofBottom + gap;
    }
}
