// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Repairs a direction when a printed flat overlaps the last letter of a repeated caption. */
final class SegueAccidentalOverlap {
    private SegueAccidentalOverlap() {}

    static List<PlayingTechniqueDetector.Word> correct(List<PlayingTechniqueDetector.Word> words,
            ScorePageInterpretation score, byte[] raw, byte[] gray, int width, int height) {
        if (raw == null || gray == null || raw.length != (long) width * height || gray.length != raw.length)
            return words;
        List<PlayingTechniqueDetector.Word> output = null;
        for (int i = 0; i < words.size(); i++) {
            var word = words.get(i);
            if (!"seguq".equalsIgnoreCase(word.text())) continue;
            var match = prove(word, words, score, raw, gray, width, height);
            if (match == null) continue;
            if (output == null) output = new ArrayList<>(words);
            output.set(i, match);
        }
        return output == null ? words : List.copyOf(output);
    }

    private static PlayingTechniqueDetector.Word prove(PlayingTechniqueDetector.Word word,
            List<PlayingTechniqueDetector.Word> words, ScorePageInterpretation score,
            byte[] raw, byte[] gray, int width, int height) {
        for (var note : score.notes()) {
            if (note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT || note.staffCount() != 1
                    || note.kind() != ScoreNoteEvent.Kind.PITCHED) continue;
            var bar = score.measures().get(note.measureIndex());
            float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float x = left + span * note.positionInMeasure(), y = note.pageY() * height;
            if (gap < 10 || gap > 25 || x - word.right() * width < .38f * gap
                    || x - word.right() * width > 1.17f * gap
                    || word.left() * width < left || word.right() * width > left + span
                    || y - word.top() * height < 1.4f * gap || y - word.top() * height > 2.8f * gap
                    || Math.abs(y - word.bottom() * height) > .58f * gap
                    || !ClosedOctaveTripletBars.provedFarFlat(raw, gray, width, height, left, span, gap, note))
                continue;
            PlayingTechniqueDetector.Word reference = null;
            for (var other : words) {
                float separation = (other.top() - word.top()) * height;
                if (!"segue".equalsIgnoreCase(other.text()) || other.left() * width < left
                        || other.right() * width > left + span
                        || separation < 7.5f * gap || separation > 11f * gap
                        || Math.abs((other.left() - word.left()) * width) > 14f * gap)
                    continue;
                if (reference != null) return null;
                reference = other;
            }
            if (reference == null) continue;
            if (similar(raw, width, height, word, reference, x, y, gap)
                    && similar(gray, width, height, word, reference, x, y, gap))
                return new PlayingTechniqueDetector.Word("segue", word.left(), word.top(), word.right(), word.bottom());
        }
        return null;
    }

    private record Box(int left, int top, int right, int bottom) {
        int wide() { return right - left + 1; }
        int tall() { return bottom - top + 1; }
    }

    private static boolean similar(byte[] ink, int width, int height,
            PlayingTechniqueDetector.Word word, PlayingTechniqueDetector.Word reference,
            float noteX, float noteY, float gap) {
        var a = rasterBox(word, width, height, gap);
        var b = rasterBox(reference, width, height, gap);
        if (a == null || b == null) return false;
        var aGlyphs = characters(ink, width, height, a);
        var bGlyphs = characters(ink, width, height, b);
        if (aGlyphs.size() < 5 || bGlyphs.size() < 5) return false;
        var aFirst = aGlyphs.get(0);
        var bFirst = bGlyphs.get(0);
        if (aFirst.tall() < .79f * gap || bFirst.tall() < .79f * gap
                || aFirst.wide() > gap || bFirst.wide() > gap
                || resemblance(ink, width, aFirst, bFirst) < .49f) return false;
        var aDescender = descender(aGlyphs, a, aFirst, gap);
        var bDescender = descender(bGlyphs, b, bFirst, gap);
        if (aDescender == null || bDescender == null
                || resemblance(ink, width, aDescender, bDescender) < .45f) return false;

        int shaft = firstFlatColumn(ink, width, height, noteX, noteY, gap, aFirst.bottom());
        int referenceEnd = lastColumn(ink, width, b.left(), b.right(), bFirst.top() - 1, bFirst.bottom() + 1);
        int start = terminalStart(ink, width, a.left(), shaft - 1, aFirst.top(), aFirst.bottom());
        int sample = terminalStart(ink, width, b.left(), referenceEnd, bFirst.top(), bFirst.bottom());
        if (shaft < 0 || start < 0 || sample < 0 || shaft - start < .28f * gap
                || shaft - start > .70f * gap || referenceEnd - sample < .68f * gap
                || start < a.left() + .69f * a.wide() || sample < b.left() + .69f * b.wide())
            return false;
        var visible = new Box(start, aFirst.top() - 1, shaft - 1, aFirst.bottom() + 1);
        float best = 0;
        int agreeing = 0;
        for (int offset = -2; offset <= 1; offset++) {
            for (int stretch = -1; stretch <= 2; stretch++) {
                var expected = new Box(sample, bFirst.top() - 1 + offset,
                        sample + visible.wide() - 1 + stretch, bFirst.bottom() + 1 + offset);
                if (expected.right() > referenceEnd || expected.wide() <= 2) continue;
                float fit = resemblance(ink, width, visible, expected);
                best = Math.max(best, fit);
                if (fit >= .64f) agreeing++;
            }
        }
        return best >= .66f && agreeing >= 2;
    }

