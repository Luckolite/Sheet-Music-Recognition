// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** A slur across the dividing rule of a compact, two-measure closing system. */
final class CompactClosingSlur {
    private record Bar(int left, int right) {}

    private CompactClosingSlur() {}

    static <T extends PrintedSlurOwners.Location> PrintedSlurPageCapture.Result append(
            int page, ScorePageInterpretation score, byte[] gray, int w, int h,
            List<PlayingTechniqueDetector.Staff> staffs, List<T> heads, Set<Integer> unbound,
            PrintedSlurPageCapture.Result captured, Set<PrintedSlurOwners.Tie> ties, boolean geometryOnly) {
        var measures = score.measures();
        int firstIndex = measures.size() - 2;
        if (captured.limitReached() || unbound.isEmpty() || firstIndex < 0) return captured;
        var first = measures.get(firstIndex);
        var last = measures.get(firstIndex + 1);
        if (!sameRow(first, last) || first.right() > last.left()
                || last.right() - first.left() >= .25f
                || firstIndex > 0 && sameRow(first, measures.get(firstIndex - 1))) return captured;
        List<ScoreSlurPages.Span> spans = new ArrayList<>(captured.source().spans());
        List<ScoreSlurPages.Span> candidates = new ArrayList<>(captured.source().candidates());
        var statuses = new EnumMap<PrintedSlurOwners.Status, Integer>(PrintedSlurOwners.Status.class);
        statuses.putAll(captured.statuses());
        for (var staff : staffs) {
            float center = (staff.top() + staff.bottom()) * .5f / h;
            if (center < first.top() || center > first.bottom()) continue;
            var local = heads.stream().filter(head -> head.staffIndex() == staff.index()
                    && head.staffCount() == staff.count()
                    && score.notes().get(head.owner()).measureIndex() >= firstIndex).toList();
            if (local.size() != 2 || !unbound.contains(local.get(0).owner())
                    || !unbound.contains(local.get(1).owner())) continue;
            T a = local.get(0), b = local.get(1);
            var na = score.notes().get(a.owner());
            var nb = score.notes().get(b.owner());
            if (na.measureIndex() != firstIndex || nb.measureIndex() != firstIndex + 1
                    || na.staffStep() == nb.staffStep()
                    || na.clefBottomDiatonic() != nb.clefBottomDiatonic()
                    || !wholeHead(na) || !wholeHead(nb)
                    || a.y() < staff.top() || a.y() > staff.bottom()
                    || b.y() < staff.top() || b.y() > staff.bottom()) continue;
            float gap = staff.gap(), span = b.x() - a.x();
            if (gap < 10 || gap > 32 || span < gap * 3.5f || span > gap * 9) continue;
            int left = Math.round(a.x() - gap * .35f), right = Math.round(b.x() + gap * .35f);
            if (left < 0 || right >= w) continue;
            var track = track(gray, w, h, staff, left, right);
            if (track == null) continue;
            int middle = Math.round((first.right() + last.left()) * .5f * w);
            int ending = Math.round(last.right() * w);
            var divider = bars(gray, w, h, track, middle, gap * .52f, gap * .52f);
            var close = bars(gray, w, h, track, ending, gap, gap * 1.7f);
            if (divider.size() != 1 || close.size() != 2
                    || divider.get(0).right() - divider.get(0).left() + 1 > gap * .32f
                    || close.get(1).left() - close.get(0).right() > gap * 1.15f
                    || divider.get(0).left() <= left + gap || divider.get(0).right() >= right - gap)
                continue;
            var strong = contour(gray, w, h, track, a, b, left, right, divider.get(0), 115);
            var edge = contour(gray, w, h, track, a, b, left, right, divider.get(0), 140);
            if (strong == null || edge == null || !sameTip(strong.start(), edge.start())
                    || !sameTip(strong.end(), edge.end())) continue;
            var binding = PrintedSlurOwners.bindPixelCells("compact-close-" + page + "-" + a.owner(),
                    page, edge, local, gray, w, h, track);
            if (binding.span().isEmpty()) continue;
            if (geometryOnly) {
                candidates.add(binding.span().orElseThrow());
                statuses.merge(binding.status(), 1, Integer::sum);
            } else {
                var resolved = PrintedSlurOwners.resolveBinding(binding,
                        List.of((PrintedSlurOwners.Head) a, (PrintedSlurOwners.Head) b), ties);
                if (resolved.span().isEmpty()) continue;
                spans.add(resolved.span().orElseThrow());
                statuses.merge(resolved.status(), 1, Integer::sum);
            }
        }
        if (spans.size() == captured.source().spans().size()
                && candidates.size() == captured.source().candidates().size()) return captured;
        var source = ScoreSlurSource.capture(spans, candidates,
                List.of(new ScoreSlurPages.Page(page, score.notes().size())));
        return new PrintedSlurPageCapture.Result(source, statuses, false, captured.declined(),
                captured.pageEdges());
    }

    private static boolean sameRow(MeasureRegion a, MeasureRegion b) {
        return Math.abs(a.top() - b.top()) < .001f && Math.abs(a.bottom() - b.bottom()) < .001f;
    }

