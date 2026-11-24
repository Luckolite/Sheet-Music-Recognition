// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Retains a printed bow-position instruction with independently witnessed words and solid stroke. */
final class OpeningBowPositionDirections {
    private static final Pattern CAPTION = Pattern.compile("Fr?\\.\\s*[-–—]\\s*M");
    private static final String ABOVE = "ABOVE";
    private static final int MAX_CAPTIONS = 16;

    record Direction(int noteIndex, String text, String placement) {}
    record Connector(int startNoteIndex, int endNoteIndex, String placement, String style) {}
    record Capture(List<Direction> directions, List<Connector> connectors) {}
    private static final Capture EMPTY = new Capture(List.of(), List.of());

    private record Box(int l, int t, int r, int b, int ink) {
        int width() { return r - l; }
        int height() { return b - t; }
        double x() { return (l + r - 1) * .5; }
        double y() { return (t + b - 1) * .5; }
        boolean same(Box other) {
            return Math.abs(l - other.l) <= 1 && Math.abs(t - other.t) <= 1
                    && Math.abs(r - other.r) <= 1 && Math.abs(b - other.b) <= 1;
        }
    }

    private record Shape(Box first, Box dot, Box line, Box last) {
        boolean same(Shape other) {
            return other != null && first.same(other.first) && dot.same(other.dot)
                    && line.same(other.line) && last.same(other.last);
        }
    }

    private record Attack(int owner, double x, double beat) {}
    private record Owners(int first, int last) {}

    private OpeningBowPositionDirections() {}

