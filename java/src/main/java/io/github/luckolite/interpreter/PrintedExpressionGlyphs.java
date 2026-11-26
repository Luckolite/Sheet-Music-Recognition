// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.io.*;
import java.util.*;

/** Raster proof for isolated breath signs and simple note-equals-note pulse relations. */
final class PrintedExpressionGlyphs {
    private final PortableOrnamentGlyphs pulses = new PortableOrnamentGlyphs();
    private final PortableOrnamentGlyphs breaths = new PortableOrnamentGlyphs();

    record Mark(String text, float left, float top, float right, float bottom) {
        PlayingTechniqueDetector.Word word(int width, int height) {
            return new PlayingTechniqueDetector.Word(
                    text, left / width, top / height, right / width, bottom / height);
        }
    }

    private record Part(int left, int top, int right, int bottom, int ink) {
        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }

        float cy() {
            return (top + bottom) * .5f;
        }

        PortableNoteOrnaments.Bounds bounds() {
            return new PortableNoteOrnaments.Bounds(left, top, right, bottom);
        }
    }

    private record Pulse(Part part, int kind, double value, Part head, Part shaft) {
        Pulse(Part part, int kind, double value) { this(part, kind, value, part, null); }
        boolean owns(Part input) { return input == head || input == shaft; }
    }

    static PrintedExpressionGlyphs load(InputStream stream) throws IOException {
        var result = new PrintedExpressionGlyphs();
        try (var in = new DataInputStream(stream)) {
            if (in.readInt() != 0x45584731) throw new IOException("Not EXG1 expression templates");
            int count = in.readInt();
            if (count < 1 || count > 64) throw new IOException("Invalid template count");
            for (int i = 0; i < count; i++) {
                int kind = in.readInt(), w = in.readInt(), h = in.readInt();
                if (kind < 1 || kind > 14 || w < 1 || h < 1 || w > 256 || h > 256)
                    throw new IOException("Invalid expression template");
                byte[] gray = new byte[w * h];
                try {
                    in.readFully(gray);
                } catch (EOFException error) {
                    throw new EOFException("Truncated expression template");
                }
                (kind >= 13 ? result.breaths : result.pulses).add(gray, w, h, kind, false);
            }
            if (in.read() != -1) throw new IOException("Trailing expression template data");
        }
        return result;
    }

    List<Mark> detect(
            ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray,
            int width,
            int height) {
        var scratch = new PrintedInkComponents();
        var result = new ArrayList<>(detect(score, staffs, gray, width, height, 175, true, scratch));
        for (int threshold : new int[] {155, 195}) {
            for (var candidate : detect(score, staffs, gray, width, height, threshold, false, scratch)) {
                if (MetricModulationText.parse(candidate.text()).isEmpty()) continue;
                boolean overlap = false;
                for (var old : result)
                    if (old.left < candidate.right
                            && candidate.left < old.right
                            && old.top < candidate.bottom
                            && candidate.top < old.bottom) overlap = true;
                if (!overlap) result.add(candidate);
            }
        }
        return List.copyOf(result);
    }

    private List<Mark> detect(
            ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray,
            int width,
            int height,
            int threshold,
            boolean detectBreaths,
            PrintedInkComponents scratch) {
        if (gray == null || width < 1 || height < 1 || gray.length != (long) width * height)
            return List.of();
        var result = new ArrayList<Mark>();
        var seen = new HashSet<String>();
        for (var staff : staffs) {
            float gap = staff.gap();
            if (!Float.isFinite(gap) || gap < 6 || gap > Math.min(width, height) / 8f) continue;
            // Directions are outside the five rails. Bound work to each written system's width.
            for (boolean above : new boolean[] {true, false}) {
                int top =
                        Math.max(
                                0,
                                (int) (above ? staff.top() - gap * 7 : staff.bottom() + gap * .5f));
                int bottom =
                        Math.min(
                                height,
                                (int) (above ? staff.top() - gap * .5f : staff.bottom() + gap * 5));
                if (bottom <= top) continue;
                float center = (staff.top() + staff.bottom()) * .5f / height;
                float left = 1, right = 0;
                for (var m : score.measures())
                    if (center >= m.top() - gap / height && center <= m.bottom() + gap / height) {
                        left = Math.min(left, m.left());
                        right = Math.max(right, m.right());
                    }
                int x0 = Math.max(0, (int) (left * width - gap * 2)),
                        x1 = Math.min(width, (int) (right * width + gap * 2));
                if (x1 <= x0) continue;
                // A metric equation may span the barline and its courtesy meter at the system end.
                int metricRight = above ? Math.min(width, (int) (right * width + gap * 5)) : x1;
                var parts = new ArrayList<Part>();
                scratch.scan(gray, width, x0, top, metricRight, bottom, threshold, (l, t, r, b, count) -> {
                    if (count >= 3) parts.add(new Part(l, t, r, b, count));
                    return true;
                });
                if (above && detectBreaths) {
                    for (var measure : score.measures()) {
                        if (center < measure.top() || center > measure.bottom()) continue;
                        var caesura = caesuraAt(gray, width, height, staff, measure);
                        if (caesura != null && seen.add("caesura:" + caesura.left + ":" + caesura.top))
                            result.add(caesura);
                    }
                    for (var part : parts) {
                        if (part.right > x1
                                || part.height() < gap * .6f
                                || part.height() > gap * 1.9f
                                || part.width() > gap * 1.9f) continue;
                        var match = breaths.templateMatch(gray, width, part.bounds());
                        if (match.kind() < 13 || match.score() < .61f) continue;
                        // A comma embedded in a text line is punctuation, not a pause.
                        if (parts.stream()
                                .anyMatch(
                                        p ->
                                                p != part
                                                        && p.height() > gap * .28f
                                                        && Math.abs(p.cy() - part.cy()) < gap * .8f
                                                        && Math.min(
                                                                        Math.abs(p.left - part.right),
                                                                        Math.abs(part.left - p.right))
                                                                < gap * .85f)) continue;
                        if (PrintedDirectionStaff.at(
                                        List.of(staff),
                                        score.measures(),
                                        score.notes(),
                                        gray,
                                        width,
                                        height,
                                        part.left,
                                        part.top,
                                        part.bottom)
                                == null) continue;
                        String key = "breath:" + part.left + ":" + part.top;
                        if (seen.add(key))
                            result.add(
                                    new Mark(
                                            match.kind() == 14 ? "breath tick" : "breath comma",
                                            part.left,
                                            part.top,
                                            part.right,
                                            part.bottom));
                    }
                }
                var bands =
                        parts.stream()
                                .filter(
                                        p ->
                                                p.width() > gap * .35f
                                                        && p.width() < gap * 1.5f
                                                        && p.height() < gap * .3f
                                                        && p.width() >= p.height() * 2.5f)
                                .toList();
                List<Pulse> notes = null;
                for (int i = 0; i < bands.size(); i++)
                    for (int j = i + 1; j < bands.size(); j++) {
                        var a = bands.get(i);
                        var b = bands.get(j);
                        if (!equalsBands(a, b, gap)) continue;
                        if (notes == null) notes = pulseNotes(parts, gray, width, gap, threshold);
                        int el = Math.min(a.left, b.left), er = Math.max(a.right, b.right);
                        float ey = (a.cy() + b.cy()) * .5f;
                        Pulse l = null, r = null;
                        boolean ambiguous = false;
                        for (var note : notes) {
                            var part = note.part;
                            if (Math.abs(part.cy() - ey) >= gap * 1.8f) continue;
                            if (part.right < el && el - part.right < gap * 2) {
                                if (l != null) {
                                    ambiguous = true;
                                    break;
                                }
                                l = note;
                            } else if (part.left > er && part.left - er < gap * 2) {
                                if (r != null) {
                                    ambiguous = true;
                                    break;
                                }
                                r = note;
                            }
                        }
                        if (ambiguous || l == null || r == null) continue;
                        if (l.value != 4
                                && r.value != 4
                                && Math.abs(l.part.height() - r.part.height()) > gap * 1.4f)
                            continue;
                        int[] ld = dots(l, parts, gap, el);
                        if (ld == null) continue;
                        int[] rd = dots(r, parts, gap, metricRight);
                        if (rd == null) continue;
                        int y0 = Math.min(l.part.top, r.part.top),
                                y1 = Math.max(l.part.bottom, r.part.bottom);
                        if (PrintedDirectionStaff.at(
                                                List.of(staff),
                                                score.measures(),
                                                score.notes(),
                                                gray,
                                                width,
                                                height,
                                                l.part.left,
                                                y0,
                                                y1)
                                        == null
                                && PrintedDirectionStaff.at(
                                                List.of(staff),
                                                score.measures(),
                                                score.notes(),
                                                gray,
                                                width,
                                                height,
                                                r.part.right,
                                                y0,
                                                y1)
                                        == null
                                && !metricStaffRails(staff, gray, width, height, l.part.left, y0, y1)
                                && !metricStaffRails(staff, gray, width, height, r.part.right, y0, y1)) continue;
                        // Every component inside the equation must be a note, equals stroke or dot.
                        boolean extra = false;
                        for (var p : parts)
                            if (p.left >= l.part.left
                                    && p.right <= rd[1]
                                    && p.top >= y0 - gap * .2f
                                    && p.bottom <= y1 + gap * .2f
                                    && !l.owns(p)
                                    && !r.owns(p)
                                    && p != a
                                    && p != b
                                    && !dot(p, l, gap)
                                    && !dot(p, r, gap)) {
                                extra = true;
                                break;
                            }
                        if (extra) continue;
                        String key = "metric:" + el + ":" + Math.round(ey);
                        if (seen.add(key))
                            result.add(
                                    new Mark(
                                            name(l.value, ld[0]) + " = " + name(r.value, rd[0]),
                                            l.part.left,
                                            y0,
                                            rd[1],
                                            y1));
                    }
            }
        }
        return List.copyOf(result);
    }

    private static String name(double value, int dots) {
        String base =
                value == 4
                        ? "whole"
                        : value == 2
                                ? "half"
                                : value == 1
                                        ? "quarter"
                                        : value == .5
                                                ? "eighth"
                                                : value == .25 ? "sixteenth" : "32nd";
        return (dots == 2 ? "double dotted " : dots == 1 ? "dotted " : "") + base;
    }

    private static boolean dot(Part p, Pulse n, float gap) {
        var b = n.part;
        float headY = (n.kind & 1) == 1 ? b.bottom - gap * .35f : b.top + gap * .35f;
        return p.left > b.right
                && p.left - b.right < gap * 1.15f
                && p.width() >= 2
                && p.height() >= 2
                && p.width() < gap * .55f
                && p.height() < gap * .55f
                && Math.abs(p.cy() - headY) < gap * .55f
                && p.ink >= p.width() * p.height() * .45f;
    }

    private static int[] dots(Pulse n, List<Part> parts, float gap, int end) {
        Part first = null, second = null;
        for (var part : parts) {
            if (part.right >= end || !dot(part, n, gap)) continue;
            if (second != null) return null;
            if (first == null) {
                first = part;
            } else if (part.left < first.left) {
                second = first;
                first = part;
            } else {
                second = part;
            }
        }
        if (first == null) return new int[] {0, n.part.right};
        if (second == null) return new int[] {1, first.right};
        if (Math.abs(first.cy() - second.cy()) > gap * .2f) return null;
        return new int[] {2, second.right};
    }

    private List<Pulse> pulseNotes(List<Part> parts, byte[] gray, int width, float gap, int threshold) {
        var notes = new ArrayList<Pulse>();
        for (var part : parts) {
            if (part.height() >= gap * .65f
                    && part.height() <= gap * 4.2f
                    && part.width() <= gap * 2.5f) {
                var match = pulses.templateMatch(gray, width, part.bounds());
                boolean supportedQuarter = (match.kind() == 5 || match.kind() == 6)
                        && match.score() >= .52f && match.margin() >= .16f
                        && plainQuarterInk(part, gray, width, gap, threshold, match.kind() == 5);
                if (match.kind() > 0 && (match.score() >= .66f && match.margin() >= .018f
                        || supportedQuarter)) {
                    double value = Math.scalb(4, -((match.kind() - 1) / 2));
                    notes.add(new Pulse(part, match.kind(), value));
                }
            }
        }
        for (Part shaft : parts) {
            if (shaft.width() > gap * .24f || shaft.height() < gap * .8f || shaft.height() > gap * 2.6f)
                continue;
            for (Part head : parts) {
                if (head.width() < gap * .48f || head.width() > gap * 1.35f
                        || head.height() < gap * .35f || head.height() > gap * 1.02f) continue;
                boolean upward = shaft.bottom <= head.top;
                int clear = upward ? head.top - shaft.bottom : shaft.top - head.bottom;
                if (clear < 0 || clear > gap * .24f
                        || (upward ? Math.abs(shaft.right - head.right) : Math.abs(shaft.left - head.left))
                                > gap * .23f) continue;
                var joined = new Part(Math.min(head.left, shaft.left), Math.min(head.top, shaft.top),
                        Math.max(head.right, shaft.right), Math.max(head.bottom, shaft.bottom), head.ink + shaft.ink);
                if (!plainQuarterInk(joined, gray, width, gap, threshold, upward)) continue;
                var match = pulses.templateMatch(gray, width, joined.bounds());
                int kind = upward ? 5 : 6;
                if (match.kind() == kind && match.score() >= .49f && match.margin() >= .14f)
                    notes.add(new Pulse(joined, kind, 1, head, shaft));
            }
        }
        return notes;
    }

    private static boolean plainQuarterInk(
            Part part, byte[] gray, int width, float gap, int threshold, boolean upward) {
        int h = part.height(), w = part.width();
        if (h < gap * 1.65f || h > gap * 3.6f || w < gap * .48f || w > gap * 1.35f) return false;
        int headDepth = Math.round(gap * .85f);
        int shaftWidth = Math.max(2, Math.round(gap * .26f));
        int leftBound = upward ? part.right - Math.round(w * .42f) : part.left;
        int rightBound = upward ? part.right : part.left + Math.round(w * .42f);
        int rows = 0, valid = 0, spread = 0;
        for (int offset = 1; offset < h - headDepth; offset++) {
            int y = upward ? part.top + offset : part.bottom - 1 - offset;
            int left = part.right, right = -1;
            for (int x = part.left; x < part.right; x++)
                if ((gray[y * width + x] & 255) < threshold) {
                    left = Math.min(left, x);
                    right = x;
                }
            rows++;
            if (right < left) continue;
            if (right - left + 1 > shaftWidth) spread++;
            else if (left >= leftBound && right <= rightBound) valid++;
        }
        if (valid < rows * .86f || spread > Math.max(1, Math.round(gap * .12f))) return false;
        int headInk = 0, broadRows = 0;
        for (int offset = 0; offset < headDepth; offset++) {
            int y = upward ? part.bottom - 1 - offset : part.top + offset;
            int ink = 0;
            for (int x = part.left; x < part.right; x++)
                if ((gray[y * width + x] & 255) < threshold) ink++;
            headInk += ink;
            if (ink >= w * .65f) broadRows++;
        }
        return headInk >= w * headDepth * .42f && broadRows >= gap * .30f;
    }

    private record SlashRow(float left, float right, int y) {}

    /** Two matching diagonal strokes cross the top rule before a closing barline. */
    static Mark caesuraAt(
            byte[] gray, int width, int height, PlayingTechniqueDetector.Staff staff, MeasureRegion measure) {
        float gap = staff.gap(), top = staff.top();
        int edge = Math.round(measure.right() * width);
        int left = Math.max(Math.round(measure.left() * width), edge - Math.round(gap * 4.6f));
        int right = Math.min(width, edge - Math.round(gap * .28f));
        int first = Math.round(top - gap * .72f), last = Math.round(top + gap * .8f);
        if (left < 0 || first < 0 || last >= height || right - left < gap * 1.65f) return null;
        var rows = new ArrayList<SlashRow>();
        int expected = 0;
        for (int y = first; y <= last; y++) {
            if (Math.abs(y - top) < gap * .22f) continue;
            expected++;
            var row = slashRow(gray, width, left, right, y, gap);
            if (row != null) rows.add(row);
        }
        if (rows.size() < expected * .9f || rows.size() < 8) return null;
        var a = rows.get(0);
        var b = rows.get(rows.size() - 1);
        float span = b.y - a.y;
        if (span < gap * 1.33f) return null;
        float leftSlope = (a.left - b.left) / span, rightSlope = (a.right - b.right) / span;
        if (leftSlope < .33f || leftSlope > .75f || rightSlope < .33f || rightSlope > .75f
                || Math.abs(leftSlope - rightSlope) > .1f) return null;
        for (var row : rows)
            if (Math.abs(row.left - (a.left - leftSlope * (row.y - a.y))) > gap * .12f
                    || Math.abs(row.right - (a.right - rightSlope * (row.y - a.y))) > gap * .12f)
                return null;
        int x = Math.round((a.left + b.right) * .5f);
        if (!caesuraStaffRails(staff, gray, width, height, x, first, last)) return null;
        return new Mark("caesura", b.left - gap * .16f, first, a.right + gap * .16f, last + 1);
    }

    private static SlashRow slashRow(
            byte[] gray, int width, int left, int right, int y, float gap) {
        float first = -1, second = -1;
        for (int x = left; x < right;) {
            if ((gray[y * width + x] & 255) >= 151) {
                x++;
                continue;
            }
            int start = x++;
            while (x < right && (gray[y * width + x] & 255) < 151) x++;
            int thickness = x - start;
            if (thickness < gap * .11f) continue;
            if (start == left || x == right || thickness > gap * .45f || second >= 0) return null;
            if (first < 0) first = (start + x - 1) * .5f;
            else second = (start + x - 1) * .5f;
        }
        return second - first >= gap * .4f && second - first <= gap * .8f
                ? new SlashRow(first, second, y) : null;
    }

    static boolean caesuraStaffRails(
            PlayingTechniqueDetector.Staff staff,
            byte[] gray, int width, int height, int x, int top, int bottom) {
        float gap = staff.gap();
        if (top < staff.top() - gap * .9f || top > staff.top() - gap * .53f
                || bottom < staff.top() + gap * .63f || bottom > staff.top() + gap) return false;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, x, staff.bottom(), gap);
        return rails != null && Math.abs(rails[0] - staff.bottom()) < gap * .45f
                && Math.abs(rails[1] - gap) < gap * .16f;
    }

    static boolean metricStaffRails(
            PlayingTechniqueDetector.Staff staff,
            byte[] gray, int width, int height, int x, int top, int bottom) {
        float gap = staff.gap();
        if (top < staff.top() - gap * 7 || bottom > staff.top() - gap * .35f) return false;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, x, staff.bottom(), gap);
        return rails != null && Math.abs(rails[0] - staff.bottom()) < gap * .5f
                && Math.abs(rails[1] - gap) < gap * .18f;
    }

    private static boolean equalsBands(Part a, Part b, float gap) {
        float separation = Math.abs(a.cy() - b.cy());
        return a.width() > gap * .35f
                && b.width() > gap * .35f
                && a.width() < gap * 1.5f
                && b.width() < gap * 1.5f
                && a.width() >= a.height() * 2.5f
                && b.width() >= b.height() * 2.5f
                && a.height() < gap * .3f
                && b.height() < gap * .3f
                && separation > gap * .15f
                && separation < gap * .85f
                && Math.min(a.right, b.right) - Math.max(a.left, b.left)
                        >= Math.max(a.width(), b.width()) * .8f;
    }

}
