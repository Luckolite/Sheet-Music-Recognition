// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import java.util.regex.*;

/** Text-layer tab symbols, including overlapping matches returned by PDF search APIs. */
public final class TabTextSource {
    private static final int WORD_BANDS = 32;
    private TabTextSource() {}

    private static final class TokenPattern {
        private static final Pattern VALUE =
                Pattern.compile(
                        "-(?:[12]|(?i:full))|(?i:full|whammy|w/bar|bar|upstroke|downstroke|strum up|strum down)|-?(?:1 1/2|1/4|1/2|3/4|1\u00bd|[\u00bc\u00bd\u00be])|[\\uE846\\uE847]|<[0-9]{1,2}>|\\([0-9]{1,2}\\)|(?<![0-9])[0-9]{1,2}[bB][0-9]{1,2}(?:[rR][0-9]{1,2})?~*|(?<![0-9])(?:[0-9]{1,2}|[xX])(?:[/\\\\hHpP][0-9]{1,2})+~*|(?<![0-9])[0-9]{1,3}(?![0-9])~*|(?<![\\p{L}\\p{N}])[QESWHT](?:\\.{1,2})?(?![\\p{L}\\p{N}])|[xXP=]|[𝅝𝅗𝅥𝅘𝅥𝅘𝅥𝅮𝅘𝅥𝅯𝅘𝅥𝅰𝄻𝄼𝄽𝄾𝄿𝅀♩♪\\uE4E3-\\uE4E8](?:\\.{1,2})?|[\\uE1E7\\uE241\\uE243\\uE245\\uE4A2\\uEAB2\\uE080-\\uE089\\uECA5\\uECA6\\uECB7]");
    }

    private static final class DigitPattern {
        private static final Pattern VALUE = Pattern.compile("[0-9]");
        private static final Pattern COMPOUND = Pattern.compile("[0-9]{2,3}");
    }

    public static List<String> tokens(String text) {
        var found = new LinkedHashSet<String>();
        var m = TokenPattern.VALUE.matcher(text);
        while (m.find()) found.add(m.group());
        for (char c = '0'; c <= '9'; c++)
            if (text.indexOf(c) >= 0) found.add(Character.toString(c));
        var result = new ArrayList<>(found);
        result.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(result);
    }

    /** PDF search finds substrings too; a rhythm letter must be an isolated source token. */
    public static boolean acceptSearchMatch(String pageText, String token, int start) {
        if (!token.matches("[QESWHT]\\.{0,2}")) return true;
        int end = start + token.length();
        if (start < 0
                || end > pageText.length()
                || !pageText.regionMatches(start, token, 0, token.length())) return false;
        return (start == 0 || !Character.isLetterOrDigit(pageText.codePointBefore(start)))
                && (end == pageText.length()
                        || !Character.isLetterOrDigit(pageText.codePointAt(end)))
                && (end == pageText.length() || pageText.charAt(end) != '.');
    }

    public static boolean needsRhythmOcr(TablatureDecoder.Staff staff) {
        return staff.frets().isEmpty()
                || staff.standardTop() < 0
                        && staff.frets().stream()
                                .anyMatch(
                                        fret ->
                                                fret.fret() >= 0
                                                        && fret.duration() <= 0
                                                        && fret.beams() == 0);
    }

    public static List<TablatureDecoder.Word> disjoint(List<TablatureDecoder.Word> input) {
        var sorted = new ArrayList<>(input);
        sorted.sort(
                Comparator.comparingInt((TablatureDecoder.Word v) -> v.text().length()).reversed());
        var singleDigits = input.stream()
                .filter(word -> DigitPattern.VALUE.matcher(word.text()).matches())
                .toList();
        var out = new ArrayList<TablatureDecoder.Word>();
        var bands = new ArrayList<List<TablatureDecoder.Word>>(WORD_BANDS);
        for (int i = 0; i < WORD_BANDS; i++) bands.add(new ArrayList<>());
        for (var v : sorted) {
            if (crossesDigitRows(v, singleDigits)) continue;
            float area = (v.right() - v.left()) * (v.bottom() - v.top());
            int first = wordBand(v.top()), last = wordBand(v.bottom());
            if (covered(v, area * .7f, first, last, bands)) continue;
            out.add(v);
            for (int band = first; band <= last; band++) {
                bands.get(band).add(v);
            }
        }
        var order = new HashMap<TablatureDecoder.Word, Integer>();
        for (int i = 0; i < input.size(); i++) order.putIfAbsent(input.get(i), i);
        out.sort(Comparator.comparingInt(order::get));
        return List.copyOf(out);
    }

