// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class PlayingTechniqueDetector {
    record Staff(float top, float bottom, float gap, int index, int count) {}

    private record MeasureStaff(int measure, int staff) {}

    enum WordAdmission {
        UNCLASSIFIED,
        CONTEXT_QUALIFIED,
        FP_GLYPH_QUALIFIED
    }

    record Word(
            String text,
            float left,
            float top,
            float right,
            float bottom,
            WordAdmission admission) {
        Word(String text, float left, float top, float right, float bottom) {
            this(text, left, top, right, bottom, WordAdmission.UNCLASSIFIED);
        }

        Word {
            java.util.Objects.requireNonNull(admission);
        }
    }

    private static final Pattern TOKEN_PUNCTUATION = Pattern.compile("^[.,:;]+|[.,:;]+$");
    private static final Pattern TOKEN_SPACE = Pattern.compile("\\s+");
    private static final Pattern DYNAMIC_TOKEN = Pattern.compile("ppp|pp|p|mp|mf|fff|ff|f");

    private PlayingTechniqueDetector() {}

    static int technique(String word) {
        if (word == null) return -1;
        String clean =
                TOKEN_PUNCTUATION.matcher(word.trim().toLowerCase(Locale.ROOT)).replaceAll("");
        // A dynamic and an expression can share one OCR line. Accept only a
        // complete two-token direction, never arbitrary prose or negation.
        String[] phrase = TOKEN_SPACE.split(clean);
        if (phrase.length == 2 && DYNAMIC_TOKEN.matcher(phrase[0]).matches())
            clean = TOKEN_PUNCTUATION.matcher(phrase[1]).replaceAll("");
        if (phrase.length == 2) {
            int mode = singleTechnique(TOKEN_PUNCTUATION.matcher(phrase[0]).replaceAll(""));
            String qualifier = TOKEN_PUNCTUATION.matcher(phrase[1]).replaceAll("");
            if ((mode == ScoreTechniqueChange.ARCO || mode == ScoreTechniqueChange.PIZZICATO)
                    && singleTechnique(qualifier) < 0) {
                var directions = ExpressiveDirectionText.parse(qualifier);
                if (directions.size() == 1
                        && directions.get(0).kind()
                                == ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION
                        && directions.get(0).qualifierText().isEmpty()) return mode;
            }
        }
        return singleTechnique(clean);
    }

    private static int singleTechnique(String clean) {
        return switch (clean) {
            case "pizz", "pizzicato" -> ScoreTechniqueChange.PIZZICATO;
            case "arco" -> ScoreTechniqueChange.ARCO;
            case "cantabile" -> ScoreTechniqueChange.CANTABILE;
            case "sostenuto" -> ScoreTechniqueChange.SOSTENUTO;
            case "marcato" -> ScoreTechniqueChange.MARCATO;
            case "ordinario", "ord" -> ScoreTechniqueChange.ORDINARIO;
            default -> -1;
        };
    }

    static List<ScoreTechniqueChange> detectWithBows(
            List<Word> words,
            List<Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] labels,
            byte[] gray,
            int width,
            int height) {
        List<ScoreTechniqueChange> result =
                new ArrayList<>(detect(words, staffs, measures, notes, width, height));
        result.addAll(
                BowDirectionDetector.detect(labels, gray, width, height, staffs, measures, notes));
        result.sort(
                Comparator.comparingInt(ScoreTechniqueChange::measureIndex)
                        .thenComparingDouble(ScoreTechniqueChange::positionInMeasure)
                        .thenComparingInt(ScoreTechniqueChange::staffIndex));
        return List.copyOf(result);
    }

    static List<ScoreTechniqueChange> detect(
            List<Word> words,
            List<Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            int width,
            int height) {
        List<ScoreTechniqueChange> result = new ArrayList<>();
        List<TechniqueTextRegions.Region> localRegions = null;
        Map<MeasureStaff, List<ScoreNoteEvent>> notesByStaff = null;
        var measuresByStaff = new HashMap<Staff, List<Integer>>();
        var changesByStaff = new HashMap<MeasureStaff, List<ScoreTechniqueChange>>();
        for (Word word : words) {
            int technique = technique(word.text);
            if (technique < 0) continue;
            if (localRegions == null)
                localRegions = TechniqueTextRegions.above(staffs, measures, notes, width, height);
            float x = word.left * width;
            float top = word.top * height, bottom = word.bottom * height;
            Staff owner = null;
            float best = Float.MAX_VALUE, second = Float.MAX_VALUE;
            boolean ownerBelow = false;
            for (Staff staff : staffs) {
                var frame = TechniqueTextRegions.local(staff, x, localRegions);
                float above = (frame.top - bottom) / staff.gap;
                float below = (top - frame.bottom) / staff.gap;
                boolean upper = above >= -.6f && above <= 4.2f;
                // OCR boxes and the row-wide staff frame can differ by half a gap.
                // Require the expression to extend beyond the bottom rule too.
                boolean lower =
                        technique >= ScoreTechniqueChange.CANTABILE
                                && below >= -.6f
                                && below <= 4.2f
                                && bottom >= frame.bottom + staff.gap * .5f;
                if (!upper && !lower) continue;
                boolean isBelow = lower && (!upper || Math.abs(below) < Math.abs(above));
                float distance = isBelow ? Math.abs(below) : Math.abs(above);
                if (distance < best) {
                    second = best;
                    best = distance;
                    owner = staff;
                    ownerBelow = isBelow;
                } else second = Math.min(second, distance);
            }
            // A below-staff expression must have an unambiguous nearest lane.
            // Keep the established above-staff attachment behavior otherwise.
            if (owner == null || (ownerBelow && second - best < .6f)) continue;
            int measure = -1;
            float closest = Float.MAX_VALUE;
            float horizontalGap = owner.gap / width;
            var staffMeasures = measuresByStaff.computeIfAbsent(
                    owner, staff -> measuresForStaff(staff, measures, height));
            for (int m : staffMeasures) {
                var region = measures.get(m);
                if (word.left > region.right() + horizontalGap * .5f) continue;
                float distance = Math.max(0, region.left() - word.left);
                if (distance < closest) {
                    closest = distance;
                    measure = m;
                }
            }
            if (measure < 0 || closest > horizontalGap * 5) continue;
            var region = measures.get(measure);
            float position =
                    Math.max(
                            0,
                            Math.min(
                                    1,
                                    (word.left - region.left())
                                            / (region.right() - region.left())));
            // Engravers center labels slightly to the right of the first affected head.
            // Snap to that head only within one staff gap; otherwise retain the printed slot.
            float nearest = horizontalGap / (region.right() - region.left());
            float printedPosition = position;
            if (notesByStaff == null) {
                notesByStaff = new HashMap<>();
                for (var note : notes) {
                    var key = new MeasureStaff(note.measureIndex(), note.staffIndex());
                    notesByStaff.computeIfAbsent(key, ignored -> new ArrayList<>()).add(note);
                }
            }
            var key = new MeasureStaff(measure, owner.index);
            for (var note : notesByStaff.getOrDefault(key, List.of())) {
                float d = Math.abs(note.positionInMeasure() - printedPosition);
                if (d <= nearest) {
                    nearest = d;
                    position = note.positionInMeasure();
                }
            }
            var change =
                    new ScoreTechniqueChange(
                            measure, position, owner.index, owner.count, technique);
            boolean duplicate = false;
            var localChanges = changesByStaff.computeIfAbsent(key, ignored -> new ArrayList<>());
            for (var prior : localChanges) {
                if (prior.technique() == technique
                        && Math.abs(prior.positionInMeasure() - position) < .03f) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                localChanges.add(change);
                result.add(change);
            }
        }
        result.sort(
                Comparator.comparingInt(ScoreTechniqueChange::measureIndex)
                        .thenComparingDouble(ScoreTechniqueChange::positionInMeasure)
                        .thenComparingInt(ScoreTechniqueChange::staffIndex));
        return List.copyOf(result);
    }

    private static List<Integer> measuresForStaff(Staff staff, List<MeasureRegion> measures, int height) {
        float center = (staff.top + staff.bottom) * .5f / height;
        float gap = staff.gap / height;
        var result = new ArrayList<Integer>();
        for (int m = 0; m < measures.size(); m++) {
            var region = measures.get(m);
            if (center < region.top() - gap || center > region.bottom() + gap) continue;
            result.add(m);
        }
        return result;
    }
}
