// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Separates compact ledger slurs from nearby disconnected ink using original pixel adjacency. */
final class DetachedLedgerSlurComponents {
    private static final int DARK_CORE = 120;
    private static final int INK_EDGE = 154;

    private DetachedLedgerSlurComponents() {}

    static List<PrintedSlurContours.Bow> capture(
            byte[] gray, int width, int height,
            PrintedSlurPageCapture.Frame<? extends PrintedSlurOwners.Location> frame,
            List<ScoreNoteEvent> notes, List<PrintedSlurContours.Bow> retained, long[] budget) {
        if (frame.heads().size() > 512 || budget[0] <= 0) return List.of();
        var heads = frame.heads().stream()
                .filter(head -> eligible(notes.get(head.owner()), head))
                .sorted(Comparator.comparingDouble(PrintedSlurOwners.Location::x)).toList();
        var recovered = new ArrayList<PrintedSlurContours.Bow>();
        for (int i = 1; i < heads.size(); i++) {
            var a = heads.get(i - 1);
            var b = heads.get(i);
            var first = notes.get(a.owner());
            var second = notes.get(b.owner());
            if (first.measureIndex() != second.measureIndex()
                    || second.staffStep() - first.staffStep() != 1
                    || !frame.track().verifiedAt(a.x()) || !frame.track().verifiedAt(b.x()))
                continue;
            float gap = frame.track().at((a.x() + b.x()) * .5f)[1];
            float separation = b.x() - a.x(), rise = a.y() - b.y();
            if (gap < 10 || gap > 24 || separation < gap * 1.55f || separation > gap * 2.5f
                    || rise < gap * .34f || rise > gap * .85f
                    || retained.stream().anyMatch(bow -> bow.above()
                            && Math.abs(bow.start().x() - a.x()) < gap * .65f
                            && Math.abs(bow.end().x() - b.x()) < gap * .65f)
                    || frame.heads().stream().anyMatch(other -> other.owner() != a.owner()
                            && other.owner() != b.owner() && other.x() >= a.x() - gap * .25f
                            && other.x() <= b.x() + gap * .25f))
                continue;
            int left = Math.max(frame.left(), Math.round(a.x() - gap * .72f));
            int right = Math.min(frame.right(), Math.round(b.x() + gap * .72f));
            int top = Math.max(frame.top(), Math.round(b.y() - gap * 2.6f));
            int bottom = Math.min(frame.bottom(), Math.round(b.y() - gap * .12f));
            if (left >= right || top >= bottom) continue;
            budget[0] -= 2L * (right - left + 1) * (bottom - top + 1);
            if (budget[0] < 0) break;
            var edge = fitUnique(gray, width, height, frame, a, b, gap, left, top, right, bottom, INK_EDGE);
            if (edge == null) continue;
            var core = fitUnique(gray, width, height, frame, a, b, gap, left, top, right, bottom, DARK_CORE);
            if (core == null || !sameTips(edge, core)) continue;
            var binding = PrintedSlurOwners.bind(
                    "detached-ledger-slur", 0, edge, frame.heads(), gray, width, height, frame.track());
            if (binding.span().isPresent()
                    && binding.span().orElseThrow().start().noteIndex() == a.owner()
                    && binding.span().orElseThrow().end().noteIndex() == b.owner())
                recovered.add(edge);
        }
        return recovered;
    }

    record Owners(PrintedSlurOwners.Location first, PrintedSlurOwners.Location second) {}

