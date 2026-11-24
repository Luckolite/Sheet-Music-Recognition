// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Recover omitted primary rails in a physically proved row-opening mixed bar. */
final class MixedUpperLedgerBeamSource {
    private MixedUpperLedgerBeamSource() {}

    static ScorePageInterpretation apply(ScorePageInterpretation score, byte[] raw, byte[] normalized,
            int width, int height) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length || score.beamSource().noteOrigins().isEmpty()) return score;
        var notes = score.notes();
        var measures = score.measures();
        var owners = new ArrayList<List<Integer>>(measures.size());
        for (var bar : measures) owners.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) owners.get(notes.get(i).measureIndex()).add(i);
        for (var line : owners)
            line.sort(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
        var source = score.beamSource();
        List<ScoreBeamSource.Group> groups = completeDoubleBeams(score, raw, normalized,
                width, height, owners, source.groups());
        groups = completeStraightQuartetLines(score, raw, normalized, width, height, owners, groups);
        for (int m = 1; m + 1 < measures.size(); m++) {
            var ordered = owners.get(m);
            if (ordered.size() != 14
                    || measures.get(m).top() <= measures.get(m - 1).bottom()) continue;
            if (!mixed(ordered, notes)
                    || !printedRails(raw, width, height, measures, m, notes, ordered)
                    || !printedRails(normalized, width, height, measures, m, notes, ordered)) continue;
            var projected = new ArrayList<>(groups);
            if (complete(source, projected, ordered.subList(0, 3))
                    && complete(source, projected, ordered.subList(3, 6))) groups = projected;
        }
        return groups.equals(source.groups()) ? score : score.withBeamSource(
                new ScoreBeamSource(groups, source.detachedColumns(), source.noteOrigins()));
    }

    /** Recover straight double beams above the staff from the four printed shafts and rails. */
    private static List<ScoreBeamSource.Group> completeDoubleBeams(
            ScorePageInterpretation score, byte[] raw, byte[] gray, int width, int height,
            List<List<Integer>> owners, List<ScoreBeamSource.Group> groups) {
        var notes = score.notes();
        var measures = score.measures();
        for (int m = 0; m < owners.size(); m++) {
            var ordered = owners.get(m);
            if (ordered.size() < 4 || ordered.size() > 24) continue;
            var bar = measures.get(m);
            StaffPitchTrack rawStaff = null;
            StaffPitchTrack grayStaff = null;
            for (int start = 0; start + 4 <= ordered.size(); start++) {
                var targets = ordered.subList(start, start + 4);
                if (targets.stream().anyMatch(i -> !straightUpperSixteenth(notes.get(i)))) continue;
                if (rawStaff == null) {
                    rawStaff = WrappedMeasureBoundaries.verifiedTripletStaff(raw, width, height, bar);
                    grayStaff = WrappedMeasureBoundaries.verifiedTripletStaff(gray, width, height, bar);
                    if (rawStaff == null || grayStaff == null) break;
                }
                if (!isolatedDoubleQuartet(raw, width, height, rawStaff, bar, notes, ordered, start)
                        || !isolatedDoubleQuartet(gray, width, height, grayStaff, bar, notes, ordered, start))
                    continue;
                groups = replacePartialDoubleGroup(score.beamSource(), groups, targets);
                start += 3;
            }
        }
        return groups;
    }

    private static List<ScoreBeamSource.Group> completeStraightQuartetLines(
            ScorePageInterpretation score, byte[] raw, byte[] gray, int width, int height,
            List<List<Integer>> owners, List<ScoreBeamSource.Group> groups) {
        var source = score.beamSource();
        for (int index = 0; index < owners.size(); index++) {
            var ordered = owners.get(index);
            if (ordered.size() != 12 && ordered.size() != 16) continue;
            var oldGroups = groups;
            if (java.util.stream.IntStream.range(0, ordered.size() / 4).allMatch(i ->
                    hasCompleteDoubleQuartet(source, oldGroups, ordered.subList(i * 4, i * 4 + 4)))) continue;
            var line = ordered.stream().map(score.notes()::get).toList();
            var bar = score.measures().get(index);
            if (!provesStraightQuartetLine(raw, width, height, bar, line)
                    || !provesStraightQuartetLine(gray, width, height, bar, line)) continue;
            var projected = groups;
            for (int start = 0; start < ordered.size(); start += 4) {
                var quartet = ordered.subList(start, start + 4);
                projected = replacePartialDoubleGroup(source, projected, quartet);
                if (!hasCompleteDoubleQuartet(source, projected, quartet)) {
                    projected = groups;
                    break;
                }
            }
            groups = projected;
        }
        return groups;
    }

    private static boolean hasCompleteDoubleQuartet(ScoreBeamSource source,
            List<ScoreBeamSource.Group> groups, List<Integer> owners) {
        var origins = owners.stream().map(source.noteOrigins()::get).toList();
        if (origins.stream().anyMatch(java.util.Optional::isEmpty)) return false;
        var columns = origins.stream().map(o -> new ScoreBeamSource.Column(List.of(o.orElseThrow()))).toList();
        return groups.stream().anyMatch(group -> group.columns().equals(columns)
                && group.bridges().equals(List.of(3, 3, 3)) && group.hooks().isEmpty() && group.tupletMark() == null);
    }

    /** Straight double beams must agree on every attachment and each break between quartets. */
    static boolean provesStraightQuartetLine(byte[] ink, int width, int height,
            MeasureRegion bar, List<ScoreNoteEvent> notes) {
        if ((notes.size() != 12 && notes.size() != 16) || notes.stream().anyMatch(n -> !straightSixteenth(n)))
            return false;
        float estimatedGap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (estimatedGap < 12 || estimatedGap > 24) return false;
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar);
        var budget = new PrintedBeamShafts.Budget(260_000);
        PrintedBeamShafts.Shaft previous = null;
        float previousX = 0, previousBottom = 0;
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            float x = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
            float y = note.pageY() * height;
            float gap = track == null ? estimatedGap : track.at(x)[1];
            float bottom = y + note.staffStep() * gap * .5f;
            if (previous != null && (x - previousX < 1.42f * gap || x - previousX > 4.2f * gap
                    || track == null && Math.abs(previousBottom - bottom) > .38f * gap)) return false;
            var shafts = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
            if (budget.refused() || shafts.size() != 1 || shafts.get(0).direction() != -note.stemDirection())
                return false;
            var active = shafts.get(0);
            if (previous != null) {
                if (i % 4 == 0) {
                    if (!WrappedEighthTriplets.matchesBeamRails(ink, width, height, previous, active, gap, 0))
                        return false;
                } else {
                    var rules = track == null ? StaffPitchTrack.linear(width, bottom, gap, 0) : track;
                    if (previous.direction() != active.direction()
                            || !WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                                    ink, width, height, previous, active, gap, rules)) return false;
                }
            }
            previous = active;
            previousX = x;
            previousBottom = bottom;
        }
        return true;
    }

    private static boolean straightSixteenth(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffIndex() == 0 && n.staffCount() == 1
                && n.stemDirection() != 0 && n.beamCount() == 2
                && n.tupletDivisor() == 1 && n.tupletNormalNotes() == 1
                && n.augmentationDots() == 0 && n.unbeamedDurationBeats() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && !n.crossStaffBeam();
    }

    private static boolean straightUpperSixteenth(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1
                && n.staffStep() >= 10 && n.stemDirection() == -1 && n.beamCount() == 2
                && n.tupletDivisor() == 1 && n.tupletNormalNotes() == 1
                && n.augmentationDots() == 0 && n.unbeamedDurationBeats() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && !n.crossStaffBeam();
    }

    static boolean provesUpperSixteenthLine(byte[] ink, int width, int height, StaffPitchTrack track,
            MeasureRegion bar, List<ScoreNoteEvent> notes) {
        if (notes.size() != 16 || notes.stream().anyMatch(n -> !straightUpperSixteenth(n))) return false;
        var ordered = java.util.stream.IntStream.range(0, notes.size()).boxed().toList();
        for (int start = 0; start < notes.size(); start += 4) {
            if (!isolatedDoubleQuartet(ink, width, height, track, bar, notes, ordered, start)) return false;
        }
        return true;
    }

    private static boolean isolatedDoubleQuartet(byte[] ink, int width, int height, StaffPitchTrack track,
            MeasureRegion bar, List<ScoreNoteEvent> notes, List<Integer> ordered, int start) {
        var budget = new PrintedBeamShafts.Budget(120_000);
        PrintedBeamShafts.Shaft previous = null;
        float previousX = 0;
        int first = Math.max(0, start - 1), end = Math.min(ordered.size(), start + 5);
        for (int i = first; i < end; i++) {
            var note = notes.get(ordered.get(i));
            float x = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
            float y = note.pageY() * height;
            var frame = track.at(x);
            float gap = frame[1];
            boolean interior = i >= start && i < start + 4;
            if (gap < 12 || gap > 24 || note.staffCount() != 1
                    || interior && y > frame[0] - 4.65f * gap
                    || previous != null && (x - previousX < gap * 1.45f || x - previousX > gap * 4.2f))
                return false;
            var shafts = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
            if (budget.refused() || shafts.size() != 1 || shafts.get(0).direction() != 1)
                return false;
            var shaft = shafts.get(0);
            int rails = i > start && i < start + 4 ? 2 : 0;
            if (previous != null && !WrappedEighthTriplets.trackedRailCount(
                            ink, width, height, track, previous, shaft, gap, rails)
                    && (rails != 2 || !WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                            ink, width, height, previous, shaft, gap, track))) return false;
            previous = shaft;
            previousX = x;
        }
        return true;
    }

    private static List<ScoreBeamSource.Group> replacePartialDoubleGroup(ScoreBeamSource source,
            List<ScoreBeamSource.Group> groups, List<Integer> owners) {
        var columns = new ArrayList<ScoreBeamSource.Column>(4);
        var anchors = new HashSet<ScoreBeamSource.Anchor>();
        for (int i : owners) {
            var anchor = source.noteOrigins().get(i).orElse(null);
            if (anchor == null || !anchors.add(anchor)
                    || source.noteOrigins().stream().filter(o -> o.isPresent() && o.get().equals(anchor))
                            .count() != 1) return groups;
            columns.add(new ScoreBeamSource.Column(List.of(anchor)));
        }
        if (source.detachedColumns().stream().anyMatch(c -> c.members().stream().anyMatch(anchors::contains)))
            return groups;
        var owned = new ArrayList<ScoreBeamSource.Group>();
        var full = List.of(3, 3, 3);
        for (var group : groups) {
            if (group.columns().stream().noneMatch(c -> c.members().stream().anyMatch(anchors::contains)))
                continue;
            if (group.columns().equals(columns) && group.bridges().equals(full) && group.hooks().isEmpty())
                return groups;
            if (!group.hooks().isEmpty() || group.tupletMark() != null
                    || !columns.containsAll(group.columns())
                    || group.bridges().stream().anyMatch(mask -> mask != 1 && mask != 3)) return groups;
            owned.add(group);
        }
        var origin = columns.get(0).members().get(0);
        String id = "printed-beam:" + origin.pageIndex() + ":" + origin.noteIndex();
        if (groups.stream().anyMatch(g -> g.id().equals(id) && !owned.contains(g))) return groups;
        var result = new ArrayList<>(groups);
        int location = owned.isEmpty() ? result.size() : result.indexOf(owned.get(0));
        result.removeAll(owned);
        result.add(location, new ScoreBeamSource.Group(id, columns, full, List.of()));
        return result;
    }

    private static boolean mixed(List<Integer> owners, List<ScoreNoteEvent> notes) {
        for (int i = 0; i < owners.size(); i++) {
            var n = notes.get(owners.get(i));
            boolean triplet = i < 6;
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1
                    || n.stemDirection() != -1 || n.crossStaffBeam() || n.augmentationDots() != 0
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || n.beamCount() != (triplet ? 1 : 2)
                    || n.tupletDivisor() != (triplet ? 3 : 1)
                    || n.tupletNormalNotes() != (triplet ? 2 : 1)
                    || n.unbeamedDurationBeats() != 0) return false;
        }
        return true;
    }

    static boolean printedRails(byte[] ink, int width, int height, List<MeasureRegion> measures,
            int measure, List<ScoreNoteEvent> notes, List<Integer> owners) {
        var bar = measures.get(measure);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar);
        if (staff == null) return false;
        var budget = new PrintedBeamShafts.Budget(150_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        var xs = new float[14];
        var ys = new float[14];
        for (int i = 0; i < owners.size(); i++) {
            var n = notes.get(owners.get(i));
            float x = left + span * n.positionInMeasure(), y = n.pageY() * height;
            float[] frame = staff.at(x);
            if (!(staff.verifiedAt(x) || i + 1 == owners.size() && staff.verifiedAt(x - gap * 2))
                    || Math.abs(frame[1] - gap) > gap * .14f
                    || y >= frame[0] - frame[1] * 4 - gap * .80f
                    || i > 0 && (x - xs[i - 1] < gap * 1.65f || x - xs[i - 1] > gap * 3.9f)) return false;
            var found = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
            if (found.size() != 1 || found.get(0).direction() != 1 || budget.refused()) return false;
            xs[i] = x;
            ys[i] = y;
            shafts.add(found.get(0));
        }
        for (int i = 0; i + 1 < shafts.size(); i++) {
            int rails = i < 6 ? (i % 3 == 2 ? 0 : 1) : i == 9 ? 0 : 2;
            var leftShaft = shafts.get(i);
            var rightShaft = shafts.get(i + 1);
            if (!WrappedEighthTriplets.trackedRailCount(
                            ink, width, height, staff, leftShaft, rightShaft, gap, rails)
                    && (rails != 2 || !WrappedEighthTriplets.matchesExactlyTwoBeamRails(
                            ink, width, height, leftShaft, rightShaft, gap, staff))) return false;
        }
        var windows = UnnumberedTripletInk.sourceRows(bar, measures, height);
        for (int k : new int[] {0, 3}) {
            float top = Math.min(ys[k], Math.min(ys[k + 1], ys[k + 2]));
            float bottom = Math.max(ys[k], Math.max(ys[k + 1], ys[k + 2]));
            int topTip = Math.min(shafts.get(k).endY(), Math.min(shafts.get(k + 1).endY(), shafts.get(k + 2).endY()));
            int bottomTip = Math.max(shafts.get(k).endY(), Math.max(shafts.get(k + 1).endY(), shafts.get(k + 2).endY()));
            if (!UnnumberedTripletInk.clearFingeringBands(
                    ink, width, height, gap, xs[k], xs[k + 2], top, bottom, topTip, bottomTip,
                    windows, staff)) return false;
        }
        return true;
    }

    private static boolean complete(ScoreBeamSource source, List<ScoreBeamSource.Group> groups,
            List<Integer> owners) {
        var cols = new ArrayList<ScoreBeamSource.Column>(3);
        var targets = new HashSet<ScoreBeamSource.Anchor>();
        for (int i : owners) {
            var anchor = source.noteOrigins().get(i).orElse(null);
            if (anchor == null || !targets.add(anchor)
                    || source.noteOrigins().stream().filter(o -> o.isPresent() && o.get().equals(anchor)).count() != 1)
                return false;
            cols.add(new ScoreBeamSource.Column(List.of(anchor)));
        }
        if (source.detachedColumns().stream().anyMatch(c -> c.members().stream().anyMatch(targets::contains)))
            return false;
        int hit = -1;
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            if (group.columns().stream().noneMatch(c -> c.members().stream().anyMatch(targets::contains))) continue;
            if (hit >= 0 || !group.hooks().isEmpty()) return false;
            if (!(group.columns().equals(cols) && group.bridges().equals(List.of(1, 1)))
                    && !(group.bridges().equals(List.of(1))
                            && (group.columns().equals(cols.subList(0, 2))
                                    || group.columns().equals(cols.subList(1, 3))))) return false;
            hit = i;
        }
        var origin = cols.get(0).members().get(0);
        String id = hit >= 0 ? groups.get(hit).id()
                : "printed-beam:" + origin.pageIndex() + ":" + origin.noteIndex();
        var group = new ScoreBeamSource.Group(id, cols, List.of(1, 1), List.of(),
                ScoreBeamSource.TupletMark.UNNUMBERED);
        if (hit >= 0) groups.set(hit, group);
        else {
            if (groups.stream().anyMatch(g -> g.id().equals(id))) return false;
            groups.add(group);
        }
        return true;
    }
}
