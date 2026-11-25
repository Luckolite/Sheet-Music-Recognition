// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class StackedNaturalAccidentalTest {
    @Test
    public void bothPrintedNaturalsAreRequiredForSharedStemRecovery() throws Exception {
        var fixture = new JoinedSignatureSharpTest();
        natural(fixture, 88, false);
        natural(fixture, 144, true);

        var interpreter = OmrScoreInterpreter.class;
        var componentType = Class.forName(interpreter.getName() + "$Component");
        var component = componentType.getDeclaredConstructors()[0];
        component.setAccessible(true);
        var candidate = Class.forName(interpreter.getName() + "$AccidentalCandidate")
                .getDeclaredConstructors()[0];
        candidate.setAccessible(true);
        var upperGlyph = component.newInstance(240, 74, 84, 66, 106, 79f, 86f);
        var lowerGlyph = component.newInstance(250, 74, 87, 122, 162, 80f, 142f);
        var upperHead = component.newInstance(185, 87, 104, 81, 95, 95f, 88f);
        var lowerHead = component.newInstance(185, 87, 104, 137, 151, 95f, 144f);
        var heads = List.of(upperHead, lowerHead);
        var candidates = List.of(candidate.newInstance(upperGlyph, OmrMeasurePostProcessor.CLEF_OR_KEY));
        var recover = interpreter.getDeclaredMethod("naturalAtStackedHead", byte[].class,
                byte[].class, byte[].class, int.class, int.class, componentType, componentType,
                List.class, List.class, float.class);
        recover.setAccessible(true);

        int width = JoinedSignatureSharpTest.W, height = JoinedSignatureSharpTest.H;
        byte[] original = fixture.gray.clone();
        assertTrue((boolean) recover.invoke(null, fixture.labels, fixture.gray, original,
                width, height, lowerGlyph, lowerHead, heads, candidates, 16f));
        assertFalse((boolean) recover.invoke(null, fixture.labels, fixture.gray, original,
                width, height, lowerGlyph, lowerHead, heads, List.of(), 16f));
        for (int y = 120; y <= 164; y++) Arrays.fill(original, y * width + 73, y * width + 88, (byte) 255);
        assertFalse((boolean) recover.invoke(null, fixture.labels, fixture.gray, original,
                width, height, lowerGlyph, lowerHead, heads, candidates, 16f));
    }

    private static void natural(JoinedSignatureSharpTest fixture, int center, boolean attached) {
        for (int x = 74; x <= 76; x++)
            for (int y = center - 22; y <= center + 9; y++) fixture.ink(x, y, 3);
        for (int x = 82; x <= 84; x++)
            for (int y = center - 10; y <= center + 18; y++) fixture.ink(x, y, 3);
        for (int x = 75; x <= 83; x++) {
            int rise = (x - 75) / 3;
            for (int y = center - 5 - rise; y <= center - 2 - rise; y++) fixture.ink(x, y, 3);
            for (int y = center + 8 - rise; y <= center + 11 - rise; y++) fixture.ink(x, y, 3);
        }
        if (attached) {
            for (int y = center - 16; y <= center + 24; y++) fixture.ink(87, y, 1);
            for (int x = 84; x <= 87; x++) fixture.ink(x, center + 6, 3);
        }
    }
}