    static Map<PrintedSlurContours.Bow, Owners> belowStaff(
            byte[] gray, int width, int height,
            PrintedSlurPageCapture.Frame<? extends PrintedSlurOwners.Location> frame,
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures,
            List<PrintedSlurContours.Bow> retained, long[] budget) {
        var recovered = new LinkedHashMap<PrintedSlurContours.Bow, Owners>();
        if (frame.heads().size() > 512 || budget[0] <= 0) return recovered;
        var heads = frame.heads().stream()
                .sorted(Comparator.comparingDouble(PrintedSlurOwners.Location::x)).toList();
        for (int i = 1; i < heads.size(); i++) {
            var a = heads.get(i - 1);
            var b = heads.get(i);
            var first = notes.get(a.owner());
            var second = notes.get(b.owner());
            if (!atLowerRule(first, a) || !atLowerRule(second, b)
                    || first.measureIndex() != second.measureIndex()
                    || first.staffStep() == second.staffStep()
                            && (second.writtenAccidental() == 0
                                    || first.writtenAccidental() == second.writtenAccidental())
                    || Math.abs(first.staffStep() - second.staffStep()) > 3) continue;
            float gap = frame.track().at((a.x() + b.x()) * .5f)[1];
            float separation = b.x() - a.x();
            if (gap < 10 || gap > 24 || separation < gap * 1.55f || separation > gap * 3.55f
                    || i > 1 && a.x() - heads.get(i - 2).x() < gap * .75f
                    || i + 1 < heads.size() && heads.get(i + 1).x() - b.x() < gap * .75f)
                continue;
            int left = Math.max(frame.left(), Math.round(a.x() - gap * 1.12f));
            int right = Math.min(frame.right(), Math.round(b.x() + gap * 1.12f));
            int top = Math.max(frame.top(), Math.round(Math.min(a.y(), b.y()) + gap * .32f));
            int bottom = Math.min(frame.bottom(), Math.round(Math.max(a.y(), b.y()) + gap * 2.05f));
            if (left >= right || top >= bottom) continue;
            budget[0] -= 2L * (right - left + 1) * (bottom - top + 1);
            if (budget[0] < 0) break;
            var edge = separation < gap * 1.65f || separation > gap * 2.95f ? null
                    : belowBow(gray, width, height, frame.track(), a, b, gap,
                    left, top, right, bottom, INK_EDGE);
            var core = edge == null ? null : belowBow(gray, width, height, frame.track(), a, b, gap,
                    left, top, right, bottom, DARK_CORE);
            if (core == null || !sameTips(edge, core)) {
                budget[0] -= 2L * (right - left + 1) * (bottom - top + 1);
                if (budget[0] < 0) break;
                edge = belowRuleBow(gray, width, frame.track(), a, b, gap, left, top, right, bottom);
            }
            if (edge == null
                    || !bothLowerHeadsOnStaff(
                            gray, width, height, frame.track(), a, b, first, second, measures)
                    || !PrintedSlurOwners.headBody(gray, width, height, a, gap)
                    || !PrintedSlurOwners.headBody(gray, width, height, b, gap)) continue;
            boolean alreadyBound = false;
            for (var bow : retained) {
                if (bow.above() || Math.abs(bow.start().x() - edge.start().x()) > gap * .45f
                        || Math.abs(bow.end().x() - edge.end().x()) > gap * .45f
                        || Math.abs(bow.start().y() - edge.start().y()) > gap * .45f) continue;
                if (PrintedSlurOwners.bind("below-existing", 0, bow, frame.heads(),
                        gray, width, height, frame.track()).span().isPresent()) {
                    alreadyBound = true;
                    break;
                }
            }
            if (!alreadyBound) recovered.put(edge, new Owners(a, b));
        }
        return recovered;
    }

