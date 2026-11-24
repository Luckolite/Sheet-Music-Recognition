// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Source proof for three straight sixteenth-note octave quartets after a printed inline meter. */
final class InlineStraightOctaveTriples {
    private InlineStraightOctaveTriples() {}

    static boolean closed(
            MeasureRegion run, List<ScoreNoteEvent> notes, byte[] ink, int width, int height) {
        return closed(run, notes, ink, width, height, false);
    }

    private static boolean closed(
            MeasureRegion run, List<ScoreNoteEvent> notes, byte[] ink, int width, int height, boolean accidentalRival) {
        if (notes.size() != 24) return false;
        var heads = notes.stream().sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
        float left = run.left() * width, span = (run.right() - run.left()) * width;
        float gap = (run.bottom() - run.top()) * height / 8.2f;
        float previousX = -1;
        int recovered = 0;
        int opposedAccidentals = 0;
        var budget = new PrintedBeamShafts.Budget(330_000);
        PrintedBeamShafts.Shaft previous = null;
        for (int attack = 0; attack < 12; attack++) {
            var a = heads.get(attack * 2);
            var b = heads.get(attack * 2 + 1);
            float ax = left + span * a.positionInMeasure(), bx = left + span * b.positionInMeasure();
            float x = (ax + bx) * .5f;
            if (!straight(a) || !straight(b) || a.stemDirection() != b.stemDirection()
                    || Math.abs(a.staffStep() - b.staffStep()) != 7 || bx - ax > .22f * gap
                    || previousX >= 0 && (x - previousX < 1.45f * gap || x - previousX > 3.55f * gap))
                return false;
            var first = PrintedBeamShafts.at(ink, width, height, ax, a.pageY() * height, gap, budget);
            var second = PrintedBeamShafts.at(ink, width, height, bx, b.pageY() * height, gap, budget);
            if (first.isEmpty() || second.isEmpty()) {
                boolean missingFirst = first.isEmpty();
                var present = missingFirst ? second : first;
                float missingX = missingFirst ? ax : bx;
                if (budget.refused() || present.size() != 1 || ++recovered > 1
                        || !joinedOctaveShaft(ink, width, present.get(0), missingX,
                                a.pageY() * height, b.pageY() * height, gap)) return false;
                first = present;
                second = present;
            }
            if (accidentalRival && (first.size() == 2 && second.size() == 1
                    || first.size() == 1 && second.size() == 2)) {
                var note = first.size() == 2 ? a : b;
                if (note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                        || ++opposedAccidentals > 1) return false;
                first = first.stream().filter(axis -> axis.direction() == -a.stemDirection()).toList();
                second = second.stream().filter(axis -> axis.direction() == -b.stemDirection()).toList();
            }
            if (first.size() != 1 || second.size() != 1) return false;
            var shaft = first.get(0);
            var other = second.get(0);
            if (shaft.direction() != -a.stemDirection() || other.direction() != shaft.direction()
                    || shaft.left() > other.right() || other.left() > shaft.right()
                    || Math.abs(shaft.endY() - other.endY()) > .28f * gap) return false;
            if (previous != null) {
                boolean nextGroup = attack % 4 == 0;
                if (nextGroup && previous.direction() != shaft.direction()) {
                    if (!opposedGroupsSeparate(ink, width, height, previous, shaft, gap)) return false;
                } else if (!WrappedEighthTriplets.matchesBeamRails(
                        ink, width, height, previous, shaft, gap, nextGroup ? 0 : 2)) return false;
            }
            previous = shaft;
            previousX = x;
        }
        return !budget.refused();
    }

