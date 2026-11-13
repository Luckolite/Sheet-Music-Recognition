// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Captured first/final measure spans, separate from printed meter and note records. */
public record ScoreBoundaryClock(double openingQuarterBeats, double closingQuarterBeats,
        java.util.Map<Integer, Integer> sourceCadences) {
    public ScoreBoundaryClock(double openingQuarterBeats, double closingQuarterBeats) {
        this(openingQuarterBeats, closingQuarterBeats, java.util.Map.of());
    }

    public ScoreBoundaryClock withSourceCadences(java.util.Map<Integer, Integer> cadences) {
        return new ScoreBoundaryClock(openingQuarterBeats, closingQuarterBeats, cadences);
    }

    public static java.util.Map<Integer, Integer> cadences(java.util.List<MeasureRegion> regions) {
        var result = new java.util.LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < regions.size(); i++) {
            int duration = regions.get(i).cadenceQuarterBeats();
            if (duration != 0) result.put(i, duration);
        }
        return java.util.Map.copyOf(result);
    }
    public ScoreBoundaryClock {
        sourceCadences = sourceCadences == null ? java.util.Map.of() : java.util.Map.copyOf(sourceCadences);
        for (var entry : sourceCadences.entrySet())
            if (entry.getKey() < 0 || entry.getValue() < 1 || entry.getValue() > 2)
                throw new IllegalArgumentException("Invalid printed cadence");
        if (!Double.isFinite(openingQuarterBeats)
                || !Double.isFinite(closingQuarterBeats)
                || openingQuarterBeats < 0
                || closingQuarterBeats < 0
                || (openingQuarterBeats == 0) != (closingQuarterBeats == 0))
            throw new IllegalArgumentException("Invalid captured boundary clock");
    }

    /** Both zero means legacy state, whose complete retained source still needs inference. */
    public static ScoreBoundaryClock legacy() {
        return new ScoreBoundaryClock(0, 0);
    }

    public boolean retained() {
        return openingQuarterBeats > 0;
    }

    public ScoreMeterMap meter(ScorePageInterpretation source, float openingNominal) {
        var nominal = new ScoreMeterMap(openingNominal, source.meterChanges());
        int count = source.measures().size();
        var bounded = retained() ? nominal.withBoundaryQuarterBeats(openingQuarterBeats, closingQuarterBeats, count)
                : nominal;
        var cadences = sourceCadences.isEmpty() ? cadences(source.measures()) : sourceCadences;
        return bounded.withSourceCadences(cadences, count);
    }

    /** Resolve once from every current source part, before selection or engraved reflow. */
    public static ScoreBoundaryClock resolve(ScorePageInterpretation source, float openingNominal) {
        int count = source.measures().size();
        if (count == 0) return legacy();
        var nominal = new ScoreMeterMap(openingNominal, source.meterChanges());
        double opening = ScoreOpeningDuration.provedQuarterBeats(source, nominal.beatsInMeasure(0));
        double closing =
                ScoreOpeningDuration.provedClosingQuarterBeats(
                        source, nominal.beatsInMeasure(count - 1));
        opening = Double.isFinite(opening) ? opening : nominal.beatsInMeasure(0);
        closing = Double.isFinite(closing) ? closing : nominal.beatsInMeasure(count - 1);
        if (count == 1) opening = closing;
        var result = new ScoreBoundaryClock(opening, closing);
        result.meter(source, openingNominal);
        return result;
    }
}
