// Copyright 2026 Luckolite

// SPDX-License-Identifier: Apache-2.0

package io.github.luckolite.interpreter;

import java.util.*;

/** Actual opening-bar duration without replacing its printed time signature. */
public final class ScoreOpeningDuration {

    private ScoreOpeningDuration() {}

    /** All source consumers share the same independently proved opening clock. */
    public static double provedQuarterBeats(ScorePageInterpretation source, float nominal) {
        if (source == null) return Double.NaN;
        try (var session = ScoreNoteTiming.beginTimingSession(source)) {
            double silent =
                    provedNumberedSilentPickup(
                            source.notes(), source.rests(), nominal, source.openingPickup());
            return Double.isFinite(silent)
                    ? silent
                    : provedQuarterBeats(
                            source.notes(),
                            source.rests(),
                            source.measures(),
                            nominal,
                            source.firstMeasureNumber(),
                            source.openingPickup(), true, startsDocument(source));
        }
    }

    /** Silent pickups require independent numbering proof and explicit rests on every staff. */
    public static double provedNumberedSilentPickup(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            float nominal,
            boolean numberedPickup) {
        if (!numberedPickup
                || notes == null
                || rests == null
                || !Float.isFinite(nominal)
                || nominal <= 0
                || notes.stream().anyMatch(n -> n != null && n.measureIndex() == 0))
            return Double.NaN;
        List<ScoreRestEvent> opening =
                rests.stream()
                        .filter(r -> r != null && r.measureIndex() == 0)
                        .collect(java.util.stream.Collectors.toList());
        if (opening.isEmpty()) return Double.NaN;
        int count = opening.get(0).staffCount();
        if (count < 1 || count > 16) return Double.NaN;
        double[] durations = new double[count];
        boolean[] present = new boolean[count];
        for (ScoreRestEvent rest : opening) {
            if (rest.staffCount() != count
                    || rest.staffIndex() < 0
                    || rest.staffIndex() >= count
                    || rest.isFullMeasure()
                    || !Float.isFinite(rest.positionInMeasure())
                    || rest.positionInMeasure() < 0
                    || rest.positionInMeasure() > 1
                    || !Double.isFinite(rest.durationBeats())
                    || rest.durationBeats() <= 0) return Double.NaN;
            // Multiple rests can represent voices or duplicates: leave ambiguous lanes alone.
            if (present[rest.staffIndex()]) return Double.NaN;
            present[rest.staffIndex()] = true;
            durations[rest.staffIndex()] = rest.durationBeats();
        }
        double span = durations[0];
        for (int staff = 0; staff < count; staff++)
            if (!present[staff] || Math.abs(durations[staff] - span) > .001) return Double.NaN;
        return span >= .125 && span < nominal - .001 ? span : Double.NaN;
    }

    /** Returns NaN unless a short layout and every printed staff agree on explicit contents. */
    public static double provedQuarterBeats(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            List<MeasureRegion> measures,
            float nominal,
            int firstMeasureNumber) {
        return provedQuarterBeats(notes, rests, measures, nominal, firstMeasureNumber, false, false, false);
    }

    private static double provedQuarterBeats(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            List<MeasureRegion> measures,
            float nominal,
            int firstMeasureNumber,
            boolean numberedPickup, boolean checkOpeningRhythm, boolean startsDocument) {

        if (firstMeasureNumber > 1
                || notes == null
                || rests == null
                || measures == null
                || measures.size() < 3
                || !Float.isFinite(nominal)
                || nominal <= 0) return Double.NaN;

        MeasureRegion first = measures.get(0);

        float width = first.right() - first.left(), height = first.bottom() - first.top();

        List<Float> widths = new ArrayList<>();

        for (int i = 1; i < measures.size(); i++) {

            MeasureRegion m = measures.get(i);

            if (Math.abs(m.top() - first.top()) > height * .25f
                    || Math.abs(m.bottom() - first.bottom()) > height * .25f) break;

            if (m.left() <= first.right()) return Double.NaN;

            widths.add(m.right() - m.left());
        }

        if (widths.size() < 2 || width <= 0) return Double.NaN;

        widths.sort(Float::compare);

        // Independent system numbers and occupied neighbor bars corroborate the
        // opening boundary when signatures make the pickup wider than usual.
        boolean alignedRest = checkOpeningRhythm && !numberedPickup
                && width <= widths.get(widths.size() / 2) * .75f
                && corroboratedAlignedOpeningRest(notes, rests) && hasPitchedNeighbors(notes);
        float referenceWidth = widths.get(widths.size() / 2);
        boolean rhythmProvesPickup = checkOpeningRhythm && !numberedPickup
                && corroboratedOpeningRhythm(notes, rests, nominal, width, referenceWidth, startsDocument);
        float maximumRatio = numberedPickup && hasPitchedNeighbors(notes) ? .85f
                : alignedRest ? .75f : rhythmProvesPickup ? .62f : .5f;
        if (width > referenceWidth * maximumRatio) return Double.NaN;
        return explicitSpan(notes, rests, 0, nominal, false,
                numberedPickup || rhythmProvesPickup || alignedRest, startsDocument);
    }