    static Map<PrintedSlurContours.Bow, Owners> aboveStaff(
            byte[] gray, int width, int height,
            PrintedSlurPageCapture.Frame<? extends PrintedSlurOwners.Location> frame,
            List<ScoreNoteEvent> notes, List<PrintedSlurContours.Bow> retained, long[] budget, long[] extensionBudget, long[] accidentalBudget, long[] junctionBudget) {
        var recovered = new LinkedHashMap<PrintedSlurContours.Bow, Owners>();
        if (frame.heads().size() > 512 || budget[0] <= 0) return recovered;
        var heads = frame.heads().stream()
                .sorted(Comparator.comparingDouble(PrintedSlurOwners.Location::x)).toList();
        for (int i = 0; i + 1 < heads.size(); i++) {
            var a = heads.get(i);
            var first = notes.get(a.owner());
            boolean upper = aboveBeamed(first, a);
            boolean inStaff = first.staffStep() == 4 && beamedSource(first, a);
            if (!upper && !inStaff) continue;
            for (int count = 1; count <= 2 && i + count < heads.size(); count++) {
                var b = heads.get(i + count);
                var last = notes.get(b.owner());
                boolean accidental = count == 1
                        && (last.writtenAccidental() == 0 || last.writtenAccidental() == 1)
                        && (first.staffStep() != last.staffStep()
                                || first.writtenAccidental() != last.writtenAccidental());
                if (inStaff && (count != 1 || last.staffStep() != 5 || last.writtenAccidental() != 0)
                        || !aboveBeamed(last, b) || first.measureIndex() != last.measureIndex()
                        || !accidental && first.staffStep() == last.staffStep()
                        || Math.abs(first.staffStep() - last.staffStep()) > (count == 1 ? 2 : 3)
                        || !nearMeasuredStaff(gray, width, height, frame.track(), a, first)
                        || !nearMeasuredStaff(gray, width, height, frame.track(), b, last)) continue;
                float gap = frame.track().at((a.x() + b.x()) * .5f)[1];
                float spacing = b.x() - a.x();
                if (gap < 10 || gap > 24 || spacing < gap * (count == 1 ? 1.55f : 3.2f)
                        || spacing > gap * (accidental ? 3.85f : count == 1 ? 3.3f : 4.65f)
                        || i > 0 && a.x() - heads.get(i - 1).x() < gap * .86f
                        || i + count + 1 < heads.size()
                                && heads.get(i + count + 1).x() - b.x() < gap * .86f)
                    continue;
                if (count == 2) {
                    var c = heads.get(i + 1);
                    var middle = notes.get(c.owner());
                    if (!aboveBeamed(middle, c) || middle.measureIndex() != first.measureIndex()
                            || first.staffStep() < 11 || middle.staffStep() < 11 || last.staffStep() < 11
                            || first.beamCount() != 2 || middle.beamCount() != 2 || last.beamCount() != 2
                            || middle.staffStep() <= Math.min(first.staffStep(), last.staffStep())
                            || middle.staffStep() >= Math.max(first.staffStep(), last.staffStep())
                            || c.x() - a.x() < gap * 1.25f || b.x() - c.x() < gap * 1.25f) continue;
                }
                int left = Math.max(frame.left(), Math.round(a.x() - gap));
                int right = Math.min(frame.right(), Math.round(b.x() + gap));
                int top = Math.max(frame.top(), Math.round(Math.min(a.y(), b.y()) - gap * 2.8f));
                int bottom = Math.min(frame.bottom(), Math.round(Math.max(a.y(), b.y()) - gap * .22f));
                if (left >= right || top >= bottom) continue;
                boolean original = upper && first.staffStep() != last.staffStep()
                        && (count == 2 || spacing <= gap * 3.3f);
                PrintedSlurContours.Bow edge = null;
                if (original) {
                    budget[0] -= 2L * (right - left + 1) * (bottom - top + 1);
                    if (budget[0] < 0) return recovered;
                    edge = aboveBow(gray, width, height, frame.track(), a, b, gap,
                            left, top, right, bottom, INK_EDGE);
                    var core = edge == null ? null : aboveBow(
                            gray, width, height, frame.track(), a, b, gap, left, top, right, bottom, DARK_CORE);
                    if (core == null || !sameTips(edge, core)) {
                        if (core == null || !weakTrailingTip(gray, width, frame.track(),
                                a, b, gap, left, top, right, bottom, edge, core, extensionBudget)) {
                            edge = outsideTopRail(gray, width, frame.track(), a, b, gap,
                                    left, top, right, bottom, extensionBudget);
                        }
                    }
                }
                if (edge == null && accidental && first.staffStep() == last.staffStep())
                    edge = outsideTopRail(gray, width, frame.track(), a, b, gap,
                            left, top, right, bottom, extensionBudget);
                if (edge == null && accidental)
                    edge = AccidentalSlurCrossing.find(gray, width, frame.track(), a, b,
                            last.writtenAccidental(), gap, left, top, right, bottom, accidentalBudget);
                if (edge == null && count == 1)
                    edge = UpperRuleSlurCrossing.find(gray, width, frame.track(), a, b,
                            gap, left, top, right, bottom, junctionBudget);
                if (edge == null && accidental && last.writtenAccidental() == 0)
                    edge = NaturalRailSlurCrossing.find(gray, width, frame.track(), a, b,
                            gap, left, top, right, bottom, junctionBudget);
                if (edge == null || !PrintedSlurOwners.headBody(gray, width, height, a, gap)
                        || !PrintedSlurOwners.headBody(gray, width, height, b, gap)) continue;
                boolean existing = false;
                for (var bow : retained) {
                    if (!bow.above() || Math.abs(bow.start().x() - edge.start().x()) > gap * .65f
                            || Math.abs(bow.end().x() - edge.end().x()) > gap * .65f
                            || Math.abs(bow.start().y() - edge.start().y()) > gap * .6f) continue;
                    if (PrintedSlurOwners.bind("above-existing", 0, bow, frame.heads(),
                            gray, width, height, frame.track()).span().isPresent()) {
                        existing = true;
                        break;
                    }
                }
                if (!existing) recovered.put(edge, new Owners(a, b));
            }
        }
        return recovered;
    }

