// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class PrintedOctaveFragmentsTest {
    private static final float[] BEATS = {4, 4};
    private static final OctaveMarkDetector.StaffSpan OPTICAL = new OctaveMarkDetector.StaffSpan(
            1, 1, 0, 1, 1, new OctaveMarkDetector.Fragment(110, 290));

    @Test
    public void usesAttackOnsetsInsteadOfTheOverlappingReleases() {
        var events = List.of(note(90, 4.75, .5, 0), note(120, 5, .75, 1),
                note(180, 5.25, .75, 1), note(270, 5.75, .5, 1), note(305, 6, .5, 0));
        assertEquals(List.of(new PrintedOctaveFragments.WrittenSpan(1, 1, 0, 1, 1, 10080, 20160)),
                PrintedOctaveFragments.retain(List.of(OPTICAL), events, List.of(), BEATS));
        assertEquals(List.of(), PrintedOctaveFragments.retain(List.of(OPTICAL), events, List.of(2), BEATS));
    }

    @Test
    public void anExcludedSimultaneousAttackOrUnprovedStopCannotAuthorizeTheFragment() {
        var start = note(120, 5, .5, 1);
        var end = note(260, 5.5, .5, 1);
        var adjacent = note(300, 6, .5, 0);
        var intruder = note(70, 5.25, .5, 0);
        assertEquals(List.of(),
                PrintedOctaveFragments.retain(List.of(OPTICAL), List.of(start, end, adjacent, intruder),
                        List.of(), BEATS));
        assertEquals(List.of(), PrintedOctaveFragments.retain(List.of(OPTICAL), List.of(start, end),
                List.of(), BEATS));
        var roundedOntoStart = note(90, 4.999999, .5, 0);
        assertEquals(List.of(), PrintedOctaveFragments.retain(
                List.of(OPTICAL), List.of(roundedOntoStart, start, end, adjacent), List.of(), BEATS));
    }

    private static Map<String, Object> note(double x, double onset, double duration, int shift) {
        return Map.of("measureIndex", 1, "staffIndex", 0, "staffCount", 1, "x", x,
                "startBeat", onset, "durationBeats", duration, "midi", 60 + 12 * shift, "octaveShift", shift);
    }
}