    static Capture capture(byte[] raw, byte[] normalized, int width, int height,
            List<SheetInterpreter.Word> words, ScorePageInterpretation score, List<Map<String, Object>> events) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length || words.size() > 2048 || score.measures().size() > 256
                || events.size() > 8192) return EMPTY;
        var captions = words.stream().filter(w -> CAPTION.matcher(w.text().strip()).matches()).toList();
        if (captions.isEmpty() || captions.size() > MAX_CAPTIONS) return EMPTY;
        var directions = new ArrayList<Direction>();
        var connectors = new ArrayList<Connector>();
        var used = new HashSet<Integer>();
        for (var caption : captions) {
            var word = new Box(Math.round(caption.left() * width), Math.round(caption.top() * height),
                    Math.round(caption.right() * width), Math.round(caption.bottom() * height), 0);
            if (word.l() < 0 || word.t() < 0 || word.r() > width || word.b() > height) continue;
            Owners found = null;
            for (int i = 0; i < score.measures().size(); i++) {
                var measure = score.measures().get(i);
                double gap = (measure.bottom() - measure.top()) * height / 8;
                if (gap < 10 || gap > 29 || word.width() < 6 * gap || word.width() > 14 * gap
                        || word.height() < gap || word.height() > 3.2 * gap
                        || word.l() < measure.left() * width - gap
                        || word.r() > measure.right() * width + gap
                        || score.measures().stream().anyMatch(m ->
                        Math.abs(m.top() - measure.top()) < .013 && m.left() < measure.left() - .015)) continue;
                int expectedTop = Math.round(measure.top() * height + 2 * (float) gap);
                if (expectedTop - word.y() < gap * .7 || expectedTop - word.y() > gap * 4.4) continue;
                int rawStaff = staffTop(raw, width, height, word, gap, expectedTop);
                int normStaff = staffTop(normalized, width, height, word, gap, expectedTop);
                if (rawStaff < 0 || normStaff < 0 || Math.abs(rawStaff - normStaff) > 2) continue;
                Shape a = shape(raw, width, height, word, gap, rawStaff);
                Shape b = shape(normalized, width, height, word, gap, normStaff);
                if (a == null || !a.same(b)) continue;
                Owners owners = bind(a, i, score, events, width, gap);
                if (owners == null) continue;
                if (found != null && !found.equals(owners)) {
                    found = null;
                    break;
                }
                found = owners;
            }
            if (found == null || used.contains(found.first()) || used.contains(found.last())) continue;
            used.add(found.first());
            used.add(found.last());
            directions.add(new Direction(found.first(), "Fr.", ABOVE));
            directions.add(new Direction(found.last(), "M", ABOVE));
            connectors.add(new Connector(found.first(), found.last(), ABOVE, "SOLID"));
        }
        return new Capture(List.copyOf(directions), List.copyOf(connectors));
    }

    private static Owners bind(Shape shape, int measure, ScorePageInterpretation score,
            List<Map<String, Object>> events, int width, double gap) {
        var attacks = new ArrayList<Attack>();
        var seen = new HashSet<Integer>();
        var collisions = new HashSet<Integer>();
        for (var event : events) {
            if (!(event.get("measureIndex") instanceof Integer bar) || bar != measure
                    || !(event.get("sourceNoteIndex") instanceof Integer index) || index < 0
                    || index >= score.notes().size() || !(event.get("x") instanceof Number x)
                    || !(event.get("startBeat") instanceof Number beat) || !Double.isFinite(x.doubleValue())
                    || !Double.isFinite(beat.doubleValue()) || x.doubleValue() < 0 || x.doubleValue() >= width)
                continue;
            var n = score.notes().get(index);
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1
                    || n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.openStringTouch() != null
                    || (n.articulations() & NoteOrnament.GRACE) != 0
                    || Boolean.TRUE.equals(event.get("tiedFromPrevious"))) continue;
            if (!seen.add(index)) collisions.add(index);
            attacks.add(new Attack(index, x.doubleValue(), beat.doubleValue()));
        }
        Attack first = owner(attacks, shape.first().x(), gap * .82, gap);
        Attack last = owner(attacks, shape.last().x(), gap * .57, gap);
        if (first == null || last == null || first.beat() >= last.beat() || first.x() >= last.x()
                || collisions.contains(first.owner()) || collisions.contains(last.owner())
                || attacks.stream().anyMatch(a -> a.owner() != first.owner() && a.beat() <= first.beat()))
            return null;
        return new Owners(first.owner(), last.owner());
    }

    private static Attack owner(List<Attack> attacks, double x, double reach, double gap) {
        Attack closest = null;
        double best = Double.POSITIVE_INFINITY, other = Double.POSITIVE_INFINITY;
        for (var candidate : attacks) {
            double distance = Math.abs(candidate.x() - x);
            if (distance < best) {
                closest = candidate;
                other = best;
                best = distance;
            } else other = Math.min(other, distance);
        }
        return best <= reach && other - best >= gap * .42 ? closest : null;
    }

    private static int staffTop(byte[] raster, int width, int height, Box word, double gap, int expected) {
        int l1 = word.l() - round(gap * .9), r1 = word.l() + round(gap * .72);
        int l2 = word.r() + round(gap * .56), r2 = word.r() + round(gap * 2.37);
        if (l1 < 0 || r2 >= width || expected < gap || expected + gap * 5 >= height) return -1;
        int result = -1;
        double best = 0;
        for (int top = expected - round(gap * .61); top <= expected + round(gap * .61); top++) {
            double score = 0;
            for (int i = 0; i < 5; i++) {
                int nominal = top + round(i * gap);
                double row = 0;
                for (int y = nominal - 1; y <= nominal + 1; y++)
                    row = Math.max(row, Math.min(ink(raster, width, y, l1, r1), ink(raster, width, y, l2, r2)));
                if (row < .58) {
                    score = 0;
                    break;
                }
                score += row;
            }
            if (score > best || score == best && score > 0 && Math.abs(top - expected) < Math.abs(result - expected)) {
                best = score;
                result = top;
            }
        }
        return result;
    }

    private static double ink(byte[] raster, int stride, int y, int left, int right) {
        int dark = 0;
        for (int x = left; x < right; x++) if ((raster[y * stride + x] & 255) <= 160) dark++;
        return dark / (double) (right - left);
    }

    private static Shape shape(byte[] raster, int width, int height, Box word, double gap, int staffTop) {
        int bottom = Math.min(word.b(), staffTop - round(.82 * gap));
        if (bottom <= word.t() || (long) word.width() * (bottom - word.t()) > 32_000) return null;
        var graph = RawNumeralComponents.capture(
                raster, width, height, word.l(), word.t(), word.r() - 1, bottom - 1, 156, () -> {}, () -> {});
        if (graph.components().size() > 48) return null;
        var pieces = new ArrayList<Box>();
        for (var c : graph.components())
            if (c.size() >= 3) pieces.add(new Box(c.left, c.top, c.right + 1, c.bottom + 1, c.size()));
        Shape result = null;
        for (var line : pieces) {
            if (line.width() < gap * 1.45 || line.width() > gap * 4 || line.height() > gap * .38
                    || line.width() < 6 * line.height() || line.ink() < line.width() * 2.5) continue;
            for (var first : pieces) {
                if (!lineLevel(first, line, gap) || line.l() - first.r() < gap * .7
                        || line.l() - first.r() > gap * 2.2 || first.width() < gap * 1.5
                        || first.width() > gap * 2.65 || !leftText(raster, width, first)) continue;
                Box dot = null;
                for (var piece : pieces) {
                    if (piece.height() > gap * .30 || piece.width() > gap * .44
                            || piece.l() < first.r() - gap * .30 || piece.l() > first.r() + gap * .41
                            || Math.abs(piece.b() - first.b()) > gap * .28) continue;
                    if (dot != null) return null;
                    dot = piece;
                }
                if (dot == null) continue;
                for (var last : pieces) {
                    if (!lineLevel(last, line, gap) || last.l() - line.r() < gap * .7
                            || last.l() - line.r() > gap * 2.2 || last.width() < gap * 1.08
                            || last.width() > gap * 2.25 || Math.abs(first.t() - last.t()) > gap * .3
                            || Math.abs(first.b() - last.b()) > gap * .3 || !rightText(raster, width, last))
                        continue;
                    if (result != null) return null;
                    result = new Shape(first, dot, line, last);
                }
            }
        }
        return result;
    }

    private static boolean lineLevel(Box glyph, Box line, double gap) {
        return glyph.height() >= gap * .92 && glyph.height() <= gap * 1.8
                && line.y() >= glyph.t() + glyph.height() * .48 && line.y() <= glyph.t() + glyph.height() * .87;
    }

    private static boolean leftText(byte[] image, int stride, Box first) {
        return support(image, stride, first, .00, .55, .00, .18) > .47
                && support(image, stride, first, .72, 1.0, .00, .27) < .12
                && support(image, stride, first, .07, .34, .64, .92) > .42
                && support(image, stride, first, .62, .91, .64, .92) > .30
                && support(image, stride, first, .38, .60, .68, .94) < .13;
    }

    private static boolean rightText(byte[] image, int stride, Box last) {
        return support(image, stride, last, .52, .85, .03, .19) > .28
                && support(image, stride, last, .40, .61, .00, .26) < .20
                && support(image, stride, last, .10, .37, .03, .21) > .41
                && support(image, stride, last, .42, .60, .53, .73) > .45
                && support(image, stride, last, .02, .27, .73, .99) > .24
                && support(image, stride, last, .71, .96, .73, .99) > .27;
    }

    private static double support(byte[] raster, int stride, Box box, double x0, double x1, double y0, double y1) {
        int l = box.l() + round(box.width() * x0), r = box.l() + round(box.width() * x1);
        int t = box.t() + round(box.height() * y0), b = box.t() + round(box.height() * y1);
        if (l >= r || t >= b) return 0;
        int count = 0;
        for (int y = t; y < b; y++)
            for (int x = l; x < r; x++)
                if ((raster[y * stride + x] & 255) <= 100) count++;
        return count / (double) ((b - t) * (r - l));
    }

    private static int round(double value) { return (int) Math.round(value); }
}