    private static boolean weakTrailingTip(
            byte[] gray, int width, StaffPitchTrack track,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, float gap,
            int left, int top, int right, int bottom,
            PrintedSlurContours.Bow edge, PrintedSlurContours.Bow core, long[] budget) {
        if (!shorterCore(edge, core, gap)) return false;
        long cells = (long) (right - left + 1) * (bottom - top + 1);
        if (budget[0] < cells) return false;
        budget[0] -= cells;
        var thin = aboveBow(gray, width, gray.length / width, track, a, b, gap,
                left, top, right, bottom, 140);
        return sameTrailingInk(gray, width, gap, edge, core, thin);
    }

    private static boolean shorterCore(
            PrintedSlurContours.Bow edge, PrintedSlurContours.Bow core, float gap) {
        int extension = edge.end().x() - core.end().x();
        return extension >= 3 && extension <= gap * .5f
                && Math.abs(edge.start().x() - core.start().x()) <= 2
                && Math.abs(edge.start().y() - core.start().y()) <= 2
                && core.points().size() >= edge.points().size() * .76f;
    }

    private static boolean sameTrailingInk(
            byte[] gray, int width, float gap, PrintedSlurContours.Bow edge,
            PrintedSlurContours.Bow core, PrintedSlurContours.Bow thin) {
        if (thin == null || !sameTips(edge, thin)) return false;
        for (var point : core.points()) {
            int i = point.x() - edge.start().x();
            if (i < 0 || i >= edge.points().size()) continue;
            var fullPoint = edge.points().get(i);
            if (fullPoint.x() != point.x() || Math.abs(fullPoint.y() - point.y()) > 1.7f)
                return false;
        }
        for (var point : thin.points()) {
            if (point.x() <= core.end().x()) continue;
            int y = Math.round(point.y());
            int outer = Math.max(4, Math.round(gap * .3f));
            if (y < outer || (y + outer) * width + point.x() >= gray.length) return false;
            int ink = gray[y * width + point.x()] & 255;
            int paperAbove = gray[(y - outer) * width + point.x()] & 255;
            int paperBelow = gray[(y + outer) * width + point.x()] & 255;
            if (ink > 154 || Math.min(paperAbove, paperBelow) < ink + 55) return false;
        }
        return true;
    }

    private static PrintedSlurContours.Bow outsideTopRail(
            byte[] gray, int width, StaffPitchTrack track,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, float gap,
            int left, int top, int right, int bottom, long[] budget) {
        if (!(track.verifiedAt(a.x()) && track.verifiedAt(b.x()))) return null;
        var aFrame = track.at(a.x());
        var bFrame = track.at(b.x());
        if (Math.abs(a.y() - (aFrame[0] - 4 * aFrame[1])) > gap * 1.3f
                || Math.abs(b.y() - (bFrame[0] - 4 * bFrame[1])) > gap * 1.3f) return null;
        int cols = right - left + 1, rows = bottom - top + 1;
        long cells = 2L * cols * rows;
        if (budget[0] < cells) return null;
        budget[0] -= cells;
        byte[] window = new byte[cols * rows];
        Arrays.fill(window, (byte) 255);
        int[] rail = new int[cols];
        for (int x = left; x <= right; x++) {
            float[] slice = track.at(x);
            int row = Math.round(slice[0] - 4 * slice[1]);
            rail[x - left] = row;
            for (int y = top; y <= Math.min(bottom, row - 3); y++)
                window[(y - top) * cols + x - left] = gray[y * width + x];
        }
        var edge = outsideRailFit(window, cols, rows, left, top, track, a, b, gap, INK_EDGE);
        if (edge == null) return null;
        var core = outsideRailFit(window, cols, rows, left, top, track, a, b, gap, DARK_CORE);
        if (core == null) return null;
        if (!sameTips(edge, core)) {
            long middleCells = (long) cols * rows;
            if (budget[0] < middleCells || !shorterCore(edge, core, gap)) return null;
            budget[0] -= middleCells;
            var thin = outsideRailFit(window, cols, rows, left, top, track, a, b, gap, 146);
            if (!sameTrailingInk(gray, width, gap, edge, core, thin)) return null;
        }
        if (Math.min(rail[edge.start().x() - left] - edge.start().y(),
                            rail[edge.end().x() - left] - edge.end().y()) > gap * .29f)
            return null;
        int hits = 0, from = edge.start().x(), to = edge.end().x();
        for (int x = from; x <= to; x++) {
            int row = rail[x - left];
            if (row < 1 || (row + 1) * width + x >= gray.length) return null;
            if ((gray[(row - 1) * width + x] & 255) < 154
                    || (gray[row * width + x] & 255) < 154
                    || (gray[(row + 1) * width + x] & 255) < 154) hits++;
        }
        return hits * 10 >= (to - from + 1) * 9 ? edge : null;
    }

