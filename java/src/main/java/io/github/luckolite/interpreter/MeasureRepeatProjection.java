// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** Uses an earlier measure's sounding content while keeping the destination's clock and cursor. */
public final class MeasureRepeatProjection {
    private MeasureRepeatProjection() {}

    public static ScoreNavigationNoteProjection.Result append(
            ScoreNavigationNoteProjection.Result ordinary,
            List<ScoreNavigationNoteProjection.SourceNote> sounds,
            ScoreNavigationPlan plan,
            ScoreMeterMap meter,
            Set<Integer> repeats) {
        if (repeats.isEmpty()) return ordinary;
        int count = plan.sourceMeasureCount();
        var sourceBars = new HashMap<Integer, List<ScoreNavigationNoteProjection.SourceNote>>();
        for (var sound : sounds) {
            int measure = (int) Math.floor(meter.measurePosition(sound.startBeat()));
            if (measure >= 0 && measure < count)
                sourceBars.computeIfAbsent(measure, ignored -> new ArrayList<>()).add(sound);
        }
        int[] content = new int[count];
        Arrays.fill(content, -1);
        for (int target = 1; target < count; target++) {
            if (!repeats.contains(target) || sourceBars.containsKey(target)) continue;
            int source = repeats.contains(target - 1) ? content[target - 1] : target - 1;
            if (source >= 0 && Math.abs(meter.quarterBeatsInMeasure(source)
                    - meter.quarterBeatsInMeasure(target)) < 1e-8)
                content[target] = source;
        }
        var expanded = new ArrayList<>(ordinary.notes());
        for (var visit : plan.traversal().occurrences()) {
            int target = visit.start().measureIndex();
            int source = content[target];
            if (source < 0) continue;
            double sourceStart = meter.startBeat(source);
            double sourceEnd = meter.startBeat(source + 1);
            double targetStart = meter.startBeat(target);
            double visitStart = visit.start().absoluteBeat(meter) - targetStart;
            double visitEnd = visit.end().absoluteBeat(meter) - targetStart;
            for (var sound : sourceBars.getOrDefault(source, List.of())) {
                double onset = sound.startBeat() - sourceStart;
                double from = Math.max(onset, visitStart);
                double to = Math.min(Math.min(sound.endBeat(), sourceEnd) - sourceStart, visitEnd);
                if (to <= from) continue;
                expanded.add(new ScoreNavigationNoteProjection.PerformedNote(
                        "simile/" + visit.occurrenceId() + "/" + sound.sourceId(),
                        sound.sourceId(), sound.sourceIndex(),
                        visit.performanceStartBeat() + from - visitStart,
                        visit.performanceStartBeat() + to - visitStart,
                        sourceStart + from, sourceStart + to,
                        from > onset, sourceStart + to < sound.endBeat(),
                        List.of(visit.occurrenceId())));
            }
        }
        expanded.sort(Comparator.comparingDouble(ScoreNavigationNoteProjection.PerformedNote::startBeat)
                .thenComparingInt(ScoreNavigationNoteProjection.PerformedNote::sourceIndex));
        return new ScoreNavigationNoteProjection.Result(expanded, ordinary.performedBeats());
    }
}
