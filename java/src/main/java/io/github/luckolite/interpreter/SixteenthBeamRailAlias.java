// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Thin sloped beam bands can be segmented as a detached head below a complete row of dyads. */
final class SixteenthBeamRailAlias {
    private SixteenthBeamRailAlias() {}

    static List<ScoreNoteEvent> remove(
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures, byte[] gray, byte[] raw,
            int width, int height) {
        return removeRailAliases(notes, measures, gray, raw, width, height, true);
    }

    /** Remove monophonic false heads that borrow the next printed shaft in reverse. */
    static List<ScoreNoteEvent> removeSharedShaftAliases(
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures, byte[] gray, byte[] raw,
            int width, int height) {
        return removeRailAliases(notes, measures, gray, raw, width, height, false);
    }

    private static List<ScoreNoteEvent> removeRailAliases(
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures, byte[] gray, byte[] raw,
            int width, int height, boolean includeDyads) {
        if (gray == null || raw == null || width < 1 || height < 1
                || gray.length != (long) width * height || raw.length != gray.length) return notes;
        Map<Integer, List<ScoreNoteEvent>> rows = new HashMap<>();
        for (var note : notes)
            rows.computeIfAbsent(note.measureIndex(), ignored -> new ArrayList<>()).add(note);
        var removed = new HashSet<ScoreNoteEvent>();
        for (var entry : rows.entrySet()) {
            int measure = entry.getKey();
            var row = entry.getValue();
            if (measure < 0 || measure >= measures.size()) continue;
            var bar = measures.get(measure);
            ScoreNoteEvent alias;
            if (row.size() == 15) {
                alias = sharedShaftAlias(row, bar, gray, raw, width, height);
            } else if (includeDyads && row.size() == 25) {
                alias = ownedAlias(row, bar, gray, raw, width, height);
            } else {
                continue;
            }
            if (alias != null) removed.add(alias);
        }
        return removed.isEmpty() ? notes : notes.stream().filter(note -> !removed.contains(note)).toList();
    }