    private static PrintedSlurContours.Bow outsideRailFit(
            byte[] crop, int width, int height, int left, int top, StaffPitchTrack track,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, float gap, int threshold) {
        var graph = RawNumeralComponents.capture(
                crop, width, height, 0, 0, width - 1, height - 1, threshold, () -> {}, () -> {});
        PrintedSlurContours.Bow result = null;
        for (var part : graph.components()) {
            if (part.left <= 1 || part.right >= width - 2 || part.top <= 1
                    || part.bottom >= height - 2 || part.right - part.left < gap * 1.65f
                    || Math.abs(left + part.left - a.x()) > gap * .62f
                    || Math.abs(left + part.right - b.x()) > gap * .62f) continue;
            var bow = PrintedSlurContours.fitPixelCells(columns(part, width, gap, left, top), track);
            if (bow == null || !bow.above() || bow.asymmetric() || bow.rmsInGaps() > .04f
                    || bow.bendInGaps() < .32f || bow.bendInGaps() > .95f
                    || a.y() - bow.start().y() < gap * .65f || a.y() - bow.start().y() > gap * 1.5f
                    || b.y() - bow.end().y() < gap * .65f || b.y() - bow.end().y() > gap * 1.5f)
                continue;
            if (result != null) return null;
            result = bow;
        }
        return result;
    }

    private static boolean aboveBeamed(ScoreNoteEvent note, PrintedSlurOwners.Location head) {
        return note.staffStep() >= 5 && beamedSource(note, head);
    }

    private static boolean beamedSource(ScoreNoteEvent note, PrintedSlurOwners.Location head) {
        return !head.grace() && note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL && note.staffCount() == 1
                && note.beamCount() >= 1 && note.stemDirection() == -1
                && !note.crossStaffBeam();
    }

    private static PrintedSlurContours.Bow aboveBow(
            byte[] gray, int width, int height, StaffPitchTrack track,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, float gap,
            int left, int top, int right, int bottom, int threshold) {
        var graph = RawNumeralComponents.capture(
                gray, width, height, left, top, right, bottom, threshold, () -> {}, () -> {});
        PrintedSlurContours.Bow result = null;
        for (var part : graph.components()) {
            if (part.left <= left + 1 || part.right >= right - 1 || part.top <= top + 1
                    || part.bottom >= bottom - 1 || part.right - part.left < gap * 1.65f
                    || Math.abs(part.left - a.x()) > gap * .62f
                    || Math.abs(part.right - b.x()) > gap * .62f) continue;
            var bow = PrintedSlurContours.fitPixelCells(columns(part, width, gap), track);
            if (bow == null || !bow.above() || bow.asymmetric() || bow.rmsInGaps() > .045f
                    || bow.bendInGaps() < .30f || bow.bendInGaps() > 1.15f
                    || a.y() - bow.start().y() < gap * .55f || a.y() - bow.start().y() > gap * 1.45f
                    || b.y() - bow.end().y() < gap * .68f || b.y() - bow.end().y() > gap * 1.65f)
                continue;
            if (result != null) return null;
            result = bow;
        }
        return result;
    }

    private static boolean bothLowerHeadsOnStaff(
            byte[] gray, int width, int height, StaffPitchTrack track,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b,
            ScoreNoteEvent first, ScoreNoteEvent second, List<MeasureRegion> measures) {
        return TerminalBelowSlurRails.proves(gray, width, height, track, a, b, first, second, measures)
                || (track.verifiedAt(a.x()) || track.verifiedAt(b.x()))
                        && nearMeasuredStaff(gray, width, height, track, a, first)
                        && nearMeasuredStaff(gray, width, height, track, b, second);
    }

