// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import java.util.regex.Pattern;

/** Native quarter-note = number marks above standalone tab systems. */
public final class TabTempo {
    private static final Pattern BPM_TOKEN = Pattern.compile("[0-9]{2,3}");

    private TabTempo() {}

    public static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            List<TablatureDecoder.Staff> tabs,
            List<TablatureDecoder.Word> words,
            int w,
            int h) {
        var changes = new ArrayList<>(score.tempoChanges());
        // Without a standalone staff and both printed operands there is no
        // tempo candidate to disambiguate.
        boolean hasCandidate = tabs.stream().anyMatch(tab -> !(tab.standardTop() >= 0))
                && words.stream().anyMatch(TabTempo::beatSymbol)
                && words.stream().anyMatch(word -> word.text().equals("="));
        var beats = new ArrayList<TablatureDecoder.Word>();
        var equals = new ArrayList<TablatureDecoder.Word>();
        var dots = new ArrayList<TablatureDecoder.Word>();
        var numbers = new ArrayList<TablatureDecoder.Word>();
        if (hasCandidate) {
            for (var token : TabTextSource.disjoint(words)) {
                switch (token.text()) {
                    case "\uECA5", "\uECA6" -> beats.add(token);
                    case "=" -> equals.add(token);
                    case "\uE1E7", "\uECB7", "." -> dots.add(token);
                    default -> {
                        if (BPM_TOKEN.matcher(token.text()).matches()) numbers.add(token);
                    }
                }
            }
        }
        for (var tab : tabs) {
            if (tab.standardTop() >= 0) continue;
            for (var note : beats) {
                if (note.bottom() * h > tab.top() || note.top() * h < tab.top() - tab.gap() * 6)
                    continue;
                for (var eq : equals) {
                    if (eq.left() < note.right()
                            || (eq.left() - note.right()) * w > tab.gap()) continue;
                    float baseline = (eq.top() + eq.bottom()) * .5f;
                    if (baseline < note.top() || baseline > note.bottom() + tab.gap() / h * .2f)
                        continue;
                    // A dotted beat needs a different conversion; do not silently read it as
                    // quarter BPM.
                    boolean dotted =
                            dots.stream()
                                    .anyMatch(
                                            v ->
                                                    v.left() >= note.right()
                                                            && v.right() <= eq.left()
                                                            && Math.abs(
                                                                                    (v.top()
                                                                                                            + v
                                                                                                                    .bottom())
                                                                                                    * .5f
                                                                                            - baseline)
                                                                            * h
                                                                    < tab.gap());
                    if (dotted) continue;
                    TablatureDecoder.Word number = null;
                    for (var v : numbers) {
                        if (v.left() < eq.right()
                                || (v.left() - eq.right()) * w > tab.gap() * 1.2f
                                || Math.abs((v.top() + v.bottom()) * .5f - baseline) * h
                                        > tab.gap() * .5f) continue;
                        if (number == null || v.left() < number.left()) number = v;
                    }
                    if (number == null) continue;
                    int bpm = Integer.parseInt(number.text());
                    if (bpm < 15 || bpm > 400) continue;
                    float x = (note.left() + note.right()) * .5f,
                            y = (tab.top() + tab.bottom()) * .5f / h;
                    for (int i = 0; i < score.measures().size(); i++) {
                        var m = score.measures().get(i);
                        if (y < m.top()
                                || y > m.bottom()
                                || x < m.left() - tab.gap() / w
                                || x > m.right()) continue;
                        final int index = i;
                        if (changes.stream().noneMatch(c -> c.measureIndex() == index)) {
                            float position =
                                    Math.max(
                                            0,
                                            Math.min(1, (x - m.left()) / (m.right() - m.left())));
                            float first = 1;
                            for (var n : score.notes())
                                if (n.measureIndex() == i)
                                    first = Math.min(first, n.positionInMeasure());
                            for (var r : score.rests())
                                if (r.measureIndex() == i)
                                    first = Math.min(first, r.positionInMeasure());
                            if (position <= first) position = 0;
                            changes.add(new ScoreTempoChange(i, position, bpm));
                        }
                        break;
                    }
                }
            }
        }
        changes.sort(
                Comparator.comparingInt(ScoreTempoChange::measureIndex)
                        .thenComparingDouble(ScoreTempoChange::positionInMeasure));
        return new ScorePageInterpretation(
                        score.measures(),
                        score.notes(),
                        score.firstMeasureNumber(),
                        score.keyChanges(),
                        changes,
                        score.meterChanges(),
                        score.rests(),
                        score.techniqueChanges(),
                        score.dynamicChanges(),
                        score.playbackDirections(),
                        score.expressiveEvents(),
                        score.slurSource(),
                        score.openingPickup(),
                        score.tremoloSource()).withBeamSource(score.beamSource())
                .carryMultiRestGroupsFrom(score);
    }

    private static boolean beatSymbol(TablatureDecoder.Word word) {
        return word.text().equals("\uECA5") || word.text().equals("\uECA6");
    }
}
