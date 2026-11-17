// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import io.github.luckolite.interpreter.ClosedSixteenthDyads.Head;
import io.github.luckolite.interpreter.ClosedSixteenthDyads.Ink;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Source proof for a complete six-quarter bar written as six double-beam groups. */
final class ClosedMonophonicSixFour {
    private ClosedMonophonicSixFour() {}

    static boolean proves(Ink normalized, Ink original, List<MeasureRegion> regions, int index,
            List<Head> unordered, List<ScoreNoteEvent> current) {
        if (original == null || index == 0 || index + 1 >= regions.size() || unordered.size() != 24)
            return false;
        if (unordered.stream().map(head -> current.get(head.owner())).allMatch(
                n -> n.beamCount() == 2 && n.tupletDivisor() == 1 && n.augmentationDots() == 0))
            return false;
        var bar = regions.get(index);
        var previous = regions.get(index - 1);
        var next = regions.get(index + 1);
        float gap = (bar.bottom() - bar.top()) * normalized.height() / 8.2f;
        float left = bar.left() * normalized.width();
        float width = (bar.right() - bar.left()) * normalized.width();
        if (gap < 12 || gap > 24 || width < 44 * gap || width > 55 * gap
                || (bar.top() - previous.bottom()) * normalized.height() < 3 * gap
                || Math.abs(bar.top() - next.top()) * normalized.height() > .35f * gap)
            return false;
        var heads = unordered.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        float inset = heads.get(0).x() - left;
        if (inset < 1.3f * gap || inset > 3.2f * gap) return false;
        var flagged = new ArrayList<Integer>();
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            var note = current.get(head.owner());
            int ratio = note.tupletDivisor();
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1
                    || note.staffIndex() != 0 || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.beamCount() < 1 || note.beamCount() > 3 || note.augmentationDots() > 1
                    || note.unbeamedDurationBeats() != 0 || note.tiedFromPrevious()
                    || note.boundaryTies() != 0 || note.crossStaffBeam()
                    || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0
                    || Math.abs(note.stemDirection()) != 1 || ratio != 1 && ratio != 3
                    || note.tupletNormalNotes() != (ratio == 3 ? 2 : 1)) return false;
            if (i > 0) {
                float step = head.x() - heads.get(i - 1).x();
                if (step < 1.50f * gap || step > 3.5f * gap) return false;
            }
            if (ratio == 3) flagged.add(i);
            if (note.augmentationDots() != 0) {
                float end = i + 1 < heads.size() ? heads.get(i + 1).x() : left + width;
                if (hasDot(normalized, head, end, gap) || hasDot(original, head, end, gap))
                    return false;
            }
        }
        if (!flagged.isEmpty() && (flagged.size() != 3
                || flagged.get(flagged.size() - 1) - flagged.get(0) != 2
                || flagged.get(0) / 4 == flagged.get(flagged.size() - 1) / 4)) return false;

        float last = heads.get(heads.size() - 1).x();
        if (!closedBoundary(normalized, bar, next, last, gap)
                || !closedBoundary(original, bar, next, last, gap)) return false;
        var normalizedShafts = shafts(normalized, bar, heads, gap, List.of());
        if (normalizedShafts.isEmpty()) return false;
        var originalShafts = shafts(original, bar, heads, gap, normalizedShafts);
        return !originalShafts.isEmpty()
                && pairedGroups(normalized, normalizedShafts, gap)
                && pairedGroups(original, originalShafts, gap);
    }

    private static boolean hasDot(Ink ink, Head head, float nextX, float gap) {
        return WrappedTripletDotInk.uniqueCore(
                ink.raw(), ink.width(), ink.height(), head.x(), head.y(), nextX, gap) != null;
    }

    private static boolean closedBoundary(Ink ink, MeasureRegion bar, MeasureRegion next, float last, float gap) {
        if (WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                ink.raw(), ink.width(), ink.height(), bar, last)) return true;
        float close = (bar.right() + next.left()) * ink.width() * .5f;
        if (close - last < 1.3f * gap || close - last > 3.3f * gap) return false;
        float bottom = bar.bottom() * ink.height() - 2.1f * gap;
        var staff = PrintedDirectionStaff.printedRules(
                ink.raw(), ink.width(), ink.height(), close - 2.35f * gap, bottom, gap);
        if (staff == null || Math.abs(staff[1] - gap) > .10f * gap) return false;
        var frame = new PlayingTechniqueDetector.Staff(staff[0] - 4 * staff[1], staff[0], staff[1], 0, 1);
        return PrintedCadenceDurations.boundaryColumn(
                ink.raw(), ink.width(), frame, close, true, false) >= 0;
    }

    private static List<PrintedBeamShafts.Shaft> shafts(
            Ink ink, MeasureRegion bar, List<Head> heads, float gap, List<PrintedBeamShafts.Shaft> reference) {
        var out = new ArrayList<PrintedBeamShafts.Shaft>(heads.size());
        var budget = new PrintedBeamShafts.Budget(900_000);
        var contrast = new PrintedBeamShaftPixels.Frame(
                ink.raw(), ink.raw(), ink.width(), ink.height(), 0, budget::take, true);
        boolean usedPaleShaft = false;
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            var found = PrintedBeamShafts.at(ink.raw(), ink.width(), ink.height(), head.x(), head.y(), gap, budget);
            if (found.isEmpty())
                found = PrintedBeamShafts.at(ink.raw(), ink.width(), ink.height(), head.x(), head.y(),
                        gap, budget, contrast);
            if (budget.refused()) return List.of();
            int expected = -head.note().stemDirection();
            PrintedBeamShafts.Shaft chosen;
            if (found.size() == 1 && found.get(0).direction() == expected) {
                chosen = found.get(0);
            } else if (found.size() == 2 && i + 1 < heads.size()
                    && found.get(0).direction() != found.get(1).direction()) {
                var rival = found.get(0).direction() == expected ? found.get(1) : found.get(0);
                if (!nextNaturalOwnsShaft(ink, bar, head, heads.get(i + 1), rival, gap)) return List.of();
                chosen = found.get(0).direction() == expected ? found.get(0) : found.get(1);
            } else if (found.isEmpty() && !usedPaleShaft && !reference.isEmpty()
                    && faintShaft(ink, head, reference.get(i), gap)) {
                usedPaleShaft = true;
                chosen = reference.get(i);
            } else return List.of();
            out.add(chosen);
        }
        return out;
    }

    /** Distinguish a competing shaft candidate from the next head's printed natural. */
    private static boolean nextNaturalOwnsShaft(
            Ink ink, MeasureRegion bar, Head earlier, Head next, PrintedBeamShafts.Shaft rival, float gap) {
        if (next.note().writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                || rival.right() - rival.left() + 1 > .33f * gap
                || rival.left() - earlier.x() < .58f * gap
                || next.x() - rival.right() < 1.15f * gap
                || next.x() - rival.right() > 2.35f * gap) return false;
        int left = Math.round(rival.left() - .10f * gap);
        int right = Math.round(next.x() - .80f * gap);
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(
                ink.raw(), ink.width(), ink.height(), bar);
        if (staff == null || !staff.verifiedAt((left + right) * .5f)) return false;
        var rules = staff.at((left + right) * .5f);
        return OmrScoreInterpreter.provesNaturalMeterInk(
                ink.raw(), ink.width(), ink.height(), left, right, next.y(),
                rules[1], Math.round(rules[0] - 4 * rules[1]));
    }


    /** The alternate raster supplies only the axis; confirm its own narrow stroke on original paper. */
    private static boolean faintShaft(Ink ink, Head head, PrintedBeamShafts.Shaft shaft, float gap) {
        int start = shaft.startY(), end = shaft.endY(), x = (shaft.left() + shaft.right()) / 2;
        int distance = Math.abs(end - start) + 1;
        if (shaft.direction() != -head.note().stemDirection() || distance < 2.4f * gap
                || distance > 4.6f * gap || shaft.right() - shaft.left() > .2f * gap
                || Math.abs(x - head.x()) < .40f * gap || Math.abs(x - head.x()) > .78f * gap)
            return false;
        int exact = 0, soft = 0;
        for (int y = Math.min(start, end); y <= Math.max(start, end); y++) {
            if (ink.dark(x, y)) exact++;
            else if (PrintedBeamRawContrast.shaftWithEnclosingPaper(
                    ink.raw(), ink.width(), ink.height(), x, y, gap, cost -> {})) soft++;
        }
        return exact >= .84f * distance && exact + soft >= .96f * distance;
    }

    private static boolean pairedGroups(Ink ink, List<PrintedBeamShafts.Shaft> shafts, float gap) {
        for (int i = 0; i < shafts.size(); i += 4) {
            var group = shafts.subList(i, i + 4);
            int direction = group.get(0).direction();
            if (group.stream().anyMatch(a -> a.direction() != direction)
                    || !ownsTwoRails(ink, group, gap)) return false;
            if (i > 0) {
                var previous = shafts.get(i - 1);
                var first = group.get(0);
                if (previous.direction() == direction && !WrappedEighthTriplets.matchesRailBridge(
                        ink.raw(), ink.width(), ink.height(), previous, first, gap, false)) return false;
            }
        }
        return true;
    }

    private static boolean ownsTwoRails(Ink ink, List<PrintedBeamShafts.Shaft> group, float gap) {
        var first = group.get(0);
        var last = group.get(group.size() - 1);
        int direction = first.direction(), x0 = shaftX(first), x1 = shaftX(last);
        int middleA = shaftX(group.get(1)), middleB = shaftX(group.get(2));
        int margin = Math.round(.34f * gap), exclude = Math.round(.22f * gap);
        if (x1 - x0 < 4.7f * gap || x1 - x0 > 9.6f * gap
                || Math.abs(last.endY() - first.endY()) > .45f * (x1 - x0)) return false;
        int[] columns = new int[x1 - x0 + 1];
        int size = 0;
        for (int x = x0 + margin; x <= x1 - margin; x++) {
            if (Math.abs(x - middleA) >= exclude && Math.abs(x - middleB) >= exclude) columns[size++] = x;
        }
        int small = Math.round(.08f * gap), large = Math.round(.58f * gap);
        int minSeam = Math.round(.57f * gap), maxSeam = Math.round(1.08f * gap);
        int radius = Math.max(2, Math.round(.125f * gap));
        for (int a = small; a <= large; a++) {
            for (int b = small; b <= large; b++) {
                var axis = new ClosedSixteenthDyads.Rails(
                        x0, first.endY() - direction * a, x1, last.endY() - direction * b, 0);
                if (band(ink, axis, columns, size, 0, radius) < .95f) continue;
                for (int separation = minSeam; separation <= maxSeam; separation++) {
                    int shift = direction * separation;
                    if (band(ink, axis, columns, size, shift, radius) < .95f
                            || band(ink, axis, columns, size, shift / 2, 1) > .67f)
                        continue;
                    boolean third = false;
                    for (int delta = minSeam; delta <= maxSeam && !third; delta++)
                        third = band(ink, axis, columns, size, shift + direction * delta, radius) >= .35f;
                    if (!third) return true;
                }
            }
        }
        return false;
    }

    private static float band(Ink ink, ClosedSixteenthDyads.Rails axis, int[] columns, int size,
            int shift, int radius) {
        int matches = 0;
        for (int i = 0; i < size; i++) {
            int x = columns[i], y = axis.at(x) - shift;
            int dark = 0;
            for (int d = -radius; d <= radius; d++) if (ink.dark(x, y + d)) dark++;
            if (dark >= 2 * radius) matches++;
        }
        return matches / (float) size;
    }

    private static int shaftX(PrintedBeamShafts.Shaft shaft) {
        return (shaft.left() + shaft.right()) / 2;
    }
}
