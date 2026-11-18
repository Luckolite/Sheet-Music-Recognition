// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import org.junit.Test;

public class StaggeredDyadAccidentalTest {
    private static final int WIDTH = 280, HEIGHT = 240;
    private final byte[] ink = new byte[WIDTH * HEIGHT];
    private final byte[] labels = new byte[ink.length];

    public StaggeredDyadAccidentalTest() {
        Arrays.fill(ink, (byte) 255);
        for (int y = 145; y <= 173; y++) glyph(y, 133, 135);
        for (int y = 158; y <= 186; y++) glyph(y, 141, 143);
        for (int base : new int[] {160, 174}) {
            for (int x = 133; x <= 143; x++) {
                int y = Math.round(base - (x - 138) * .25f);
                for (int dy = -1; dy <= 1; dy++) glyph(y + dy, x, x);
            }
        }
        for (int y = 126; y <= 161; y++) glyph(y, 149, 151);
        for (int y = 146; y <= 150; y++) glyph(y, 149, 157);
        for (int y = 159; y <= 160; y++) glyph(y, 143, 151);
    }

    @Test
    public void originalCrossbarsInTheLeftSliceIdentifyTheLowerNatural() throws Exception {
        assertTrue(proves(ink, ScoreNoteEvent.ACCIDENTAL_NATURAL));
        assertFalse(proves(ink, ScoreNoteEvent.ACCIDENTAL_SHARP));
        byte[] partial = ink.clone();
        for (int y = 169; y <= 177; y++)
            Arrays.fill(partial, y * WIDTH + 136, y * WIDTH + 141, (byte) 255);
        assertFalse(proves(partial, ScoreNoteEvent.ACCIDENTAL_NATURAL));
    }

    @Test
    public void staffInkDoesNotExtendTheTailOfTheOriginalNatural() throws Exception {
        byte[] original = ink.clone();
        for (int y = 154; y <= 157; y++)
            Arrays.fill(original, y * WIDTH + 138, y * WIDTH + 151, (byte) 0);
        assertTrue(proves(original, ScoreNoteEvent.ACCIDENTAL_NATURAL, true));
        byte[] partial = original.clone();
        for (int y = 169; y <= 177; y++)
            Arrays.fill(partial, y * WIDTH + 136, y * WIDTH + 141, (byte) 255);
        assertFalse(proves(partial, ScoreNoteEvent.ACCIDENTAL_NATURAL, true));
    }

    private void glyph(int y, int left, int right) {
        Arrays.fill(ink, y * WIDTH + left, y * WIDTH + right + 1, (byte) 0);
        Arrays.fill(labels, y * WIDTH + left, y * WIDTH + right + 1, OmrMeasurePostProcessor.CLEF_OR_KEY);
    }

    private boolean proves(byte[] source, int kind) throws Exception {
        return proves(source, kind, false);
    }

    private boolean proves(byte[] source, int kind, boolean useMembership) throws Exception {
        var interpreter = OmrScoreInterpreter.class;
        var component = Class.forName(interpreter.getName() + "$Component");
        var collector = OmrScoreInterpreter.tremoloMemberCollector(ink, labels, WIDTH, HEIGHT);
        var lookup = interpreter.getDeclaredMethod("findComponents", byte[].class, int.class, int.class,
                byte.class, byte[].class, IdentityHashMap.class, ExactComponentMembers.Collector.class);
        lookup.setAccessible(true);
        var shapes = (List<?>) lookup.invoke(null, labels, WIDTH, HEIGHT, OmrMeasurePostProcessor.CLEF_OR_KEY,
                ink, null, collector);
        assertTrue(collector.seal(new ExactComponentMembers.Budget(2L * labels.length + 4096, 4096)));
        var make = component.getDeclaredConstructors()[0];
        make.setAccessible(true);
        var note = make.newInstance(210, 159, 181, 162, 178, 170f, 170f);
        if (useMembership) {
            var recover = interpreter.getDeclaredMethod("naturalFromOwnedCrossbarsWithoutStaff",
                    byte[].class, byte[].class, int.class, int.class,
                    ExactComponentMembers.Collector.class, component, component, float.class);
            recover.setAccessible(true);
            for (var shape : shapes)
                if ((boolean) recover.invoke(null, source, source, WIDTH, HEIGHT, collector, shape, note, 16f))
                    return true;
            return false;
        }
        var recover = interpreter.getDeclaredMethod("ownedLeftCrossbars", byte[].class, byte[].class,
                int.class, int.class, ExactComponentMembers.Collector.class,
                component, component, float.class, int.class);
        recover.setAccessible(true);
        for (var shape : shapes)
            if ((boolean) recover.invoke(null, ink, source, WIDTH, HEIGHT, collector, shape, note, 16f, kind))
                return true;
        return false;
    }
}
