// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;

/** The selected opening tempo controls playback; printed later changes keep their proportions. */
public final class ScoreTempoSelection {
    private ScoreTempoSelection() {}

    /** Non-quarter printed pulses take precedence over the meter's default pulse unit. */
    public static double selectedQuarterBpm(
            double selectedMarkedBpm, double fallbackQuarterBpm, List<ScoreTempoChange> printed) {
        if (printed != null)
            for (var mark : printed)
                if (mark.measureIndex() == 0
                        && mark.positionInMeasure() == 0
                        && mark.beatUnit() != 1) return selectedMarkedBpm * mark.beatUnit();
        return fallbackQuarterBpm;
    }

    /**
     * Resolve a saved metronome number against a printed opening mark. An unset tempo or a
     * saved number that matches the printed pulse uses the mark's quarter-note rate.
     * A different catalog pulse unit also permits a whole-BPM rounded match.
     * Other values keep the caller's already converted quarter-note fallback.
     */
    public static double playbackQuarterBpm(
            int savedBpm, double fallbackQuarterBpm, List<ScoreTempoChange> printed) {
        if (printed != null) {
            for (var mark : printed) {
                if (mark.measureIndex() != 0 || mark.positionInMeasure() != 0) continue;
                if (savedBpm <= 0 || (savedBpm >= 30 && savedBpm <= 400
                        && savedBpm * mark.beatUnit() == mark.bpm()))
                    return mark.bpm();
                if (matchesSavedPrintedPulse(savedBpm, fallbackQuarterBpm, mark)
                        && unambiguousOpening(printed) != null)
                    return mark.bpm();
                break;
            }
        }
        return fallbackQuarterBpm;
    }

    /**
     * A saved BPM that matches the primary edition's opening applies to that edition.
     * Use an alternate edition's own opening when both printed marks are unambiguous.
     */
    public static double arrangementQuarterBpm(
            int savedBpm, double fallbackQuarterBpm, List<ScoreTempoChange> printed,
            List<ScoreTempoChange> primaryPrinted) {
        var primary = unambiguousOpening(primaryPrinted);
        if (savedBpm >= 30 && savedBpm <= 400 && primary != null
                && (matchesSavedPrintedPulse(savedBpm, fallbackQuarterBpm, primary)
                        || fallbackQuarterBpm == primary.bpm())) {
            var selected = unambiguousOpening(printed);
            if (selected != null) return selected.bpm();
        }
        return playbackQuarterBpm(savedBpm, fallbackQuarterBpm, printed);
    }

    private static ScoreTempoChange unambiguousOpening(List<ScoreTempoChange> printed) {
        ScoreTempoChange opening = null;
        if (printed == null) return null;
        for (var mark : printed) {
            if (mark.measureIndex() != 0 || mark.positionInMeasure() != 0) continue;
            if (opening != null && !opening.equals(mark)) return null;
            opening = mark;
        }
        return opening;
    }

    private static boolean matchesSavedPrintedPulse(
            int savedBpm, double fallbackQuarterBpm, ScoreTempoChange mark) {
        if (savedBpm < 30 || savedBpm > 400) return false;
        if (savedBpm * mark.beatUnit() == mark.bpm()) return true;
        // The catalog stores whole BPM. Apply its rounding only when the two pulse units differ.
        return fallbackQuarterBpm != savedBpm * mark.beatUnit()
                && Math.round(mark.bpm() / mark.beatUnit()) == savedBpm;
    }

    public static List<ScoreTempoChange> atOpeningTempo(
            double selectedQuarterBpm, List<ScoreTempoChange> printed) {
        if (printed == null || printed.isEmpty()) return List.of();
        ScoreTempoChange opening = null;
        for (ScoreTempoChange mark : printed)
            if (mark.measureIndex() == 0 && mark.positionInMeasure() == 0) {
                opening = mark;
                break;
            }
        if (opening == null) return List.copyOf(printed);
        double ratio = selectedQuarterBpm / opening.bpm();
        var result = new java.util.ArrayList<ScoreTempoChange>();
        for (ScoreTempoChange mark : printed)
            result.add(
                    new ScoreTempoChange(
                            mark.measureIndex(),
                            mark.positionInMeasure(),
                            Math.max(15, Math.min(1600, mark.bpm() * ratio)),
                            mark.beatUnit()));
        return List.copyOf(result);
    }

    /** Re-engraving uses the selected tempo without losing the printed pulse or later changes. */
    public static List<ScoreTempoChange> forEngraving(
            double selectedMarkedBpm,
            double fallbackQuarterBpm,
            double fallbackBeatUnit,
            List<ScoreTempoChange> printed) {
        var ordered =
                (printed == null ? List.<ScoreTempoChange>of() : printed)
                        .stream()
                                .sorted(
                                        java.util.Comparator.comparingInt(
                                                        ScoreTempoChange::measureIndex)
                                                .thenComparingDouble(
                                                        ScoreTempoChange::positionInMeasure))
                                .toList();
        if (selectedMarkedBpm <= 0) return ordered;
        double quarterBpm = selectedQuarterBpm(selectedMarkedBpm, fallbackQuarterBpm, ordered);
        return forEngravingQuarterBpm(quarterBpm, fallbackBeatUnit, ordered);
    }

    /** Re-engraves an already converted quarter-note clock without applying the printed unit again. */
    public static List<ScoreTempoChange> forEngravingQuarterBpm(
            double quarterBpm, double fallbackBeatUnit, List<ScoreTempoChange> printed) {
        var ordered =
                (printed == null ? List.<ScoreTempoChange>of() : printed)
                        .stream()
                                .sorted(
                                        java.util.Comparator.comparingInt(
                                                        ScoreTempoChange::measureIndex)
                                                .thenComparingDouble(
                                                        ScoreTempoChange::positionInMeasure))
                                .toList();
        if (quarterBpm <= 0) return ordered;
        if (ordered.stream()
                .anyMatch(mark -> mark.measureIndex() == 0 && mark.positionInMeasure() == 0))
            return atOpeningTempo(quarterBpm, ordered);
        var result = new java.util.ArrayList<ScoreTempoChange>();
        result.add(new ScoreTempoChange(0, 0, quarterBpm, fallbackBeatUnit));
        result.addAll(ordered);
        return List.copyOf(result);
    }
}
