// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Literal Roman string continuations that interrupt and then restore a printed upper staff rule. */
final class PrintedStringContinuations {
    private static final int STRONG_RULE = 122;
    private static final int FAINT_DASH = 225;

    record Mark(int startNoteIndex, int stopBeforeNoteIndex, String text, int number,
                String placement, String style, double endRelativeX) {}

    private record Word(int left, int top, int right, int bottom) {
        double x() { return (left + right) * .5; }
        double y() { return (top + bottom) * .5; }
    }

    private record Stroke(int left, int right) {
        int width() { return right - left; }
    }

    private record Trace(List<Stroke> dashes, int restoration, double gap) {
        int right() { return dashes.get(dashes.size() - 1).right(); }

        boolean corroborates(Trace other) {
            return other != null && Math.abs(gap - other.gap) <= .5
                    && Math.abs(restoration - other.restoration) <= 1 && dashes.equals(other.dashes);
        }
    }

    private record Head(int owner, int measure, double x, double beat) {}

    private PrintedStringContinuations() {}

    static List<Mark> capture(
            byte[] raw, byte[] normalized, int width, int height, List<SheetInterpreter.Word> words,
            List<PrintedRomanStrings.Mark> openingMarks, ScorePageInterpretation score,
            List<Map<String, Object>> events) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length || words.size() > 2048 || openingMarks.size() > 32
                || score.measures().size() > 256 || events.size() > 8192) return List.of();

        var heads = readHeads(score, events, width);
        var result = new ArrayList<Mark>();
        for (var mark : openingMarks) {
            if (!mark.placement().equals("ABOVE") || mark.noteIndex() < 0
                    || mark.noteIndex() >= score.notes().size()) continue;
            Head first = uniqueHead(heads, mark.noteIndex());
            if (first == null) continue;
            var frame = score.measures().get(first.measure());
            double approximateGap = (frame.bottom() - frame.top()) * height / 8;
            if (approximateGap < 10 || approximateGap > 29) continue;
            Word word = matchedWord(words, mark.literal(), width, height, first.x(), approximateGap, frame);
            if (word == null) continue;
            Trace original = trace(raw, width, height, word, approximateGap);
            if (original == null || !original.corroborates(trace(normalized, width, height, word, approximateGap)))
                continue;
            Head stop = stopBefore(heads, first, original);
            if (stop == null) continue;
            double endRelativeX = Math.round(1000 * (original.right() - stop.x()) / original.gap()) / 100.0;
            result.add(new Mark(first.owner(), stop.owner(), mark.literal(), mark.number(),
                    mark.placement(), "DASHED", endRelativeX));
        }
        return List.copyOf(result);
    }

    private static List<Head> readHeads(ScorePageInterpretation score, List<Map<String, Object>> events, int width) {
        var heads = new ArrayList<Head>();
        var seen = new HashSet<Integer>();
        var repeated = new HashSet<Integer>();
        for (var event : events) {
            if (!(event.get("sourceNoteIndex") instanceof Integer index) || index < 0 || index >= score.notes().size()
                    || !(event.get("measureIndex") instanceof Integer measure) || measure < 0
                    || measure >= score.measures().size() || !(event.get("x") instanceof Number x)
                    || !(event.get("startBeat") instanceof Number beat) || !Double.isFinite(x.doubleValue())
                    || !Double.isFinite(beat.doubleValue()) || x.doubleValue() < 0 || x.doubleValue() >= width)
                continue;
            var note = score.notes().get(index);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.measureIndex() != measure || note.staffCount() != 1
                    || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || (note.articulations() & NoteOrnament.GRACE) != 0
                    || Boolean.TRUE.equals(event.get("tiedFromPrevious"))) continue;
            if (!seen.add(index)) repeated.add(index);
            heads.add(new Head(index, measure, x.doubleValue(), beat.doubleValue()));
        }
        if (!repeated.isEmpty()) heads.removeIf(h -> repeated.contains(h.owner()));
        return heads;
    }

    private static Head uniqueHead(List<Head> heads, int owner) {
        for (var head : heads) {
            if (head.owner() == owner) return head;
        }
        return null;
    }

    private static Word matchedWord(List<SheetInterpreter.Word> words, String label, int width, int height,
            double headX, double gap, MeasureRegion frame) {
        Word result = null;
        double expected = frame.top() * height + 2 * gap;
        for (var candidate : words) {
            if (!candidate.text().strip().equals(label)) continue;
            var word = new Word(Math.round(candidate.left() * width), Math.round(candidate.top() * height),
                    Math.round(candidate.right() * width), Math.round(candidate.bottom() * height));
            double w = word.right() - word.left(), h = word.bottom() - word.top();
            if (Math.abs(headX - word.x()) > .8 * gap || Math.abs(expected - word.y()) > .8 * gap
                    || w < gap * .65 || w > gap * 4.2 || h < gap * 1.2 || h > gap * 2.75)
                continue;
            if (result != null) return null;
            result = word;
        }
        return result;
    }

    private static Trace trace(byte[] image, int width, int height, Word word, double gap) {
        int[] rules = rules(image, width, height, word, gap);
        if (rules == null) return null;
        double measured = (rules[4] - rules[0]) / 4.0;
        int top = rules[0];
        if (Math.abs(top - word.y()) > gap * .38) return null;
        for (int i = 1; i < rules.length; i++)
            if (Math.abs(rules[i] - rules[i-1] - measured) > measured * .20) return null;

        int start = word.right() - round(.18 * gap), end = word.right() + round(5 * gap);
        int[] darkest = new int[end - start];
        for (int x = start; x < end; x++) {
            int value = 255;
            for (int y = top - 2; y <= top + 2; y++) value = Math.min(value, image[y * width + x] & 255);
            darkest[x - start] = value;
        }
        var solid = strokes(darkest, 0, darkest.length, STRONG_RULE - 1);
        if (solid.size() < 2 || solid.get(0).left() != 0) return null;
        int before = solid.get(0).right();
        int after = -1;
        for (int i = 1; i < solid.size(); i++) {
            if (solid.get(i).width() > gap * 1.1) {
                after = solid.get(i).left();
                break;
            }
        }
        if (after - before < gap * 1.45 || after - before > gap * 3.7) return null;
        for (int x = before; x < after; x++) if (darkest[x] < 135) return null;

        var dashes = new ArrayList<Stroke>();
        int shortestPitch = Integer.MAX_VALUE, longestPitch = 0;
        for (var stroke : strokes(darkest, before, after, FAINT_DASH)) {
            if (stroke.left() == before || stroke.right() == after) continue;
            if (stroke.width() < measured * .19 || stroke.width() > measured * .55) return null;
            if (!dashes.isEmpty()) {
                int pitch = stroke.left() + start - dashes.get(dashes.size() - 1).left();
                if (pitch < measured * .45 || pitch > measured * .95) return null;
                shortestPitch = Math.min(shortestPitch, pitch);
                longestPitch = Math.max(longestPitch, pitch);
            }
            dashes.add(new Stroke(stroke.left() + start, stroke.right() + start));
        }
        return dashes.size() >= 3 && dashes.size() <= 5 && longestPitch - shortestPitch <= measured * .20
                ? new Trace(List.copyOf(dashes), after + start, measured) : null;
    }

    private static List<Stroke> strokes(int[] values, int left, int right, int maximum) {
        var result = new ArrayList<Stroke>();
        for (int x = left; x < right;) {
            if (values[x] > maximum) {
                x++;
                continue;
            }
            int start = x++;
            while (x < right && values[x] <= maximum) x++;
            result.add(new Stroke(start, x));
        }
        return result;
    }

    private static int[] rules(byte[] image, int width, int height, Word word, double gap) {
        int left = word.left() - round(2.2 * gap), leftStop = word.left() - round(.35 * gap);
        int rightStart = word.right() + round(3 * gap), right = word.right() + round(6.5 * gap);
        int first = round(word.y() - gap), last = round(word.bottom() + gap * 1.5);
        if (left < 0 || right >= width || first < 3 || last + round(4 * gap) + 3 >= height) return null;
        int[] result = null;
        double best = 0;
        int nearest = Integer.MAX_VALUE;
        for (int top = first; top < last; top++) {
            var candidate = new int[5];
            double support = 0;
            for (int line = 0; line < candidate.length; line++) {
                int expected = top + round(line * gap);
                double ink = 0;
                for (int y = expected - 2; y <= expected + 2; y++) {
                    double trial = Math.min(darkShare(image, width, y, left, leftStop),
                            darkShare(image, width, y, rightStart, right));
                    if (trial > ink || trial == ink && Math.abs(y - expected) < Math.abs(candidate[line] - expected)) {
                        ink = trial;
                        candidate[line] = y;
                    }
                }
                if (ink < .63) {
                    support = 0;
                    break;
                }
                support += ink;
            }
            int distance = Math.abs(top - word.bottom());
            if (support > best || support == best && support > 0 && distance < nearest) {
                best = support;
                nearest = distance;
                result = candidate;
            }
        }
        return result;
    }

    private static double darkShare(byte[] image, int stride, int y, int left, int right) {
        int dark = 0;
        for (int x = left; x < right; x++) if ((image[y * stride + x] & 255) <= 160) dark++;
        return dark / (double) (right - left);
    }

    private static Head stopBefore(List<Head> heads, Head first, Trace proof) {
        Head stop = null;
        int internal = 0;
        double gap = proof.gap();
        for (var head : heads) {
            if (head.measure() != first.measure() || head.owner() == first.owner()) continue;
            if (head.beat() <= first.beat() || head.x() < first.x() + gap * .75) return null;
            if (head.x() <= proof.right() && head.x() >= proof.dashes().get(0).left() - gap * .35) internal++;
            if (head.x() <= proof.right() || head.x() > proof.right() + gap * .65) continue;
            if (stop != null || head.x() <= proof.restoration() || head.x() - proof.restoration() > gap * .38)
                return null;
            stop = head;
        }
        return internal > 0 ? stop : null;
    }

    private static int round(double value) { return (int) Math.round(value); }
}