    private static int wordBand(float y) {
        return Math.min(WORD_BANDS - 1, (int) (y * WORD_BANDS));
    }

    private static boolean covered(
            TablatureDecoder.Word word, float minimumArea, int first, int last,
            List<List<TablatureDecoder.Word>> bands) {
        for (int band = first; band <= last; band++) {
            for (var old : bands.get(band)) {
                // Compare each pair only in the first band shared by their boxes.
                if (band != first && wordBand(old.top()) != band) continue;
                float x = Math.min(word.right(), old.right()) - Math.max(word.left(), old.left());
                if (x <= 0) continue;
                float y = Math.min(word.bottom(), old.bottom()) - Math.max(word.top(), old.top());
                if (y > 0 && x * y > minimumArea) return true;
            }
        }
        return false;
    }

    private static boolean crossesDigitRows(
            TablatureDecoder.Word word, List<TablatureDecoder.Word> singleDigits) {
        if (!DigitPattern.COMPOUND.matcher(word.text()).matches()) return false;
        var digits = new ArrayList<TablatureDecoder.Word>();
        for (var v : singleDigits)
            if (v.left() >= word.left() - .00001f
                    && v.right() <= word.right() + .00001f
                    && v.top() >= word.top() - .00001f
                    && v.bottom() <= word.bottom() + .00001f) digits.add(v);
        for (var a : digits)
            for (var b : digits)
                if (Math.abs((a.top() + a.bottom() - b.top() - b.bottom()) * .5f)
                        > Math.max(a.bottom() - a.top(), b.bottom() - b.top()) * 1.25f) return true;
        return false;
    }

    public static List<TablatureDecoder.Word> joinDigits(
            List<TablatureDecoder.Staff> tabs, List<TablatureDecoder.Word> input, int w, int h) {
        var words = new ArrayList<>(disjoint(input));
        for (var tab : tabs)
            for (int string = 0; string < tab.stringCount(); string++) {
                float cy = tab.top() + string * tab.gap();
                var lane = new ArrayList<TablatureDecoder.Word>();
                for (var v : words)
                    if (Math.abs((v.top() + v.bottom()) * .5f * h - cy) < tab.gap() * .35f
                            && DigitPattern.VALUE.matcher(v.text()).matches())
                        lane.add(v);
                lane.sort(Comparator.comparingDouble(TablatureDecoder.Word::left));
                for (int i = 0; i + 1 < lane.size(); i++) {
                    var a = lane.get(i);
                    var b = lane.get(i + 1);
                    float gap = (b.left() - a.right()) * w;
                    int fret = Integer.parseInt(a.text() + b.text());
                    if (gap < 0
                            || gap > tab.gap() * .55f
                            || (b.right() - a.left()) * w > tab.gap() * 1.65f
                            || fret > 36
                            || a.text().equals("0")) continue;
                    if (Math.abs((a.top() + a.bottom() - b.top() - b.bottom()) * .5f * h)
                            > tab.gap() * .15f) continue;
                    words.remove(a);
                    words.remove(b);
                    words.add(
                            new TablatureDecoder.Word(
                                    a.text() + b.text(),
                                    a.left(),
                                    Math.min(a.top(), b.top()),
                                    b.right(),
                                    Math.max(a.bottom(), b.bottom())));
                    i++;
                }
            }
        return List.copyOf(words);
    }
}
