// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Conservative ownership of isolated printed fingering digits on ordinary pitched attacks. */
final class PrintedFingeringDigits {
    enum Placement {
        ABOVE,
        BELOW
    }

    record Mark(int noteIndex, int digit, Placement placement) {}

    private record Head(
            int index, ScoreNoteEvent note, double x, double y, double onset, double top,
            double bottom, double gap, boolean harmonic) {}

    private record WordBox(
            int digit, double left, double top, double right, double bottom, double x, double y) {}

    private PrintedFingeringDigits() {}

    static List<Mark> capture(
            byte[] gray, int width, int height, List<SheetInterpreter.Word> words,
            ScorePageInterpretation score, List<Map<String, Object>> events) {
        if (words.isEmpty() || score.notes().size() != events.size()) return List.of();
        var heads = new ArrayList<Head>();
        double widestGap = 0;
        for (int index = 0; index < score.notes().size(); index++) {
            var note = score.notes().get(index);
            var region = score.measures().get(note.measureIndex());
            var event = events.get(index);
            double top = (double) region.top() * height, bottom = (double) region.bottom() * height;
            double gap = (bottom - top) / 8;
            if (gap <= 0) continue;
            heads.add(new Head(index, note, ((Number) event.get("x")).doubleValue(),
                    ((Number) event.get("y")).doubleValue(),
                    ((Number) event.get("startBeat")).doubleValue(), top, bottom, gap,
                    note.openStringTouch() != null || note.harmonicSemitones() != 0
                            || event.containsKey("harmonicTouchMidi")));
            widestGap = Math.max(widestGap, gap);
        }
        heads.sort(Comparator.comparingDouble(Head::x));
        var boxes = words.stream()
                .map(word -> toPixels(word, width, height))
                .sorted(Comparator.comparingDouble(WordBox::x)).toList();
        Mark[] candidates = new Mark[score.notes().size()];
        boolean[] ambiguous = new boolean[candidates.length];
        for (var box : boxes) {
            if (box.digit() < 0) continue;
            var head = uniqueOffer(box, heads, widestGap);
            if (head == null || !isolated(box, head, heads, boxes)
                    || !retainsDigitInk(gray, width, height, box, head.gap())) continue;
            int index = head.index();
            if (candidates[index] != null) ambiguous[index] = true;
            candidates[index] = new Mark(index, box.digit(),
                    box.y() < head.y() ? Placement.ABOVE : Placement.BELOW);
        }
        var marks = new ArrayList<Mark>();
        for (int i = 0; i < candidates.length; i++)
            if (candidates[i] != null && !ambiguous[i]) marks.add(candidates[i]);
        return List.copyOf(marks);
    }

    private static WordBox toPixels(SheetInterpreter.Word word, int width, int height) {
        int digit = switch (word.text()) {
            case "0" -> 0;
            case "1" -> 1;
            case "2" -> 2;
            case "4" -> 4;
            default -> -1;
        };
        double l = (double) word.left() * width, t = (double) word.top() * height;
        double r = (double) word.right() * width, b = (double) word.bottom() * height;
        return new WordBox(digit, l, t, r, b, (l + r) / 2, (t + b) / 2);
    }

    private static Head uniqueOffer(WordBox box, List<Head> heads, double widestGap) {
        double radius = widestGap * .52;
        Head owner = null;
        for (int i = firstHeadAt(heads, box.x() - radius);
                i < heads.size() && heads.get(i).x() <= box.x() + radius; i++) {
            var head = heads.get(i);
            double gap = head.gap(), dy = Math.abs(box.y() - head.y()) / gap;
            double w = (box.right() - box.left()) / gap, h = (box.bottom() - box.top()) / gap;
            if (Math.abs(box.x() - head.x()) > .52 * gap || dy < 1.2 || dy > 4.2
                    || w < .66 || w > 3.6 || h < .75 || h > 2.9
                    || box.y() < head.top() - 5 * gap || box.y() > head.bottom() + 5 * gap) continue;
            if (owner != null) return null;
            owner = head;
        }
        return owner;
    }

    private static boolean isolated(WordBox box, Head head, List<Head> heads, List<WordBox> words) {
        var note = head.note();
        double gap = head.gap(), h = (box.bottom() - box.top()) / gap;
        if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || (note.articulations() & NoteOrnament.GRACE) != 0 || head.harmonic()
                || h < .9 || h > 2.2 || Math.abs(box.x() - head.x()) > .36 * gap
                || Math.abs(box.y() - head.y()) > 3.3 * gap) return false;
        for (int i = firstHeadAt(heads, box.x() - gap);
                i < heads.size() && heads.get(i).x() <= box.x() + gap; i++) {
            var other = heads.get(i);
            if (other == head || other.note().measureIndex() != note.measureIndex()) continue;
            if (Math.abs(other.x() - box.x()) <= .74 * gap && Math.abs(other.y() - box.y()) <= 4.8 * gap)
                return false;
            if (Math.abs(other.x() - head.x()) <= .60 * gap
                    && (Math.abs(other.onset() - head.onset()) <= 1.0 / 48
                        || Math.abs(other.y() - head.y()) <= 5.5 * gap)) return false;
        }
        int left = 0, right = words.size();
        while (left < right) {
            int mid = (left + right) >>> 1;
            if (words.get(mid).x() < box.x() - .8 * gap) left = mid + 1;
            else right = mid;
        }
        for (int i = left; i < words.size() && words.get(i).x() < box.x() + .8 * gap; i++) {
            var other = words.get(i);
            if (!other.equals(box) && Math.abs(other.x() - box.x()) < .8 * gap
                    && Math.abs(other.y() - box.y()) < 2.2 * gap) return false;
        }
        return true;
    }

    private static int firstHeadAt(List<Head> heads, double x) {
        int left = 0, right = heads.size();
        while (left < right) {
            int mid = (left + right) >>> 1;
            if (heads.get(mid).x() < x) left = mid + 1;
            else right = mid;
        }
        return left;
    }

    private static boolean retainsDigitInk(byte[] gray, int width, int height, WordBox box, double gap) {
        int l = (int) Math.round(box.left()), t = (int) Math.round(box.top());
        int r = (int) Math.round(box.right()) - 1, b = (int) Math.round(box.bottom()) - 1;
        if (l > r || t > b || (long) (r - l + 1) * (b - t + 1) > 16384) return false;
        var source = RawNumeralComponents.capture(gray, width, height, l, t, r, b, 139, () -> {}, () -> {});
        RawNumeralComponents.Component glyph = null;
        for (var component : source.components()) {
            int glyphHeight = component.bottom - component.top + 1;
            if (component.size() < 12 || glyphHeight <= .5 * gap || glyphHeight >= 1.75 * gap) continue;
            if (glyph != null) return false;
            glyph = component;
        }
        if (glyph == null || glyph.left == l || glyph.right == r || glyph.top == t || glyph.bottom == b)
            return false;
        double aspect = (glyph.right - glyph.left + 1.0) / (glyph.bottom - glyph.top + 1);
        if (!(aspect > .27 && aspect < 1.25)) return false;
        return box.digit() != 1 || (aspect >= .35 && aspect <= .72);
    }
}