    private static boolean startsDocument(ScorePageInterpretation source) {
        var origin = source.clockContextSource().origin();
        return origin.boundDocument() && origin.physicalPageIndex() == 0;
    }

    private static boolean corroboratedAlignedOpeningRest(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests) {
        ScoreRestEvent opening = null;
        for (var rest : rests) {
            if (rest == null || rest.measureIndex() != 0) continue;
            if (rest.staffCount() != 2 || rest.staffIndex() < 0
                    || rest.staffIndex() > 1 || rest.kind() != ScoreRestEvent.Kind.LITERAL)
                return false;
            if (opening != null && opening.staffIndex() != rest.staffIndex()) return false;
            if (opening == null || rest.positionInMeasure() < opening.positionInMeasure()) opening = rest;
        }
        if (opening == null) return false;
        float firstAttack = Float.POSITIVE_INFINITY;
        for (var note : notes) {
            if (note == null || note.measureIndex() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0) continue;
            if (note.staffCount() != 2 || note.staffIndex() != 1 - opening.staffIndex()
                    || note.kind() != ScoreNoteEvent.Kind.PITCHED) return false;
            firstAttack = Math.min(firstAttack, note.positionInMeasure());
        }
        return Math.abs(firstAttack - opening.positionInMeasure()) <= .06f;
    }

