// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original repeated signature followed closely by an explicit note accidental. */
public final class CloseHeaderAccidentalTest {
    private void flat(JoinedSignatureSharpTest f, int x, int y) {
        for (int yy = y - 28; yy <= y + 7; yy++)
            for (int xx = x; xx <= x + 2; xx++) f.ink(xx, yy, 3);
        for (int yy = y - 7; yy <= y + 7; yy++)
            for (int xx = x + 2; xx <= x + 12; xx++) {
                double radius = Math.pow((xx - x - 3) / 9d, 2) + Math.pow((yy - y) / 7d, 2);
                if (radius <= 1 && radius >= .35) f.ink(xx, yy, 3);
            }
    }

    private JoinedSignatureSharpTest page(boolean samePitch, boolean accidental) {
        var f = new JoinedSignatureSharpTest();
        f.row(100, 0, 0, false);
        flat(f, 80, 132);
        f.row(310, 0, 0, false);
        flat(f, 80, 342);
        if (accidental) flat(f, 104, samePitch ? 286 : 318);
        for (int y = 282; y <= 294; y++)
            for (int x = 126; x <= 142; x++)
                if ((x - 134) * (x - 134) / 64d + (y - 288) * (y - 288) / 36d <= 1) f.ink(x, y, 2);
        for (int y = 288; y <= 340; y++) f.ink(126, y, 1);
        var m = f.measures.get(1);
        f.measures.set(
                1,
                new MeasureRegion(
                        115f / JoinedSignatureSharpTest.W, m.right(), m.top(), m.bottom()));
        return f;
    }

    @Test
    public void repeatedFlatThenSamePitchAccidentalDoesNotAddAFlat() {
        assertEquals(List.of(-1), page(true, true).keys());
    }

    @Test
    public void properlyOrderedTwoFlatSignatureStillChangesKey() {
        assertEquals(List.of(-1, -2), page(false, true).keys());
    }

    @Test
    public void singleRepeatedFlatStillHasOneFlat() {
        assertEquals(List.of(-1), page(true, false).keys());
    }

    @Test
    public void masksAreNotMutated() {
        var f = page(true, true);
        var g = f.gray.clone();
        var l = f.labels.clone();
        f.keys();
        assertArrayEquals(g, f.gray);
        assertArrayEquals(l, f.labels);
    }

    @Test
    public void clefDoesNotAuthorizeCloseUnorderedPairJustOutsideOldDistance() {
        var f = page(true, true);
        // Move only the musical head/stem six pixels right. The final accidental now
        // clears the old 1.35-space gate but is still a local printed accidental.
        for (int y = 270; y <= 345; y++)
            for (int x = 150; x >= 126; x--) {
                if (x >= 132) {
                    f.labels[y * JoinedSignatureSharpTest.W + x] =
                            f.labels[y * JoinedSignatureSharpTest.W + x - 6];
                    f.gray[y * JoinedSignatureSharpTest.W + x] =
                            f.gray[y * JoinedSignatureSharpTest.W + x - 6];
                } else {
                    f.labels[y * JoinedSignatureSharpTest.W + x] = 0;
                    f.gray[y * JoinedSignatureSharpTest.W + x] = (byte) 255;
                }
            }
        assertEquals(List.of(-1), f.keys());
    }

    @Test
    public void repeatedSharpsCrossingAnInferredBarOwnOnlyTheCompletePrintedSequence()
            throws Exception {
        var fixture = new JoinedSignatureSharpTest();
        int[] offsets = {0, 24, -8, 16};
        var interpreter = OmrScoreInterpreter.class;
        var staffType = Class.forName(interpreter.getName() + "$Staff");
        var staffConstructor = staffType.getDeclaredConstructor(float.class, float.class, float.class);
        staffConstructor.setAccessible(true);
        var staff = staffConstructor.newInstance(100f, 164f, 16f);
        var componentType = Class.forName(interpreter.getName() + "$Component");
        var component = componentType.getDeclaredConstructors()[0];
        component.setAccessible(true);
        var candidate = Class.forName(interpreter.getName() + "$AccidentalCandidate")
                .getDeclaredConstructors()[0];
        candidate.setAccessible(true);
        var expected = new java.util.ArrayList<>();
        var candidates = new java.util.ArrayList<>();
        for (int i = 0; i < offsets.length; i++) {
            int x = 80 + 21 * i, y = 100 + offsets[i];
            fixture.sharp(x, y);
            var glyph = component.newInstance(248, x - 8, x + 7, y - 22, y + 22,
                    (float) x, (float) y);
            expected.add(glyph);
            candidates.add(candidate.newInstance(glyph, OmrMeasurePostProcessor.CLEF_OR_KEY));
        }
        fixture.sharp(168, 116);
        var inline = component.newInstance(248, 160, 175, 94, 138, 168f, 116f);
        candidates.add(candidate.newInstance(inline, OmrMeasurePostProcessor.CLEF_OR_KEY));
        var detect = interpreter.getDeclaredMethod("retainRepeatedHeaderAcrossBarEdge", byte[].class,
                byte[].class, byte[].class, int.class, int.class, List.class, List.class,
                staffType, int.class, float.class, float.class);
        detect.setAccessible(true);

        int width = JoinedSignatureSharpTest.W, height = JoinedSignatureSharpTest.H;
        byte[] raw = fixture.gray.clone();
        var owned = new java.util.ArrayList<>();
        detect.invoke(null, fixture.labels, fixture.gray, raw, width, height, owned, candidates,
                staff, 4, 126f, 181f);
        assertEquals(expected, owned);

        owned.clear();
        for (int y = 92; y <= 140; y++) {
            java.util.Arrays.fill(raw, y * width + 135, y * width + 151, (byte) 255);
        }
        detect.invoke(null, fixture.labels, fixture.gray, raw, width, height, owned, candidates,
                staff, 4, 126f, 181f);
        assertTrue(owned.isEmpty());
    }
}