    private static ScoreNoteEvent sharedShaftAlias(List<ScoreNoteEvent> row, MeasureRegion bar,
            byte[] gray, byte[] raw, int width, int height) {
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return null;
        row.sort(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure));
        ScoreNoteEvent candidate = null;
        for (var note : row) {
            if (!regular(note) || note.harmonicSemitones() != 0 || note.openStringTouch() != null
                    || note.boundaryTies() != 0 || note.tiedFromPrevious()) return null;
            if (note.stemDirection() == -1 && note.beamCount() >= 1 && note.beamCount() <= 2
                    && note.unbeamedDurationBeats() == 0) continue;
            if (candidate != null || note.stemDirection() != 1
                    || note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || note.articulations() != 0
                    || !((note.beamCount() == 0 && note.unbeamedDurationBeats() == 1)
                            || (note.beamCount() == 1 && note.unbeamedDurationBeats() == 0))) return null;
            candidate = note;
        }
        if (candidate == null) return null;
        int index = row.indexOf(candidate);
        if (index == 0 || index + 1 == row.size()) return null;
        var before = row.get(index - 1);
        var after = row.get(index + 1);
        if (before.beamCount() != 2 || after.beamCount() != 2) return null;
        float x = left + span * candidate.positionInMeasure(), y = candidate.pageY() * height;
        float lx = left + span * before.positionInMeasure(), ly = before.pageY() * height;
        float rx = left + span * after.positionInMeasure(), ry = after.pageY() * height;
        if (x - lx < .75f * gap || x - lx > 1.6f * gap
                || rx - x < .75f * gap || rx - x > 1.6f * gap) return null;
        var track = StaffPitchTrack.linear(width, bar.bottom() * height - 2.1f * gap, gap, 0);
        return sharedShaftRailInk(gray, width, height, x, y, lx, ly, rx, ry, gap, track)
                && sharedShaftRailInk(raw, width, height, x, y, lx, ly, rx, ry, gap, track) ? candidate : null;
    }

    private static boolean sharedShaftRailInk(byte[] ink, int width, int height,
            float x, float y, float lx, float ly, float rx, float ry, float gap, StaffPitchTrack track) {
        var budget = new PrintedBeamShafts.Budget(120_000);
        var before = PrintedBeamShafts.at(ink, width, height, lx, ly, gap, budget);
        var after = PrintedBeamShafts.at(ink, width, height, rx, ry, gap, budget);
        var aliases = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        if (budget.refused() || before.size() != 1 || after.size() > 1 || aliases.size() != 1) return false;
        var a = before.get(0);
        var b = after.isEmpty() ? FadedTripletDownshaft.measure(ink, width, height, rx, ry, gap) : after.get(0);
        var alias = aliases.get(0);
        if (b == null || a.direction() != 1 || b.direction() != 1 || alias.direction() != -1
                || alias.left() < b.left() || alias.right() > b.right()
                || Math.abs(ry - alias.endY()) > gap * .46f
                || !WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink, width, height, a, b, gap, track))
            return false;
        float start = (a.left() + a.right()) * .5f, stop = (b.left() + b.right()) * .5f;
        if (x <= start || x >= stop) return false;
        float slope = (b.endY() - a.endY()) / (stop - start);
        float depth = (a.endY() + slope * (x - start) - y) / gap;
        int from = Math.max(a.right() + 2, Math.round(x - .85f * gap));
        int to = Math.min(b.left() - 2, Math.round(x + .70f * gap));
        int cx = Math.round(x), cy = Math.round(y), radius = Math.round(.44f * gap);
        if (depth < .55f || depth > 1.12f || to - from < gap * .90f
                || from < 0 || to >= width || cx < from || cx > to
                || cy - radius < 3 || cy + radius >= height - 3) return false;
        int rows = 0;
        for (int off = -radius; off <= radius; off++) {
            int sample = cy + off;
            if ((ink[sample * width + cx] & 255) >= 165) continue;
            rows++;
            int hits = 0;
            for (int col = from; col <= to; col++) {
                int yy = sample + Math.round(slope * (col - cx));
                if (yy >= 0 && yy < height && (ink[yy * width + col] & 255) < 190) hits++;
            }
            if (hits < (to - from + 1) * .80f) return false;
        }
        return rows >= Math.round(gap * .45f)
                && paperGutter(ink, width, height, start, a.endY(), slope, from, to, gap);
    }

    private static ScoreNoteEvent ownedAlias(
            List<ScoreNoteEvent> notes,
            MeasureRegion region,
            byte[] gray,
            byte[] raw,
            int width,
            int height) {
        float left = region.left() * width, span = (region.right() - region.left()) * width;
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        if (gap < 9 || gap > 25 || span < gap * 25 || span > gap * 37) return null;
        notes.sort(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure));
        var isolated = notes.stream().filter(SixteenthBeamRailAlias::possibleAlias).toList();
        if (isolated.size() != 1) return null;
        var alias = isolated.get(0);
        int index = notes.indexOf(alias);
        if (index < 2 || index > 22 || index % 2 != 0) return null;
        var dyads = new ArrayList<>(notes);
        dyads.remove(index);
        float col = -1;
        for (int i = 0; i < dyads.size(); i += 2) {
            var a = dyads.get(i);
            var b = dyads.get(i + 1);
            float x = a.positionInMeasure() * span;
            if (!member(a)
                    || !member(b)
                    || Math.abs(a.staffStep() - b.staffStep()) != 5
                    || b.positionInMeasure() * span - x > gap * .23f
                    || (i > 0 && (x - col < gap * 1.65f || x - col > gap * 3.9f))) return null;
            col = x;
        }
        var before = lower(dyads.get(index - 2), dyads.get(index - 1));
        var after = lower(dyads.get(index), dyads.get(index + 1));
        float ax = left + alias.positionInMeasure() * span, ay = alias.pageY() * height;
        float bx = left + before.positionInMeasure() * span, by = before.pageY() * height;
        float cx = left + after.positionInMeasure() * span, cy = after.pageY() * height;
        if (ax - bx < gap * .95f
                || cx - ax < gap * .95f
                || ax - bx > gap * 2.2f
                || cx - ax > gap * 2.2f
                || ay - Math.max(by, cy) < gap * 1.5f
                || ay - Math.min(by, cy) > gap * 3.2f) return null;

        return railOwns(gray, width, height, ax, ay, bx, by, cx, cy, gap)
                        && railOwns(raw, width, height, ax, ay, bx, by, cx, cy, gap)
                ? alias
                : null;
    }

    static List<ScoreNoteEvent> removeClosedOctaveAliases(
            List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures,
            List<ScoreMeterChange> meters,
            ScoreMeterChange inheritedMeter,
            byte[] gray,
            byte[] raw,
            int width,
            int height) {
        if (gray == null
                || raw == null
                || width < 1
                || height < 1
                || gray.length != (long) width * height
                || raw.length != gray.length) return notes;
        var rows = new HashMap<Integer, List<ScoreNoteEvent>>();
        for (var note : notes)
            rows.computeIfAbsent(note.measureIndex(), unused -> new ArrayList<>()).add(note);
        var removed = new HashSet<ScoreNoteEvent>();
        for (var entry : rows.entrySet()) {
            var row = entry.getValue();
            int index = entry.getKey();
            if (index < 0 || index >= measures.size()
                    || row.size() < 25 || row.size() > 34) continue;
            int pairs = closedOctavePairCount(meters, inheritedMeter, index);
            if (pairs > 0 && row.size() > pairs * 2 && row.size() <= pairs * 2 + 2) {
                var bar = measures.get(index);
                var aliases = closedOctaveAliases(row, bar, pairs, gray, raw, width, height);
                removed.addAll(aliases);
                if (aliases.isEmpty() && pairs == 16 && row.size() == 33) {
                    var atShaft = closingOctaveBeamTipAlias(row, bar, gray, raw, width, height);
                    if (atShaft != null) removed.add(atShaft);
                }
            }
        }
        return removed.isEmpty()
                ? notes
                : notes.stream().filter(note -> !removed.contains(note)).toList();
    }

    /**
     * A common-time row of fourteen sixteenth-note thirds and one eighth closes
     * without an extra head when the two enclosing shafts own its rail ink.
     */
    static List<ScoreNoteEvent> removeClosingThirdAliases(
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures,
            List<ScoreMeterChange> meters, ScoreMeterChange inheritedMeter,
            byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || width < 1 || height < 1
                || gray.length != (long) width * height || raw.length != gray.length) return notes;
        var rows = new HashMap<Integer, List<ScoreNoteEvent>>();
        for (var note : notes)
            rows.computeIfAbsent(note.measureIndex(), unused -> new ArrayList<>()).add(note);
        var removed = new HashSet<ScoreNoteEvent>();
        for (var entry : rows.entrySet()) {
            int measure = entry.getKey();
            if (entry.getValue().size() != 31 || measure < 0 || measure >= measures.size()
                    || closedOctavePairCount(meters, inheritedMeter, measure) != 16) continue;
            var alias = closingThirdAlias(entry.getValue(), measures.get(measure), gray, raw, width, height);
            if (alias != null) removed.add(alias);
        }
        return removed.isEmpty()
                ? notes : notes.stream().filter(note -> !removed.contains(note)).toList();
    }

    private static ScoreNoteEvent closingThirdAlias(
            List<ScoreNoteEvent> row, MeasureRegion region, byte[] gray, byte[] raw, int width, int height) {
        float span = (region.right() - region.left()) * width;
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        if (region.cadenceQuarterBeats() != 0 || gap < 11 || gap > 23
                || span < 30 * gap || span > 46 * gap) return null;
        row.sort(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure));
        var suspicious = row.stream().filter(n -> possibleOctaveAlias(n)
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.stemDirection() == 0 && n.beamCount() == 0
                && n.articulations() == 0).toList();
        if (suspicious.size() != 1) return null;
        var alias = suspicious.get(0);
        int at = row.indexOf(alias);
        if (at < 2 || at > 26 || at % 2 != 0) return null;
        row.remove(at);
        float previous = -1;
        for (int i = 0; i < row.size(); i += 2) {
            var a = row.get(i);
            var b = row.get(i + 1);
            int beams = i == row.size() - 2 ? 1 : 2;
            float x = a.positionInMeasure() * span;
            if (!closingThirdMember(a, beams) || !closingThirdMember(b, beams)
                    || a.octaveShift() != alias.octaveShift() || b.octaveShift() != alias.octaveShift()
                    || Math.abs(a.staffStep() - b.staffStep()) != 2
                    || Math.abs(Math.abs(a.pageY() - b.pageY()) * height - gap) > .28f * gap
                    || b.positionInMeasure() * span - x > .22f * gap
                    || i > 0 && (x - previous < 1.7f * gap || x - previous > 3.9f * gap)) return null;
            previous = x;
        }
        var before = lower(row.get(at - 2), row.get(at - 1));
        var after = lower(row.get(at), row.get(at + 1));
        float left = region.left() * width;
        float ax = left + alias.positionInMeasure() * span, ay = alias.pageY() * height;
        float bx = left + before.positionInMeasure() * span, by = before.pageY() * height;
        float cx = left + after.positionInMeasure() * span, cy = after.pageY() * height;
        for (var plane : List.of(gray, raw)) {
            var budget = new PrintedBeamShafts.Budget(40_000);
            if (!PrintedBeamShafts.at(plane, width, height, ax, ay, gap, budget).isEmpty()
                    || budget.refused()
                    || !octaveRailOwns(plane, width, height, ax, ay, bx, by, cx, cy, gap)) return null;
        }
        return alias;
    }

    private static boolean closingThirdMember(ScoreNoteEvent n, int beams) {
        return regular(n) && n.stemDirection() == -1 && n.beamCount() == beams
                && n.unbeamedDurationBeats() == 0 && n.harmonicSemitones() == 0
                && n.openStringTouch() == null;
    }

    /** An upward closing rail may borrow the preceding shaft as a contradictory downstem. */
    static List<ScoreNoteEvent> removeBeforeHalfAliases(
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures,
            List<ScoreMeterChange> meters, ScoreMeterChange inherited,
            byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height || raw.length != gray.length)
            return notes;
        var rows = new HashMap<Integer, List<ScoreNoteEvent>>();
        for (var n : notes) rows.computeIfAbsent(n.measureIndex(), unused -> new ArrayList<>()).add(n);
        var removed = new HashSet<ScoreNoteEvent>();
        for (var entry : rows.entrySet()) {
            int bar = entry.getKey();
            var row = entry.getValue();
            if (bar < 0 || bar >= measures.size() || row.size() != 19
                    || closedOctavePairCount(meters, inherited, bar) != 16) continue;
            var region = measures.get(bar);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            float left = region.left() * width, span = (region.right() - region.left()) * width;
            if (region.cadenceQuarterBeats() != 0 || gap < 10 || gap > 25
                    || span < gap * 19 || span > gap * 29) continue;
            row.sort(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure));
            var alias = row.get(14);
            if (!regular(alias) || alias.beamCount() != 0 || alias.stemDirection() != -1
                    || alias.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_QUARTER
                    || alias.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || alias.articulations() != 0 || alias.tiedFromPrevious() || alias.boundaryTies() != 0)
                continue;
            row.remove(14);
            if (!plainClosingThirds(row, gap, span)) continue;
            if (upwardRailBeforeHalf(gray, width, height, region, row, alias, left, span, gap)
                    && upwardRailBeforeHalf(raw, width, height, region, row, alias, left, span, gap))
                removed.add(alias);
        }
        return removed.isEmpty() ? notes : notes.stream().filter(n -> !removed.contains(n)).toList();
    }

    private static boolean plainClosingThirds(List<ScoreNoteEvent> row, float gap, float span) {
        float previous = -1;
        for (int i = 0; i < row.size(); i += 2) {
            var a = row.get(i);
            var b = row.get(i + 1);
            float position = a.positionInMeasure() * span;
            if (i > 0 && (position - previous < gap * 1.65f || position - previous > gap * 3.2f)
                    || (b.positionInMeasure() - a.positionInMeasure()) * span > gap * .22f
                    || Math.abs(a.staffStep() - b.staffStep()) != 2) return false;
            previous = position;
            for (var n : List.of(a, b)) {
                if (!regular(n) || n.tiedFromPrevious() || n.boundaryTies() != 0
                        || n.articulations() != 0 || n.harmonicSemitones() != 0 || n.openStringTouch() != null)
                    return false;
                if (i == 16) {
                    if (n.beamCount() != 0 || n.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_HALF
                            || n.stemDirection() != 1) return false;
                } else if (n.beamCount() != 2 || n.unbeamedDurationBeats() != 0
                        || n.stemDirection() != (i < 8 ? -1 : 1)) return false;
            }
        }
        return true;
    }

    private static boolean upwardRailBeforeHalf(
            byte[] ink, int width, int height, MeasureRegion region, List<ScoreNoteEvent> row,
            ScoreNoteEvent alias, float left, float span, float gap) {
        var before = row.get(12).pageY() < row.get(13).pageY() ? row.get(12) : row.get(13);
        var after = row.get(14).pageY() < row.get(15).pageY() ? row.get(14) : row.get(15);
        float x = left + alias.positionInMeasure() * span, y = alias.pageY() * height;
        var budget = new PrintedBeamShafts.Budget(70_000);
        var a = PrintedBeamShafts.at(ink, width, height, left + before.positionInMeasure() * span,
                before.pageY() * height, gap, budget);
        var b = PrintedBeamShafts.at(ink, width, height, left + after.positionInMeasure() * span,
                after.pageY() * height, gap, budget);
        var borrowed = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        if (a.size() != 1 || b.size() != 1 || borrowed.size() != 1) return false;
        var first = a.get(0);
        var last = b.get(0);
        var reused = borrowed.get(0);
        float from = (first.left() + first.right()) * .5f, to = (last.left() + last.right()) * .5f;
        if (first.direction() != -1 || last.direction() != -1 || reused.direction() != 1
                || reused.left() < first.left() - 2 || reused.right() > first.right() + 1
                || x - from < gap * .45f || x - from > gap * .80f || to - x < gap * 1.35f
                || to - from > gap * 3.2f || Math.abs(last.endY() - first.endY()) > gap * .55f) return false;
        float outer = first.endY() + (last.endY() - first.endY()) * ((x - from) / (to - from));
        if (y - outer < gap * .49f || y - outer > gap * .82f
                || !WrappedEighthTriplets.matchesBeamRails(ink, width, height, first, last, gap, 2))
            return false;
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, region, true);
        if (track == null) return false;
        var bottom = track.at(to);
        var staff = new PlayingTechniqueDetector.Staff(bottom[0] - 4 * bottom[1], bottom[0], bottom[1], 0, 1);
        int edge = PrintedCadenceDurations.boundaryColumn(
                ink, width, staff, region.right() * width + gap * .5f, false, false);
        if (edge < 0) return false;
        for (var half : row.subList(16, 18)) {
            float headX = left + half.positionInMeasure() * span;
            if (edge - headX < gap * 3.4f || edge - headX > gap * 5f
                    || !PrintedCadenceDurations.hollowPocket(
                            ink, width, height, headX, half.pageY() * height, gap)) return false;
        }
        return true;
    }

    private static int closedOctavePairCount(
            List<ScoreMeterChange> meters, ScoreMeterChange inherited, int measure) {
        var used = inherited;
        for (var meter : meters) {
            if (meter.measureIndex() > measure) break;
            used = meter;
        }
        if (used == null || used.denominator() != 4) return 0;
        return switch (used.numerator()) {
            case 3 -> 12;
            case 4 -> 16;
            default -> 0;
        };
    }

    /** The only unpaired readings in a complete octave row may be ink shared with its rails. */
    private static List<ScoreNoteEvent> closedOctaveAliases(
            List<ScoreNoteEvent> row,
            MeasureRegion region,
            int pairs,
            byte[] gray,
            byte[] raw,
            int width,
            int height) {
        float left = region.left() * width, span = (region.right() - region.left()) * width;
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        if (gap < 11 || gap > 23 || span < 30 * gap || span > 53 * gap) return List.of();
        var suspicious = row.stream().filter(SixteenthBeamRailAlias::possibleOctaveAlias).toList();
        if (row.size() - suspicious.size() != pairs * 2) return List.of();
        var notes =
                row.stream()
                        .filter(n -> !suspicious.contains(n))
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        var lower = new ArrayList<ScoreNoteEvent>(pairs);
        for (int i = 0; i < notes.size(); i += 2) {
            var a = notes.get(i);
            var b = notes.get(i + 1);
            if (!ordinaryOctaveMember(a)
                    || !ordinaryOctaveMember(b)
                    || a.stemDirection() != b.stemDirection()
                    || Math.abs(a.staffStep() - b.staffStep()) != 7
                    || (b.positionInMeasure() - a.positionInMeasure()) * span > .24f * gap
                    || Math.abs(a.pageY() - b.pageY()) * height < 3.1f * gap
                    || Math.abs(a.pageY() - b.pageY()) * height > 3.9f * gap) return List.of();
            var bottom = lower(a, b);
            if (!lower.isEmpty()) {
                float advance =
                        (bottom.positionInMeasure()
                                        - lower.get(lower.size() - 1).positionInMeasure())
                                * span;
                if (advance < 1.55f * gap || advance > 4.1f * gap) return List.of();
            }
            lower.add(bottom);
        }
        for (var alias : suspicious) {
            boolean proved = false;
            for (int i = 1; i < lower.size(); i++) {
                var before = lower.get(i - 1);
                var after = lower.get(i);
                if (alias.positionInMeasure() <= before.positionInMeasure()
                        || alias.positionInMeasure() >= after.positionInMeasure()) continue;
                if (before.stemDirection() != -1
                        || after.stemDirection() != -1
                        || before.octaveShift() != alias.octaveShift()
                        || after.octaveShift() != alias.octaveShift()) break;
                float ax = left + alias.positionInMeasure() * span, ay = alias.pageY() * height;
                float bx = left + before.positionInMeasure() * span, by = before.pageY() * height;
                float cx = left + after.positionInMeasure() * span, cy = after.pageY() * height;
                proved =
                        octaveRailOwns(gray, width, height, ax, ay, bx, by, cx, cy, gap)
                                && octaveRailOwns(raw, width, height, ax, ay, bx, by, cx, cy, gap);
                break;
            }
            if (!proved) return List.of();
        }
        return suspicious;
    }

    /** A descending quartet can leave a detached reading just before a shared downshaft. */
    private static ScoreNoteEvent closingOctaveBeamTipAlias(
            List<ScoreNoteEvent> row, MeasureRegion bar, byte[] gray, byte[] raw, int width, int height) {
        float span = (bar.right() - bar.left()) * width;
        float left = bar.left() * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (bar.cadenceQuarterBeats() != 0 || gap < 12 || gap > 23
                || span < 34 * gap || span > 44 * gap) return null;
        var suspects = row.stream().filter(note -> possibleOctaveAlias(note)
                && note.stemDirection() == 1 && note.beamCount() == 0
                && note.articulations() == 0).toList();
        if (suspects.size() != 1) return null;
        var alias = suspects.get(0);
        var heads = row.stream().filter(note -> note != alias)
                .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
        var lower = new ArrayList<ScoreNoteEvent>(16);
        int unconfirmedSteps = 0, overreadRails = 0;
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            int beams = a.beamCount();
            int steps = Math.abs(a.staffStep() - b.staffStep());
            if (!regular(a) || !regular(b) || beams != b.beamCount()
                    || !(beams == 2 || i < 24 && beams == 3)
                    || a.stemDirection() != -1 || b.stemDirection() != -1
                    || a.harmonicSemitones() != 0 || b.harmonicSemitones() != 0
                    || a.openStringTouch() != null || b.openStringTouch() != null
                    || a.unbeamedDurationBeats() != 0 || b.unbeamedDurationBeats() != 0
                    || a.octaveShift() != b.octaveShift()
                    || Math.abs(a.pageY() - b.pageY()) * height < 3.2f * gap
                    || Math.abs(a.pageY() - b.pageY()) * height > 3.85f * gap
                    || (b.positionInMeasure() - a.positionInMeasure()) * span > .20f * gap) return null;
            if (steps != 7 && (i >= 24 || steps != 6 || a.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_SHARP
                    || b.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_SHARP || ++unconfirmedSteps > 1))
                return null;
            if (beams == 3 && ++overreadRails > 1) return null;
            var current = lower(a, b);
            if (i > 0) {
                float advance = (current.positionInMeasure() - lower.get(lower.size() - 1).positionInMeasure()) * span;
                if (advance < 1.7f * gap || advance > 3.7f * gap) return null;
            }
            lower.add(current);
        }
        var before = lower.get(12);
        var after = lower.get(13);
        if (alias.positionInMeasure() <= before.positionInMeasure()
                || alias.positionInMeasure() >= after.positionInMeasure()
                || lower.subList(12, 16).stream().anyMatch(n -> n.octaveShift() != alias.octaveShift()))
            return null;
        float x = left + alias.positionInMeasure() * span, y = alias.pageY() * height;
        for (var plane : List.of(gray, raw)) {
            if (!doubleRailReachesNextShaft(plane, width, height, left, span, lower.subList(12, 16), x, y, gap))
                return null;
        }
        return alias;
    }

    private static boolean doubleRailReachesNextShaft(byte[] ink, int width, int height, float left, float span,
            List<ScoreNoteEvent> ending, float x, float y, float gap) {
        var budget = new PrintedBeamShafts.Budget(100_000);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>();
        for (var member : ending) {
            var found = PrintedBeamShafts.at(ink, width, height,
                    left + member.positionInMeasure() * span, member.pageY() * height, gap, budget);
            if (found.size() != 1 || found.get(0).direction() != 1) return false;
            shafts.add(found.get(0));
        }
        if (!PrintedBeamShafts.at(ink, width, height, x, y, gap, budget).isEmpty() || budget.refused())
            return false;
        for (int i = 1; i < shafts.size(); i++)
            if (!WrappedEighthTriplets.matchesBeamRails(
                    ink, width, height, shafts.get(i - 1), shafts.get(i), gap, 2)) return false;
        var a = shafts.get(0);
        var b = shafts.get(1);
        float ax = (a.left() + a.right()) * .5f, bx = (b.left() + b.right()) * .5f;
        float slope = (b.endY() - a.endY()) / (bx - ax);
        float depth = b.endY() - slope * (bx - x) - y;
        if (x < b.left() - .25f * gap || x > b.left() || slope < .10f || slope > .31f
                || depth < .52f * gap || depth > .75f * gap
                || y - ending.get(1).pageY() * height < 2.55f * gap) return false;
        int sample = Math.round(.50f * gap);
        int dark = 0, paper = 0;
        for (int i = 2; i <= sample; i++) {
            int col = Math.round(x) - i;
            int ribbon = Math.round(y - i * slope);
            if ((ink[ribbon * width + col] & 255) < 162) dark++;
            int below = Math.round(b.endY() - slope * (bx - col) + .27f * gap);
            if ((ink[below * width + col] & 255) > 205) paper++;
        }
        return dark >= (sample - 1) * .84f && paper >= (sample - 1) * .84f;
    }

    private static boolean ordinaryOctaveMember(ScoreNoteEvent note) {
        return regular(note)
                && note.stemDirection() != 0
                && note.beamCount() == 2
                && note.unbeamedDurationBeats() == 0;
    }

    private static boolean possibleOctaveAlias(ScoreNoteEvent n) {
        return regular(n)
                && !n.tiedFromPrevious()
                && n.boundaryTies() == 0
                && n.harmonicSemitones() == 0
                && n.openStringTouch() == null
                && (n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                        || n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL)
                && ((n.stemDirection() >= 0 && n.beamCount() == 1 && n.unbeamedDurationBeats() == 0)
                        || (n.stemDirection() == 0 && n.beamCount() == 3 && n.unbeamedDurationBeats() == 0)
                        || (n.stemDirection() >= 0
                                && n.beamCount() == 0
                                && n.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_QUARTER));
    }

    private static boolean octaveRailOwns(
            byte[] ink,
            int width,
            int height,
            float ax,
            float ay,
            float bx,
            float by,
            float cx,
            float cy,
            float gap) {
        float rise = (ay - Math.min(by, cy)) / gap;
        if (ax - bx < .52f * gap || cx - ax < .74f * gap
                || ay - Math.max(by, cy) < 1.7f * gap || rise > 4.48f) return false;
        var budget = new PrintedBeamShafts.Budget(40_000);
        var own = PrintedBeamShafts.at(ink, width, height, ax, ay, gap, budget);
        var a = PrintedBeamShafts.at(ink, width, height, bx, by, gap, budget);
        var b = PrintedBeamShafts.at(ink, width, height, cx, cy, gap, budget);
        if (budget.refused() || a.size() != 1 || b.size() != 1 || own.size() > 1) return false;
        var from = a.get(0);
        var to = b.get(0);
        if (from.direction() != 1 || to.direction() != 1) return false;
        boolean narrowOwnedColumn = false;
        if (own.isEmpty()) {
            if (ax - bx < .74f * gap) return false;
        } else {
            var borrowed = own.get(0);
            // The reverse trace must be enclosed by the genuine downstroke and end at
            // its printed upper octave. A separate upward shaft is a real independent witness.
            if (borrowed.direction() != -1
                    || borrowed.left() < to.left()
                    || borrowed.right() > to.right()
                    || Math.abs(borrowed.endY() - (cy - 3.5f * gap)) > .62f * gap) return false;
            narrowOwnedColumn = borrowed.left() == borrowed.right() && to.left() < to.right()
                    && Math.abs(borrowed.endY() - (cy - 3.5f * gap)) <= .34f * gap;
        }
        if (rise > 4.4f && !narrowOwnedColumn) return false;
        float x0 = (from.left() + from.right()) * .5f, x1 = (to.left() + to.right()) * .5f;
        if (ax <= x0 || ax >= x1 || x1 - x0 < 1.75f * gap || x1 - x0 > 3.9f * gap) return false;
        float slope = (to.endY() - from.endY()) / (x1 - x0);
        if (own.isEmpty() && slope < -.35f) {
            var recovered = clippedDownstrokeEnd(ink, width, height, from, to, gap);
            if (recovered != null) {
                to = recovered;
                slope = (to.endY() - from.endY()) / (x1 - x0);
            }
        }
        float depth = (from.endY() + slope * (ax - x0) - ay) / gap;
        if (Math.abs(slope) > .35f
                || depth < (narrowOwnedColumn ? .27f : .29f)
                || depth > .89f
                || !WrappedEighthTriplets.matchesBeamRails(ink, width, height, from, to, gap, 2)
                || !PrintedTupletBeamOwner.connectedHeads(
                        ink, width, height, bx, by, cx, cy, gap, -1)) return false;
        // A candidate completely contained by the rails leaves the same nearby paper
        // gutter as the independently owned shafts; crossing staff rules may occupy it.
        int fromX = Math.max(from.right() + 3, Math.round(ax - .42f * gap));
        int throughX = Math.min(to.left() - 2, Math.round(ax + .42f * gap));
        if (throughX - fromX < .33f * gap) return false;
        return paperGutter(ink, width, height, x0, from.endY(), slope, fromX, throughX, gap);
    }

    private static boolean paperGutter(byte[] ink, int width, int height, float shaftX, int shaftTip,
            float slope, int fromX, int throughX, float gap) {
        int paperColumns = 0;
        for (int x = fromX; x <= throughX; x++) {
            float y = shaftTip + slope * (x - shaftX);
            int start = Math.round(y + .09f * gap), end = Math.round(y + .52f * gap);
            if (start < 0 || end >= height) return false;
            for (int at = start; at <= end; at++) {
                if ((ink[at * width + x] & 255) > 205) {
                    paperColumns++;
                    break;
                }
            }
        }
        return paperColumns >= (throughX - fromX + 1) * .90f;
    }

    /** Follow a faint continuation through a staff crossing to the attached beam band. */
    private static PrintedBeamShafts.Shaft clippedDownstrokeEnd(
            byte[] ink, int width, int height, PrintedBeamShafts.Shaft left,
            PrintedBeamShafts.Shaft shortRight, float gap) {
        int first = shortRight.endY() + 1;
        int last = shortRight.endY() + Math.round(1.8f * gap);
        if (first < 0 || last + 1 >= height) return null;
        int endpoint = -1;
        for (int y = first; y <= last; y++) {
            int row = y * width;
            boolean continuation = false;
            for (int x = shortRight.left(); x <= shortRight.right(); x++) {
                if ((ink[row + x] & 255) < 208) {
                    continuation = true;
                    break;
                }
            }
            if (!continuation) {
                endpoint = y - 1;
                break;
            }
        }
        if (endpoint < first + .85f * gap || endpoint < left.endY()
                || endpoint > left.endY() + .55f * gap) return null;
        return new PrintedBeamShafts.Shaft(shortRight.left(), shortRight.right(),
                shortRight.startY(), endpoint, shortRight.direction(), shortRight.columns());
    }

    private static boolean regular(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffIndex() == 0
                && n.staffCount() == 1
                && n.tupletDivisor() == 1
                && n.augmentationDots() == 0
                && !n.crossStaffBeam()
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean possibleAlias(ScoreNoteEvent n) {
        return regular(n)
                && !n.tiedFromPrevious()
                && n.boundaryTies() == 0
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.octaveShift() == 0
                && n.harmonicSemitones() == 0
                && n.openStringTouch() == null
                && ((n.stemDirection() == 0 && n.beamCount() == 2 && n.unbeamedDurationBeats() == 0)
                        || (n.stemDirection() == 1
                                && n.beamCount() == 0
                                && n.unbeamedDurationBeats() == ScoreNoteEvent.DURATION_QUARTER));
    }

    private static boolean member(ScoreNoteEvent n) {
        return regular(n)
                && n.stemDirection() == -1
                && n.beamCount() >= 2
                && n.beamCount() <= 3
                && n.unbeamedDurationBeats() == 0;
    }

    private static ScoreNoteEvent lower(ScoreNoteEvent a, ScoreNoteEvent b) {
        return a.pageY() > b.pageY() ? a : b;
    }

    private static boolean railOwns(
            byte[] gray,
            int width,
            int height,
            float ax,
            float ay,
            float bx,
            float by,
            float cx,
            float cy,
            float gap) {
        var budget = new PrintedBeamShafts.Budget(30_000);
        if (!PrintedBeamShafts.at(gray, width, height, ax, ay, gap, budget).isEmpty()) return false;
        var a = PrintedBeamShafts.at(gray, width, height, bx, by, gap, budget);
        var b = PrintedBeamShafts.at(gray, width, height, cx, cy, gap, budget);
        if (a.size() != 1
                || b.size() != 1
                || a.get(0).direction() != 1
                || b.get(0).direction() != 1) return false;
        var from = a.get(0);
        var to = b.get(0);
        float startX = (from.left() + from.right()) * .5f, endX = (to.left() + to.right()) * .5f;
        if (endX - startX < gap * 2
                || ax <= startX
                || ax >= endX
                || Math.abs(to.endY() - from.endY()) > gap * .70f) return false;
        float slope = (to.endY() - from.endY()) / (endX - startX);
        float stemEnd = from.endY() + slope * (ax - startX);
        if (stemEnd - ay < gap * .48f
                || stemEnd - ay > gap * .84f
                || !PrintedTupletBeamOwner.connectedHeads(
                        gray, width, height, bx, by, cx, cy, gap, -1)) return false;
        int first = Math.round(startX + gap * .24f), last = Math.round(endX - gap * .24f);
        int limit = last - first + 1;
        int upper = 0, lower = 0, whiteBelow = 0;
        for (int x = first; x <= last; x++) {
            float y = from.endY() + slope * (x - startX);
            int edge = Math.round(y - gap), base = Math.round(y - gap * .26f);
            int below = Math.round(y + gap * .30f);
            if (x < 0 || x >= width || edge < 0 || below >= height) return false;
            if ((gray[edge * width + x] & 255) < 155) upper++;
            if ((gray[base * width + x] & 255) < 155) lower++;
            if ((gray[below * width + x] & 255) > 180) whiteBelow++;
        }
        return upper >= limit * .84f && lower >= limit * .90f && whiteBelow >= limit * .92f;
    }
}
