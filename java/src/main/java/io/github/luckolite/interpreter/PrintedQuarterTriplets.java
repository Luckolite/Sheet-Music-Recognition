// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** A complete printed bracket can group three quarter notes across a wide engraving. */
final class PrintedQuarterTriplets {
    private PrintedQuarterTriplets() {}

    static ScorePageInterpretation recover(ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Word> words, byte[] gray, byte[] source, int width, int height) {
        if (gray == null || source == null || gray.length != (long) width * height
                || source.length != gray.length) return score;
        var numerals = words.stream().filter(word -> word.text().strip().equals("3")).toList();
        if (numerals.isEmpty()) return score;
        var notes = score.notes();
        var lanes = new ArrayList<List<Integer>>(score.measures().size());
        for (int i = 0; i < score.measures().size(); i++) lanes.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            if (note.measureIndex() >= 0 && note.measureIndex() < lanes.size()
                    && (note.articulations() & NoteOrnament.GRACE) == 0)
                lanes.get(note.measureIndex()).add(i);
        }
        var rests = new java.util.BitSet();
        for (var rest : score.rests()) rests.set(rest.measureIndex());
        List<ScoreNoteEvent> corrected = null;
        for (int m = 0; m < lanes.size(); m++) {
            var indices = lanes.get(m);
            if ((indices.size() != 3 && indices.size() != 6) || rests.get(m)
                    || indices.stream().map(notes::get).anyMatch(PrintedQuarterTriplets::ineligible)) continue;
            var bar = score.measures().get(m);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 10 || gap > 26) continue;
            boolean all = true;
            for (int i = 0; i < indices.size(); i += 3) {
                var a = notes.get(indices.get(i));
                var b = notes.get(indices.get(i + 1));
                var c = notes.get(indices.get(i + 2));
                float left = x(bar, a, width), mid = x(bar, b, width), right = x(bar, c, width);
                float step = Math.min(mid - left, right - mid);
                if (step < gap * 2 || step > gap * 8
                        || Math.abs(mid - (left + right) * .5f) > gap * .7f
                        || !numberedBracket(numerals, gray, source, width, height, bar, gap, left, mid, right)) {
                    all = false;
                    break;
                }
            }
            if (!all) continue;
            if (corrected == null) corrected = new ArrayList<>(notes);
            for (int i : indices) corrected.set(i, notes.get(i).withTupletRatio(3, 2));
        }
        return corrected == null ? score : score.withNotes(corrected,
                IntStream.range(0, notes.size()).boxed().toList()).withClockContextSource(score.clockContextSource());
    }

    private static boolean ineligible(ScoreNoteEvent note) {
        return note.staffCount() != 1 || note.kind() != ScoreNoteEvent.Kind.PITCHED
                || note.stemDirection() != 1 || note.unbeamedDurationBeats() != 1
                || note.augmentationDots() != 0 || note.beamCount() != 0 || note.tupletDivisor() != 1;
    }

    private static float x(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
    }

    private static boolean numberedBracket(List<PlayingTechniqueDetector.Word> numerals, byte[] gray,
            byte[] source, int width, int height, MeasureRegion bar,
            float gap, float left, float mid, float right) {
        float upper = bar.top() * height;
        for (var digit : numerals) {
            float centerX = (digit.left() + digit.right()) * .5f * width;
            float centerY = (digit.top() + digit.bottom()) * .5f * height;
            if (Math.abs(centerX - mid) > gap || centerY < upper - gap * 1.5f || centerY > upper + gap * 1.6f)
                continue;
            int minY = Math.max(1, Math.round(digit.top() * height - gap * .2f));
            int maxY = Math.min(height - Math.round(gap) - 1, Math.round(digit.bottom() * height));
            for (int y = minY; y <= maxY; y++)
                if (hasArms(gray, width, y, gap, left, centerX, right)
                        && (source == gray || hasArms(source, width, y, gap, left, centerX, right)))
                    return true;
        }
        return false;
    }

    private static boolean hasArms(byte[] gray, int width, int y, float gap, float left, float center, float right) {
        int a = Math.round(left), b = Math.round(center - gap * 1.15f);
        int c = Math.round(center + gap * 1.15f), d = Math.round(right);
        int margin = Math.round(gap * 1.5f);
        if (a - margin < 0 || d + margin >= width || b - a < gap || d - c < gap) return false;
        for (int x = a; x <= b; x++) if (!dark(gray, width, x, y)) return false;
        for (int x = c; x <= d; x++) if (!dark(gray, width, x, y)) return false;
        int blank = 0;
        for (int x = b + 1; x < c && blank < 3; x++)
            blank = dark(gray, width, x, y) ? 0 : blank + 1;
        if (blank < 3) return false;
        int leftEnd = a, rightEnd = d;
        while (leftEnd > a - margin && dark(gray, width, leftEnd - 1, y)) leftEnd--;
        while (rightEnd < d + margin && dark(gray, width, rightEnd + 1, y)) rightEnd++;
        if (leftEnd == a - margin || rightEnd == d + margin) return false;
        return hook(gray, width, leftEnd, y, 1, gap) && hook(gray, width, rightEnd, y, -1, gap);
    }

    private static boolean hook(byte[] gray, int width, int end, int y, int inward, float gap) {
        for (int offset = 0; offset <= 2; offset++) {
            int x = end + inward * offset, count = 0, length = Math.round(gap * .65f);
            for (int d = 1; d <= length; d++) if (dark(gray, width, x, y + d)) count++;
            if (count >= length * .85f) return true;
        }
        return false;
    }

    private static boolean dark(byte[] gray, int width, int x, int y) {
        return (gray[y * width + x] & 255) < 175;
    }
}
