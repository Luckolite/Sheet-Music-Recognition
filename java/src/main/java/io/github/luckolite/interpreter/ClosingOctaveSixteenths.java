// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** Recover a missing opening octave head and remove an attack inside the corresponding double beam. */
final class ClosingOctaveSixteenths {
    private ClosingOctaveSixteenths() {}

    private record Pair(int lower, int upper) {}
    private record Point(float x, float y) {}
    private record Run(List<Pair> pairs, int fragment) {}

    static ScorePageInterpretation restore(ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] labels, byte[] gray, byte[] raw, int w, int h) {
        if (labels == null || gray == null || raw == null || gray.length != (long) w * h
                || labels.length != gray.length || raw.length != gray.length) return score;
        var notes = score.notes();
        var rows = new ArrayList<List<Integer>>();
        for (var ignored : score.measures()) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        var rests = new java.util.BitSet(rows.size());
        for (var rest : score.rests()) rests.set(rest.measureIndex());
        int next = 0;
        var meter = initial;
        var result = score;
        for (int i = 0; i < rows.size(); i++) {
            while (next < score.meterChanges().size() && score.meterChanges().get(next).measureIndex() <= i)
                meter = score.meterChanges().get(next++);
            var owners = rows.get(i);
            var bar = score.measures().get(i);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4 || rests.get(i)
                    || owners.size() != 30 || bar.cadenceQuarterBeats() != 0) continue;
            float gap = (bar.bottom() - bar.top()) * h / 8.2f;
            if (gap < 12 || gap > 24) continue;
            owners.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            var run = matchRun(owners, notes, bar, w, h, gap);
            if (run == null) continue;
            var lower = notes.get(run.pairs().get(0).lower());
            var candidate = openingHead(labels, gray, w, h, bar, lower, gap);
            if (candidate == null || !provesInk(gray, w, h, bar, notes, run, candidate, gap)
                    || !provesInk(raw, w, h, bar, notes, run, candidate, gap)) continue;
            result = restoreRun(result, run, candidate, bar, w, h);
        }
        return result;
    }

    private static Run matchRun(List<Integer> owners, List<ScoreNoteEvent> notes,
            MeasureRegion bar, int w, int h, float gap) {
        var pairs = new ArrayList<Pair>();
        var first = notes.get(owners.get(0));
        int fragment = -1;
        for (int j = 0; j < owners.size();) {
            int owner = owners.get(j++);
            var note = notes.get(owner);
            int attack = pairs.size();
            if (note.beamCount() == 0) {
                if (attack != 9 || fragment >= 0 || note.stemDirection() != 1 || note.unbeamedDurationBeats() != 1
                        || note.tiedFromPrevious() || note.kind() != ScoreNoteEvent.Kind.PITCHED
                        || note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return null;
                fragment = owner;
                continue;
            }
            if (attack >= 15) return null;
            boolean lone = j == 1;
            if (!lone && j == owners.size()) return null;
            int other = lone ? -1 : owners.get(j++);
            if (other >= 0 && notes.get(other).pageY() > note.pageY()) {
                int upper = owner;
                owner = other;
                other = upper;
                note = notes.get(owner);
            }
            int step = attack == 14 ? first.staffStep() - 9 : first.staffStep() - attack / 2 - 2 * (attack % 2);
            int rails = attack == 14 ? 1 : 2;
            boolean tuple = attack < 3;
            if (!clockHead(note, step, rails, tuple)) return null;
            if (other >= 0) {
                var upper = notes.get(other);
                if (!clockHead(upper, step + 7, rails, tuple)
                        || Math.abs(x(bar, note, w) - x(bar, upper, w)) > .22f * gap
                        || Math.abs((note.pageY() - upper.pageY()) * h - 3.5f * gap) > .24f * gap) return null;
            }
            pairs.add(new Pair(owner, other));
        }
        return pairs.size() == 15 && fragment >= 0 ? new Run(pairs, fragment) : null;
    }

    private static boolean clockHead(ScoreNoteEvent n, int step, int rail, boolean tuple) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1 && n.staffIndex() == 0
                && n.staffStep() == step && n.stemDirection() == -1 && n.beamCount() == rail
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && n.unbeamedDurationBeats() == 0
                && n.augmentationDots() == 0 && n.tupletDivisor() == (tuple ? 3 : 1)
                && n.tupletNormalNotes() == (tuple ? 2 : 1) && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0 && n.harmonicSemitones() == 0 && n.openStringTouch() == null
                && !n.tiedFromPrevious() && n.boundaryTies() == 0 && !n.crossStaffBeam()
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static Point openingHead(byte[] labels, byte[] gray, int w, int h,
            MeasureRegion bar, ScoreNoteEvent lower, float gap) {
        float x = x(bar, lower, w), y = lower.pageY() * h - 3.5f * gap;
        int left = Math.round(x - .83f * gap), right = Math.round(x + .86f * gap);
        int top = Math.round(y - .58f * gap), bottom = Math.round(y + .58f * gap);
        if (left < 0 || top < 0 || right >= w || bottom >= h) return null;
        int count = 0;
        long sumX = 0, sumY = 0;
        for (int at = top; at <= bottom; at++)
            for (int col = left; col <= right; col++) {
                int pixel = at * w + col;
                if (labels[pixel] != OmrMeasurePostProcessor.NOTEHEAD || (gray[pixel] & 255) >= 155) continue;
                count++;
                sumX += col;
                sumY += at;
            }
        if (count < .68f * gap * gap || count > 1.44f * gap * gap) return null;
        var point = new Point((float) sumX / count, (float) sumY / count);
        return Math.abs(point.x() - x) <= .24f * gap && Math.abs(point.y() - y) <= .24f * gap ? point : null;
    }

    private static boolean provesInk(byte[] ink, int w, int h, MeasureRegion bar,
            List<ScoreNoteEvent> notes, Run run, Point missing, float gap) {
        if (!ClosedDottedSixteenths.hasClosingRule(ink, w, h, bar)
                || !FilledSparseBeamHead.completeInterior(ink, w, h, missing.x(), missing.y(), gap)) return false;
        var budget = new PrintedBeamShafts.Budget(420_000);
        var axes = new ArrayList<PrintedBeamShafts.Shaft>();
        float priorX = 0;
        for (var pair : run.pairs()) {
            var lower = notes.get(pair.lower());
            float x = x(bar, lower, w), y = lower.pageY() * h;
            if (!axes.isEmpty() && (x - priorX < 1.52f * gap || x - priorX > 2.92f * gap)) return false;
            var candidates = PrintedBeamShafts.at(ink, w, h, x, y, gap, budget);
            if (candidates.size() != 1 || candidates.get(0).direction() != 1) return false;
            var axis = candidates.get(0);
            float upperX = pair.upper() < 0 ? missing.x() : x(bar, notes.get(pair.upper()), w);
            float upperY = pair.upper() < 0 ? missing.y() : notes.get(pair.upper()).pageY() * h;
            if (!sameStem(ink, w, axis, upperX, upperY, y, gap)) return false;
            axes.add(axis);
            priorX = x;
        }
        if (budget.refused()) return false;
        float floor = bar.bottom() * h - 2.1f * gap;
        var track = StaffPitchTrack.forPrintedSlurs(ink, w, h, floor - 4 * gap, floor, gap);
        if (track == null) return false;
        for (int i = 1; i < axes.size(); i++) {
            var a = axes.get(i - 1);
            var b = axes.get(i);
            if (!(i % 4 == 0 ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, w, h, bar, a, b, gap)
                    : i == 14 ? WrappedEighthTriplets.matchesBeamRails(ink, w, h, a, b, gap, 1)
                    : WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink, w, h, a, b, gap, track))) return false;
        }
        return insideBeam(ink, w, h, bar, notes.get(run.fragment()), axes.get(8), axes.get(9), gap);
    }

    private static boolean sameStem(byte[] ink, int w, PrintedBeamShafts.Shaft axis,
            float upperX, float upperY, float lowerY, float gap) {
        int top = Math.round(upperY + .20f * gap), bottom = Math.round(lowerY - .15f * gap);
        for (int col = axis.left(); col <= axis.right(); col++) {
            if (upperX - col < .34f * gap || upperX - col > .86f * gap) continue;
            int white = 0;
            for (int y = top; y <= bottom; y++)
                if ((ink[y * w + col] & 255) >= 170) white++;
            if (white <= 2) return true;
        }
        return false;
    }

    private static boolean insideBeam(byte[] ink, int w, int h, MeasureRegion bar, ScoreNoteEvent note,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap) {
        float center = x(bar, note, w), y = note.pageY() * h;
        if (center < a.right() + .38f * gap || center > b.left() - .33f * gap) return false;
        float lower = a.endY() + (b.endY() - a.endY()) * (center - a.right()) / (b.left() - a.right());
        if (y < lower - 1.3f * gap || y > lower + .24f * gap) return false;
        int left = Math.round(center - .38f * gap), right = Math.round(center + .38f * gap);
        int top = Math.round(y - .22f * gap), bottom = Math.round(y + .22f * gap);
        if (left < 0 || right >= w || top < 0 || bottom >= h) return false;
        int inkRows = 0;
        for (int at = top; at <= bottom; at++) {
            int dark = 0;
            for (int col = left; col <= right; col++)
                if ((ink[at * w + col] & 255) < 150) dark++;
            if (dark >= .90f * (right - left + 1)) inkRows++;
        }
        return inkRows >= Math.round(.20f * gap);
    }

    private static ScorePageInterpretation restoreRun(ScorePageInterpretation score, Run run, Point head,
            MeasureRegion bar, int width, int height) {
        var notes = score.notes();
        int lowerOwner = run.pairs().get(0).lower();
        var lower = notes.get(lowerOwner);
        float position = (head.x() / width - bar.left()) / (bar.right() - bar.left());
        var upper = new ScoreNoteEvent(lower.measureIndex(), position, lower.staffStep() + 7,
                lower.staffIndex(), lower.staffCount(), head.y() / height, false, 0, 2,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0, lower.clefBottomDiatonic())
                .withStemDirection(-1);
        var edited = new ArrayList<ScoreNoteEvent>();
        var sourceOwners = new ArrayList<Integer>();
        var changed = new HashSet<Integer>();
        for (int i = 0; i < 3; i++) {
            var pair = run.pairs().get(i);
            changed.add(pair.lower());
            if (pair.upper() >= 0) changed.add(pair.upper());
        }
        int added = -1;
        for (int i = 0; i < notes.size(); i++) {
            if (i == run.fragment()) continue;
            if (i == lowerOwner) {
                added = edited.size();
                edited.add(upper);
                sourceOwners.add(-1);
            }
            edited.add(changed.contains(i) ? notes.get(i).withTupletRatio(1, 1) : notes.get(i));
            sourceOwners.add(i);
        }
        var result = score.withNotes(edited, sourceOwners);
        var projected = beams(result.beamSource(), score.beamSource(), run, added);
        return projected == result.beamSource() ? score : result.withBeamSource(projected);
    }

    private static ScoreBeamSource beams(ScoreBeamSource remapped, ScoreBeamSource original, Run run, int added) {
        var origins = new ArrayList<>(remapped.noteOrigins());
        var first = original.noteOrigins().get(run.pairs().get(0).lower());
        if (first.isEmpty() || added < 0) return remapped;
        int page = first.get().pageIndex();
        var fresh = new ScoreBeamSource.Anchor(page, ScoreBeamSource.MAX_NOTES - 1 - added);
        if (origins.contains(Optional.of(fresh))) return remapped;
        origins.set(added, Optional.of(fresh));
        var columns = new ArrayList<ScoreBeamSource.Column>();
        var touched = new HashSet<ScoreBeamSource.Anchor>();
        for (var pair : run.pairs()) {
            var low = original.noteOrigins().get(pair.lower());
            var high = pair.upper() < 0 ? Optional.of(fresh) : original.noteOrigins().get(pair.upper());
            if (low.isEmpty() || high.isEmpty() || low.get().pageIndex() != page || high.get().pageIndex() != page)
                return remapped;
            columns.add(new ScoreBeamSource.Column(List.of(low.get(), high.get())));
            if (!touched.add(low.get()) || !touched.add(high.get())) return remapped;
        }
        var fragment = original.noteOrigins().get(run.fragment());
        fragment.ifPresent(touched::add);
        if (original.detachedColumns().stream().anyMatch(c -> c.members().stream().anyMatch(touched::contains)))
            return remapped;
        var groups = new ArrayList<ScoreBeamSource.Group>();
        for (var g : original.groups()) {
            var anchors = g.columns().stream().flatMap(c -> c.members().stream()).toList();
            if (anchors.stream().noneMatch(touched::contains)) groups.add(g);
            else if (!touched.containsAll(anchors) || !g.hooks().isEmpty()) return remapped;
        }
        for (int i = 0; i < 15; i += 4) {
            var members = columns.subList(i, Math.min(i + 4, 15));
            groups.add(new ScoreBeamSource.Group("printed-beam:" + page + ":" + members.get(0).members().get(0).noteIndex(),
                    members, i == 12 ? List.of(3, 1) : List.of(3, 3, 3), List.of()));
        }
        return new ScoreBeamSource(groups, remapped.detachedColumns(), origins);
    }

    private static float x(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
    }
}