    private static boolean nearMeasuredStaff(
            byte[] gray, int width, int height, StaffPitchTrack track,
            PrintedSlurOwners.Location head, ScoreNoteEvent note) {
        if (track.verifiedAt(head.x())) return true;
        float[] projected = track.at(head.x());
        float gap = projected[1];
        float[] local = PrintedDirectionStaff.printedRules(
                gray, width, height, head.x(), projected[0], gap);
        return local != null && Math.abs(local[0] - projected[0]) <= gap * .13f
                && Math.abs(local[1] - gap) <= gap * .045f
                && Math.abs(local[0] - note.staffStep() * local[1] * .5f - head.y()) <= gap * .24f;
    }

    private static PrintedSlurContours.Bow belowRuleBow(
            byte[] gray, int width, StaffPitchTrack track,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, float gap,
            int left, int top, int right, int bottom) {
        int windowWidth = right - left + 1, windowHeight = bottom - top + 1;
        byte[] below = new byte[windowWidth * windowHeight];
        Arrays.fill(below, (byte) 255);
        int[] floor = new int[windowWidth];
        for (int x = left; x <= right; x++) {
            int column = x - left;
            floor[column] = Math.round(track.at(x)[0]);
            for (int y = Math.max(top, floor[column] + 3); y <= bottom; y++)
                below[(y - top) * windowWidth + column] = gray[y * width + x];
        }
        var edge = trimmedBow(below, windowWidth, windowHeight, left, top, track, a, b, gap, INK_EDGE);
        if (edge == null) return null;
        var core = trimmedBow(below, windowWidth, windowHeight, left, top, track, a, b, gap, DARK_CORE);
        if (core == null || !sameTips(edge, core)) return null;
        int from = edge.start().x(), to = edge.end().x();
        int span = to - from + 1, hits = 0;
        float firstDepth = edge.start().y() - floor[from - left];
        float lastDepth = edge.end().y() - floor[to - left];
        if (Math.min(firstDepth, lastDepth) > gap * .25f) return null;
        for (int x = from; x <= to; x++) {
            int rule = floor[x - left];
            if (rule < 1 || (rule + 1) * width + x >= gray.length) return null;
            if ((gray[(rule - 1) * width + x] & 255) < 154
                    || (gray[rule * width + x] & 255) < 154
                    || (gray[(rule + 1) * width + x] & 255) < 154) hits++;
        }
        return hits * 10 >= span * 9 ? edge : null;
    }

    private static PrintedSlurContours.Bow trimmedBow(
            byte[] crop, int width, int height, int left, int top, StaffPitchTrack track,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, float gap, int threshold) {
        var graph = RawNumeralComponents.capture(
                crop, width, height, 0, 0, width - 1, height - 1, threshold, () -> {}, () -> {});
        PrintedSlurContours.Bow result = null;
        for (var part : graph.components()) {
            if (part.left <= 1 || part.right >= width - 2 || part.top <= 1
                    || part.bottom >= height - 2 || part.right - part.left < gap * 1.65f
                    || Math.abs(left + part.left - a.x()) > gap * .71f
                    || Math.abs(left + part.right - b.x()) > gap * .71f) continue;
            var bow = PrintedSlurContours.fitPixelCells(columns(part, width, gap, left, top), track);
            if (bow == null || bow.above() || bow.asymmetric() || bow.rmsInGaps() > .045f
                    || bow.bendInGaps() < .31f || bow.bendInGaps() > 1f
                    || bow.start().y() - a.y() < gap * .55f || bow.start().y() - a.y() > gap * 1.65f
                    || bow.end().y() - b.y() < gap * .45f || bow.end().y() - b.y() > gap * 1.65f)
                continue;
            if (result != null) return null;
            result = bow;
        }
        return result;
    }

    private static boolean atLowerRule(ScoreNoteEvent note, PrintedSlurOwners.Location head) {
        return !head.grace() && note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL && note.staffCount() == 1
                && note.staffStep() >= -1 && note.staffStep() <= 2
                && note.beamCount() >= 1 && note.stemDirection() == 1 && !note.crossStaffBeam();
    }