    private static Box rasterBox(PlayingTechniqueDetector.Word word, int width, int height, float gap) {
        int left = Math.round(word.left() * width), right = Math.round(word.right() * width);
        int top = Math.round(word.top() * height), bottom = Math.round(word.bottom() * height);
        if (left < 1 || right + 1 >= width || top < 4 || bottom + 4 >= height
                || right - left < 4.5f * gap || right - left > 7.5f * gap
                || bottom - top < 1.7f * gap || bottom - top > 3f * gap) return null;
        return new Box(left, top, right, bottom);
    }

    private static List<Box> characters(byte[] ink, int width, int height, Box word) {
        return RawNumeralComponents.captureLongWindow(ink, width, height,
                word.left(), word.top(), word.right(), word.bottom(), 155).components().stream()
                .filter(c -> c.size() >= 20)
                .map(c -> new Box(c.left, c.top, c.right, c.bottom))
                .sorted(Comparator.comparingInt(Box::left)).toList();
    }

    private static Box descender(List<Box> glyphs, Box word, Box first, float gap) {
        var matches = glyphs.stream()
                .filter(g -> g.left() > word.left() + .27f * word.wide()
                        && g.left() < word.left() + .53f * word.wide()
                        && g.top() <= first.top() + .18f * gap && g.bottom() >= first.bottom() + .35f * gap
                        && g.wide() >= gap && g.wide() <= 1.6f * gap)
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static int firstFlatColumn(byte[] ink, int width, int height, float noteX, float noteY,
            float gap, int base) {
        int left = Math.round(noteX - 1.78f * gap), right = Math.round(noteX - 1.20f * gap);
        int bottom = Math.round(noteY + .27f * gap);
        if (left < 0 || right >= width || bottom >= height || bottom - base < gap * .68f) return -1;
        for (int x = left; x <= right; x++) {
            int dark = 0;
            for (int y = base; y <= bottom; y++) if (dark(ink, width, x, y)) dark++;
            if (dark >= .88f * (bottom - base + 1)) return x;
        }
        return -1;
    }

    private static int lastColumn(byte[] ink, int width, int left, int right, int top, int bottom) {
        for (int x = right; x >= left; x--)
            if (columnHasInk(ink, width, x, top, bottom)) return x;
        return -1;
    }

    private static int terminalStart(byte[] ink, int width, int left, int end, int top, int bottom) {
        if (end <= left || !columnHasInk(ink, width, end, top, bottom)) return -1;
        for (int x = end - 1; x > left; x--)
            if (!columnHasInk(ink, width, x, top, bottom)) return x + 1;
        return -1;
    }

    private static boolean columnHasInk(byte[] ink, int width, int x, int top, int bottom) {
        for (int y = top; y <= bottom; y++) if (dark(ink, width, x, y)) return true;
        return false;
    }

    private static float resemblance(byte[] ink, int width, Box a, Box b) {
        int either = 0, both = 0;
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                boolean first = sample(ink, width, a, x, y);
                boolean second = sample(ink, width, b, x, y);
                if (first || second) either++;
                if (first && second) both++;
            }
        }
        return either == 0 ? 0 : both / (float) either;
    }

    private static boolean sample(byte[] ink, int width, Box b, int x, int y) {
        return dark(ink, width, b.left() + (2 * x + 1) * b.wide() / 48,
                b.top() + (2 * y + 1) * b.tall() / 48);
    }

    private static boolean dark(byte[] ink, int width, int x, int y) {
        return (ink[y * width + x] & 255) < 165;
    }
}
