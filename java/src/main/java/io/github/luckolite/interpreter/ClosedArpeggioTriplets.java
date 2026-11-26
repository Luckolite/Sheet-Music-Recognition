// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Resolve unnumbered eighth-note triplets when the complete mixed-beam bar closes the clock. */
final class ClosedArpeggioTriplets {
    private ClosedArpeggioTriplets() {}

    private static final List<Integer> GROUP_STARTS = List.of(0, 3, 6, 10, 14);

    static ScorePageInterpretation restore(ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height
                || raw.length != gray.length) return score;
        var bars = score.measures();
        var notes = score.notes();
        var beams = score.beamSource();
        if (beams.noteOrigins().size() != notes.size()) return score;
        var rows = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        var hasRest = new java.util.BitSet(bars.size());
        for (var rest : score.rests()) hasRest.set(rest.measureIndex());
        var meter = initial;
        int nextMeter = 0;
        List<ScoreNoteEvent> repaired = null;
        var aliases = new java.util.BitSet(notes.size());
        for (int i = 0; i < bars.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            var row = rows.get(i);
            var bar = bars.get(i);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || (row.size() != 14 && row.size() != 15) || hasRest.get(i) || bar.cadenceQuarterBeats() != 0) continue;
            row.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            var owners = dottedTransitionOwners(bar, notes, row, gray, raw, width, height);
            if (!owners.isEmpty()) {
                var projected = projectDottedTransition(beams, row, owners);
                if (projected == beams) continue;
                beams = projected;
                if (repaired == null) repaired = new ArrayList<>(notes);
                for (int j = 0; j < owners.size(); j++) {
                    int index = owners.get(j);
                    var note = notes.get(index);
                    if (j < 6) note = note.withTupletRatio(3, 2);
                    else note = ClosedDottedHookCadence.rhythm(note, j % 2 == 0 ? 2 : 3, j % 2 == 0 ? 1 : 0);
                    repaired.set(index, note);
                }
                if (row.size() > owners.size()) aliases.set(row.get(2));
                continue;
            }
            if (row.size() != 14) continue;
            var music = row.stream().map(notes::get).toList();
            if (!matchesNotes(music) || !matchesArpeggio(music)
                    || !provesRails(gray, width, height, bar, music)
                    || !provesRails(raw, width, height, bar, music)) continue;
            var updatedBeams = replaceGroups(beams, row);
            if (updatedBeams == beams) continue;
            beams = updatedBeams;
            if (repaired == null) repaired = new ArrayList<>(notes);
            for (int j = 0; j < 6; j++)
                repaired.set(row.get(j), music.get(j).withTupletRatio(3, 2));
        }
        if (repaired == null) return score;
        var retained = IntStream.range(0, notes.size()).filter(i -> !aliases.get(i)).boxed().toList();
        return score.withBeamSource(beams).withNotes(retained.stream().map(repaired::get).toList(), retained);
    }

    private static List<Integer> dottedTransitionOwners(
            MeasureRegion bar, List<ScoreNoteEvent> notes, List<Integer> row,
            byte[] gray, byte[] raw, int w, int h) {
        if (row.size() != 14 && row.size() != 15) return List.of();
        var owners = new ArrayList<>(row);
        ScoreNoteEvent alias = owners.size() == 15 ? notes.get(owners.remove(2)) : null;
        var music = owners.stream().map(notes::get).toList();
        if (!matchesArpeggio(music)) return List.of();
        var first = music.get(0);
        if (first.augmentationDots() != 1 || music.get(3).augmentationDots() != 1) return List.of();
        for (int i = 0; i < music.size(); i++) {
            var n = music.get(i);
            int depth = i < 6 ? i % 3 == 1 ? 2 : 1 : 2;
            boolean optionalHook = i >= 6 && i % 2 == 1 && n.beamCount() == 3;
            int dots = i == 0 || i == 3 || i >= 6 && i % 2 == 0 ? 1 : 0;
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1 || n.staffIndex() != 0
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.stemDirection() != -1
                    || n.clefBottomDiatonic() != first.clefBottomDiatonic()
                    || n.beamCount() != depth && !optionalHook
                    || n.augmentationDots() > dots || n.unbeamedDurationBeats() != 0
                    || n.crossStaffBeam() || n.tiedFromPrevious() || n.boundaryTies() != 0
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                    || (n.articulations() & NoteOrnament.GRACE) != 0
                    || !((n.tupletDivisor() == 1 && n.tupletNormalNotes() == 1)
                            || (i < 6 && n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2))) return List.of();
        }
        if (alias != null && (alias.kind() != ScoreNoteEvent.Kind.PITCHED || alias.staffCount() != 1
                || alias.staffIndex() != 0 || alias.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || alias.clefBottomDiatonic() != first.clefBottomDiatonic()
                || alias.stemDirection() == -1 || alias.augmentationDots() != 0 || alias.articulations() != 0
                || alias.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || alias.tupletDivisor() != 1 || alias.tiedFromPrevious() || alias.boundaryTies() != 0
                || alias.leadingRestBeats() != 0 || alias.followingRestBeats() != 0
                || alias.crossStaffBeam() || alias.harmonicSemitones() != 0 || alias.openStringTouch() != null))
            return List.of();
        if (!provesDottedTransition(raw, w, h, bar, music, alias)
                || gray != raw && !provesDottedTransition(gray, w, h, bar, music, alias)) return List.of();
        return owners;
    }

    private static boolean provesDottedTransition(byte[] ink, int w, int h, MeasureRegion bar,
            List<ScoreNoteEvent> music, ScoreNoteEvent alias) {
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        if (gap < 10 || gap > 25 || !ClosedDottedSixteenths.hasClosingRule(ink, w, h, bar)) return false;
        var budget = new PrintedBeamShafts.Budget(500_000);
        var axes = new ArrayList<PrintedBeamShafts.Shaft>(music.size());
        var heads = new ArrayList<OwnedTertiaryBeamlets.Head>(music.size());
        for (int i = 0; i < music.size(); i++) {
            var note = music.get(i);
            float x = xAt(bar, note, w), y = note.pageY() * h;
            var found = PrintedBeamShafts.at(ink, w, h, x, y, gap, budget);
            if (found.size() != 1 || found.get(0).direction() != 1 || budget.refused()) return false;
            var axis = found.get(0);
            if (i > 0) {
                float advance = x - heads.get(i - 1).x();
                var prior = axes.get(i - 1);
                if (advance < 1.65f * gap || advance > 4.45f * gap
                        || !(GROUP_STARTS.contains(i)
                                ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, w, h, bar, prior, axis, gap)
                                : WrappedEighthTriplets.matchesBeamRails(ink, w, h, prior, axis, gap, i < 6 ? 1 : 2)))
                    return false;
            }
            axes.add(axis);
            heads.add(new OwnedTertiaryBeamlets.Head(i, note, x, y, gap));
        }
        for (int i : new int[] {0, 3, 6, 8, 10, 12}) {
            var head = heads.get(i);
            float next = Math.min(heads.get(i + 1).x(), head.x() + 2.20f * gap);
            float minimum = (i < 6 ? .98f : .75f) * gap;
            var core = WrappedTripletDotInk.singleAugmentationCore(ink, w, h, head.x(), head.y(), next, gap);
            if (core == null || core.x() - head.x() < minimum)
                core = WrappedTripletDotInk.singleAugmentationCore(
                        ink, w, h, head.x() + (i < 6 ? .24f : -.10f) * gap, head.y(), next, gap);
            if (core == null || core.x() - head.x() < minimum) return false;
        }
        for (int i : new int[] {7, 9, 11, 13}) {
            var head = heads.get(i);
            var prior = heads.get(i - 1);
            float next = i + 1 < heads.size() ? heads.get(i + 1).x() : bar.right() * w;
            if (WrappedTripletDotInk.hasIndependentCore(ink, w, h, head.x(), head.y(), next, gap)
                    || !(OwnedTertiaryBeamlets.provesMonophonicBackwardHook(ink, w, h, head, prior, gap)
                            || OwnedTertiaryBeamlets.provesBackwardHookOnJoinedRails(
                                    ink, w, h, head.x(), head.y(), prior.x(), prior.y(), gap)
                            || OwnedTertiaryBeamlets.provesBackwardHookFromStemTrace(
                                    ink, w, h, head, prior, gap))) return false;
        }
        return hasSecondaryHook(ink, w, axes.get(0), axes.get(1), gap)
                && hasSecondaryHook(ink, w, axes.get(3), axes.get(4), gap)
                && (alias == null
                        || transitionRailOwnsAlias(ink, w, h, bar, alias, axes.get(1), axes.get(2), gap, budget));
    }

    private static boolean hasSecondaryHook(byte[] ink, int width, PrintedBeamShafts.Shaft left,
            PrintedBeamShafts.Shaft right, float gap) {
        int x = (right.left() + right.right()) / 2;
        int start = (left.left() + left.right()) / 2;
        float slope = (right.endY() - left.endY()) / (float) (x - start);
        int near = Math.round(gap * .22f), reach = Math.round(gap * .68f);
        int far = Math.round(gap * 1.37f);
        if (x - far - 2 <= start || x >= width) return false;
        for (int depth = Math.round(gap * .95f); depth <= far; depth++) {
            int connected = 0, clear = 0;
            for (int d = near; d <= reach; d++) {
                int y = Math.round(right.endY() - d * slope) - depth;
                if (y >= 0 && y < ink.length / width && (ink[y * width + x - d] & 255) < 155) connected++;
            }
            for (int d = far - 3; d <= far + 2; d++) {
                int y = Math.round(right.endY() - d * slope) - depth;
                if (y >= 0 && y < ink.length / width && (ink[y * width + x - d] & 255) >= 155) clear++;
            }
            if (connected >= reach - near && clear >= 5) return true;
        }
        return false;
    }

    private static boolean transitionRailOwnsAlias(byte[] ink, int w, int h, MeasureRegion bar,
            ScoreNoteEvent alias, PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap,
            PrintedBeamShafts.Budget budget) {
        float x = xAt(bar, alias, w), y = alias.pageY() * h;
        int ax = (a.left() + a.right()) / 2, bx = (b.left() + b.right()) / 2;
        if (x - ax < gap * 1.1f || bx - x < gap * .60f || bx <= ax) return false;
        float slope = (b.endY() - a.endY()) / (float) (bx - ax);
        float outer = a.endY() + slope * (x - ax);
        if (slope > -.14f || slope < -.35f || outer - y < gap * .14f || outer - y > gap * .60f) return false;
        var claims = PrintedBeamShafts.at(ink, w, h, x, y, gap, budget);
        // An apparent reverse shaft may be the real terminal shaft read from its beam end.
        if (budget.refused() || claims.size() > 1) return false;
        if (!claims.isEmpty()) {
            var claim = claims.get(0);
            if (claim.direction() != -1 || claim.left() > b.right() || claim.right() < b.left()
                    || Math.abs(claim.endY() - b.startY()) > .38f * gap) return false;
        }
        int radius = Math.round(gap * .18f), span = Math.round(gap * .60f);
        int cx = Math.round(x);
        if (cx - span < 0 || cx + span >= w || y - gap < 0 || y + gap >= h) return false;
        for (int offset = -span; offset <= span; offset++) {
            int row = Math.round(y + slope * offset), dark = 0;
            for (int dy = -radius; dy <= radius; dy++)
                if ((ink[(row + dy) * w + cx + offset] & 255) < 155) dark++;
            if (dark < radius) return false;
        }
        return true;
    }

    private static float xAt(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
    }

    private static ScoreBeamSource projectDottedTransition(ScoreBeamSource beam,
            List<Integer> row, List<Integer> owners) {
        var anchors = owners.stream().map(i -> beam.noteOrigins().get(i).orElse(null)).toList();
        if (anchors.contains(null) || new HashSet<>(anchors).size() != owners.size()) return beam;
        int page = anchors.get(0).pageIndex();
        if (anchors.stream().anyMatch(a -> a.pageIndex() != page)) return beam;
        var local = new HashSet<>(anchors);
        for (int index : row) beam.noteOrigins().get(index).ifPresent(local::add);
        var groups = new ArrayList<ScoreBeamSource.Group>();
        var ids = new HashSet<String>();
        var tripletMarks = new ScoreBeamSource.TupletMark[] {
                ScoreBeamSource.TupletMark.UNNUMBERED, ScoreBeamSource.TupletMark.UNNUMBERED};
        for (var group : beam.groups()) {
            var members = group.columns().stream().flatMap(c -> c.members().stream()).toList();
            if (members.stream().anyMatch(local::contains)) {
                if (!local.containsAll(members)) return beam;
                if (group.tupletMark() != null && group.tupletMark().numbered()) {
                    if (members.equals(anchors.subList(0, 3))) tripletMarks[0] = group.tupletMark();
                    else if (members.equals(anchors.subList(3, 6))) tripletMarks[1] = group.tupletMark();
                    else return beam;
                }
            } else {
                groups.add(group);
                ids.add(group.id());
            }
        }
        var detached = new ArrayList<ScoreBeamSource.Column>();
        for (var column : beam.detachedColumns()) {
            if (column.members().stream().noneMatch(local::contains)) detached.add(column);
            else if (!local.containsAll(column.members()) || column.members().size() != 1) return beam;
        }
        for (int group = 0; group < 4; group++) {
            int start = GROUP_STARTS.get(group), end = GROUP_STARTS.get(group + 1);
            var columns = anchors.subList(start, end).stream().map(a -> new ScoreBeamSource.Column(List.of(a))).toList();
            String id = "printed-beam:" + page + ":" + anchors.get(start).noteIndex();
            if (!ids.add(id)) return beam;
            boolean triplet = group < 2;
            var backward = ScoreBeamSource.Direction.BACKWARD;
            var hooks = triplet ? List.of(new ScoreBeamSource.Hook(1, 2, backward))
                    : List.of(new ScoreBeamSource.Hook(1, 3, backward), new ScoreBeamSource.Hook(3, 3, backward));
            groups.add(new ScoreBeamSource.Group(id, columns,
                    triplet ? List.of(1, 1) : List.of(3, 3, 3), hooks,
                    triplet ? tripletMarks[group] : null));
        }
        return new ScoreBeamSource(groups, detached, beam.noteOrigins());
    }


    private static boolean matchesNotes(List<ScoreNoteEvent> row) {
        var first = row.get(0);
        for (int i = 0; i < row.size(); i++) {
            var note = row.get(i);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1 || note.staffIndex() != 0
                    || note.beamCount() != (i < 6 ? 1 : 2) || Math.abs(note.stemDirection()) != 1
                    || first.stemDirection() != note.stemDirection() || note.unbeamedDurationBeats() != 0
                    || note.tupletDivisor() != 1 || note.tupletNormalNotes() != 1 || note.augmentationDots() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0 || note.tiedFromPrevious()
                    || note.boundaryTies() != 0 || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                    || note.crossStaffBeam() || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.harmonicSemitones() != 0 || note.openStringTouch() != null
                    || note.octaveShift() != first.octaveShift()) return false;
        }
        return true;
    }

    private static boolean matchesArpeggio(List<ScoreNoteEvent> row) {
        int[] steps = row.stream().mapToInt(ScoreNoteEvent::staffStep).toArray();
        return steps[0] == steps[6] && steps[1] == steps[5] && steps[2] == steps[4]
                && steps[3] == steps[10] && steps[8] == steps[12] && steps[9] == steps[11]
                && steps[0] < steps[1] && steps[1] < steps[2] && steps[2] < steps[3]
                && steps[6] < steps[7] && steps[7] < steps[8] && steps[8] < steps[9]
                && steps[9] < steps[10] && steps[13] < steps[12];
    }

    private static boolean provesRails(byte[] ink, int w, int h, MeasureRegion bar, List<ScoreNoteEvent> row) {
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        if (gap < 10 || gap > 25 || !ClosedDottedSixteenths.hasClosingRule(ink, w, h, bar)) return false;
        var budget = new PrintedBeamShafts.Budget(400_000);
        PrintedBeamShafts.Shaft previous = null;
        float previousX = 0;
        for (int i = 0; i < row.size(); i++) {
            var note = row.get(i);
            float x = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * w;
            if (i > 0 && (x - previousX < 1.5f * gap || x - previousX > 4.5f * gap)) return false;
            var axes = PrintedBeamShafts.at(ink, w, h, x, note.pageY() * h, gap, budget);
            if (axes.size() != 1 || axes.get(0).direction() != -note.stemDirection()) return false;
            var shaft = axes.get(0);
            if (i > 0 && !(GROUP_STARTS.contains(i)
                    ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, w, h, bar, previous, shaft, gap)
                    : WrappedEighthTriplets.matchesBeamRails(ink, w, h, previous, shaft, gap, i < 6 ? 1 : 2)))
                return false;
            previous = shaft;
            previousX = x;
        }
        return !budget.refused() && bar.right() * w - previousX >= .9f * gap;
    }

    static int unnumberedOpenings(byte[] ink, int width, int height, MeasureRegion bar,
            UnnumberedTripletInk.Rows rows, List<ScoreNoteEvent> notes) {
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var staff = WrappedMeasureBoundaries.closedTripletStaff(ink, width, height, bar);
        if (staff == null) return 0;
        int hidden = 0;
        var budget = new PrintedBeamShafts.Budget(140_000);
        for (int start : new int[] {0, 3}) {
            float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
            int tip = 0;
            var x = new float[3];
            int witnessed = 0;
            for (int i = 0; i < x.length; i++) {
                var note = notes.get(start + i);
                x[i] = left + span * note.positionInMeasure();
                float y = note.pageY() * height;
                var shafts = PrintedBeamShafts.at(ink, width, height, x[i], y, gap, budget);
                if (shafts.size() != 1 || shafts.get(0).direction() != 1 || budget.refused()) break;
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
                tip = Math.max(tip, shafts.get(0).endY());
                witnessed++;
            }
            if (witnessed == 3 && top - 1.5f * gap >= rows.first() && tip + 1.5f * gap <= rows.last()
                    && !UnnumberedTripletInk.bracketBesideBeam(
                            ink, width, height, gap, x[0], x[2], top, bottom, tip, rows, staff, true)
                    && !UnnumberedTripletInk.centeredNumeralCandidate(
                            ink, width, height, x[1], top - 3.4f * gap, top - .4f * gap, gap, rows, staff)
                    && !UnnumberedTripletInk.centeredNumeralCandidate(
                            ink, width, height, x[1], tip + .35f * gap, tip + 3.65f * gap, gap, rows, staff))
                hidden |= 1 << (start / 3);
        }
        return hidden;
    }

    private static ScoreBeamSource replaceGroups(ScoreBeamSource source, List<Integer> owners) {
        return replaceGroups(source, owners,
                List.of(List.of(1, 1), List.of(1, 1), List.of(3, 3, 3), List.of(3, 3, 3)), 2);
    }

    static ScoreBeamSource replaceGroups(ScoreBeamSource source, List<Integer> owners,
            List<List<Integer>> bridges, int initialTuplets) {
        var origins = owners.stream().map(source.noteOrigins()::get).toList();
        if (origins.stream().anyMatch(Optional::isEmpty)) return source;
        var anchors = origins.stream().map(Optional::orElseThrow).toList();
        if (new HashSet<>(anchors).size() != owners.size()
                || anchors.stream().anyMatch(a -> a.pageIndex() != anchors.get(0).pageIndex())
                || source.detachedColumns().stream().anyMatch(c -> c.members().stream().anyMatch(anchors::contains)))
            return source;
        var groups = new ArrayList<>(source.groups());
        int start = 0;
        for (int i = 0; i < bridges.size(); i++) {
            var masks = bridges.get(i);
            int end = start + masks.size() + 1;
            var groupAnchors = anchors.subList(start, end);
            start = end;
            var columns = groupAnchors.stream().map(a -> new ScoreBeamSource.Column(List.of(a))).toList();
            var old = groups.stream().filter(g -> g.columns().stream()
                    .anyMatch(c -> c.members().stream().anyMatch(groupAnchors::contains))).toList();
            if (old.stream().anyMatch(g -> !columns.containsAll(g.columns())
                    || !g.hooks().isEmpty() || g.tupletMark() != null)) return source;
            var first = groupAnchors.get(0);
            String id = old.isEmpty()
                    ? "printed-beam:" + first.pageIndex() + ":" + first.noteIndex() : old.get(0).id();
            int position = old.isEmpty() ? groups.size() : groups.indexOf(old.get(0));
            groups.removeAll(old);
            groups.add(position, new ScoreBeamSource.Group(id, columns,
                    masks, List.of(), i < initialTuplets ? ScoreBeamSource.TupletMark.UNNUMBERED : null));
        }
        return new ScoreBeamSource(groups, source.detachedColumns(), source.noteOrigins());
    }
}