    /** A complete triple-meter bar of twelve straight attacks excludes a borrowed triplet. */
    static ScorePageInterpretation restoreChromaticClock(ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height || raw.length != gray.length)
            return score;
        var notes = score.notes();
        var measures = score.measures();
        var rows = new ArrayList<List<Integer>>(measures.size());
        for (int i = 0; i < measures.size(); i++) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        var rests = new java.util.BitSet(measures.size());
        for (var rest : score.rests()) rests.set(rest.measureIndex());
        var meter = initial;
        int next = 0;
        var beams = score.beamSource();
        ArrayList<ScoreNoteEvent> edited = null;
        for (int bar = 0; bar < measures.size(); bar++) {
            while (next < score.meterChanges().size() && score.meterChanges().get(next).measureIndex() <= bar)
                meter = score.meterChanges().get(next++);
            var owners = rows.get(bar);
            if (meter == null || meter.numerator() != 3 || meter.denominator() != 4 || rests.get(bar)
                    || owners.size() != 24 || measures.get(bar).cadenceQuarterBeats() != 0) continue;
            owners.sort(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
            var row = owners.stream().map(notes::get).toList();
            if (!chromaticFalseTuplet(row)) continue;
            var straight = row.stream().map(n -> n.withTupletRatio(1, 1)).toList();
            if (!closed(measures.get(bar), straight, gray, width, height, true)
                    || !closed(measures.get(bar), straight, raw, width, height, true)) continue;
            var update = octaveQuartets(beams, owners);
            if (update == beams) continue;
            beams = update;
            if (edited == null) edited = new ArrayList<>(notes);
            for (int i = 6; i < 12; i++) edited.set(owners.get(i), straight.get(i));
        }
        return edited == null ? score : score.withNotes(edited,
                java.util.stream.IntStream.range(0, notes.size()).boxed().toList()).withBeamSource(beams);
    }

    private static boolean chromaticFalseTuplet(List<ScoreNoteEvent> row) {
        int previous = -1;
        for (int i = 0; i < row.size(); i += 2) {
            var a = row.get(i);
            var b = row.get(i + 1);
            int step = Math.min(a.staffStep(), b.staffStep());
            boolean triplet = i >= 6 && i < 12;
            if (a.tupletDivisor() != (triplet ? 3 : 1) || b.tupletDivisor() != a.tupletDivisor()
                    || a.tupletNormalNotes() != (triplet ? 2 : 1)
                    || b.tupletNormalNotes() != a.tupletNormalNotes()
                    || a.stemDirection() != -1 || b.stemDirection() != -1
                    || a.octaveShift() != b.octaveShift() || a.octaveShift() != row.get(0).octaveShift()
                    || i > 0 && (step > previous || step < previous - 1)
                    || triplet && (a.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                            || a.writtenAccidental() != b.writtenAccidental())) return false;
            previous = step;
        }
        return true;
    }

    private static ScoreBeamSource octaveQuartets(ScoreBeamSource source, List<Integer> owners) {
        var anchors = owners.stream().map(source.noteOrigins()::get).toList();
        if (anchors.stream().anyMatch(java.util.Optional::isEmpty)) return source;
        var ordered = anchors.stream().map(java.util.Optional::orElseThrow).toList();
        if (new java.util.HashSet<>(ordered).size() != 24
                || ordered.stream().anyMatch(a -> a.pageIndex() != ordered.get(0).pageIndex())
                || source.detachedColumns().stream().anyMatch(c -> c.members().stream().anyMatch(ordered::contains)))
            return source;
        var groups = new ArrayList<>(source.groups());
        for (int i = 0; i < 24; i += 8) {
            var members = ordered.subList(i, i + 8);
            var columns = new ArrayList<ScoreBeamSource.Column>(4);
            for (int j = 0; j < 8; j += 2) columns.add(new ScoreBeamSource.Column(members.subList(j, j + 2)));
            var overlaps = groups.stream().filter(g -> g.columns().stream()
                    .flatMap(c -> c.members().stream()).anyMatch(members::contains)).toList();
            if (overlaps.stream().anyMatch(g -> !g.hooks().isEmpty()
                    || g.columns().stream().anyMatch(c -> c.members().stream().anyMatch(a -> !members.contains(a)))))
                return source;
            var first = members.get(0);
            String id = overlaps.isEmpty() ? "printed-beam:" + first.pageIndex() + ":" + first.noteIndex()
                    : overlaps.get(0).id();
            groups.removeAll(overlaps);
            groups.add(new ScoreBeamSource.Group(id, columns, List.of(3, 3, 3), List.of()));
        }
        return new ScoreBeamSource(groups, source.detachedColumns(), source.noteOrigins());
    }

    private static boolean joinedOctaveShaft(byte[] ink, int width, PrintedBeamShafts.Shaft axis,
            float headX, float aY, float bY, float gap) {
        int first = Math.round(Math.min(aY, bY));
        int last = Math.round(Math.max(aY, bY));
        if (first < 0 || last >= ink.length / width) return false;
        for (int x = axis.left(); x <= axis.right(); x++) {
            float offset = (headX - x) * axis.direction();
            if (offset < .34f * gap || offset > .86f * gap) continue;
            int y = first;
            while (y <= last && (ink[y * width + x] & 255) < 170) y++;
            if (y > last) return true;
        }
        return false;
    }

