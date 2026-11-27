// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import java.util.regex.Pattern;

/** Page-local evidence for a printed capo and the written-to-fret interval of a paired staff. */
public final class PrintedTabCapo {
    private static final String HEADER = "printed-tab-capo:v1";
    private static final String ROW = "printed-tab-capo-row:v1";
    private static final Pattern CAPO = Pattern.compile(
            "(?im)^\\h*capo\\h*[:=]?\\h*(0|1[0-2]|[1-9]|XII|XI|X|IX|VIII|VII|VI|V|IV|III|II|I)\\h*[.]?\\h*$");
    private static final List<String> ROMAN =
            List.of("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII");

    private PrintedTabCapo() {}

    // Empty is an absent declaration; -1 is a conflicting declaration and must stop inheritance.
    static OptionalInt parse(String text) {
        var matcher = CAPO.matcher(text);
        int capo = -1;
        while (matcher.find()) {
            String value = matcher.group(1).toUpperCase(Locale.ROOT);
            int number = value.charAt(0) <= '9' ? Integer.parseInt(value) : ROMAN.indexOf(value);
            if (capo >= 0 && capo != number) return OptionalInt.of(-1);
            capo = number;
        }
        return capo < 0 ? OptionalInt.empty() : OptionalInt.of(capo);
    }

    static ScorePageInterpretation capture(ScorePageInterpretation score,
            List<TablatureDecoder.Staff> tabs, List<TablatureDecoder.Word> words,
            int width, int height, int physicalPage) {
        if (physicalPage < 0 || tabs.isEmpty() || score.measures().isEmpty()) return score;
        var events = new ArrayList<>(score.expressiveEvents());
        var first = tabs.get(0);
        float limit = (first.standardTop() >= 0 ? first.standardTop() : first.top()) - first.gap();
        var header = words.stream().filter(word -> word.bottom() * height < limit).distinct().toList();
        var declarations = new TreeSet<Integer>();
        for (var word : header) parse(word.text()).ifPresent(declarations::add);
        parse(TabTuning.headerText(header)).ifPresent(declarations::add);
        int capo = declarations.isEmpty() ? -2 : declarations.size() == 1 ? declarations.first() : -1;
        events.add(mark(HEADER, physicalPage, 0, score.measures().size(),
                score.firstMeasureNumber() + ":" + capo, 0));
        for (var tab : tabs) {
            if (tab.standardTop() < 0 || tab.gap() <= 0 || tab.frets().isEmpty()) continue;
            var counts = new TreeMap<Integer, Set<Long>>();
            int left = score.measures().size(), right = -1;
            int[] tuning = TablatureDecoder.tabTuning(tab, null);
            for (var note : score.notes()) {
                if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1) continue;
                int m = note.measureIndex();
                var bar = score.measures().get(m);
                if (tab.standardTop() / height < bar.top() - tab.gap() / height * 2
                        || tab.standardTop() / height > bar.bottom()
                        || bar.bottom() * height >= tab.top() + tab.gap()) continue;
                left = Math.min(left, m);
                right = Math.max(right, m);
                float x = (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * width;
                int pitch = TablatureDecoder.printedMidi(note, score);
                for (var fret : tab.frets()) {
                    if (fret.fret() < 0 || TabEffect.kind(fret.marks()) == TabEffect.HARMONIC
                            || Math.abs(fret.x() - x) >= tab.gap() * .65f) continue;
                    int delta = TablatureDecoder.midi(fret.string(), fret.fret(), tuning, 0) - pitch;
                    if (delta < -24 || delta > 0) continue;
                    long attack = ((long)m << 32) | Math.round(fret.x() / (tab.gap() * .5f));
                    counts.computeIfAbsent(delta, key -> new HashSet<>()).add(attack);
                }
            }
            if (right < left || counts.values().stream().noneMatch(seen -> seen.size() >= 3)) continue;
            StringBuilder payload = new StringBuilder();
            counts.forEach((delta, seen) -> {
                if (!payload.isEmpty()) payload.append(',');
                payload.append(delta).append('=').append(seen.size());
            });
            events.add(mark(ROW, physicalPage, left, right + 1, payload.toString(),
                    score.measures().get(left).left()));
        }
        return events.size() == score.expressiveEvents().size() ? score : score.withExpressiveEvents(events);
    }

    private static ScoreExpressiveEvent mark(String type, int page, int first, int end,
            String payload, float x) {
        return new ScoreExpressiveEvent(type + ":" + page + ":" + first,
                ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION, Optional.of(new ScoreAnchor(first, 0)),
                end < 0 ? Optional.empty() : Optional.of(new ScoreAnchor(end, 0)),
                ScoreExpressiveEvent.Scope.UNRESOLVED, 0, 1, Optional.of(payload),
                ScoreExpressiveEvent.Strength.UNSPECIFIED, "",
                List.of(new ScoreExpressiveEvent.Evidence(type, page, x, 0, 1, "")));
    }
}