    private static boolean corroboratedOpeningRhythm(
            List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests, float nominal,
            float openingWidth, float neighborWidth, boolean startsDocument) {
        boolean wider = openingWidth > neighborWidth * .42f;
        if ((wider && (!startsDocument || openingWidth > neighborWidth * .62f))
                || rests.stream().anyMatch(rest -> rest != null && rest.measureIndex() <= 2)) return false;
        double[] duration = new double[3];
        float[] position = {-1, -1, -1};
        int[] attacks = new int[3];
        for (var note : notes) {
            if (note == null || note.measureIndex() > 2
                    || (note.articulations() & NoteOrnament.GRACE) != 0) continue;
            int bar = note.measureIndex();
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffIndex() != 0
                    || note.staffCount() != 1 || note.crossStaffBeam()
                    || note.leadingRestBeats() > 0 || note.followingRestBeats() > 0
                    || !Float.isFinite(note.positionInMeasure())
                    || note.positionInMeasure() <= position[bar] + .003f
                    || attacks[bar] == 0 && bar > 0 && note.positionInMeasure() > .20f)
                return false;
            double advance = ScoreNoteTiming.advanceDurationBeats(note);
            if (!Double.isFinite(advance) || advance <= 0) return false;
            duration[bar] += advance;
            position[bar] = note.positionInMeasure();
            attacks[bar]++;
        }
        return attacks[0] > 0 && attacks[0] <= 4 && duration[0] <= nominal * .5
                && (!wider || attacks[0] >= 2 && position[0] >= .6f)
                && Math.abs(duration[1] - nominal) <= .001 && Math.abs(duration[2] - nominal) <= .001;
    }

    private static boolean hasPitchedNeighbors(List<ScoreNoteEvent> notes) {
        boolean first = false, second = false;
        for (var note : notes) {
            if (note == null || note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || (note.articulations() & NoteOrnament.GRACE) != 0) continue;
            if (note.measureIndex() == 1) first = true;
            if (note.measureIndex() == 2) second = true;
            if (first && second) return true;
        }
        return false;
    }

    public static double provedClosingQuarterBeats(ScorePageInterpretation source, float nominal) {
        if (source == null) return Double.NaN;
        try (var session = ScoreNoteTiming.beginTimingSession(source)) {
            return provedClosingQuarterBeats(
                    source.notes(), source.rests(), source.measures().size(), nominal);
        }
    }

    /** A shortened ending requires agreeing independent staves and explicit terminal rests. */
    public static double provedClosingQuarterBeats(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            int measureCount,
            float nominal) {

        if (notes == null
                || rests == null
                || measureCount < 1
                || !Float.isFinite(nominal)
                || nominal <= 0) return Double.NaN;

        return explicitSpan(notes, rests, measureCount - 1, nominal, true);
    }

    private static double explicitSpan(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            int measure,
            float nominal,
            boolean terminal) {
        return explicitSpan(notes, rests, measure, nominal, terminal, false, false);
    }

    private static double explicitSpan(
            List<ScoreNoteEvent> notes,
            List<ScoreRestEvent> rests,
            int measure,
            float nominal,
            boolean terminal, boolean numberedPickup, boolean startsDocument) {

        List<ScoreNoteEvent> opening =
                notes.stream()
                        .filter(
                                n ->
                                        n != null
                                                && n.measureIndex() == measure
                                                && (n.articulations() & NoteOrnament.GRACE) == 0)
                        .collect(java.util.stream.Collectors.toList());

        List<ScoreRestEvent> silence =
                rests.stream()
                        .filter(r -> r != null && r.measureIndex() == measure)
                        .collect(java.util.stream.Collectors.toList());

        if (opening.isEmpty()) return Double.NaN;

        int staffs = opening.get(0).staffCount();

        if (staffs < (terminal ? 2 : 1)
                || staffs > 16
                || opening.stream()
                        .anyMatch(
                                n ->
                                        n.staffCount() != staffs
                                                || n.staffIndex() < 0
                                                || n.staffIndex() >= staffs
                                                || !Float.isFinite(n.positionInMeasure())
                                                || n.tiedFromPrevious()
                                                || n.crossStaffBeam()
                                                // Incoming shoulders on the first physical page have no earlier note.
                                                || (n.boundaryTies() & (startsDocument ? 0b1100 : 0b1111)) != 0)
                || silence.stream()
                        .anyMatch(
                                r ->
                                        r.staffCount() != staffs
                                                || r.staffIndex() < 0
                                                || r.staffIndex() >= staffs
                                                || !Float.isFinite(r.positionInMeasure())))
            return Double.NaN;

        boolean omittedNumberedPickupLane =
                numberedPickup
                        && !terminal
                        && opening.stream().anyMatch(n -> n.kind() == ScoreNoteEvent.Kind.PITCHED);
        float chordTolerance = !terminal && staffs == 2 && silence.size() == 2
                && silence.get(0).staffIndex() != silence.get(1).staffIndex()
                && opening.stream().allMatch(n -> n.leadingRestBeats() > 0)
                ? .006f : .003f;
        double span = Double.NaN;
        int explicitLanes = 0;

        for (int staff = 0; staff < staffs; staff++) {

            final int lane = staff;

            List<ScoreNoteEvent> voice =
                    opening.stream()
                            .filter(n -> n.staffIndex() == lane)
                            .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                            .collect(java.util.stream.Collectors.toList());

            List<ScoreRestEvent> gaps =
                    silence.stream()
                            .filter(r -> r.staffIndex() == lane)
                            .collect(java.util.stream.Collectors.toList());

            if (voice.isEmpty() && gaps.isEmpty()) {
                if (omittedNumberedPickupLane) continue;
                return Double.NaN;
            }
            if (voice.isEmpty() && gaps.size() == 1 && gaps.get(0).isFullMeasure()) continue;
            gaps =
                    gaps.stream()
                            .filter(r -> !r.isFullMeasure())
                            .collect(java.util.stream.Collectors.toList());
            if (voice.isEmpty() && gaps.isEmpty()) return Double.NaN;

            if (terminal) {

                if (gaps.isEmpty()) return Double.NaN;

                float lastNote =
                        voice.isEmpty() ? -1 : voice.get(voice.size() - 1).positionInMeasure();

                if (gaps.stream().noneMatch(r -> r.positionInMeasure() > lastNote))
                    return Double.NaN;
            }

            double total = 0;

            for (ScoreRestEvent rest : gaps) {

                if (!Double.isFinite(rest.durationBeats()) || rest.durationBeats() <= 0)
                    return Double.NaN;

                total += rest.durationBeats();
            }

            float lastPosition = Float.NaN;

            double lastDuration = Double.NaN;

            for (ScoreNoteEvent note : voice) {

                double duration = ScoreNoteTiming.advanceDurationBeats(note);

                if (!Double.isFinite(duration) || duration <= 0) return Double.NaN;

                double before = 0, after = 0;

                for (ScoreRestEvent rest : gaps) {

                    if (rest.positionInMeasure() < note.positionInMeasure())
                        before += rest.durationBeats();
                    else after += rest.durationBeats();
                }

                if (note.leadingRestBeats() > before + .001
                        || note.followingRestBeats() > after + .001) return Double.NaN;

                if (Float.isFinite(lastPosition)
                        && note.positionInMeasure() - lastPosition < chordTolerance) {

                    if (Math.abs(duration - lastDuration) > .001) return Double.NaN;

                } else {

                    total += duration;

                    lastPosition = note.positionInMeasure();

                    lastDuration = duration;
                }
            }

            if (!numberedPickup
                    && !voice.isEmpty()
                    && gaps.isEmpty()
                    && voice.get(0).positionInMeasure() < .4f
                    && voice.stream().anyMatch(n -> !n.compactOpening())) return Double.NaN;

            if (Double.isFinite(span) && Math.abs(span - total) > .001) return Double.NaN;

            span = total;
            explicitLanes++;
        }

        return (!terminal || explicitLanes >= 2)
                        && Double.isFinite(span)
                        && span >= .125
                        && span < nominal - .001
                ? span
                : Double.NaN;
    }
}
