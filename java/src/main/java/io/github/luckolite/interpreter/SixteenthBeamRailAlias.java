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
            List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures,
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
        Map<Integer, List<ScoreNoteEvent>> rows = new HashMap<>();
        for (var n : notes)
            rows.computeIfAbsent(n.measureIndex(), unused -> new ArrayList<>()).add(n);
        var removed = new HashSet<ScoreNoteEvent>();
        for (var entry : rows.entrySet()) {
            if (entry.getValue().size() != 25) continue;
            var alias =
                    ownedAlias(
                            entry.getValue(),
                            measures.get(entry.getKey()),
                            gray,
                            raw,
                            width,
                            height);
            if (alias != null) removed.add(alias);
        }
        return removed.isEmpty()
                ? notes
                : notes.stream().filter(note -> !removed.contains(note)).toList();
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
            if ((row.size() == 33 || row.size() == 34)
                    && index >= 0
                    && index < measures.size()
                    && inCommonTime(meters, inheritedMeter, index)) {
                removed.addAll(
                        closedOctaveAliases(row, measures.get(index), gray, raw, width, height));
            }
        }
        return removed.isEmpty()
                ? notes
                : notes.stream().filter(note -> !removed.contains(note)).toList();
    }

    private static boolean inCommonTime(
            List<ScoreMeterChange> meters, ScoreMeterChange inherited, int measure) {
        var used = inherited;
        for (var meter : meters) {
            if (meter.measureIndex() > measure) break;
            used = meter;
        }
        return used != null && used.numerator() == 4 && used.denominator() == 4;
    }

    /** The only unpaired readings in a complete octave row may be ink shared with its rails. */
    private static List<ScoreNoteEvent> closedOctaveAliases(
            List<ScoreNoteEvent> row,
            MeasureRegion region,
            byte[] gray,
            byte[] raw,
            int width,
            int height) {
        float left = region.left() * width, span = (region.right() - region.left()) * width;
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        if (gap < 11 || gap > 23 || span < 30 * gap || span > 53 * gap) return List.of();
        var suspicious = row.stream().filter(SixteenthBeamRailAlias::possibleOctaveAlias).toList();
        if (row.size() - suspicious.size() != 32) return List.of();
        var notes =
                row.stream()
                        .filter(n -> !suspicious.contains(n))
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        var lower = new ArrayList<ScoreNoteEvent>(16);
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
        int paperColumns = 0;
        for (int x = fromX; x <= throughX; x++) {
            float y = from.endY() + slope * (x - x0);
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