    private static PrintedSlurContours.Bow belowBow(
            byte[] gray, int width, int height, StaffPitchTrack track,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, float gap,
            int left, int top, int right, int bottom, int threshold) {
        var graph = RawNumeralComponents.capture(
                gray, width, height, left, top, right, bottom, threshold, () -> {}, () -> {});
        PrintedSlurContours.Bow result = null;
        for (var part : graph.components()) {
            if (part.left <= left + 1 || part.right >= right - 1 || part.top <= top + 1
                    || part.bottom >= bottom - 1 || part.right - part.left < gap * 1.65f
                    || Math.abs(part.left - a.x()) > gap * .65f
                    || Math.abs(part.right - b.x()) > gap * .65f)
                continue;
            var bow = PrintedSlurContours.fitPixelCells(columns(part, width, gap), track);
            if (bow == null || bow.above() || bow.asymmetric()
                    || Math.abs(bow.bendInGaps()) < (threshold == DARK_CORE ? .30f : .32f) || Math.abs(bow.bendInGaps()) > 1.2f
                    || bow.rmsInGaps() > .07f
                    || bow.start().y() - a.y() < gap * .55f || bow.start().y() - a.y() > gap * 1.95f
                    || bow.end().y() - b.y() < gap * .40f || bow.end().y() - b.y() > gap * 1.6f)
                continue;
            if (result != null) return null;
            result = bow;
        }
        return result;
    }

    private static boolean eligible(ScoreNoteEvent note, PrintedSlurOwners.Location head) {
        return !head.grace() && note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL && note.staffCount() == 1
                && note.staffStep() >= 11 && note.beamCount() == 2 && note.stemDirection() == -1
                && !note.crossStaffBeam();
    }

    private static PrintedSlurContours.Bow fitUnique(
            byte[] gray, int width, int height,
            PrintedSlurPageCapture.Frame<? extends PrintedSlurOwners.Location> frame,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, float gap,
            int left, int top, int right, int bottom, int threshold) {
        var graph = RawNumeralComponents.capture(
                gray, width, height, left, top, right, bottom, threshold, () -> {}, () -> {});
        PrintedSlurContours.Bow result = null;
        for (var component : graph.components()) {
            if (component.left <= left + 1 || component.right >= right - 1
                    || component.top <= top + 1 || component.bottom >= bottom - 1
                    || Math.abs(component.left - a.x()) > gap * .59f
                    || Math.abs(component.right - b.x()) > gap * .59f
                    || component.right - component.left < gap * 1.55f) continue;
            var bow = PrintedSlurContours.fitPixelCells(columns(component, width, gap), frame.track());
            if (bow == null || !bow.above() || bow.asymmetric() || bow.rmsInGaps() > .065f
                    || bow.bendInGaps() < .36f || bow.bendInGaps() > 1.12f
                    || a.y() - bow.start().y() < gap * .63f || a.y() - bow.start().y() > gap * 1.45f
                    || b.y() - bow.end().y() < gap * .54f || b.y() - bow.end().y() > gap * 1.35f)
                continue;
            if (result != null) return null;
            result = bow;
        }
        return result;
    }

    private static List<PrintedSlurContours.ForeignRun> columns(
            RawNumeralComponents.Component component, int stride, float gap) {
        return columns(component, stride, gap, 0, 0);
    }

    static List<PrintedSlurContours.ForeignRun> columns(
            RawNumeralComponents.Component component, int stride, float gap, int left, int topOffset) {
        int length = component.right - component.left + 1;
        int[] top = new int[length], bottom = new int[length], count = new int[length];
        Arrays.fill(top, Integer.MAX_VALUE);
        for (int i = 0; i < component.size(); i++) {
            int index = component.pixel(i), column = index % stride - component.left, row = index / stride;
            top[column] = Math.min(top[column], row);
            bottom[column] = Math.max(bottom[column], row);
            count[column]++;
        }
        var result = new ArrayList<PrintedSlurContours.ForeignRun>(length);
        for (int column = 0; column < length; column++) {
            if (count[column] == 0 || count[column] != bottom[column] - top[column] + 1
                    || count[column] > Math.round(gap * .42f)) return List.of();
            result.add(new PrintedSlurContours.ForeignRun(left + component.left + column,
                    List.of(new PrintedSlurContours.RawBand(topOffset + top[column], topOffset + bottom[column]))));
        }
        return result;
    }

    private static boolean sameTips(PrintedSlurContours.Bow edge, PrintedSlurContours.Bow core) {
        return Math.abs(edge.start().x() - core.start().x()) <= 2
                && Math.abs(edge.start().y() - core.start().y()) <= 2
                && Math.abs(edge.end().x() - core.end().x()) <= 2
                && Math.abs(edge.end().y() - core.end().y()) <= 2;
    }
}
