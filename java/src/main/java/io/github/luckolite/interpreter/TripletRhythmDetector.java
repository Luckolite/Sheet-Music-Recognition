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
        if (rests == null || rests.isEmpty())
            return new Rhythm(apply(notes, measures, gray, width, height), rests);
        var printedStaffs = new PrintedStaffs();
        List<ScoreNoteEvent> slots =
                new ArrayList<>(isolatedPrintedLanes(notes, measures, gray, width, height, printedStaffs));
        List<Integer> restIndices = new ArrayList<>();
        for (int restIndex = 0; restIndex < rests.size(); restIndex++) {
            ScoreRestEvent rest = rests.get(restIndex);
            if (rest.isFullMeasure()) continue;
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
                        rests,
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
            double delta = rest.durationBeats() - scaled.get(i).durationBeats();
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
        return new Rhythm(List.copyOf(result), List.copyOf(scaled));
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
        List<Onset> result = new ArrayList<>();
        for (int i = 0; i < notes.size(); ) {
            int firstIndex = order == null ? i : order.get(i);
            ScoreNoteEvent first = notes.get(firstIndex);
            List<Integer> chord = null;
            float top = first.pageY(), bottom = top;
            int j = i + 1;
            boolean includesRest = firstIndex >= virtualStart;
            while (j < notes.size()) {
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
                    || n.augmentationDots() != 0
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
                        && first.beamCount() == 2
                        && result.get(b.indices().get(0)).beamCount() == 2
                        && result.get(c.indices().get(0)).beamCount() == 2) {
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
                        && !bracket
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
                                        gray, width, height, leftX, leftY, rightX, rightY, gap,
                                        side)
                                && hasNearbyFour(gray, width, height, numeral, gap)) {
                            fingering = true;
                            break;
                        }
                    }
                    if (fingering) continue;
                }
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

    /** Preserve a printed binary pair without changing a complete four-quarter staff clock. */
    static List<ScoreNoteEvent> neutralPrintedPairs(
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures,
            List<ScoreMeterChange> meters, byte[] gray, int width, int height) {
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
                    || !fourQuarterContext(meters, first.measureIndex())
                    || Math.abs(neutralStaffClock(groups, notes, first) - 4) > .001) continue;
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
        return List.copyOf(result);
    }

    private static boolean ordinaryBeamedEighth(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.beamCount() == 1
                && note.augmentationDots() == 0 && note.tupletDivisor() == 1
                && note.stemDirection() != 0 && !note.crossStaffBeam()
                && (note.articulations() & NoteOrnament.GRACE) == 0;
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
            List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures,
            List<ScoreMeterChange> meters,
            byte[] gray,
            int width,
            int height) {
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
        return List.copyOf(result);
    }

    /** Resolve a complete printed seven-sixteenth group against established document meter. */
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
                    || first.beamCount() != 2
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
            boolean valid = true;
            float previous = -1;
            for (int i : members) {
                var n = notes.get(i);
                if (n.tupletNormalNotes() != 4
                        || n.beamCount() != 2
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
            for (int i : members) candidate.set(i, notes.get(i).withTupletRatio(7, 6));
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
        List<ScoreNoteEvent> result = new ArrayList<>(notes);
        for (int i = 0; i + divisor - 1 < groups.size(); i++) {
            Onset firstGroup = groups.get(i);
            for (int index : firstGroup.indices()) {
                ScoreNoteEvent first = result.get(index);
                if (first.tupletDivisor() != 1
                        || first.beamCount() < 1
                        || first.augmentationDots() != 0
                        || first.measureIndex() < 0
                        || first.measureIndex() >= measures.size()) continue;
                if (divisor == 4 && (first.beamCount() > 2) != rapidQuadruplets) continue;
                List<Onset> run = new ArrayList<>();
                float minimum = Float.MAX_VALUE, maximum = 0;
                boolean valid = true;
                for (int j = 0; j < divisor; j++) {
                    Onset onset = matching(groups.get(i + j), result, first);
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
                        && matching(groups.get(i - 1), result, first) != null
                        && firstGroup.position() - groups.get(i - 1).position() < minimum * 1.5f
                        && !separateTupletBoundary(
                                groups.get(i - 1), firstGroup, bar, gray, width, height, gap)
                        && !(divisor == 6
                                && adjacentSix(
                                        groups, result, i - 6, first, bar, gray, width, height,
                                        gap))) continue;
                if (i + divisor < groups.size()
                        && matching(groups.get(i + divisor), result, first) != null
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
                        && !(divisor == 6
                                && adjacentSix(
                                        groups, result, i + 6, first, bar, gray, width, height,
                                        gap))) continue;
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
        if (group.size() != 3) return false;
        ScoreNoteEvent[] heads = new ScoreNoteEvent[3];
        for (int i = 0; i < 3; i++) {
            var members = group.get(i).indices();
            if (members.size() != 1) return false;
            int index = members.get(0);
            if (index < 0 || index >= virtualStart || index >= notes.size()) return false;
            heads[i] = notes.get(index);
            if (heads[i].kind() != ScoreNoteEvent.Kind.PITCHED
                    || heads[i].beamCount() != 1
                    || heads[i].stemDirection() == 0
                    || heads[i].crossStaffBeam()
                    || (heads[i].articulations() & NoteOrnament.GRACE) != 0
                    || !sameVoice(heads[0], heads[i])
                    || heads[i].beamCount() != heads[0].beamCount()
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
    private static boolean adjacentSix(
            List<Onset> groups,
            List<ScoreNoteEvent> notes,
            int start,
            ScoreNoteEvent first,
            MeasureRegion bar,
            byte[] gray,
            int width,
            int height,
            float gap) {
        if (start < 0 || start + 6 > groups.size()) return false;
        float top = Float.MAX_VALUE, bottom = 0, min = Float.MAX_VALUE, max = 0;
        for (int j = 0; j < 6; j++) {
            Onset onset = matching(groups.get(start + j), notes, first);
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
                (bar.left() + groups.get(start + 5).position() * (bar.right() - bar.left()))
                        * width;
        if (x2 - x1 < gap * 3 || x2 - x1 > gap * 26) return false;
        Glyph glyph =
                findContrastedNumeral(
                        gray, width, height, x1, x2, top, bottom, gap, true, Float.NaN, Float.NaN,
                        6);
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
                        ownership);
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
                number == 3
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
        for (int pass = 0; crop != null && pass < 2; pass++)
            for (int level : levels) {
                var local = crop.window(level, pass != 0);
                if (local == null) continue;
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
                                glyph ->
                                        ownership.test(
                                                new Glyph(
                                                        glyph.left() + local.left(),
                                                                glyph.top() + local.top(),
                                                        glyph.right() + local.left(),
                                                                glyph.bottom() + local.top())));
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
        boolean[] visited = new boolean[localWidth * localHeight];
        int[] queue = new int[visited.length];
        boolean usesComponentShape = number != 2 && number != 4 && number != 5
                && number != 6 && number != 7;
        int[] component = usesComponentShape ? new int[visited.length] : null;
        for (int origin = 0; origin < visited.length; origin++) {
            if (visited[origin]) continue;
            visited[origin] = true;
            if (!dark(gray, width, left + origin % localWidth, top + origin / localWidth)) continue;
            int count = 0, pending = 1;
            queue[0] = origin;
            int minX = right, maxX = left, minY = bottom, maxY = top;
            while (pending > 0) {
                int current = queue[--pending];
                if (component != null) component[count] = current;
                count++;
                int x = current % localWidth, y = current / localWidth;
                minX = Math.min(minX, left + x);
                maxX = Math.max(maxX, left + x);
                minY = Math.min(minY, top + y);
                maxY = Math.max(maxY, top + y);
                int firstRow = Math.max(0, y - 1), lastRow = Math.min(localHeight - 1, y + 1);
                int firstColumn = Math.max(0, x - 1), lastColumn = Math.min(localWidth - 1, x + 1);
                for (int ny = firstRow; ny <= lastRow; ny++) {
                    int row = ny * localWidth;
                    for (int nx = firstColumn; nx <= lastColumn; nx++) {
                        int next = row + nx;
                        if (visited[next]) continue;
                        visited[next] = true;
                        if (dark(gray, width, left + nx, top + ny)) queue[pending++] = next;
                    }
                }
            }
            if (number == 3 && shortNotes && minX <= left && maxX >= right && minY < firstY - gap) {
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
            int gw = maxX - minX + 1, gh = maxY - minY + 1;
            if (minX <= left
                    || maxX >= right
                    || minY <= top
                    || maxY >= bottom
                    || gh < gap * .7f
                    || gh > gap * 2.3f
                    || gw < gh * .30f
                    || gw > gh * .95f
                    || count < gw * gh * .15f
                    || count > gw * gh * .70f
                    || !(maxY < firstY - gap || minY > lastY + gap)) continue;
            if (Float.isFinite(headX)
                    && (headX < minX - gap * .1f
                            || headX > maxX + gap * .1f
                            || headY < minY - gap * .1f
                            || headY > maxY + gap * .1f)) continue;
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
            if (shortNotes || bracket) {
                Glyph glyph = new Glyph(minX, minY, maxX, maxY);
                if (ownership.test(glyph)) return glyph;
            }
        }
        return null;
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
        for (int i = 0; i < count; i++) {
            int x = sourceLeft + component[i] % sourceWidth - left;
            int y = sourceTop + component[i] / sourceWidth - top;
            pixels[y * w + x] = 0;
        }
        return looksLikeThree(pixels, w, 0, 0, w, h);
    }

    private static boolean looksLikeThree(byte[] gray, int width, int left, int top, int w, int h) {
        int[] min = new int[h], max = new int[h];
        for (int y = 0; y < h; y++) {
            min[y] = w;
            max[y] = -1;
            for (int x = 0; x < w; x++)
                if (dark(gray, width, left + x, top + y)) {
                    min[y] = Math.min(min[y], x);
                    max[y] = x;
                }
        }
        int upperOpen = 0, lowerOpen = 0, upperPocket = 0, lowerPocket = 0;
        int upperRasterPocket = 0, lowerRasterPocket = 0;
        int upperLobe = -1, lowerLobe = -1, waist = w, waistLeft = w, lowerOpenLeft = w;
        for (int y = 0; y < h; y++) {
            // Staff-rule removal can leave a blank scanline through a rest hook.
            // No ink is not an opening or an indented waist of a printed 3.
            if (max[y] < 0) continue;
            float fraction = y / (float) h;
            // A row occupies a whole pixel band; include a short opening that
            // crosses a lobe boundary instead of discarding it at small sizes.
            float nextFraction = (y + 1) / (float) h;
            if (nextFraction > .15f && fraction <= .36f + 1f / h) {
                upperLobe = Math.max(upperLobe, max[y]);
                if (min[y] >= w * .40f) upperOpen++;
                if (hasLobePocket(gray, width, left, top + y, w)) upperPocket++;
                if (hasLobePocket(gray, width, left, top + y, w, true)) upperRasterPocket++;
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
                if (hasLobePocket(gray, width, left, top + y, w)) lowerPocket++;
                if (hasLobePocket(gray, width, left, top + y, w, true)) lowerRasterPocket++;
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
        int foot = -1, capLeft = w;
        for (int y = 0; y < Math.max(2, (int) Math.ceil(h * .10)); y++)
            capLeft = Math.min(capLeft, min[y]);
        for (int y = (int) Math.ceil(h * .92); y < h; y++) foot = Math.max(foot, max[y]);
        int extendedSolidFoot = 0;
        for (int y = (int) Math.ceil(h * .55f); y < (int) Math.ceil(h * .92f); y++) {
            if (min[y] > w * .4f || max[y] - min[y] + 1 < w * .6f) continue;
            boolean growsFromDiagonal = false;
            for (int prior = Math.max(0, y - Math.max(2, Math.round(h * .15f))); prior < y; prior++)
                if (max[prior] >= 0
                        && max[y] - max[prior] >= Math.max(2, Math.ceil(w * .2f))
                        && min[y] <= min[prior] + indentation) growsFromDiagonal = true;
            if (!growsFromDiagonal) continue;
            int occupied = 0;
            for (int x = min[y]; x <= max[y]; x++)
                if (dark(gray, width, left + x, top + y)) occupied++;
            if (occupied >= (max[y] - min[y] + 1) * .9f) extendedSolidFoot++;
        }
        // A tilted 2's full terminal can end above the last antialiased rows.
        // Its solid foot extends beyond the preceding diagonal, not around a lower bowl.
        boolean lobes =
                extendedSolidFoot < required
                        && lowerLobe - foot >= indentation
                        && upperLobe - waist >= indentation;
        boolean strictLower =
                lowerLobe - waist >= indentation
                        || lowerLobe >= w * .5f
                                && lowerOpen >= required
                                && lowerOpenLeft - waistLeft >= indentation
                                && upperLobe - capLeft >= Math.max(2, (int) Math.ceil(w * .25f));
        // Reject a complete four before accepting either three recognition path.
        if (lobes && upper && lower && strictLower) {
            int legacyArea = 0;
            for (int yy = 0; yy < h; yy++)
                for (int xx = 0; xx < w; xx++)
                    if (dark(gray, width, left + xx, top + yy)) legacyArea++;
            return !completeFourCrossbar(gray, width, legacyArea, left, top, w, h);
        }
        boolean rasterUpper =
                upperOpen >= required || upperOpen >= 1 && upperRasterPocket >= required;
        boolean rasterLower =
                lowerOpen >= required || lowerOpen >= 1 && lowerRasterPocket >= required;
        boolean curledLower =
                lowerLobe - waist >= indentation
                        || lowerLobe >= w * .5f
                                && rasterLower
                                && lowerOpenLeft - waistLeft >= indentation
                                && upperLobe - capLeft >= Math.max(2, (int) Math.ceil(w * .25f));
        if (!(lobes && rasterUpper && rasterLower && curledLower)) return false;
        // A four's complete spine and crossbar contradict the added two-bowl proposal.
        // Do not reinterpret a four's narrow diagonal paper pocket as a curled terminal.
        int area = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) if (dark(gray, width, left + x, top + y)) area++;
        return !completeFourCrossbar(gray, width, area, left, top, w, h);
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
        return hasLobePocket(gray, width, left, y, w, false);
    }

    private static boolean hasLobePocket(
            byte[] gray, int width, int left, int y, int w, boolean pixelCenter) {
        int run = 0;
        for (int x = Math.round(w * .25f); x < w; x++) {
            if (!dark(gray, width, left + x, y)) run++;
            else {
                // Only the additive recovery compares the sampled pixel center.
                if (run >= Math.max(2, Math.round(w * .22f))
                        && x + (pixelCenter ? .5f : 0) >= w * .55f) return true;
                run = 0;
            }
        }
        return false;
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
        return (gray[y * width + x] & 0xff) <= 165;
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
        Glyph plain =
                bracketedCurveThreePass(
                        gray, width, height, left, right, top, firstX, lastX, firstY, gap,
                        ownership, false);
        return plain != null
                ? plain
                : bracketedCurveThreePass(
                        gray, width, height, left, right, top, firstX, lastX, firstY, gap,
                        ownership, true);
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
            boolean occluded) {
        int center = (left + right) / 2, span = right - left + 1;
        for (int cy = Math.round(top + gap * .7f);
                cy <= Math.min(firstY - 1, top + gap * 2.3f);
                cy++) {
            for (int angle = -10; angle <= 10; angle++) {
                float slope = angle * .05f;
                int hits = 0, thin = 0;
                for (int x = left; x <= right; x++) {
                    int y = Math.round(cy + slope * (x - center));
                    if (y < 2 || y >= height - 2) continue;
                    if (dark(gray, width, x, y - 1)
                            || dark(gray, width, x, y)
                            || dark(gray, width, x, y + 1)) hits++;
                    int ink = 0;
                    for (int yy = Math.max(0, y - Math.round(gap * .4f));
                            yy <= Math.min(height - 1, y + Math.round(gap * .4f));
                            yy++) if (dark(gray, width, x, yy)) ink++;
                    if (ink <= Math.max(3, gap * .35f)) thin++;
                }
                if (hits < span * .94f || thin < span * .90f) continue;
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
                boolean[] seen = new boolean[span * rows];
                int[] queue = new int[seen.length];
                for (int seed = 0; seed < seen.length; seed++) {
                    if (seen[seed] || !dark(gray, width, left + seed % span, top + seed / span))
                        continue;
                    int take = 0, size = 1, l = right, r = left, t = cut, b = top;
                    queue[0] = seed;
                    seen[seed] = true;
                    while (take < size) {
                        int at = queue[take++], x = at % span, y = at / span;
                        l = Math.min(l, left + x);
                        r = Math.max(r, left + x);
                        t = Math.min(t, top + y);
                        b = Math.max(b, top + y);
                        for (int dy = -1; dy <= 1; dy++)
                            for (int dx = -1; dx <= 1; dx++) {
                                int xx = x + dx, yy = y + dy;
                                if (xx < 0 || xx >= span || yy < 0 || yy >= rows) continue;
                                int next = yy * span + xx;
                                if (!seen[next] && dark(gray, width, left + xx, top + yy)) {
                                    seen[next] = true;
                                    queue[size++] = next;
                                }
                            }
                    }
                    int gw = r - l + 1, gh = b - t + 1;
                    if (l <= left
                            || r >= right
                            || t != top
                            || b != cut
                            || gh < gap * .7f
                            || gh > gap * 2.3f
                            || gw < gh * .3f
                            || gw > gh * .95f
                            || size < gw * gh * .15f
                            || size > gw * gh * .7f
                            || b >= firstY - gap) continue;
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