    private static boolean wholeHead(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.unbeamedDurationBeats() == 4
                && n.beamCount() == 0 && n.stemDirection() == 0 && n.augmentationDots() == 0
                && !n.tiedFromPrevious() && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static StaffPitchTrack track(byte[] gray, int w, int h, PlayingTechniqueDetector.Staff staff,
            int left, int right) {
        float gap = staff.gap();
        float[] a = PrintedDirectionStaff.printedRules(gray, w, h, left, staff.bottom(), gap);
        float[] b = PrintedDirectionStaff.printedRules(gray, w, h, right, staff.bottom(), gap);
        if (a == null || b == null || Math.abs(a[0] - staff.bottom()) > gap * .2f
                || Math.abs(b[0] - a[0]) > gap * .12f
                || Math.abs(a[1] - gap) > gap * .08f || Math.abs(a[1] - b[1]) > gap * .06f)
            return null;
        return StaffPitchTrack.fromVerifiedSamples(
                List.of(new float[]{left, a[0], a[1]}, new float[]{right, b[0], b[1]}));
    }

    private static List<Bar> bars(byte[] gray, int w, int h, StaffPitchTrack track,
            int x, float before, float after) {
        int left = Math.max(0, x - Math.round(before)), right = Math.min(w - 1, x + Math.round(after));
        var bands = new ArrayList<Bar>();
        int start = -1;
        for (int col = left; col <= right + 1; col++) {
            float[] rule = track.at(col);
            int top = Math.round(rule[0] - 4 * rule[1]), bottom = Math.round(rule[0]);
            if (top < 0 || bottom >= h) return List.of();
            int hit = 0;
            if (col <= right)
                for (int y = top; y <= bottom; y++)
                    if ((gray[y * w + col] & 255) < 145) hit++;
            if (hit >= (bottom - top + 1) * .93f) {
                if (start < 0) start = col;
            } else if (start >= 0) {
                if (start == left || col > right || col - start > rule[1] * .8f) return List.of();
                bands.add(new Bar(start, col - 1));
                start = -1;
            }
        }
        return bands;
    }

    private static PrintedSlurContours.Bow contour(byte[] gray, int w, int h, StaffPitchTrack track,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b, int left, int right,
            Bar divider, int threshold) {
        float[] frame = track.at((left + right) * .5f);
        float gap = frame[1];
        int top = Math.round(Math.min(a.y(), b.y()) - 2.75f * gap);
        int bottom = Math.round(Math.max(a.y(), b.y()) - .66f * gap);
        if (top < 1 || bottom >= h - 1) return null;
        boolean[] rails = new boolean[bottom - top + 1];
        for (int y = top; y <= bottom; y++) {
            float rule = (frame[0] - y) / gap;
            if (rule < -.18f || rule > 4.18f || Math.abs(rule - Math.round(rule)) > .18f) continue;
            int hit = 0;
            for (int x = left; x <= right; x++)
                if ((gray[y * w + x] & 255) < 145) hit++;
            rails[y - top] = hit >= (right - left + 1) * .94f;
        }
        var cells = new ArrayList<PrintedSlurContours.ForeignRun>();
        int lastX = -1;
        float lastY = 0;
        for (int x = left; x <= right; x++) {
            if (x >= divider.left() - 1 && x <= divider.right() + 1) continue;
            float headY = a.y() + (b.y() - a.y()) * (x - a.x()) / (b.x() - a.x());
            int first = -1, last = -1;
            for (int y = Math.max(top, Math.round(headY - 2.75f * gap));
                    y <= Math.min(bottom, Math.round(headY - .66f * gap)); y++) {
                if (rails[y - top] || (gray[y * w + x] & 255) >= threshold) continue;
                if (first < 0) first = y;
                last = y;
            }
            if (first < 0) continue;
            if (last - first + 1 > gap * .42f) return null;
            for (int y = first; y <= last; y++)
                if (!rails[y - top] && (gray[y * w + x] & 255) >= threshold) return null;
            float center = (first + last) * .5f;
            if (lastX >= 0 && !supportedJoin(gray, w, rails, top, divider, gap, lastX, lastY, x, center))
                return null;
            cells.add(new PrintedSlurContours.ForeignRun(x,
                    List.of(new PrintedSlurContours.RawBand(first, last))));
            lastX = x;
            lastY = center;
        }
        var bow = PrintedSlurContours.fitPixelCells(cells, track);
        return bow != null && bow.above() && bow.rmsInGaps() <= .08f
                && bow.start().x() < divider.left() && bow.end().x() > divider.right() ? bow : null;
    }

    private static boolean supportedJoin(byte[] gray, int w, boolean[] rails, int top, Bar divider,
            float gap, int from, float a, int to, float b) {
        int dx = to - from;
        if (dx > gap * .48f || Math.abs(b - a) > dx * gap * .24f) return false;
        for (int x = from + 1; x < to; x++) {
            int y = Math.round(a + (b - a) * (x - from) / dx);
            boolean occluded = x >= divider.left() - 1 && x <= divider.right() + 1;
            boolean ink = false;
            for (int offset = -1; offset <= 1; offset++) {
                int row = y + offset;
                if (row < top || row >= top + rails.length) continue;
                occluded |= rails[row - top];
                ink |= (gray[row * w + x] & 255) < 145;
            }
            if (!occluded || !ink) return false;
        }
        return true;
    }

    private static boolean sameTip(PrintedSlurContours.Point a, PrintedSlurContours.Point b) {
        return Math.abs(a.x() - b.x()) <= 2 && Math.abs(a.y() - b.y()) <= 2;
    }
}
