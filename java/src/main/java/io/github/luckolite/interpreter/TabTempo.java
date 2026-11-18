// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import java.util.regex.Pattern;

/** Native metronome marks above standalone tab systems. */
public final class TabTempo {
    private static final Pattern BPM_TOKEN = Pattern.compile("[0-9]{2,3}");

    private TabTempo() {}

    public static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            List<TablatureDecoder.Staff> tabs,
            List<TablatureDecoder.Word> words,
            int w,
            int h) {
        if (tabs.stream().noneMatch(tab -> tab.standardTop() < 0)
                || words.stream().noneMatch(word -> beatUnit(word.text()) > 0)
                || words.stream().noneMatch(word -> word.text().equals("="))) return score;
        var changes = new ArrayList<>(score.tempoChanges());
        var beats = new ArrayList<TablatureDecoder.Word>();
        var equals = new ArrayList<TablatureDecoder.Word>();
        var dots = new ArrayList<TablatureDecoder.Word>();
        var numbers = new ArrayList<TablatureDecoder.Word>();
        for (var token : TabTextSource.disjoint(words)) {
            if (beatUnit(token.text()) > 0) beats.add(token);
            else switch (token.text()) {
                case "=" -> equals.add(token);
                case "\uE1E7", "\uECB7", "." -> dots.add(token);
                default -> {
                    if (BPM_TOKEN.matcher(token.text()).matches()) numbers.add(token);
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
                    int dotCount = 0;
                    for (var dot : dots) {
                        if (dot.left() >= note.right() && dot.right() <= eq.left()
                                && Math.abs((dot.top() + dot.bottom()) * .5f - baseline) * h
                                        <= tab.gap() * .4f) dotCount++;
                    }
                    if (dotCount > 2) continue;
                    double unit = beatUnit(note.text())
                            * (dotCount == 2 ? 1.75 : dotCount == 1 ? 1.5 : 1);
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
                    double quarterBpm = bpm * unit;
                    if (bpm < 15 || bpm > 400 || quarterBpm < 15) continue;
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
                            changes.add(new ScoreTempoChange(i, position, quarterBpm, unit));
                        }
                        break;
                    }
                }
            }
        }
        if (changes.size() == score.tempoChanges().size()) return score;
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
                        score.tremoloSource(),
                        score.multiRestGroups(),
                        score.beamSource(),
                        score.clockContextSource());
    }

    private static double beatUnit(String text) {
        return switch (text) {
            case "\uECA3", "\uECA4" -> 2;
            case "\uECA5", "\uECA6" -> 1;
            case "\uECA7", "\uECA8" -> .5;
            default -> 0;
        };
    }
}