    private static boolean opposedGroupsSeparate(
            byte[] ink, int width, int height, PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap) {
        if (Math.abs(a.endY() - b.endY()) < gap * 5 || b.left() - a.right() < gap * 1.4f) return false;
        int x = (a.right() + b.left()) / 2;
        int across = Math.round(gap * .24f), vertical = Math.round(gap * .64f);
        for (int end : new int[] {a.endY(), b.endY()}) {
            if (end < vertical || end + vertical >= height || x < across || x + across >= width) return false;
            for (int row = end - vertical; row <= end + vertical; row++)
                for (int col = x - across; col <= x + across; col++)
                    if ((ink[row * width + col] & 255) < 155) return false;
        }
        return true;
    }

    private static boolean straight(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffIndex() == 0 && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && Math.abs(n.stemDirection()) == 1
                && n.beamCount() == 2 && n.augmentationDots() == 0 && n.unbeamedDurationBeats() == 0
                && n.tupletDivisor() == 1 && !n.crossStaffBeam() && !n.tiedFromPrevious()
                && n.boundaryTies() == 0 && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    static boolean fingeringRest(MeasureRegion bar, ScoreRestEvent rest, List<PlayingTechniqueDetector.Word> words,
            byte[] gray, byte[] raw, int width, int height) {
        if (rest.kind() != ScoreRestEvent.Kind.LITERAL || rest.durationBeats() != 1
                || rest.staffIndex() != 0 || rest.staffCount() != 1) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float centerX = (bar.left() + (bar.right() - bar.left()) * rest.positionInMeasure()) * width;
        float centerY = rest.pageY() * height;
        if (gap < 10 || gap > 27 || centerY >= bar.top() * height - .75f * gap
                || rest.positionInMeasure() < .08f || rest.positionInMeasure() > .22f) return false;
        var owner = words.stream().filter(word -> word.text().equals("31")
                && centerX >= word.left() * width && centerX <= word.right() * width
                && centerY >= word.top() * height && centerY <= word.bottom() * height).toList();
        if (owner.size() != 1) return false;
        var word = owner.get(0);
        int left = Math.round(word.left() * width), right = Math.round(word.right() * width);
        int top = Math.round(word.top() * height), bottom = Math.round(word.bottom() * height);
        if (left < 0 || right >= width || top < 0 || bottom >= height
                || right - left > 2.0f * gap || bottom - top > 3.5f * gap) return false;
        return fingeringInk(gray, width, height, left, right, top, bottom, gap)
                && fingeringInk(raw, width, height, left, right, top, bottom, gap);
    }

    private static boolean fingeringInk(
            byte[] ink, int w, int h, int l, int r, int top, int bottom, float gap) {
        var components = RawNumeralComponents.capture(ink, w, h, l, top, r, bottom, () -> {}, () -> {})
                .components().stream().filter(g -> g.size() > .16f * gap * gap)
                .sorted(Comparator.comparingInt(g -> g.top)).toList();
        if (components.size() != 2) return false;
        var upper = components.get(0);
        var lower = components.get(1);
        return lower.top - upper.bottom >= .14f * gap && lower.top - upper.bottom <= .65f * gap
                && Math.abs(upper.left + upper.right - lower.left - lower.right) <= .65f * gap
                && TripletRhythmDetector.fingerOne(ink, w, lower);
    }

    static ScorePageInterpretation removeFingeringRest(
            ScorePageInterpretation score, List<PlayingTechniqueDetector.Word> words,
            byte[] gray, byte[] raw, int width, int height) {
        if (score.rests().isEmpty() || words.isEmpty()) return score;
        var rests = new ArrayList<>(score.rests());
        rests.removeIf(rest -> {
            int i = rest.measureIndex();
            if (i == 0 || score.measures().get(i - 1).cadenceQuarterBeats() != 2) return false;
            var measure = score.measures().get(i);
            if (measure.cadenceQuarterBeats() != 3 || !fingeringRest(measure, rest, words, gray, raw, width, height))
                return false;
            var heads = score.notes().stream().filter(n -> n.measureIndex() == i).toList();
            return closed(measure, heads, gray, width, height) && closed(measure, heads, raw, width, height);
        });
        return rests.size() == score.rests().size() ? score : score.withRests(List.copyOf(rests));
    }
}
