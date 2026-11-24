// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class HarmonicAccidentalCarryTest {
    private static ScoreNoteEvent head(float position, int accidental) {
        return new ScoreNoteEvent(0, position, 10, 0, 1, .5f, false, 0, 2, accidental,
                0, 0, 0, 0, ScoreNoteEvent.CLEF_TREBLE);
    }

    @Test
    public void removedTouchDoesNotAlterStoppedPitch() {
        var touch = head(.2f, ScoreNoteEvent.ACCIDENTAL_FLAT);
        var stop = head(.4f, ScoreNoteEvent.ACCIDENTAL_FLAT);
        var stops = new HashMap<ScoreNoteEvent, ScoreNoteEvent>();
        stops.put(stop, stop.withOctaveShift(2));
        OctaveAccidentalCarry.restoreStoppedPitches(List.of(touch, stop),
                List.of(new OctaveAccidentalCarry(touch, stop)), Set.of(touch), stops);
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FROM_KEY, stops.get(stop).writtenAccidental());
        assertEquals(2, stops.get(stop).octaveShift());
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FLAT, touch.writtenAccidental());
    }

    @Test
    public void precedingStoppedSignStillGoverns() {
        var natural = head(.1f, ScoreNoteEvent.ACCIDENTAL_NATURAL);
        var touch = head(.2f, ScoreNoteEvent.ACCIDENTAL_FLAT);
        var stop = head(.4f, ScoreNoteEvent.ACCIDENTAL_FLAT);
        var stops = new HashMap<ScoreNoteEvent, ScoreNoteEvent>();
        stops.put(stop, stop.withOctaveShift(2));
        OctaveAccidentalCarry.restoreStoppedPitches(List.of(natural, touch, stop),
                List.of(new OctaveAccidentalCarry(touch, stop)), Set.of(touch), stops);
        assertEquals(ScoreNoteEvent.ACCIDENTAL_NATURAL, stops.get(stop).writtenAccidental());
    }

    @Test
    public void printedAccidentalAndRealOwnersStayIntact() {
        var owner = head(.1f, ScoreNoteEvent.ACCIDENTAL_FLAT);
        var unrelatedTouch = head(.2f, ScoreNoteEvent.ACCIDENTAL_NATURAL);
        var stop = head(.3f, ScoreNoteEvent.ACCIDENTAL_FLAT);
        var written = head(.4f, ScoreNoteEvent.ACCIDENTAL_SHARP);
        var stops = new HashMap<ScoreNoteEvent, ScoreNoteEvent>();
        stops.put(stop, stop.withOctaveShift(2));
        stops.put(written, written.withOctaveShift(2));
        OctaveAccidentalCarry.restoreStoppedPitches(List.of(owner, unrelatedTouch, stop, written),
                List.of(new OctaveAccidentalCarry(owner, stop)), Set.of(unrelatedTouch), stops);
        assertEquals(ScoreNoteEvent.ACCIDENTAL_FLAT, stops.get(stop).writtenAccidental());
        assertEquals(ScoreNoteEvent.ACCIDENTAL_SHARP, stops.get(written).writtenAccidental());
    }
}
