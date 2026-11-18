// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** Verifies that the inline signature can own a proposed whole-note head. */
public class InlineWholeMeterAliasTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    private static final float GAP = 16;
    private static final int FIRST_LINE = 54;

    @Test
    public void bothPlanesBindStackedDigitsToTheSameInlineDivider() throws Exception {
        var image = InlineTripleMeterProofTest.signature('3', '4', true);
        assertTrue(proves(image, image));
    }

    @Test
    public void missingSecondStrokeOrDifferentDigitsRetainsTheNote() throws Exception {
        var decoded = InlineTripleMeterProofTest.signature('3', '4', true);
        assertFalse(proves(decoded, InlineTripleMeterProofTest.signature('3', '4', false)));
        assertFalse(proves(decoded, InlineTripleMeterProofTest.signature('2', '4', true)));
    }

    private static boolean proves(byte[] decoded, byte[] source) throws Exception {
        byte[] labels = new byte[decoded.length];
        for (int y = 70; y <= 86; y++) {
            for (int x = 125; x <= 150; x++) {
                if ((decoded[y * WIDTH + x] & 255) < 155)
                    labels[y * WIDTH + x] = OmrMeasurePostProcessor.NOTEHEAD;
            }
        }
        var rules = List.of(new MeterChangeDetector.Crop(
                103, FIRST_LINE - 3, 121, FIRST_LINE + 68, FIRST_LINE, GAP));
        var bars = List.of(
                new MeasureRegion(20f / WIDTH, 109f / WIDTH, 30f / HEIGHT, 145f / HEIGHT),
                new MeasureRegion(120f / WIDTH, 300f / WIDTH, 30f / HEIGHT, 145f / HEIGHT));
        var heads = List.of(new ScoreNoteEvent(
                1, (183f - 120f) / 180f, 4, 0, 1, 87f / HEIGHT,
                false, 0, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0));
        var proof = MeterNumeralWholeNoteAlias.class.getDeclaredMethod(
                "provesInlineTriple", List.class, byte[].class, byte[].class, byte[].class,
                int.class, int.class, List.class, List.class, int.class, float.class,
                float.class, float.class);
        proof.setAccessible(true);
        return (boolean) proof.invoke(null, rules, labels, decoded, source, WIDTH, HEIGHT,
                bars, heads, 1, 136.5f, 78f, 183f);
    }
}
