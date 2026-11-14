// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** A closed common-time bar can continue an unnumbered eighth-triplet run into sixteenths. */
final class EighthTripletsBeforeSixteenths {
    private EighthTripletsBeforeSixteenths() {}

    static ScorePageInterpretation capture(
            ScorePageInterpretation score, byte[] source, int width, int height) {
        if (width < 1
                || height < 1
                || source == null
                || source.length != (long) width * height
                || score.measures().size() < 2
                || score.meterChanges().isEmpty()) return score;
        var notes = new ArrayList<>(score.notes());
        var bars = score.measures();
        var owners = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) owners.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) {
            int measure = notes.get(i).measureIndex();
            if (measure >= 0 && measure < bars.size()) owners.get(measure).add(i);
        }
        int[] numerators = new int[bars.size()], denominators = new int[bars.size()];
        for (var m : score.meterChanges())
            if (m.measureIndex() >= 0 && m.measureIndex() < bars.size()) {
                numerators[m.measureIndex()] = m.numerator();
                denominators[m.measureIndex()] = m.denominator();
            }
        int numerator = 0, denominator = 0;
        boolean previousCommon = false, changed = false;
        for (int i = 0; i < bars.size(); i++) {
            if (denominators[i] != 0) {
                numerator = numerators[i];
                denominator = denominators[i];
            }
            boolean common = numerator == 4 && denominator == 4;
            if (i > 0
                    && common
                    && previousCommon
                    && ownsClosedTransition(
                            score,
                            notes,
                            owners.get(i - 1),
                            owners.get(i),
                            i,
                            source,
                            width,
                            height)) {
                for (int index : owners.get(i).subList(0, 6))
                    notes.set(index, notes.get(index).withTupletRatio(3, 2));
                changed = true;
            }
            previousCommon = common;
        }
        return changed
                ? score.withNotes(notes, IntStream.range(0, notes.size()).boxed().toList())
                        .withClockContextSource(score.clockContextSource())
                : score;
    }

    private static boolean ownsClosedTransition(
            ScorePageInterpretation score,
            List<ScoreNoteEvent> notes,
            List<Integer> previous,
            List<Integer> owners,
            int bar,
            byte[] source,
            int width,
            int height) {
        var region = score.measures().get(bar);
        var before = score.measures().get(bar - 1);
        if (previous.size() != 12
                || owners.size() != 14
                || region.cadenceQuarterBeats() != 0
                || before.cadenceQuarterBeats() != 0
                || score.rests().stream()
                        .anyMatch(r -> r.measureIndex() == bar || r.measureIndex() == bar - 1)
                || score.multiRestGroups().stream()
                        .anyMatch(
                                g ->
                                        g.firstMeasureIndex() <= bar
                                                && g.firstMeasureIndex() + g.measureCount()
                                                        > bar - 1)) return false;
        Comparator<Integer> engraved =
                Comparator.comparingDouble(i -> notes.get(i).positionInMeasure());
        previous.sort(engraved);
        owners.sort(engraved);
        int clef = notes.get(previous.get(0)).clefBottomDiatonic();
        for (int j = 0; j < previous.size(); j++) {
            var n = notes.get(previous.get(j));
            if (!ordinary(n, 1, clef)) return false;
            if (j > 0
                    && n.positionInMeasure() - notes.get(previous.get(j - 1)).positionInMeasure()
                            <= .018f) return false;
        }
        if (notes.get(previous.get(0)).positionInMeasure() > .18f
                || notes.get(previous.get(11)).positionInMeasure() < .8f
                || notes.get(owners.get(0)).positionInMeasure() > .18f
                || notes.get(owners.get(13)).positionInMeasure() < .86f) return false;
        for (int j = 0; j < owners.size(); j++)
            if (!ordinary(notes.get(owners.get(j)), j < 6 ? 1 : 2, clef)) return false;

        var opening = owners.subList(0, 6);
        var ending = owners.subList(6, 14);
        return WrappedEighthTriplets.mixedOpeningGroups(
                                source, width, height, before, notes, previous.subList(0, 6))
                        != null
                && WrappedEighthTriplets.mixedOpeningGroups(
                                source, width, height, region, notes, opening)
                        != null
                && WrappedEighthTriplets.doubleRailGroups(
                                source, width, height, region, notes, ending)
                        != null
                && WrappedEighthTriplets.mixedCut(
                        source,
                        width,
                        height,
                        region,
                        notes.get(owners.get(5)),
                        notes.get(owners.get(6)));
    }

    private static boolean ordinary(ScoreNoteEvent n, int beams, int clef) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffIndex() == 0
                && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.beamCount() == beams
                && n.augmentationDots() == 0
                && n.unbeamedDurationBeats() == 0
                && !n.crossStaffBeam()
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && !n.tiedFromPrevious()
                && n.boundaryTies() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0
                && clef != ScoreNoteEvent.CLEF_UNKNOWN
                && n.clefBottomDiatonic() == clef
                && n.tupletDivisor() == 1
                && n.tupletNormalNotes() == 1
                && Math.abs(n.stemDirection()) == 1;
    }
}
