// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OneSidedDoubleSharpSeedTest {
    private static final int WIDTH = 110, HEIGHT = 100;

    @Test
    public void rawArmsRecoverTheDoubleSharpFromOneSemanticHalf() throws Exception {
        assertTrue(matches(true, true, true));
    }

    @Test
    public void aMissingSourceArmOrWrongSeedLabelRefusesRecovery() throws Exception {
        assertFalse(matches(true, false, true));
        assertFalse(matches(false, true, true));
        assertFalse(matches(true, true, false));
    }

    private static boolean matches(boolean decoderInk, boolean sourceInk, boolean accidentalSeed)
            throws Exception {
        byte[] decoded = new byte[WIDTH * HEIGHT], source = new byte[decoded.length];
        byte[] labels = new byte[decoded.length];
        Arrays.fill(decoded, (byte) 255);
        Arrays.fill(source, (byte) 255);
        byte type = accidentalSeed
                ? OmrMeasurePostProcessor.CLEF_OR_KEY : OmrMeasurePostProcessor.SYMBOL;
        int count = 0;
        for (int y = 0; y < 18; y++)
            for (int x = 0; x < 18; x++)
                if (Math.abs(x - y) <= 2 || Math.abs(x + y - 17) <= 2) {
                    int at = (31 + y) * WIDTH + 36 + x;
                    if (decoderInk || x >= 8) decoded[at] = 0;
                    if (sourceInk || x >= 8) source[at] = 0;
                    labels[at] = x < 8 ? OmrMeasurePostProcessor.SYMBOL : type;
                    if (x >= 8) count++;
                }
        var decoder = OmrScoreInterpreter.class;
        var component = Class.forName(decoder.getName() + "$Component");
        var accidental = Class.forName(decoder.getName() + "$AccidentalCandidate");
        var rect = component.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class, float.class, float.class);
        var wrapper = accidental.getDeclaredConstructor(component, byte.class);
        rect.setAccessible(true);
        wrapper.setAccessible(true);
        Object glyph = rect.newInstance(count, 44, 53, 31, 48, 49f, 39.5f);
        Object head = rect.newInstance(300, 57, 79, 31, 48, 68f, 39.5f);
        var method = decoder.getDeclaredMethod("rawCompactDoubleSharp", byte[].class, byte[].class,
                byte[].class, int.class, int.class, List.class, component, float.class, List.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, labels, decoded, source, WIDTH, HEIGHT,
                List.of(wrapper.newInstance(glyph, type)), head, 17f, List.of(head));
    }
}
