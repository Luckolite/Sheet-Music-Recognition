// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Retains printed intra-measure octave boundaries after the sounding events are finalized. */
final class PrintedOctaveFragments {
    private static final int TICKS_PER_QUARTER = 10080;

    record CompleteSpan(int firstMeasureIndex, int lastMeasureIndex, int staffIndex,
            int staffCount, int octaveShift) {
        CompleteSpan(OctaveMarkDetector.StaffSpan span) {
            this(span.firstMeasureIndex(), span.lastMeasureIndex(), span.staffIndex(),
                    span.staffCount(), span.octaveShift());
        }
    }

    record WrittenSpan(int firstMeasureIndex, int lastMeasureIndex, int staffIndex, int staffCount,
            int octaveShift, long firstOnsetTicks, long followingOnsetTicks) {}

    private record Attack(long onset, int shift, boolean insideInk) {}

    private PrintedOctaveFragments() {}

    static List<WrittenSpan> retain(List<OctaveMarkDetector.StaffSpan> source,
            List<Map<String, Object>> events, List<Integer> continuations, float[] beats) {
        var result = new ArrayList<WrittenSpan>();
        long[] starts = new long[beats.length + 1];
        for (int i = 0; i < beats.length; i++)
            starts[i + 1] = starts[i] + ticks(beats[i]);
        for (var span : source) {
            int measure = span.firstMeasureIndex();
            if (span.fragment() == null || measure != span.lastMeasureIndex()
                    || measure < 0 || measure + 1 >= starts.length || continuations.contains(measure)
                    || continuations.contains(measure + 1)) continue;
            var retained = retain(span, events, starts[measure], starts[measure + 1]);
            if (retained != null) result.add(retained);
        }
        return List.copyOf(result);
    }

    private static WrittenSpan retain(OctaveMarkDetector.StaffSpan span, List<Map<String, Object>> events,
            long measureStart, long measureEnd) {
        var attacks = new ArrayList<Attack>();
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        for (var event : events) {
            if (!Integer.valueOf(span.firstMeasureIndex()).equals(event.get("measureIndex"))
                    || !Integer.valueOf(span.staffIndex()).equals(event.get("staffIndex"))
                    || !Integer.valueOf(span.staffCount()).equals(event.get("staffCount"))) continue;
            if (!(event.get("midi") instanceof Number) || !(event.get("x") instanceof Number x)
                    || !(event.get("startBeat") instanceof Number start)
                    || event.containsKey("writtenPlacement")) return null;
            long onset = ticks(start.doubleValue());
            if (!Double.isFinite(start.doubleValue()) || !Float.isFinite(x.floatValue())
                    || onset < measureStart || onset >= measureEnd) return null;
            int shift = (int) event.getOrDefault("octaveShift", 0);
            boolean inside = x.floatValue() >= span.fragment().left() && x.floatValue() <= span.fragment().right();
            if (inside) {
                if (shift != span.octaveShift()) return null;
                first = Math.min(first, onset);
                last = Math.max(last, onset);
            }
            attacks.add(new Attack(onset, shift, inside));
        }
        if (last <= first) return null;
        long following = Long.MAX_VALUE;
        for (var note : attacks) {
            if (note.insideInk) continue;
            if (note.onset >= first && note.onset <= last) return null;
            if (note.onset > last) following = Math.min(following, note.onset);
        }
        if (following == Long.MAX_VALUE) return null;
        for (var note : attacks)
            if (note.onset == following && note.shift == span.octaveShift()) return null;
        return new WrittenSpan(span.firstMeasureIndex(), span.lastMeasureIndex(), span.staffIndex(),
                span.staffCount(), span.octaveShift(), first - measureStart, following - measureStart);
    }

    private static long ticks(double beats) {
        return (long) Math.rint(beats * TICKS_PER_QUARTER);
    }
}
