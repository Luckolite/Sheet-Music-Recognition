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
