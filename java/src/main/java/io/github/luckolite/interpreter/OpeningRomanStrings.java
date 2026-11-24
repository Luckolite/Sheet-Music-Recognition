// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Recovers Roman string labels on a sequence of monophonic system openings. */
final class OpeningRomanStrings {
    private static final List<String> LITERALS = List.of("I", "II", "III", "IV");
    // Standard violin string numbers run from the highest string to the lowest.
    private static final int[] OPEN_MIDI = {76, 69, 62, 55};

    private record Head(int owner, double x, double y, double beat, int midi) {}
    private record Box(int left, int top, int right, int bottom) {}
    private record Run(int left, int right) {
        double center() { return (left + right) * .5; }
        boolean caps(double center, double width) {
            return left <= center && center <= right && right - left >= width;
        }
    }

    private OpeningRomanStrings() {}

    static List<PrintedRomanStrings.Mark> capture(byte[] raw, byte[] normalized, int width, int height,
            List<SheetInterpreter.Word> words, ScorePageInterpretation score, List<Map<String, Object>> events) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length || words.size() > 2048 || score.measures().size() > 256)
            return List.of();
        var romans = words.stream().filter(w -> LITERALS.contains(w.text().strip())).toList();
        if (romans.size() < 3 || romans.size() > 64) return List.of();
        var measures = score.measures();
        var first = new HashMap<Integer, Head>();
        var tied = new HashSet<Integer>();
        var counts = new HashMap<Integer, Integer>();
        for (var event : events) {
            if (!(event.get("sourceNoteIndex") instanceof Integer index) || index < 0 || index >= score.notes().size()
                    || !(event.get("measureIndex") instanceof Integer measure) || measure < 0 || measure >= measures.size())
                continue;
            counts.merge(index, 1, Integer::sum);
            var note = score.notes().get(index);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1
                    || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || !(event.get("startBeat") instanceof Number beat) || !Double.isFinite(beat.doubleValue()))
                continue;
            var previous = first.get(measure);
            if (previous != null && previous.beat() == beat.doubleValue()) {
                tied.add(measure);
                continue;
            }
            if (previous != null && previous.beat() < beat.doubleValue()) continue;
            double x = event.get("x") instanceof Number value ? value.doubleValue() : Double.NaN;
            double y = event.get("y") instanceof Number value ? value.doubleValue() : Double.NaN;
            int pitch = event.get("midi") instanceof Integer midi ? midi : -1;
            if (!Double.isFinite(x) || !Double.isFinite(y)) pitch = -1;
            if (note.visualSize() != ScoreNoteEvent.VisualSize.FULL || note.openStringTouch() != null
                    || note.harmonicSemitones() != 0 || (note.articulations() & NoteOrnament.GRACE) != 0
                    || Boolean.TRUE.equals(event.get("tiedFromPrevious"))) pitch = -1;
            tied.remove(measure);
            first.put(measure, new Head(index, x, y,
                    beat.doubleValue(), pitch));
        }

        var marks = new ArrayList<PrintedRomanStrings.Mark>();
        var upper = new HashSet<Integer>();
        var numbers = new HashSet<Integer>();
        for (int i = 0; i < measures.size(); i++) {
            var head = first.get(i);
            var region = measures.get(i);
            double gap = (region.bottom() - region.top()) * height / 8.0;
            if (head == null || head.midi() < 0 || counts.get(head.owner()) != 1 || tied.contains(i)
                    || gap < 10 || gap > 29 || measures.stream().anyMatch(m ->
                    Math.abs(m.top() - region.top()) < .013 && m.left() < region.left() - .015)) continue;
            var above = new ArrayList<PrintedRomanStrings.Mark>();
            var below = new ArrayList<PrintedRomanStrings.Mark>();
            for (var word : romans) {
                var literal = word.text().strip();
                int number = LITERALS.indexOf(literal) + 1;
                double x = (word.left() + word.right()) * width * .5;
                double y = (word.top() + word.bottom()) * height * .5;
                double separation = (y - head.y()) / gap;
                if (head.midi() < OPEN_MIDI[number - 1] || Math.abs(x - head.x()) > .8 * gap
                        || Math.abs(separation) < 2.4 || Math.abs(separation) > 7) continue;
                var bounds = new Box(Math.round(word.left() * width), Math.round(word.top() * height),
                        Math.round(word.right() * width), Math.round(word.bottom() * height));
                int w = bounds.right() - bounds.left(), h = bounds.bottom() - bounds.top();
                if (bounds.left() < 0 || bounds.right() > width || bounds.top() < 0 || bounds.bottom() > height
                        || w < .65 * gap || w > 4.2 * gap || h < 1.2 * gap || h > 2.75 * gap
                        || !proof(raw, width, bounds, literal, gap)
                        || !proof(normalized, width, bounds, literal, gap)) continue;
                var placement = separation < 0 ? "ABOVE" : "BELOW";
                (separation < 0 ? above : below).add(
                        new PrintedRomanStrings.Mark(head.owner(), literal, number, placement));
            }
            if (above.size() != 1) continue;
            marks.add(above.get(0));
            upper.add(above.get(0).number());
            numbers.add(above.get(0).number());
            if (below.size() == 1 && below.get(0).number() != above.get(0).number()) {
                marks.add(below.get(0));
                numbers.add(below.get(0).number());
            }
        }
        return upper.size() >= 3 && numbers.contains(4) ? List.copyOf(marks) : List.of();
    }

    static boolean proof(byte[] image, int width, Box bounds, String literal, double gap) {
        int first = locate(image, width, bounds, literal, gap, 100);
        int second = locate(image, width, bounds, literal, gap, 138);
        return first >= 0 && second >= 0 && Math.abs(first - second) <= .18 * gap;
    }

    private static int locate(byte[] image, int width, Box bounds, String literal, double gap, int cutoff) {
        var lines = new ArrayList<List<Run>>();
        int span = bounds.right() - bounds.left();
        for (int y = bounds.top(); y < bounds.bottom(); y++) {
            var runs = new ArrayList<Run>();
            for (int x = 0; x < span;) {
                if ((image[y * width + bounds.left() + x] & 255) > cutoff) {
                    x++;
                    continue;
                }
                int start = x++;
                while (x < span && (image[y * width + bounds.left() + x] & 255) <= cutoff) x++;
                if (x - start < 2) continue;
                if (start == 0 || x == span) {
                    runs.clear();
                    break;
                }
                runs.add(new Run(start, x));
            }
            lines.add(runs);
        }
        for (int y = 0; y < lines.size(); y++) {
            for (int h = round(1.19 * gap); h <= round(1.57 * gap) && y + h < lines.size(); h++) {
                var rows = lines.subList(y, y + h + 1);
                if (literal.equals("IV") ? provesFour(rows, h, gap) : provesShafts(rows, h, gap, literal.length()))
                    return y;
            }
        }
        return -1;
    }

    private static boolean provesFour(List<List<Run>> rows, int h, double gap) {
        var top = band(rows, h, .23, .40).stream().filter(row -> row.size() == 3).toList();
        var bottom = band(rows, h, .67, .82).stream().filter(row -> row.size() == 2).toList();
        if (top.size() < 3 || bottom.size() < 3) return false;
        double i = medianCenter(top, 0);
        double left = medianCenter(top, 1), right = medianCenter(top, 2);
        double tip = medianCenter(bottom, 1);
        if (Math.abs(i - medianCenter(bottom, 0)) > .12 * gap
                || left - i < .38 * gap || left - i > 1.55 * gap || right - left < .42 * gap
                || tip < left + .18 * gap || tip > right - .08 * gap) return false;
        return band(rows, h, 0, .15).stream().anyMatch(row -> row.size() == 3
                && row.get(0).caps(i, .43 * gap) && row.get(1).caps(left, .39 * gap)
                && row.get(2).caps(right, .31 * gap))
                && band(rows, h, .86, 1).stream().anyMatch(row -> row.size() == 2
                && row.get(0).caps(i, .43 * gap));
    }

    private static boolean provesShafts(List<List<Run>> rows, int h, double gap, int count) {
        var mid = band(rows, h, .25, .74).stream().filter(row -> row.size() == count).toList();
        if (mid.size() < round(.37 * h)) return false;
        double[] centers = new double[count];
        for (int k = 0; k < count; k++) {
            final int i = k;
            var xs = mid.stream().mapToDouble(row -> row.get(i).center()).sorted().toArray();
            if (xs[xs.length - 1] - xs[0] > .14 * gap) return false;
            centers[i] = .5 * (xs[xs.length / 2] + xs[(xs.length - 1) / 2]);
            if (i > 0 && (centers[i] - centers[i - 1] < .55 * gap || centers[i] - centers[i - 1] > 1.05 * gap))
                return false;
        }
        return band(rows, h, 0, .18).stream().anyMatch(row -> caps(row, centers, gap))
                && band(rows, h, .82, 1).stream().anyMatch(row -> caps(row, centers, gap));
    }

    private static boolean caps(List<Run> row, double[] centers, double gap) {
        for (double center : centers) {
            if (row.stream().noneMatch(r -> r.caps(center, .45 * gap))) return false;
        }
        return true;
    }

    private static double medianCenter(List<List<Run>> rows, int index) {
        var values = rows.stream().mapToDouble(row -> row.get(index).center()).sorted().toArray();
        return .5 * (values[values.length / 2] + values[(values.length - 1) / 2]);
    }

    private static List<List<Run>> band(List<List<Run>> rows, int h, double start, double end) {
        return rows.subList(round(h * start), round(h * end) + 1);
    }

    private static int round(double value) { return (int) Math.round(value); }
}
